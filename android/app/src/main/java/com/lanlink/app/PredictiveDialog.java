package com.lanlink.app;

import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.Context;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.window.OnBackInvokedCallback;

/** Each window owns its back gesture, so nested popups never navigate the page below. */
final class PredictiveDialog extends Dialog {
  private final android.app.Activity owner;
  private boolean canCancel = true;
  private OnBackInvokedCallback backCallback;
  private float restingDim;
  private android.graphics.drawable.Drawable decorBackground;
  private float decorElevation;
  private boolean decorWillNotDraw, decorPrepared;
  private final java.util.Map<View, boolean[]> scrollbarStates = new java.util.IdentityHashMap<>();

  PredictiveDialog(Context context) { this(context, 0); }
  PredictiveDialog(Context context, int theme) {
    super(context, Ui.dark ? R.style.PopupThemeDark : R.style.PopupTheme); owner = context instanceof android.app.Activity ? (android.app.Activity)context : null;
  }

  private View surface() {
    return findViewById(android.R.id.content);
  }

  private void hideScrollbars(View view) {
    if (view == null) return;
    if (view.isVerticalScrollBarEnabled() || view.isHorizontalScrollBarEnabled()) {
      scrollbarStates.put(view, new boolean[]{view.isVerticalScrollBarEnabled(), view.isHorizontalScrollBarEnabled()});
      view.setVerticalScrollBarEnabled(false); view.setHorizontalScrollBarEnabled(false);
    }
    if (view instanceof ViewGroup) for (int i=0; i<((ViewGroup)view).getChildCount(); i++)
      hideScrollbars(((ViewGroup)view).getChildAt(i));
  }

  private void beginPreview() {
    resetPreview();
    // Move the complete window surface once, including its rounded background.
    // Stop entry motion before taking over the gesture's transforms.
    ViewGroup content = findViewById(android.R.id.content);
    if (content != null && content.getChildCount() > 0) {
      View body = content.getChildAt(0); body.animate().cancel();
      body.setTranslationX(0); body.setTranslationY(0); body.setAlpha(1);
    }
    View decor = getWindow().getDecorView();
    decorBackground = decor.getBackground(); decorElevation = decor.getElevation();
    decorWillNotDraw = decor.willNotDraw(); decorPrepared = true;
    // The framework decor can draw a fallback background/shadow behind the moving card.
    // Keep that window layer transparent and animate its content frame as one surface.
    decor.setBackgroundColor(android.graphics.Color.TRANSPARENT);
    decor.setWillNotDraw(true); decor.setElevation(0);
    hideScrollbars(surface());
    if (owner != null) hideScrollbars(owner.getWindow().getDecorView());
  }

  private void resetPreview() {
    View surface = surface();
    if (surface != null) {
      surface.animate().cancel();
      surface.setTranslationX(0); surface.setScaleX(1); surface.setScaleY(1); surface.setAlpha(1);
    }
    if (decorPrepared && getWindow() != null) {
      View decor = getWindow().getDecorView();
      decor.setBackground(decorBackground); decor.setWillNotDraw(decorWillNotDraw);
      decor.setElevation(decorElevation); decorPrepared = false; decorBackground = null;
    }
    for (java.util.Map.Entry<View, boolean[]> entry : scrollbarStates.entrySet()) {
      entry.getKey().setVerticalScrollBarEnabled(entry.getValue()[0]);
      entry.getKey().setHorizontalScrollBarEnabled(entry.getValue()[1]);
    }
    scrollbarStates.clear();
    if (getWindow() != null) getWindow().setDimAmount(restingDim);
  }

  @Override protected void onStart() {
    super.onStart();
    getWindow().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
    restingDim = getWindow().getAttributes().dimAmount;
    updateBackCallback();
  }

  @Override protected void onStop() {
    unregisterBackCallback(); resetPreview(); super.onStop();
  }

  @Override public void setCancelable(boolean cancelable) {
    super.setCancelable(cancelable); canCancel = cancelable;
    if (isShowing()) updateBackCallback();
  }

  @Override public void onBackPressed() { if (canCancel) cancel(); }

  private void unregisterBackCallback() {
    if (Build.VERSION.SDK_INT >= 33 && backCallback != null && getWindow() != null)
      getWindow().getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
    backCallback = null;
  }

  private void updateBackCallback() {
    if (Build.VERSION.SDK_INT < 33) return;
    unregisterBackCallback();
    if (!canCancel) return;
    if (Build.VERSION.SDK_INT >= 34) {
      backCallback = new android.window.OnBackAnimationCallback() {
        public void onBackStarted(android.window.BackEvent event) {
          restingDim = getWindow().getAttributes().dimAmount; beginPreview();
        }
        public void onBackProgressed(android.window.BackEvent event) {
          View surface = surface();
          if (surface == null || !ValueAnimator.areAnimatorsEnabled()) return;
          float progress = event.getProgress();
          surface.setPivotX(surface.getWidth() / 2f); surface.setPivotY(surface.getHeight() / 2f);
          surface.setScaleX(1 - .06f * progress); surface.setScaleY(1 - .06f * progress);
          float shift = 32 * getContext().getResources().getDisplayMetrics().density * progress;
          if (getWindow().getAttributes().width > 0)
            shift = Math.min(shift, surface.getWidth() * .03f * progress);
          surface.setTranslationX(shift * (event.getSwipeEdge() == android.window.BackEvent.EDGE_LEFT ? 1 : -1));
          // Keep the card opaque; only the dim scrim fades to reveal the page below.
          surface.setAlpha(1);
          getWindow().setDimAmount(restingDim * (1 - .7f * progress));
        }
        public void onBackCancelled() {
          View surface = surface();
          if (surface == null) return;
          surface.animate().translationX(0).scaleX(1).scaleY(1).alpha(1)
              .setDuration(ValueAnimator.areAnimatorsEnabled() ? 180 : 0)
              .withEndAction(PredictiveDialog.this::resetPreview).start();
          getWindow().setDimAmount(restingDim);
        }
        public void onBackInvoked() { onBackPressed(); }
      };
    } else backCallback = this::onBackPressed;
    getWindow().getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
        android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
  }
}
