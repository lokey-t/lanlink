package com.lanlink.app;

import android.os.SystemClock;
import org.json.JSONObject;

final class AdaptiveVideo {
  volatile int rate = 4_000_000;
  int good;
  long changedAt = -6000;
  double baseRtt = Double.POSITIVE_INFINITY;
  final java.util.function.LongSupplier clock;
  AdaptiveVideo() { this(SystemClock::elapsedRealtime); }
  AdaptiveVideo(java.util.function.LongSupplier clock) { this.clock = clock; }

  synchronized boolean feedback(JSONObject report, long queued) {
    if (report == null) return false;
    double delay = report.optDouble("delayMs", -1), rtt = report.optDouble("rttMs", -1), stalled = report.optDouble("stalledMs", 0), age = report.optDouble("sampleAgeMs", 0);
    if (!Double.isFinite(delay) || delay < 0 || delay > 60000 || !Double.isFinite(rtt) || rtt < 0 || rtt > 30000 || !Double.isFinite(stalled) || stalled < 0 || !Double.isFinite(age) || age < 0 || queued < 0) return false;
    if (rtt > 0) baseRtt = Math.min(baseRtt, rtt);
    long now = clock.getAsLong();
    int next = rate, rttBudget = rtt > 500 ? 1000000 : rtt > 250 ? 2000000 : 8000000;
    if (rate > rttBudget || queued > Math.max(32768, rate / 8 * .2) || delay > 100 || stalled > 1500 || report.optBoolean("needKey") && age > 1000 || rtt > Math.max(180, baseRtt * 2.5)) {
      good = 0;
      if (now - changedAt < 2000) return false;
      next = Math.max(500000, (int) Math.round(rate * .65 / 100000) * 100000);
    } else {
      boolean fresh = age < 1500 && stalled < 1500 && (!report.has("frames") || report.optInt("frames") > 0);
      good = fresh && delay < 40 && queued < 16384 && !report.optBoolean("needKey") ? good + 1 : 0;
      if (good >= 6 && now - changedAt >= 6000) { good = 0; next = Math.min(rttBudget, (int) Math.round(rate * 1.15 / 100000) * 100000); }
    }
    if (next == rate) return false;
    rate = next; changedAt = now; return true;
  }

  boolean congested(long queued) { return feedback(Wire.obj("delayMs", 0, "rttMs", 0, "frames", 0), queued); }

  static final class Feedback {
    long base = Long.MAX_VALUE, delay, peak, sampleAt = -1, aliveAt = SystemClock.elapsedRealtime();
    int frames;
    volatile long rtt;
    synchronized void reset() { base = Long.MAX_VALUE; delay = peak = rtt = 0; sampleAt = -1; aliveAt = SystemClock.elapsedRealtime(); frames = 0; }
    synchronized void receive(VideoWire.Frame frame) {
      long now = SystemClock.elapsedRealtime(); aliveAt = now;
      if (frame.heartbeat) return;
      long transit = System.currentTimeMillis() - frame.time;
      if (base != Long.MAX_VALUE && Math.abs(transit - base) > 10000) { base = transit; peak = 0; }
      base = Math.min(base, transit); delay = Math.max(0, transit - base); peak = Math.max(peak, delay); sampleAt = now; frames++;
    }
    synchronized JSONObject report() {
      long now = SystemClock.elapsedRealtime();
      JSONObject report = Wire.obj("delayMs", frames > 0 ? peak : delay, "rttMs", rtt, "sampleAgeMs", Math.max(0, now - (sampleAt < 0 ? aliveAt : sampleAt)), "stalledMs", Math.max(0, now - aliveAt), "frames", frames);
      peak = 0; frames = 0; return report;
    }
  }
}
