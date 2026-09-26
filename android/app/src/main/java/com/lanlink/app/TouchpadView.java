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
  float x, y, startX, startY, scrollY;
  double dx, dy;
  int wheel;
  long downAt;
  boolean multi, drag, moved, closed, touching;
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
    worker.scheduleWithFixedDelay(
        () -> {
          try {
            flush();
          } catch (Exception error) {
            report(error);
          }
        },
        0,
        16,
        TimeUnit.MILLISECONDS);
  }

  void report(Exception error) {
    long now = android.os.SystemClock.elapsedRealtime();
    if (now - errorAt >= 2000) {
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
    worker.execute(
        () -> {
          try {
            flush();
            sender.send(event);
          } catch (Exception error) {
            report(error);
          }
        });
  }

  void button(int b, boolean down) {
    event(Wire.obj("kind", "button", "button", b, "down", down));
  }

  protected void onDraw(Canvas canvas) {
    float density = getResources().getDisplayMetrics().density;
    paint.setColor(touching ? 0xffe8e5fb : 0xffefedf8);
    canvas.drawRoundRect(0, 0, getWidth(), getHeight(), 24 * density, 24 * density, paint);
    paint.setColor(0xff77758c);
    paint.setTextAlign(Paint.Align.CENTER);
    paint.setTextSize(14 * density);
    canvas.drawText(drag ? "正在拖动" : "触控板", getWidth() / 2f, getHeight() / 2f - 5 * density, paint);
    paint.setTextSize(11 * density);
    canvas.drawText("轻点单击 · 双指滚动 · 长按拖动", getWidth() / 2f, getHeight() / 2f + 20 * density, paint);
  }

  public boolean onTouchEvent(MotionEvent e) {
    int action = e.getActionMasked();
    float density = getResources().getDisplayMetrics().density;
    if (action == MotionEvent.ACTION_DOWN) {
      touching = true;
      multi = false;
      moved = false;
      drag = false;
      downAt = e.getEventTime();
      x = startX = e.getX();
      y = startY = e.getY();
      postDelayed(hold, 400);
      getParent().requestDisallowInterceptTouchEvent(true);
    } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
      multi = true;
      removeCallbacks(hold);
      if (drag) {
        button(0, false);
        drag = false;
      }
      scrollY = (e.getY(0) + e.getY(1)) / 2;
    } else if (action == MotionEvent.ACTION_MOVE) {
      if (Math.hypot(e.getX() - startX, e.getY() - startY) > 8 * density) {
        moved = true;
        removeCallbacks(hold);
      }
      if (multi && e.getPointerCount() >= 2) {
        float cy = (e.getY(0) + e.getY(1)) / 2;
        int delta = Math.round((cy - scrollY) * 3 / density);
        scrollY = cy;
        if (delta != 0)
          synchronized (this) {
            wheel = Math.max(-2400, Math.min(2400, wheel + delta));
          }
      } else if (!multi)
        synchronized (this) {
          dx += (e.getX() - x) * 1.5 / density;
          dy += (e.getY() - y) * 1.5 / density;
        }
      x = e.getX();
      y = e.getY();
    } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
      removeCallbacks(hold);
      touching = false;
      if (drag) {
        button(0, false);
        drag = false;
      } else if (action == MotionEvent.ACTION_UP && !moved && e.getEventTime() - downAt < 400) {
        button(multi ? 2 : 0, true);
        button(multi ? 2 : 0, false);
        performClick();
      }
      getParent().requestDisallowInterceptTouchEvent(false);
    }
    invalidate();
    return true;
  }

  public boolean performClick() {
    super.performClick();
    return true;
  }

  public void close() {
    removeCallbacks(hold);
    if (drag) button(0, false);
    closed = true;
    worker.shutdown();
  }
}
