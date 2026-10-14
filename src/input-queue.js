import {performance} from 'node:perf_hooks';
const motion = (method, args) => method === 'input' && ['move', 'relative'].includes(args.kind);
const clamp = (n, min, max) => Math.max(min, Math.min(max, n));
export const MOTION_INTERVAL = { min: 4, max: 24 };
// These lightweight operations reflect input responsiveness. File pacing,
// encoder startup and text insertion are application work, not network RTT.
export const motionRttSample = (method, args = {}) => method === 'linkPing' || method === 'keepControl' || method === 'input' && ['move', 'relative', 'button', 'key', 'wheel'].includes(args.kind);

// Each peer owns its controller. RTT includes the remote input/queue cost; a
// Datagram's local enqueue acknowledgement is deliberately not an RTT sample.
export class AdaptiveMotion {
  constructor(now = () => performance.now()) {
    this.now = now; this.interval = MOTION_INTERVAL.min; this.good = 0; this.recoverSince = null;
    this.changedAt = -Infinity; this.baseRtt = Infinity; this.rtt = 0; this.rttAt = -Infinity; this.rttBuckets = [];
    this.queued = 0; this.queueAt = -Infinity; this.delay = 0; this.delayAt = -Infinity;
  }
  feedback(report = {}) {
    const {rttMs, delayMs, queued} = report;
    if (rttMs === undefined && delayMs === undefined && queued === undefined) return;
    if (rttMs !== undefined && (!Number.isFinite(rttMs) || rttMs < 0 || rttMs > 30000)) return;
    if (![delayMs, queued].every(n => n === undefined || Number.isFinite(n) && n >= 0)) return;
    const now = this.now();
    if (rttMs !== undefined) {
      // A route can change without replacing the peer (USB / LAN / QUIC).
      // Bound the baseline to ten seconds rather than pinning it to an old route.
      const bucket = Math.floor(now / 500), last = this.rttBuckets.at(-1);
      if (last?.bucket === bucket) last.rtt = Math.min(last.rtt, rttMs);
      else this.rttBuckets.push({bucket, rtt: rttMs});
      while (this.rttBuckets[0].bucket <= bucket - 20) this.rttBuckets.shift();
      this.baseRtt = Math.min(...this.rttBuckets.map(sample => sample.rtt));
      this.rtt = this.rttAt === -Infinity ? rttMs : this.rtt + (rttMs - this.rtt) * (rttMs < this.rtt ? .5 : .25);
      this.rttAt = now;
    }
    if (queued !== undefined) { this.queued = queued; this.queueAt = now; }
    if (delayMs !== undefined) { this.delay = delayMs; this.delayAt = now; }
    // Stable propagation latency is not congestion. Only rising RTT and fresh
    // queue pressure justify adding a small, bounded coalescing delay.
    const excess = now - this.rttAt <= 2000 ? Math.max(0, this.rtt - this.baseRtt) : 0;
    const backlog = now - this.queueAt <= 500 ? this.queued : 0;
    const delay = now - this.delayAt <= 500 ? this.delay : 0;
    const target = backlog >= 65536 || delay >= 80 ? 24 : backlog >= 16384 ? 20
      : backlog >= 4096 || excess >= 40 || delay >= 35 ? 16 : excess >= 15 || delay >= 15 ? 12 : MOTION_INTERVAL.min;
    if (target > this.interval) {
      this.good = 0; this.recoverSince = null;
      if (now - this.changedAt >= 120) { this.interval = backlog >= 131072 ? MOTION_INTERVAL.max : Math.min(target, this.interval + 4); this.changedAt = now; }
    } else if (target < this.interval) {
      this.recoverSince ??= now;
      // Clean Datagram queue reports are useful recovery evidence, rather than
      // resetting the good RTT count on every mouse movement.
      if (++this.good >= 3 && now - this.recoverSince >= 200 && now - this.changedAt >= 250) {
        this.interval = Math.max(target, this.interval - 4); this.changedAt = now; this.good = 0; this.recoverSince = now;
      }
    } else { this.good = 0; this.recoverSince = null; }
  }
}

// Movement coalesces only within ordering barriers. Raw distance has a short
// lifetime, so stalled networks never replay seconds of movement after recovery.
export class InputQueue {
  constructor(send, options = {}) {
    this.send = send; this.queue = []; this.running = false; this.now = options.now || (() => performance.now());
    this.options = options; this.pacing = options.adaptive ? new AdaptiveMotion(this.now) : null; this.lastMotionAt = -Infinity;
  }
  feedback(report) { const before = this.pacing?.interval; this.pacing?.feedback(report); if (this.pacing?.interval < before) this.wake?.(); }
  request(method, args, timeout) {
    return new Promise((resolve, reject) => {
      const at = this.now(), moving = motion(method, args), tail = this.queue.at(-1);
      if (moving && tail && motion(tail.method, tail.args) && tail.args.kind === args.kind && (!args.motion || tail.args.motion?.epoch === args.motion.epoch && tail.args.conn === args.conn)) {
        // Cumulative Datagram snapshots replace older snapshots, never add them.
        if (args.motion && args.motion.seq <= tail.args.motion.seq) { resolve({}); return; }
        tail.args = {...args}; tail.at = at;
        if (!args.motion && args.dx !== undefined) {
          tail.samples = (tail.samples || []).filter(sample => at - sample.at <= 120);
          tail.samples.push({at, dx: args.dx, dy: args.dy});
        } else tail.samples = [];
        tail.waiters.push({resolve, reject}); return;
      }
      if (this.queue.length >= 64) { reject(Error('输入队列繁忙，请稍后重试')); return; }
      this.queue.push({method, args: {...args}, timeout, at, samples: moving && !args.motion && args.dx !== undefined ? [{at, dx: args.dx, dy: args.dy}] : [], waiters: [{resolve, reject}]});
      // A click/key must not wait for an artificial motion pacing interval.
      if (!moving) this.wake?.();
      this.flush();
    });
  }
  async flush() {
    if (this.running) return; this.running = true;
    try {
      while (this.queue.length) {
        let job = this.queue[0];
        while (this.pacing && motion(job.method, job.args) && this.queue.length === 1) {
          const wait = this.pacing.interval - (this.now() - this.lastMotionAt);
          if (wait <= 0) break;
          await new Promise(resolve => { const timer = setTimeout(done, wait); const self = this; function done() { clearTimeout(timer); self.wake = null; resolve(); } this.wake = done; });
        }
        // Only movement may overlap a reliable input acknowledgement. A second
        // button/key/session boundary still waits, so reliable inputs cannot reorder.
        if(this.inflight&&(!motion(job.method,job.args)||!this.options.overlap?.('input',job.args))){await this.inflight;continue;}
        job = this.queue.shift();
        if (job.samples.length) {
          const fresh = job.samples.filter(sample => this.now() - sample.at <= 120), limit = job.args.kind === 'relative' ? 1000 : 32000;
          job.args = {...job.args, dx: clamp(fresh.reduce((sum, s) => sum + s.dx, 0), -limit, limit), dy: clamp(fresh.reduce((sum, s) => sum + s.dy, 0), -limit, limit)};
        }
        const start = this.now(); if (motion(job.method, job.args)) this.lastMotionAt = start;
        const complete=async()=>{
          try {
            const result=await this.send(job.method,job.args,job.timeout);
            if(this.pacing&&(!this.options.measure||this.options.measure(job.method,job.args)))this.feedback({rttMs:this.now()-start});
            for(const waiter of job.waiters)waiter.resolve(result);
          }catch(error){for(const waiter of job.waiters)waiter.reject(error);}
        };
        if(this.options.overlap?.(job.method,job.args)&&!motion(job.method,job.args)){
          const pending=complete();this.inflight=pending;
          pending.finally(()=>{if(this.inflight===pending)this.inflight=null;});
        }else await complete();
      }
    } finally { this.running = false; }
  }
}
