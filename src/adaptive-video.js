import {performance} from 'node:perf_hooks';
const boundedRate = rate => Math.max(500000, Math.min(8000000, Number.isFinite(rate) ? rate : 4000000));
// Fast reduction and slow recovery. A silent/stale receiver is never evidence
// that the link is healthy; queue limits scale with the current sending budget.
export class AdaptiveVideo {
  constructor(rate = 4000000, now = () => performance.now()) { this.rate = boundedRate(rate); this.now = now; this.changedAt = -Infinity; this.good = 0; this.baseRtt = Infinity; }
  feedback(report = {}, queued = 0) {
    const delay = Number(report.delayMs), rtt = Number(report.rttMs), stalled = Number(report.stalledMs || 0), age = Number(report.sampleAgeMs || 0);
    if (![delay, rtt, stalled, age, queued].every(n => Number.isFinite(n) && n >= 0) || delay > 60000 || rtt > 30000) return false;
    this.baseRtt = Math.min(this.baseRtt, rtt || Infinity);
    const rttBudget = rtt > 500 ? 1000000 : rtt > 250 ? 2000000 : 8000000;
    const bad = this.rate > rttBudget || queued > Math.max(32768, this.rate / 8 * .2) || delay > 100 || stalled > 1500 || report.needKey === true && age > 1000 || rtt > Math.max(180, this.baseRtt * 2.5);
    const now = this.now();
    if (bad) { this.good = 0; if (now - this.changedAt < 2000) return false; return this.set(Math.round(this.rate * .65 / 100000) * 100000); }
    const fresh = age < 1500 && stalled < 1500 && (report.frames === undefined || report.frames > 0);
    if (fresh && delay < 40 && queued < 16384 && !report.needKey) this.good++; else this.good = 0;
    if (this.good >= 6 && now - this.changedAt >= 6000) { this.good = 0; return this.set(Math.min(rttBudget, Math.round(this.rate * 1.15 / 100000) * 100000)); }
    return false;
  }
  congested(queued) { return this.feedback({delayMs: 0, rttMs: 0, frames: 0}, queued); }
  set(rate) { rate = boundedRate(rate); if (rate === this.rate) return false; this.rate = rate; this.changedAt = this.now(); return true; }
}
export class VideoFeedback {
  constructor(now = Date.now, monotonic = now) { this.now = now; this.monotonic = monotonic; this.base = Infinity; this.delay = 0; this.peak = 0; this.rtt = 0; this.sampleAt = null; this.aliveAt = monotonic(); this.frames = 0; }
  receive(frame) {
    const now = this.monotonic(); this.aliveAt = now;
    if (frame.codec === 'ping') return;
    const transit = this.now() - frame.time; if (!Number.isFinite(transit)) return;
    if (this.base !== Infinity && Math.abs(transit - this.base) > 10000) { this.base = transit; this.peak = 0; }
    this.base = Math.min(this.base, transit); this.delay = Math.max(0, transit - this.base); this.peak = Math.max(this.peak, this.delay); this.sampleAt = now; this.frames++;
  }
  report() {
    const now = this.monotonic(), age = this.sampleAt === null ? now - this.aliveAt : now - this.sampleAt;
    const report = {delayMs: Math.round(this.frames ? this.peak : this.delay), rttMs: this.rtt, sampleAgeMs: Math.max(0, Math.round(age)), stalledMs: Math.max(0, Math.round(now - this.aliveAt)), frames: this.frames};
    this.peak = 0; this.frames = 0; return report;
  }
}
