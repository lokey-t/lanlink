package com.lanlink.app;

import android.os.Build;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.View;
import java.util.function.LongSupplier;

/** Small native touch cues, shared by desktop gestures and every touchpad surface. */
final class TouchHaptics {
  private static final float SCROLL_STEP_DP = 18f;
  private static final long SCROLL_INTERVAL_MS = 80;
  private final View view;
  private final LongSupplier clock;
  private float scrollDistance;
  private long lastFeedbackAt = -1;

  TouchHaptics(View view) {
    this(view, SystemClock::uptimeMillis);
  }

  TouchHaptics(View view, LongSupplier clock) {
    this.view = view;
    this.clock = clock;
  }

  void tap(boolean secondary) {
    reset();
    feedback(secondary ? HapticFeedbackConstants.CONTEXT_CLICK : HapticFeedbackConstants.VIRTUAL_KEY);
  }

  void dragStart() {
    reset();
    feedback(HapticFeedbackConstants.LONG_PRESS);
  }

  void dragEnd() {
    reset();
    feedback(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE);
  }

  /** Distance and time gates keep fast scrolling soft and stationary finger jitter quiet. */
  void scroll(float distanceDp) {
    if (!Float.isFinite(distanceDp) || distanceDp == 0) return;
    if (scrollDistance * distanceDp < 0) scrollDistance = 0;
    scrollDistance += distanceDp;
    if (Math.abs(scrollDistance) < SCROLL_STEP_DP) return;
    // Consume crossed steps even when throttled: there is never a queued vibration burst.
    scrollDistance %= SCROLL_STEP_DP;
    long now = clock.getAsLong();
    if (lastFeedbackAt >= 0 && now - lastFeedbackAt < SCROLL_INTERVAL_MS) return;
    feedback(Build.VERSION.SDK_INT >= 34
        ? HapticFeedbackConstants.SEGMENT_FREQUENT_TICK : HapticFeedbackConstants.CLOCK_TICK);
  }

  void reset() {
    scrollDistance = 0;
  }

  private void feedback(int effect) {
    if (!view.isEnabled() || !view.isHapticFeedbackEnabled()) return;
    // No override flags or raw vibrator calls: Android retains control of the user's
    // touch-feedback preference, hardware support and the strength of each native cue.
    view.performHapticFeedback(effect);
    lastFeedbackAt = clock.getAsLong();
  }
}
