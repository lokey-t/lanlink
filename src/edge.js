export class EdgeSwitch {
  constructor(now = () => performance.now()) { this.now = now; this.candidate = null; this.since = 0; this.cooldown = 0; }
  reset() { this.candidate = null; this.cooldown = this.now() + 700; }
  update(x, y, width, height, edges, dwell) {
    const t = this.now(); if (t < this.cooldown) return null;
    const edge = x <= 1 ? 'left' : x >= width - 2 ? 'right' : y <= 1 ? 'top' : y >= height - 2 ? 'bottom' : null;
    const target = edge && edges[edge];
    if (!target) { this.candidate = null; return null; }
    const key = edge + ':' + target;
    if (this.candidate !== key) { this.candidate = key; this.since = t; return null; }
    if (t - this.since < dwell) return null;
    this.reset(); return { edge, target };
  }
}
