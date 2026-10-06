package com.lanlink.app;

/** Monotonic idle deadline; a fresh consent is not consumed before the first viewer. */
final class ProjectionIdle {
  static final long[] OPTIONS = {0, 60_000, 300_000, 600_000, 1_800_000, 3_600_000, -1};
  boolean used;
  long idleSince = -1;

  void reset() {
    used = false;
    idleSince = -1;
  }

  boolean expired(boolean busy, long now, long delay) {
    if (busy) {
      used = true;
      idleSince = -1;
      return false;
    }
    if (!used) return false;
    if (idleSince < 0) idleSince = now;
    return delay >= 0 && now - idleSince >= delay;
  }
}
