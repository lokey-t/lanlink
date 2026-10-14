package com.lanlink.app;

import android.content.Context;
import android.graphics.Matrix;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;

/** Shared video surfaces with optional, explicitly enabled fullscreen remote gestures. */
@android.annotation.SuppressLint("ViewConstructor") // Constructed with its shared render surfaces.
final class VideoViewport extends FrameLayout {
  final TextureView texture;
  final ImageView image;
  private final boolean interactive;
  private int sourceWidth, sourceHeight;
  float zoom = 1, panX, panY;
  private float lastX, lastY, lastSpan, downX, downY;
  private long downAt, lastTap;
  private boolean moved, multi;
  boolean followCursor = true;
  private boolean gestureActive;
  private final int touchSlop;
  private boolean remoteGestures, pinchEnabled;
  private boolean keyboardGesture;
  private TapListener tapListener;
  private DragListener dragListener;
  private Runnable keyboardListener;
  private boolean dragging;
  private float dragX, dragY;
  private final TouchHaptics haptics = new TouchHaptics(this);
  final Runnable hold = () -> {
    if (!remoteGestures || !gestureActive || multi || moved || keyboardGesture
        || dragging || dragListener == null) return;
    float[] point = sourcePoint(downX, downY, false);
    if (point == null) return;
    dragging = true;
    dragX = point[0];
    dragY = point[1];
    haptics.dragStart();
    dragListener.start(point[0], point[1]);
  };

  interface TapListener {
    /** Source-image coordinates, normalized to the inclusive range [0, 1]. */
    void tap(float x, float y);
  }

  interface DragListener {
    void start(float x, float y);
    void move(float x, float y);
    void end(float x, float y);
  }

  /** Switching modes resets the transform; updating fullscreen options preserves it. */
  void setRemoteGestures(
      boolean enabled, boolean pinchEnabled, TapListener tap, Runnable keyboard) {
    setRemoteGestures(enabled, pinchEnabled, tap, keyboard, null);
  }

  void setRemoteGestures(boolean enabled, boolean pinchEnabled, TapListener tap,
      Runnable keyboard, DragListener drag) {
    boolean changed = remoteGestures != enabled;
    endGesture();
    lastTap = 0;
    remoteGestures = enabled;
    this.pinchEnabled = enabled && pinchEnabled;
    tapListener = enabled ? tap : null;
    dragListener = enabled ? drag : null;
    keyboardListener = enabled ? keyboard : null;
    if (changed) reset();
    setContentDescription(
        enabled
            ? "全屏远程画面，单指轻点点击，长按拖动鼠标，双指缩放移动，三指打开键盘"
            : interactive ? "远程画面，可拖动、双指缩放，双击复位" : "远程手机画面");
  }

  void cursor(float x, float y) {
    if (!followCursor
        || gestureActive
        || zoom <= 1
        || !Float.isFinite(x)
        || !Float.isFinite(y)
        || x < 0
        || x > 1
        || y < 0
        || y > 1) return;
    float dw = sourceWidth * baseScale() * zoom, dh = sourceHeight * baseScale() * zoom;
    float px = (getWidth() - dw) / 2 + panX + x * dw;
    float py = (getHeight() - dh) / 2 + panY + y * dh;
    float margin = Math.min(getWidth(), getHeight()) * .12f;
    if (dw > getWidth())
      panX += px < margin ? margin - px : px > getWidth() - margin ? getWidth() - margin - px : 0;
    if (dh > getHeight())
      panY += py < margin ? margin - py : py > getHeight() - margin ? getHeight() - margin - py : 0;
    apply();
  }

  VideoViewport(Context context, TextureView texture, ImageView image, boolean interactive) {
    super(context);
    this.texture = texture;
    this.image = image;
    this.interactive = interactive;
    touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    image.setScaleType(ImageView.ScaleType.MATRIX);
    addView(texture, new LayoutParams(-1, -1));
    addView(image, new LayoutParams(-1, -1));
    setContentDescription(interactive ? "远程画面，可拖动、双指缩放，双击复位" : "远程手机画面");
  }

  void fit(int width, int height) {
    if (width > 0 && height > 0) {
      if (sourceWidth != width || sourceHeight != height) {
        endGesture();
        sourceWidth = width;
        sourceHeight = height;
        reset();
      }
    }
    apply();
  }

  void reset() {
    endGesture();
    zoom = 1;
    panX = panY = 0;
    apply();
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldW, int oldH) {
    super.onSizeChanged(w, h, oldW, oldH);
    if (w != oldW || h != oldH) endGesture();
    apply();
  }

  private float baseScale() {
    return sourceWidth > 0 && sourceHeight > 0
        ? Math.min((float) getWidth() / sourceWidth, (float) getHeight() / sourceHeight)
        : 1;
  }

  private void apply() {
    int w = getWidth(), h = getHeight();
    if (sourceWidth < 1 || sourceHeight < 1 || w < 1 || h < 1) return;
    float scale = baseScale() * zoom;
    float dw = sourceWidth * scale, dh = sourceHeight * scale;
    // Fullscreen centers letterboxed axes; the ordinary local viewport retains its free pan.
    float limitX = (remoteGestures ? Math.max(0, dw - w) : Math.abs(dw - w)) / 2;
    float limitY = (remoteGestures ? Math.max(0, dh - h) : Math.abs(dh - h)) / 2;
    panX = Math.max(-limitX, Math.min(limitX, panX));
    panY = Math.max(-limitY, Math.min(limitY, panY));
    float left = (w - dw) / 2 + panX, top = (h - dh) / 2 + panY;
    Matrix video = new Matrix();
    video.setScale(dw / w, dh / h);
    video.postTranslate(left, top);
    texture.setTransform(video);
    Matrix jpeg = new Matrix();
    // ImageView uses drawable intrinsic pixels; decoded bitmaps may have density metadata.
    float iw = image.getDrawable() == null ? sourceWidth : image.getDrawable().getIntrinsicWidth();
    float ih =
        image.getDrawable() == null ? sourceHeight : image.getDrawable().getIntrinsicHeight();
    jpeg.setScale(dw / Math.max(1, iw), dh / Math.max(1, ih));
    jpeg.postTranslate(left, top);
    image.setImageMatrix(jpeg);
  }

  @Override
  public boolean onInterceptTouchEvent(MotionEvent e) {
    return interactive || remoteGestures;
  }

  @Override
  public boolean onTouchEvent(MotionEvent e) {
    if (remoteGestures) return onRemoteTouchEvent(e);
    if (!interactive) return super.onTouchEvent(e);
    int action = e.getActionMasked(), count = e.getPointerCount();
    if (!gestureActive && action != MotionEvent.ACTION_DOWN) return true;
    float x = count > 1 ? (e.getX(0) + e.getX(1)) / 2 : e.getX(),
        y = count > 1 ? (e.getY(0) + e.getY(1)) / 2 : e.getY();
    float span = count > 1 ? (float) Math.hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0)) : 0;
    if (action == MotionEvent.ACTION_DOWN) {
      endGesture();
      gestureActive = true;
      disallowParentIntercept(true);
      lastX = downX = x;
      lastY = downY = y;
      lastSpan = 0;
      downAt = e.getEventTime();
      moved = multi = false;
    } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
      lastX = x;
      lastY = y;
      lastSpan = span;
      multi = true;
    } else if (action == MotionEvent.ACTION_MOVE) {
      float before = zoom;
      if (count > 1 && lastSpan > 1) zoom = Math.max(1, Math.min(5, zoom * span / lastSpan));
      float ratio = zoom / before;
      // Keep the point under the pinch center stationary while translating that center.
      panX = (panX - (lastX - getWidth() / 2f)) * ratio + x - getWidth() / 2f;
      panY = (panY - (lastY - getHeight() / 2f)) * ratio + y - getHeight() / 2f;
      if (Math.hypot(x - downX, y - downY) > 8 || count > 1) moved = true;
      lastX = x;
      lastY = y;
      lastSpan = span;
      apply();
    } else if (action == MotionEvent.ACTION_POINTER_UP) {
      int keep = e.getActionIndex() == 0 ? 1 : 0;
      lastX = e.getX(keep);
      lastY = e.getY(keep);
      lastSpan = 0;
    } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
      gestureActive = false;
      if (action == MotionEvent.ACTION_UP && !multi && !moved && e.getEventTime() - downAt < 250) {
        if (lastTap != 0 && e.getEventTime() - lastTap < 300) {
          reset();
          lastTap = 0;
        } else lastTap = e.getEventTime();
        performClick();
      }
      disallowParentIntercept(false);
    }
    return true;
  }

  private boolean onRemoteTouchEvent(MotionEvent e) {
    int action = e.getActionMasked(), count = e.getPointerCount();
    if (action == MotionEvent.ACTION_DOWN) {
      endGesture();
      gestureActive = true;
      disallowParentIntercept(true);
      downX = lastX = e.getX();
      downY = lastY = e.getY();
      downAt = e.getEventTime();
      lastSpan = 0;
      moved = multi = keyboardGesture = false;
      if (dragListener != null && sourcePoint(downX, downY, false) != null)
        postDelayed(hold, ViewConfiguration.getLongPressTimeout());
      return true;
    }
    // Configuration changes and cancelled gestures cannot turn leftover events into a click.
    if (!gestureActive) return true;
    if (action == MotionEvent.ACTION_CANCEL) {
      endGesture();
      return true;
    }
    if (action == MotionEvent.ACTION_UP) {
      if (dragging) {
        lastX = e.getX();
        lastY = e.getY();
        updateDragPoint(lastX, lastY);
      }
      boolean tapped =
          !dragging
              && !multi
              && !moved
              && !keyboardGesture
              && Math.hypot(e.getX() - downX, e.getY() - downY) <= touchSlop
              && e.getEventTime() - downAt < ViewConfiguration.getLongPressTimeout();
      // End before callbacks: showing the keyboard or changing the view may reenter this view.
      endGesture();
      if (tapped) dispatchRemoteTap(e.getX(), e.getY());
      return true;
    }
    if (count >= 3) {
      removeCallbacks(hold);
      endDrag();
      multi = true;
      lastSpan = 0;
      if (!keyboardGesture) {
        keyboardGesture = true;
        if (keyboardListener != null) keyboardListener.run();
      }
    }
    // After a three-finger gesture, consume every remaining finger until the final ACTION_UP.
    if (keyboardGesture) return true;
    if (action == MotionEvent.ACTION_POINTER_DOWN) {
      removeCallbacks(hold);
      endDrag();
      multi = true;
      if (count == 2) rebasePinch(e);
      return true;
    }
    if (action == MotionEvent.ACTION_POINTER_UP) {
      multi = true;
      // The event still contains the departing pointer. A subsequent pair starts a new pinch.
      lastSpan = 0;
      return true;
    }
    if (action == MotionEvent.ACTION_MOVE) {
      if (count == 1) {
        lastX = e.getX();
        lastY = e.getY();
        if (dragging) {
          updateDragPoint(lastX, lastY);
          if (dragListener != null) dragListener.move(dragX, dragY);
        } else if (Math.hypot(lastX - downX, lastY - downY) > touchSlop) {
          moved = true;
          removeCallbacks(hold);
        }
        return true;
      }
      multi = true;
      if (count == 2 && pinchEnabled) {
        float x = (e.getX(0) + e.getX(1)) / 2;
        float y = (e.getY(0) + e.getY(1)) / 2;
        float span = (float) Math.hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0));
        if (lastSpan > 1) {
          float before = zoom;
          zoom = Math.max(1, Math.min(5, zoom * span / lastSpan));
          float ratio = zoom / before;
          // Preserve the source point beneath the moving midpoint, including while at a limit.
          panX = (panX - (lastX - getWidth() / 2f)) * ratio + x - getWidth() / 2f;
          panY = (panY - (lastY - getHeight() / 2f)) * ratio + y - getHeight() / 2f;
          apply();
        }
        lastX = x;
        lastY = y;
        lastSpan = span;
      }
    }
    return true;
  }

  private void rebasePinch(MotionEvent e) {
    lastX = (e.getX(0) + e.getX(1)) / 2;
    lastY = (e.getY(0) + e.getY(1)) / 2;
    lastSpan = (float) Math.hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0));
  }

  private void dispatchRemoteTap(float x, float y) {
    if (tapListener == null) return;
    float[] point = sourcePoint(x, y, false);
    if (point == null) return;
    tapListener.tap(point[0], point[1]);
    haptics.tap(false);
    performClick();
  }

  private float[] sourcePoint(float x, float y, boolean clamp) {
    if (sourceWidth < 1
        || sourceHeight < 1
        || getWidth() < 1
        || getHeight() < 1
        || !Float.isFinite(x) || !Float.isFinite(y)) return null;
    if (!clamp && (x < 0
        || x > getWidth()
        || y < 0
        || y > getHeight())) return null;
    float scale = baseScale() * zoom;
    float dw = sourceWidth * scale, dh = sourceHeight * scale;
    float left = (getWidth() - dw) / 2 + panX, top = (getHeight() - dh) / 2 + panY;
    // Letterbox touches never produce remote input.
    if (!clamp && (x < left || x > left + dw || y < top || y > top + dh)) return null;
    return new float[] {
        Math.max(0, Math.min(1, (x - left) / dw)),
        Math.max(0, Math.min(1, (y - top) / dh))};
  }

  private void disallowParentIntercept(boolean disallow) {
    ViewParent parent = getParent();
    if (parent != null) parent.requestDisallowInterceptTouchEvent(disallow);
  }

  private void endGesture() {
    removeCallbacks(hold);
    endDrag();
    gestureActive = false;
    keyboardGesture = false;
    lastSpan = 0;
    disallowParentIntercept(false);
    haptics.reset();
  }

  private void endDrag() {
    if (!dragging) return;
    dragging = false;
    // Keep the last source point even if a rotation/detach temporarily gives us zero size.
    if (dragListener != null) dragListener.end(dragX, dragY);
    haptics.dragEnd();
  }

  private void updateDragPoint(float x, float y) {
    float[] point = sourcePoint(x, y, true);
    if (point != null) { dragX = point[0]; dragY = point[1]; }
  }

  @Override
  public void onWindowFocusChanged(boolean hasWindowFocus) {
    super.onWindowFocusChanged(hasWindowFocus);
    if (!hasWindowFocus) endGesture();
  }

  @Override
  protected void onDetachedFromWindow() {
    endGesture();
    super.onDetachedFromWindow();
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }
}
