package com.lanlink.app;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/** Checks native feedback requests without relying on an emulator having a vibration motor. */
final class TouchHapticsProbe {
  private static final class FeedbackView extends View {
    final List<Integer> effects = new ArrayList<>();

    FeedbackView(Context context) {
      super(context);
    }

    @Override public boolean performHapticFeedback(int effect) {
      effects.add(effect);
      return true;
    }
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  static String run(Instrumentation test) {
    test.runOnMainSync(() -> {
      FeedbackView view = new FeedbackView(test.getTargetContext());
      long[] now = {1000};
      TouchHaptics haptics = new TouchHaptics(view, () -> now[0]);
      haptics.tap(false);
      haptics.tap(true);
      haptics.dragStart();
      haptics.dragEnd();
      check(view.effects.equals(java.util.Arrays.asList(
          HapticFeedbackConstants.VIRTUAL_KEY, HapticFeedbackConstants.CONTEXT_CLICK,
          HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.VIRTUAL_KEY_RELEASE)),
          "tap, secondary click, drag start and release have distinct native cues");

      view.effects.clear();
      haptics.reset();
      now[0] += 100;
      for (int i = 0; i < 20; i++) {
        haptics.scroll(2);
        haptics.scroll(-2);
      }
      check(view.effects.isEmpty(), "stationary scroll jitter does not accumulate a vibration");
      haptics.reset();
      haptics.scroll(9);
      check(view.effects.isEmpty(), "small scroll movement stays quiet");
      haptics.scroll(9);
      int tick = Build.VERSION.SDK_INT >= 34
          ? HapticFeedbackConstants.SEGMENT_FREQUENT_TICK : HapticFeedbackConstants.CLOCK_TICK;
      check(view.effects.equals(java.util.Collections.singletonList(tick)),
          "crossing a scroll step emits one soft cue");
      for (int i = 0; i < 20; i++) haptics.scroll(180);
      check(view.effects.size() == 1, "fast scroll updates are rate limited without catch-up bursts");
      now[0] += 80;
      haptics.scroll(1);
      check(view.effects.size() == 1, "throttled movement leaves no vibration backlog");
      haptics.scroll(17);
      check(view.effects.size() == 2, "continued scrolling resumes a soft cue after the interval");
      haptics.dragEnd();
      check(view.effects.size() == 3
              && view.effects.get(2) == HapticFeedbackConstants.VIRTUAL_KEY_RELEASE,
          "scroll throttling never suppresses a deliberate release cue");

      view.effects.clear();
      now[0] += 100;
      haptics.scroll(17);
      haptics.reset();
      haptics.scroll(1);
      haptics.scroll(Float.NaN);
      haptics.scroll(Float.POSITIVE_INFINITY);
      check(view.effects.isEmpty(), "cancellation clears scroll travel and invalid input is quiet");
      view.setEnabled(false);
      haptics.tap(false);
      haptics.dragStart();
      haptics.dragEnd();
      haptics.scroll(180);
      check(view.effects.isEmpty(), "disabled controls never request haptic feedback");
      view.setEnabled(true);
      view.setHapticFeedbackEnabled(false);
      haptics.tap(false);
      haptics.dragStart();
      haptics.dragEnd();
      haptics.scroll(180);
      check(view.effects.isEmpty(), "the view haptic-feedback preference is honored");
      view.setHapticFeedbackEnabled(true);
      haptics.tap(false);
      check(view.effects.size() == 1, "feedback resumes when enabled again");
    });
    return "PASS: native touch cues, scroll distance/rate limits, cancellation and haptic opt-out";
  }
}
