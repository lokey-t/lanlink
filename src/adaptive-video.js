// Conservative AIMD controller: queue growth reduces bitrate quickly; recovery is gradual.
export class AdaptiveVideo {
  constructor(rate = 4000000, now = Date.now) { this.rate = rate; this.now = now; this.changedAt = 0; this.good = 0; this.baseRtt = Infinity; }
  feedback(report = {}, queued = 0) {
    const delay = Number(report.delayMs), rtt = Number(report.rttMs);
    if (!Number.isFinite(delay) || delay < 0 || delay > 60000 || !Number.isFinite(rtt) || rtt < 0 || rtt > 30000) return false;
    this.baseRtt = Math.min(this.baseRtt, rtt || Infinity);
    const bad = queued > 65536 || delay > 100 || rtt > Math.max(180, this.baseRtt * 2.5);
    const now = this.now();
    if (bad) { this.good = 0; if (now - this.changedAt < 3000) return false; return this.set(Math.max(500000, Math.round(this.rate * .65 / 100000) * 100000)); }
    if (delay < 40 && queued < 16384) this.good++; else this.good = 0;
    if (this.good >= 6 && now - this.changedAt >= 6000) { this.good = 0; return this.set(Math.min(8000000, Math.round(this.rate * 1.15 / 100000) * 100000)); }
    return false;
  }
  set(rate) { if (rate === this.rate) return false; this.rate = rate; this.changedAt = this.now(); return true; }
}
export class VideoFeedback {
  constructor(now = Date.now) { this.now = now; this.base = Infinity; this.delay = 0; this.rtt = 0; this.sampleAt = 0; }
  receive(frame) { const transit = this.now() - frame.time; if (!Number.isFinite(transit)) return; if (this.base !== Infinity && Math.abs(transit - this.base) > 10000) this.base = transit; this.base = Math.min(this.base, transit); this.delay = Math.max(0, transit - this.base); this.sampleAt = this.now(); }
  report() { return { delayMs: this.now() - this.sampleAt < 4000 ? Math.round(this.delay) : 0, rttMs: this.rtt }; }
}
