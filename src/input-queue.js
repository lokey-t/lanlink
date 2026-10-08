import {performance} from 'node:perf_hooks';
const motion = (method, args) => method === 'input' && ['move', 'relative'].includes(args.kind);
const clamp = (n, min, max) => Math.max(min, Math.min(max, n));

// Each peer owns its controller. RTT includes the remote input/queue cost; a
// Datagram's local enqueue acknowledgement is deliberately not an RTT sample.
export class AdaptiveMotion {
  constructor(now = () => performance.now()) { this.now = now; this.interval = 8; this.good = 0; this.changedAt = -Infinity; this.baseRtt = Infinity; this.rtt = 0; }
  feedback(report = {}) {
    const {rttMs, delayMs = 0, queued = 0} = report;
    if (rttMs !== undefined && (!Number.isFinite(rttMs) || rttMs < 0 || rttMs > 30000)) return;
    if (![delayMs, queued].every(n => Number.isFinite(n) && n >= 0)) return;
    if (rttMs !== undefined) { this.baseRtt = Math.min(this.baseRtt, rttMs); this.rtt = this.rtt ? this.rtt * .75 + rttMs * .25 : rttMs; }
    const latency = Math.max(this.rtt, rttMs || 0, delayMs * 2);
    const congested = queued > 32768 || delayMs > 70 || latency > 120 || latency > Math.max(70, this.baseRtt * 2);
    const now = this.now();
    if (congested) {
      this.good = 0;
      const target = clamp(Math.ceil(latency / 4), 16, 50);
      if (now - this.changedAt >= 250 && (target > this.interval || queued > 32768)) { this.interval = clamp(Math.max(target, this.interval + 4), 8, 50); this.changedAt = now; }
    } else if (rttMs !== undefined && delayMs < 35 && queued < 8192) {
      if (++this.good >= 8 && now - this.changedAt >= 2000) { this.interval = Math.max(8, this.interval - 4); this.good = 0; this.changedAt = now; }
    } else this.good = 0;
  }
}

// Movement coalesces only within ordering barriers. Raw distance has a short
// lifetime, so stalled networks never replay seconds of movement after recovery.
export class InputQueue {
  constructor(send, options = {}) {
    this.send = send; this.queue = []; this.running = false; this.now = options.now || (() => performance.now());
    this.options = options; this.pacing = options.adaptive ? new AdaptiveMotion(this.now) : null; this.lastMotionAt = -Infinity;
  }
  feedback(report) { this.pacing?.feedback(report); }
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
        if (this.pacing && motion(job.method, job.args) && this.queue.length === 1) {
          const wait = this.pacing.interval - (this.now() - this.lastMotionAt);
          if (wait > 0) await new Promise(resolve => { const timer = setTimeout(done, wait); const self = this; function done() { clearTimeout(timer); self.wake = null; resolve(); } this.wake = done; });
        }
        job = this.queue.shift();
        if (job.samples.length) {
          const fresh = job.samples.filter(sample => this.now() - sample.at <= 120), limit = job.args.kind === 'relative' ? 1000 : 32000;
          job.args = {...job.args, dx: clamp(fresh.reduce((sum, s) => sum + s.dx, 0), -limit, limit), dy: clamp(fresh.reduce((sum, s) => sum + s.dy, 0), -limit, limit)};
        }
        const start = this.now(); if (motion(job.method, job.args)) this.lastMotionAt = start;
        try {
          const result = await this.send(job.method, job.args, job.timeout);
          if (this.pacing && (!this.options.measure || this.options.measure(job.method, job.args))) this.feedback({rttMs: this.now() - start});
          for (const waiter of job.waiters) waiter.resolve(result);
        } catch (error) { for (const waiter of job.waiters) waiter.reject(error); }
      }
    } finally { this.running = false; }
  }
}
