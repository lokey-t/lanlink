export class EdgeSwitch {
  constructor(now = () => performance.now()) { this.now = now; this.candidate = null; this.since = 0; this.cooldown = 0; }
  reset() { this.candidate = null; this.progress = null; this.cooldown = this.now() + 1000; }
  suppress() { this.candidate = null; this.progress = null; this.waitingForLeave = true; }
  waitForLeave() { this.reset(); this.suppress(); }
  update(x, y, width, height, edges, dwell, eligible = true) {
    if (!eligible) { this.candidate = null; this.progress = null; return null; }
    const t = this.now(); if (t < this.cooldown) return null;
    const touching = [x <= 1 && 'left', x >= width - 2 && 'right', y <= 1 && 'top', y >= height - 2 && 'bottom'].filter(Boolean);
    const edge = touching.find(side => edges[side]) || touching[0] || null;
    const target = edge && edges[edge];
    if (this.waitingForLeave) { if(!edge) this.waitingForLeave=false; this.candidate=null; return null; }
    if (!target) { this.candidate = null; this.progress = null; return null; }
    const key = edge + ':' + target;
    if (this.candidate !== key) { this.candidate = key; this.since = t; }
    dwell = Number.isFinite(dwell) ? Math.max(0,dwell) : 100;
    this.progress = { edge, progress: dwell === 0 ? 1 : Math.min(1,(t-this.since)/dwell), dwell };
    if (t - this.since < dwell) return null;
    this.reset(); return { edge, target };
  }
}
