package com.lanlink.app;

import org.json.JSONObject;

final class AdaptiveVideo {
  int rate = 4_000_000, good;
  long changedAt;
  double baseRtt = Double.POSITIVE_INFINITY;

  boolean feedback(JSONObject report, long queued) {
    if (report == null) return false;
    double delay = report.optDouble("delayMs", -1), rtt = report.optDouble("rttMs", -1);
    if (!Double.isFinite(delay)
        || delay < 0
        || delay > 60000
        || !Double.isFinite(rtt)
        || rtt < 0
        || rtt > 30000) return false;
    if (rtt > 0) baseRtt = Math.min(baseRtt, rtt);
    long now = System.currentTimeMillis();
    int next = rate;
    if (queued > 65536 || delay > 100 || rtt > Math.max(180, baseRtt * 2.5)) {
      good = 0;
      if (now - changedAt < 3000) return false;
      next = Math.max(500000, (int) Math.round(rate * .65 / 100000) * 100000);
    } else {
      good = delay < 40 && queued < 16384 ? good + 1 : 0;
      if (good >= 6 && now - changedAt >= 6000) {
        good = 0;
        next = Math.min(8000000, (int) Math.round(rate * 1.15 / 100000) * 100000);
      }
    }
    if (next == rate) return false;
    rate = next;
    changedAt = now;
    return true;
  }

  static final class Feedback {
    long base = Long.MAX_VALUE, delay, sampleAt;volatile long rtt;

    synchronized void receive(VideoWire.Frame frame) {
      long now = System.currentTimeMillis(), transit = now - frame.time;
      if (base != Long.MAX_VALUE && Math.abs(transit - base) > 10000) base = transit;
      base = Math.min(base, transit);
      delay = Math.max(0, transit - base);
      sampleAt = now;
    }

    synchronized JSONObject report() {
      return Wire.obj(
          "delayMs", System.currentTimeMillis() - sampleAt < 4000 ? delay : 0, "rttMs", rtt);
    }
  }
}
