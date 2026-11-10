package com.lanlink.app;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

/** One compact acknowledgement, with an indicator of how long it remains visible. */
final class TimedToast {
  private PopupWindow active;

  void dismiss() {
    if (active != null) active.dismiss();
  }

  void show(View anchor, Ui ui, String message, boolean warning) {
    if (!anchor.isAttachedToWindow() || message == null || message.trim().isEmpty()) return;
    dismiss();
    int accent = warning ? (Ui.dark ? 0xffffbf73 : 0xff9c611c) : Ui.PURPLE;
    LinearLayout card = ui.column();
    card.setPadding(ui.dp(11), ui.dp(7), ui.dp(11), ui.dp(6));
    GradientDrawable background = ui.shape(Ui.CARD, 10);
    background.setStroke(ui.dp(1), Ui.LINE);
    card.setBackground(background);
    TextView text = ui.text(message, 12, Ui.INK);
    int limit = Math.max(ui.dp(96), Math.min(ui.dp(248), anchor.getWidth() - ui.dp(48)));
    int textWidth = (int) Math.ceil(android.text.Layout.getDesiredWidth(message, text.getPaint()));
    int width = Math.min(limit, Math.max(ui.dp(114), textWidth)) + card.getPaddingLeft() + card.getPaddingRight();
    card.addView(text);
    FrameLayout track = new FrameLayout(anchor.getContext());
    track.setBackground(ui.shape(Ui.LINE, 1));
    track.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    LinearLayout.LayoutParams trackSize = new LinearLayout.LayoutParams(-1, ui.dp(2));
    trackSize.topMargin = ui.dp(6);
    card.addView(track, trackSize);
    View remaining = new View(anchor.getContext());
    remaining.setBackground(ui.shape(accent, 1));
    track.addView(remaining, new FrameLayout.LayoutParams(-1, -1));
    remaining.setPivotX(0);

    PopupWindow popup = new PopupWindow(card, width, -2, false);
    popup.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
    popup.setTouchable(false);
    popup.setElevation(ui.dp(4));
    AccessibilityManager accessibility = (AccessibilityManager) anchor.getContext()
        .getSystemService(Context.ACCESSIBILITY_SERVICE);
    int duration = accessibility.getRecommendedTimeoutMillis(warning ? 4000 : 3000,
        AccessibilityManager.FLAG_CONTENT_TEXT);
    long start = SystemClock.uptimeMillis();
    Runnable tick = new Runnable() {
      @Override public void run() {
        if (active != popup || !popup.isShowing()) return;
        float fraction = Math.max(0, 1 - (SystemClock.uptimeMillis() - start) / (float) duration);
        remaining.setScaleX(fraction);
        if (fraction == 0) popup.dismiss();
        else card.postOnAnimation(this);
      }
    };
    popup.setOnDismissListener(() -> {
      card.removeCallbacks(tick);
      if (active == popup) active = null;
    });
    try {
      int topInset = anchor.getRootWindowInsets() == null ? 0 : anchor.getRootWindowInsets().getSystemWindowInsetTop();
      popup.showAtLocation(anchor, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, topInset + ui.dp(12));
      active = popup;
      text.announceForAccessibility(message);
      card.postOnAnimation(tick);
    } catch (WindowManager.BadTokenException ignored) {
      popup.dismiss();
    }
  }
}
