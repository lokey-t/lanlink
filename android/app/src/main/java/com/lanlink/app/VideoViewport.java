package com.lanlink.app;

import android.content.Context;
import android.graphics.Matrix;
import android.view.MotionEvent;
import android.view.TextureView;
import android.widget.FrameLayout;
import android.widget.ImageView;

/** Local-only viewport. Its gestures never reach the remote input channel. */
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
    image.setScaleType(ImageView.ScaleType.MATRIX);
    addView(texture, new LayoutParams(-1, -1));
    addView(image, new LayoutParams(-1, -1));
    setContentDescription(interactive ? "远程画面，可拖动、双指缩放，双击复位" : "远程手机画面");
  }

  void fit(int width, int height) {
    if (width > 0 && height > 0) {
      if (sourceWidth != width || sourceHeight != height) {
        sourceWidth = width;
        sourceHeight = height;
        reset();
      }
    }
    apply();
  }

  void reset() {
    zoom = 1;
    panX = panY = 0;
    apply();
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldW, int oldH) {
    super.onSizeChanged(w, h, oldW, oldH);
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
    // Keep a zoomed image covering the viewport; fitted letterbox space can be repositioned.
    float limitX = Math.abs(dw - w) / 2, limitY = Math.abs(dh - h) / 2;
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
    return interactive;
  }

  @Override
  public boolean onTouchEvent(MotionEvent e) {
    if (!interactive) return super.onTouchEvent(e);
    int action = e.getActionMasked(), count = e.getPointerCount();
    float x = count > 1 ? (e.getX(0) + e.getX(1)) / 2 : e.getX(),
        y = count > 1 ? (e.getY(0) + e.getY(1)) / 2 : e.getY();
    float span = count > 1 ? (float) Math.hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0)) : 0;
    if (action == MotionEvent.ACTION_DOWN) {
      gestureActive = true;
      getParent().requestDisallowInterceptTouchEvent(true);
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
      getParent().requestDisallowInterceptTouchEvent(false);
    }
    return true;
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }
}
