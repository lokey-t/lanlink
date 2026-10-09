package com.lanlink.app;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Relative mouse, tap, hold-to-drag and two-finger scroll; movement is coalesced. */
final class TouchpadView extends View implements AutoCloseable {
  interface Sender {
    void send(JSONObject event) throws Exception;
  }

  final Sender sender;
  final java.util.function.Consumer<Exception> onError;
  long errorAt;
  final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
  final Paint paint = new Paint(3);
  final android.util.SparseArray<PointF> starts = new android.util.SparseArray<>();
  float x, y, startX, startY, scrollX, scrollY, scrollStartX, scrollStartY;
  double dx, dy;
  int wheel;
  float scrollRemainder;
  long downAt;
  int primaryId = -1, maxFingers;
  boolean multi, drag, moved, touching, compactDark;
  float surfaceOpacity = 1f;
  volatile boolean closed;
  final Runnable hold =
      () -> {
        if (touching && !multi && !moved && !closed) {
          drag = true;
          button(0, true);
          invalidate();
        }
      };

  TouchpadView(Context context, Sender sender) {
    this(context, sender, error -> {});
  }

  TouchpadView(Context context, Sender sender, java.util.function.Consumer<Exception> onError) {
    super(context);
    this.sender = sender;
    this.onError = onError;
    setContentDescription("触控板：单指移动，轻点单击，长按拖动，双指滚动，双指轻点右键");
    setFocusable(true);
    worker.execute(this::pump);
  }

  /** One task at a time; an RPC already taking 8ms must not add another delay. */
  void pump() {
    if (closed) return;
    long started = android.os.SystemClock.elapsedRealtime();
    try { flush(); } catch (Exception error) { report(error); }
    if (!closed) try {
      worker.schedule(this::pump, nextPollDelay(android.os.SystemClock.elapsedRealtime() - started), TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException ignored) {}
  }

  static long nextPollDelay(long spent) { return Math.max(0, QuicMotion.Pacing.MIN - spent); }

  /** Compact inset surface used by the full-screen controller's side pads. */
  void setCompactDark(boolean compact) {
    compactDark = compact;
    invalidate();
  }

  /** Changes only the compact pad's fill; its outline and grip marks remain legible. */
  void setSurfaceOpacity(float opacity) {
    surfaceOpacity = Float.isNaN(opacity) ? 1f : Math.max(0f, Math.min(1f, opacity));
    invalidate();
  }

  void report(Exception error) {
    long now = android.os.SystemClock.elapsedRealtime();
    if (errorAt == 0 || now - errorAt >= 2000) {
      errorAt = now;
      onError.accept(error);
    }
  }

  void flush() throws Exception {
    double a, b;
    int scroll;
    synchronized (this) {
      a = Math.max(-1000, Math.min(1000, dx));
      b = Math.max(-1000, Math.min(1000, dy));
      scroll = Math.max(-2400, Math.min(2400, wheel));
      dx = 0;
      dy = 0;
      wheel = 0;
    }
    if (a != 0 || b != 0) sender.send(Wire.obj("kind", "relative", "dx", a, "dy", b));
    if (scroll != 0) sender.send(Wire.obj("kind", "wheel", "delta", scroll));
  }

  void event(JSONObject event) {
    if (closed) return;
    try {
      worker.execute(
          () -> {
            try {
              flush();
              sender.send(event);
            } catch (Exception error) {
              report(error);
            }
          });
    } catch (RejectedExecutionException ignored) {
      // A detached window may deliver its final cancel after close().
    }
  }

  void button(int b, boolean down) {
    event(Wire.obj("kind", "button", "button", b, "down", down));
  }

  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float density = getResources().getDisplayMetrics().density;
    float inset = density, radius = (compactDark ? 26 : 24) * density;
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(compactDark ? touching ? 0xff343b50 : 0xff252c3c : touching ? Ui.LILAC : Ui.BG);
    if (compactDark) paint.setAlpha(Math.round(255 * Math.min(1f, surfaceOpacity + (touching ? .08f : 0f))));
    canvas.drawRoundRect(inset, inset, getWidth() - inset, getHeight() - inset, radius, radius, paint);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(density);
    paint.setColor(compactDark ? touching ? 0xffb5a6ff : 0xff566078 : Ui.LINE);
    canvas.drawRoundRect(inset, inset, getWidth() - inset, getHeight() - inset, radius, radius, paint);
    paint.setStyle(Paint.Style.FILL);
    if (compactDark) {
      paint.setColor(touching ? 0xffa69ccf : 0xff69728a);
      for (int row = -1; row <= 1; row++)
        for (int col = -1; col <= 1; col++)
          canvas.drawCircle(getWidth() / 2f + col * 10 * density,
              getHeight() / 2f + row * 10 * density, 1.4f * density, paint);
      if (drag) {
        paint.setTextSize(11 * density);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("拖动中", getWidth() / 2f, getHeight() - 17 * density, paint);
      }
    } else {
      paint.setColor(Ui.MUTED);
      paint.setTextAlign(Paint.Align.CENTER);
      paint.setTextSize(14 * density);
      canvas.drawText(drag ? "正在拖动" : "触控板", getWidth() / 2f, getHeight() / 2f - 5 * density, paint);
      paint.setTextSize(11 * density);
      canvas.drawText("轻点单击 · 双指滚动 · 长按拖动", getWidth() / 2f, getHeight() / 2f + 20 * density, paint);
    }
  }

  private void intercept(boolean disallow) {
    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(disallow);
  }

  private void checkMovement(MotionEvent event, float density) {
    for (int i = 0; i < event.getPointerCount(); i++) {
      PointF start = starts.get(event.getPointerId(i));
      if (start != null && Math.hypot(event.getX(i) - start.x, event.getY(i) - start.y) > 8 * density) {
        moved = true;
        removeCallbacks(hold);
      }
    }
  }

  public boolean onTouchEvent(MotionEvent e) {
    if (closed || !isEnabled()) return false;
    int action = e.getActionMasked();
    float density = getResources().getDisplayMetrics().density;
    if (touching && action != MotionEvent.ACTION_DOWN) checkMovement(e, density);
    if (action == MotionEvent.ACTION_DOWN) {
      cancelGesture();
      touching = true;
      multi = false;
      moved = false;
      maxFingers = 1;
      primaryId = e.getPointerId(0);
      starts.put(primaryId, new PointF(e.getX(), e.getY()));
      downAt = e.getEventTime();
      x = startX = e.getX();
      y = startY = e.getY();
      postDelayed(hold, 400);
      intercept(true);
    } else if (!touching) {
      return true;
    } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
      int index = e.getActionIndex();
      starts.put(e.getPointerId(index), new PointF(e.getX(index), e.getY(index)));
      multi = true;
      maxFingers = Math.max(maxFingers, e.getPointerCount());
      removeCallbacks(hold);
      if (drag) {
        button(0, false);
        drag = false;
        moved = true;
      }
      if (e.getPointerCount() == 2) {
        scrollX = scrollStartX = (e.getX(0) + e.getX(1)) / 2;
        scrollY = scrollStartY = (e.getY(0) + e.getY(1)) / 2;
        scrollRemainder = 0;
      } else {
        // Three fingers belong to the surrounding controller, never to a mouse click.
        moved = true;
      }
    } else if (action == MotionEvent.ACTION_MOVE) {
      if (multi && e.getPointerCount() == 2 && maxFingers == 2) {
        float cx = (e.getX(0) + e.getX(1)) / 2;
        float cy = (e.getY(0) + e.getY(1)) / 2;
        if (Math.hypot(cx - scrollStartX, cy - scrollStartY) > 4 * density) moved = true;
        scrollRemainder += (cy - scrollY) * 3 / density;
        int delta = Math.round(scrollRemainder);
        scrollRemainder -= delta;
        scrollX = cx;
        scrollY = cy;
        if (delta != 0) {
          moved = true;
          synchronized (this) {
            wheel = Math.max(-2400, Math.min(2400, wheel + delta));
          }
        }
      } else if (!multi) {
        int index = e.findPointerIndex(primaryId);
        if (index < 0) {
          cancelGesture();
          return true;
        }
        float currentX = e.getX(index), currentY = e.getY(index);
        if (Math.hypot(currentX - startX, currentY - startY) > 8 * density) {
          moved = true;
          removeCallbacks(hold);
        }
        synchronized (this) {
          dx += (currentX - x) * 1.5 / density;
          dy += (currentY - y) * 1.5 / density;
        }
        x = currentX;
        y = currentY;
      }
    } else if (action == MotionEvent.ACTION_POINTER_UP) {
      // Keep multi set until the last finger lifts; resuming a single pointer jumps the cursor.
      removeCallbacks(hold);
    } else if (action == MotionEvent.ACTION_CANCEL) {
      cancelGesture();
    } else if (action == MotionEvent.ACTION_UP) {
      removeCallbacks(hold);
      touching = false;
      if (!multi) {
        synchronized (this) {
          dx += (e.getX() - x) * 1.5 / density;
          dy += (e.getY() - y) * 1.5 / density;
        }
      }
      if (drag) {
        button(0, false);
        drag = false;
      } else if (!moved && maxFingers <= 2 && e.getEventTime() - downAt < 400) {
        button(multi ? 2 : 0, true);
        button(multi ? 2 : 0, false);
        performClick();
      }
      primaryId = -1;
      starts.clear();
      intercept(false);
    }
    invalidate();
    return true;
  }

  /** End an interrupted gesture without synthesizing a click or keeping a drag held. */
  void cancelGesture() {
    removeCallbacks(hold);
    touching = false;
    multi = false;
    moved = true;
    primaryId = -1;
    maxFingers = 0;
    starts.clear();
    scrollRemainder = 0;
    synchronized (this) {
      dx = dy = 0;
      wheel = 0;
    }
    if (drag) {
      button(0, false);
      drag = false;
    }
    intercept(false);
    invalidate();
  }

  public boolean performClick() {
    super.performClick();
    return true;
  }

  public void onWindowFocusChanged(boolean hasWindowFocus) {
    super.onWindowFocusChanged(hasWindowFocus);
    if (!hasWindowFocus) cancelGesture();
  }

  protected void onDetachedFromWindow() {
    cancelGesture();
    super.onDetachedFromWindow();
  }

  public void close() {
    if (closed) return;
    cancelGesture();
    closed = true;
    worker.shutdown();
  }
}
