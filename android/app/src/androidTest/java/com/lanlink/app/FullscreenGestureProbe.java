package com.lanlink.app;

import android.app.Activity;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.drawable.ShapeDrawable;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** Real multi-pointer input, with in-memory senders so no remote device receives input. */
final class FullscreenGestureProbe {
  private static final int SECOND_DOWN = MotionEvent.ACTION_POINTER_DOWN | (1 << 8);
  private static final int SECOND_UP = MotionEvent.ACTION_POINTER_UP | (1 << 8);
  private static final int THIRD_DOWN = MotionEvent.ACTION_POINTER_DOWN | (2 << 8);
  private static final int THIRD_UP = MotionEvent.ACTION_POINTER_UP | (2 << 8);

  private static void check(boolean value, String detail) {
    if (!value) throw new AssertionError(detail);
  }

  private static void near(float actual, float expected, String detail) {
    check(Math.abs(actual - expected) < .002f, detail + ": " + actual + " != " + expected);
  }

  /** Preserves Android downTime and pointer identities through each gesture. */
  private static final class Gesture {
    final View target;
    final long downAt = SystemClock.uptimeMillis();
    long elapsed;

    Gesture(View target) {
      this.target = target;
    }

    Gesture send(int action, float... xy) {
      int[] ids = new int[xy.length / 2];
      for (int i = 0; i < ids.length; i++) ids[i] = i;
      return sendIds(action, ids, xy);
    }

    Gesture sendIds(int action, int[] ids, float... xy) {
      check(ids.length * 2 == xy.length, "event coordinates match pointer identities");
      MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[ids.length];
      MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[ids.length];
      for (int i = 0; i < ids.length; i++) {
        properties[i] = new MotionEvent.PointerProperties();
        properties[i].id = ids[i];
        properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
        coords[i] = new MotionEvent.PointerCoords();
        coords[i].x = xy[i * 2];
        coords[i].y = xy[i * 2 + 1];
        coords[i].pressure = 1;
        coords[i].size = 1;
      }
      MotionEvent event = MotionEvent.obtain(
          downAt, downAt + elapsed, action, ids.length, properties, coords, 0, 0,
          1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
      try {
        target.dispatchTouchEvent(event);
      } finally {
        event.recycle();
      }
      elapsed += 16;
      return this;
    }
  }

  private static void tap(View target, float x, float y) {
    new Gesture(target).send(MotionEvent.ACTION_DOWN, x, y).send(MotionEvent.ACTION_UP, x, y);
  }

  private static void size(View view, int width, int height) {
    view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
    view.layout(0, 0, width, height);
  }

  private static final class ViewportFixture {
    final FrameLayout parent;
    final VideoViewport viewport;
    final List<float[]> taps = new ArrayList<>();
    final AtomicInteger keyboards = new AtomicInteger();

    ViewportFixture(Context context, boolean legacyInteractive) {
      parent = new FrameLayout(context);
      ImageView image = new ImageView(context);
      ShapeDrawable frame = new ShapeDrawable();
      frame.setIntrinsicWidth(1920);
      frame.setIntrinsicHeight(1080);
      image.setImageDrawable(frame);
      viewport = new VideoViewport(context, new TextureView(context), image, legacyInteractive);
      parent.addView(viewport, new FrameLayout.LayoutParams(600, 600));
      size(parent, 600, 600);
    }

    void remote(boolean enabled, boolean pinch) {
      viewport.setRemoteGestures(enabled, pinch,
          (x, y) -> taps.add(new float[] {x, y}), keyboards::incrementAndGet);
    }

    void lastTap(float x, float y, String detail) {
      check(!taps.isEmpty(), detail + " delivered");
      float[] actual = taps.get(taps.size() - 1);
      near(actual[0], x, detail + " x");
      near(actual[1], y, detail + " y");
    }
  }

  private static void viewportGestures(Instrumentation test) {
    test.runOnMainSync(() -> {
      ViewportFixture f = new ViewportFixture(test.getTargetContext(), true);
      VideoViewport v = f.viewport;
      f.remote(true, true);
      tap(v, 300, 300);
      check(f.taps.isEmpty(), "no absolute click before remote frame dimensions are known");
      v.fit(1920, 1080);
      tap(v, 150, 215.625f);
      f.lastTap(.25f, .25f, "letterboxed frame maps top-left quarter");
      tap(v, 450, 384.375f);
      f.lastTap(.75f, .75f, "letterboxed frame maps bottom-right quarter");
      int clicks = f.taps.size();
      tap(v, 300, 50);
      tap(v, 300, 550);
      check(f.taps.size() == clicks, "tapping top/bottom letterbox sends no remote click");

      new Gesture(v).send(MotionEvent.ACTION_DOWN, 150, 300)
          .send(MotionEvent.ACTION_MOVE, 450, 300).send(MotionEvent.ACTION_UP, 450, 300);
      near(v.panX, 0, "single-finger drag does not pan remote frame");
      near(v.panY, 0, "single-finger drag keeps vertical fit");
      check(f.taps.size() == clicks, "single-finger drag is not a tap");
      Gesture longPress = new Gesture(v).send(MotionEvent.ACTION_DOWN, 300, 300);
      longPress.elapsed = ViewConfiguration.getLongPressTimeout() + 20;
      longPress.send(MotionEvent.ACTION_UP, 300, 300);
      check(f.taps.size() == clicks, "holding remote frame does not become a click");
      new Gesture(v).send(MotionEvent.ACTION_DOWN, 300, 300)
          .send(MotionEvent.ACTION_CANCEL, 300, 300);
      check(f.taps.size() == clicks, "cancelled tap sends no remote input");

      Gesture pinch = new Gesture(v).send(MotionEvent.ACTION_DOWN, 250, 300)
          .send(SECOND_DOWN, 250, 300, 350, 300)
          .send(MotionEvent.ACTION_MOVE, 200, 300, 400, 300);
      near(v.zoom, 2, "two-finger pinch doubles remote frame size");
      pinch.send(MotionEvent.ACTION_MOVE, 250, 300, 450, 300);
      near(v.panX, 50, "two-finger translation pans the zoomed frame");
      pinch.send(SECOND_UP, 250, 300, 450, 300)
          .send(MotionEvent.ACTION_MOVE, 400, 300).send(MotionEvent.ACTION_UP, 400, 300);
      near(v.panX, 50, "remaining pinch finger cannot pan or jump the frame");
      check(f.taps.size() == clicks, "pinch ending with one finger never generates a click");
      tap(v, 450, 300);
      f.lastTap(7f / 12f, .5f, "zoom and translation invert into remote coordinates");
      tap(v, 450, 300);
      near(v.zoom, 2, "two remote taps never trigger legacy double-tap reset");
      check(f.taps.size() == clicks + 2, "two direct remote taps both reach the target");
      tap(v, -1, 300);
      tap(v, 601, 300);
      check(f.taps.size() == clicks + 2, "zoomed frame outside viewport cannot receive clicks");

      float[] video = {0, 0, 600, 600}, jpeg = {0, 0, 1920, 1080};
      v.texture.getTransform(new Matrix()).mapPoints(video);
      v.image.getImageMatrix().mapPoints(jpeg);
      for (int i = 0; i < video.length; i++)
        near(video[i], jpeg[i], "JPEG and texture use identical fullscreen transforms");

      v.reset();
      clicks = f.taps.size();
      Gesture keyboard = new Gesture(v).send(MotionEvent.ACTION_DOWN, 200, 300)
          .send(SECOND_DOWN, 200, 300, 400, 300)
          .send(THIRD_DOWN, 200, 300, 400, 300, 300, 400);
      check(f.keyboards.get() == 1, "third finger opens keyboard immediately");
      keyboard.send(MotionEvent.ACTION_MOVE, 100, 200, 500, 200, 300, 450)
          .send(THIRD_UP, 100, 200, 500, 200, 300, 450)
          .send(THIRD_DOWN, 100, 200, 500, 200, 300, 450)
          .send(THIRD_UP, 100, 200, 500, 200, 300, 450)
          .send(SECOND_UP, 100, 200, 500, 200)
          .send(MotionEvent.ACTION_MOVE, 400, 400).send(MotionEvent.ACTION_UP, 400, 400);
      check(f.keyboards.get() == 1, "same three-finger gesture opens keyboard only once");
      check(f.taps.size() == clicks, "three-finger keyboard gesture never clicks");
      near(v.zoom, 1, "keyboard gesture freezes scale after third pointer");
      near(v.panX, 0, "keyboard gesture freezes horizontal movement");
      near(v.panY, 0, "keyboard gesture freezes vertical movement");
      new Gesture(v).send(MotionEvent.ACTION_DOWN, 200, 300)
          .send(SECOND_DOWN, 200, 300, 400, 300)
          .send(THIRD_DOWN, 200, 300, 400, 300, 300, 400)
          .send(MotionEvent.ACTION_CANCEL, 200, 300, 400, 300, 300, 400);
      check(f.keyboards.get() == 2, "a fresh three-finger gesture can open keyboard again");

      f.remote(true, false);
      new Gesture(v).send(MotionEvent.ACTION_DOWN, 250, 300)
          .send(SECOND_DOWN, 250, 300, 350, 300)
          .send(MotionEvent.ACTION_MOVE, 200, 350, 500, 350)
          .send(SECOND_UP, 200, 350, 500, 350).send(MotionEvent.ACTION_UP, 200, 350);
      near(v.zoom, 1, "disabled pinch keeps fit scale");
      near(v.panX, 0, "disabled pinch prevents horizontal panning");
      near(v.panY, 0, "disabled pinch prevents vertical panning");
      check(f.taps.size() == clicks, "disabled pinch still suppresses multi-finger clicks");
      new Gesture(v).send(MotionEvent.ACTION_DOWN, 200, 300)
          .send(SECOND_DOWN, 200, 300, 400, 300)
          .send(THIRD_DOWN, 200, 300, 400, 300, 300, 400)
          .send(MotionEvent.ACTION_CANCEL, 200, 300, 400, 300, 300, 400);
      check(f.keyboards.get() == 3, "keyboard gesture works while pinch is disabled");
      tap(v, 300, 300);
      f.lastTap(.5f, .5f, "tap recovers after cancellation and disabled pinch");

      f.remote(false, true);
      clicks = f.taps.size();
      new Gesture(v).send(MotionEvent.ACTION_DOWN, 300, 300)
          .send(MotionEvent.ACTION_MOVE, 300, 350).send(MotionEvent.ACTION_UP, 300, 350);
      near(v.panY, 50, "leaving fullscreen restores legacy local viewport panning");
      check(f.taps.size() == clicks, "legacy viewport does not send remote taps");

      f.remote(true, true);
      Gesture interrupted = new Gesture(v).send(MotionEvent.ACTION_DOWN, 250, 300)
          .send(SECOND_DOWN, 250, 300, 350, 300);
      f.remote(false, true);
      interrupted.send(MotionEvent.ACTION_MOVE, 100, 400, 500, 400)
          .send(SECOND_UP, 100, 400, 500, 400).send(MotionEvent.ACTION_UP, 100, 400);
      near(v.zoom, 1, "exiting fullscreen cancels a pinch before remaining events arrive");
      near(v.panY, 0, "remaining events after fullscreen exit do not jump the local frame");

      ViewportFixture phone = new ViewportFixture(test.getTargetContext(), false);
      phone.viewport.fit(1080, 1920);
      phone.remote(true, true);
      tap(phone.viewport, 300, 150);
      phone.lastTap(.5f, .25f, "fullscreen enables gestures for portrait phone viewport");
      int phoneClicks = phone.taps.size();
      tap(phone.viewport, 50, 300);
      tap(phone.viewport, 550, 300);
      check(phone.taps.size() == phoneClicks, "portrait side bars never generate remote clicks");
      phone.remote(false, true);
      tap(phone.viewport, 300, 300);
      check(phone.taps.size() == phoneClicks, "phone viewport stops sending taps on exit");
    });
  }

  private static int buttons(List<JSONObject> events, int button, boolean down) {
    int count = 0;
    for (JSONObject event : events)
      if ("button".equals(event.optString("kind")) && event.optInt("button") == button
          && event.optBoolean("down") == down) count++;
    return count;
  }

  private static void drain(TouchpadView pad) throws Exception {
    pad.worker.submit(() -> {
      try {
        pad.flush();
      } catch (Exception error) {
        throw new AssertionError(error);
      }
    }).get(3, TimeUnit.SECONDS);
  }

  private static void touchpadGestures(Instrumentation test) throws Exception {
    List<JSONObject> events = new CopyOnWriteArrayList<>();
    List<Exception> errors = new CopyOnWriteArrayList<>();
    TouchpadView[] fixture = {null};
    test.runOnMainSync(() -> {
      FrameLayout parent = new FrameLayout(test.getTargetContext());
      fixture[0] = new TouchpadView(test.getTargetContext(), events::add, errors::add);
      parent.addView(fixture[0], new FrameLayout.LayoutParams(600, 600));
      size(parent, 600, 600);
      fixture[0].setCompactDark(true);
    });
    TouchpadView pad = fixture[0];
    try {
      test.runOnMainSync(() -> tap(pad, 150, 150));
      drain(pad);
      check(buttons(events, 0, true) == 1 && buttons(events, 0, false) == 1,
          "touchpad tap emits a balanced native left-button click");
      events.clear();
      test.runOnMainSync(() -> new Gesture(pad).send(MotionEvent.ACTION_DOWN, 150, 150)
          .send(MotionEvent.ACTION_MOVE, 400, 250).send(MotionEvent.ACTION_UP, 400, 250));
      drain(pad);
      double dx = 0, dy = 0;
      for (JSONObject event : events) {
        if ("relative".equals(event.optString("kind"))) {
          dx += event.optDouble("dx");
          dy += event.optDouble("dy");
        }
        check(!"button".equals(event.optString("kind")), "moving the pointer does not click");
      }
      float density = test.getTargetContext().getResources().getDisplayMetrics().density;
      near((float) dx, 375 / density, "touchpad preserves total relative x movement");
      near((float) dy, 150 / density, "touchpad preserves total relative y movement");

      events.clear();
      test.runOnMainSync(() -> new Gesture(pad).send(MotionEvent.ACTION_DOWN, 150, 150)
          .send(SECOND_DOWN, 150, 150, 250, 150)
          .send(MotionEvent.ACTION_MOVE, 150, 350, 250, 350)
          .send(MotionEvent.ACTION_POINTER_UP, 150, 350, 250, 350)
          .sendIds(MotionEvent.ACTION_MOVE, new int[] {1}, 300, 400)
          .sendIds(MotionEvent.ACTION_UP, new int[] {1}, 300, 400));
      drain(pad);
      boolean scrolled = false;
      for (JSONObject event : events) {
        if ("wheel".equals(event.optString("kind")) && event.optInt("delta") != 0) scrolled = true;
        check(!"button".equals(event.optString("kind")), "two-finger scroll never clicks");
        check(!"relative".equals(event.optString("kind")),
            "remaining scroll finger never jumps the remote pointer");
      }
      check(scrolled, "two-finger movement sends native wheel input");

      events.clear();
      test.runOnMainSync(() -> new Gesture(pad).send(MotionEvent.ACTION_DOWN, 150, 150)
          .send(SECOND_DOWN, 150, 150, 250, 150)
          .send(SECOND_UP, 150, 150, 250, 150).send(MotionEvent.ACTION_UP, 150, 150));
      drain(pad);
      check(buttons(events, 2, true) == 1 && buttons(events, 2, false) == 1
          && buttons(events, 0, true) == 0, "two-finger tap sends only a balanced right click");
      events.clear();
      test.runOnMainSync(() -> new Gesture(pad).send(MotionEvent.ACTION_DOWN, 150, 150)
          .send(SECOND_DOWN, 150, 150, 250, 150)
          .send(THIRD_DOWN, 150, 150, 250, 150, 200, 250)
          .send(THIRD_UP, 150, 150, 250, 150, 200, 250)
          .send(SECOND_UP, 150, 150, 250, 150).send(MotionEvent.ACTION_UP, 150, 150));
      drain(pad);
      check(events.isEmpty(), "three-finger pad gesture is not misread as a right click");

      test.runOnMainSync(() -> new Gesture(pad).send(MotionEvent.ACTION_DOWN, 150, 150)
          .send(MotionEvent.ACTION_CANCEL, 150, 150));
      drain(pad);
      check(events.isEmpty(), "cancelled touchpad tap produces no click");
      test.runOnMainSync(() -> {
        new Gesture(pad).send(MotionEvent.ACTION_DOWN, 150, 150);
        pad.cancelGesture();
        pad.hold.run();
      });
      drain(pad);
      check(events.isEmpty(), "cancelling a pad removes the pending hold and tap");

      test.runOnMainSync(() -> {
        Gesture drag = new Gesture(pad).send(MotionEvent.ACTION_DOWN, 150, 150);
        pad.hold.run(); // Run the scheduled hold deterministically on the UI thread.
        drag.send(MotionEvent.ACTION_MOVE, 400, 250).send(MotionEvent.ACTION_CANCEL, 400, 250);
      });
      drain(pad);
      check(buttons(events, 0, true) == 1 && buttons(events, 0, false) == 1,
          "ACTION_CANCEL releases a dragged remote mouse button exactly once");
      events.clear();
      test.runOnMainSync(() -> {
        new Gesture(pad).send(MotionEvent.ACTION_DOWN, 150, 150);
        pad.hold.run();
        pad.cancelGesture();
        pad.cancelGesture();
      });
      drain(pad);
      check(buttons(events, 0, true) == 1 && buttons(events, 0, false) == 1,
          "hiding a touchpad releases drag once even if cancellation repeats");
      events.clear();
      test.runOnMainSync(() -> {
        Gesture drag = new Gesture(pad).send(MotionEvent.ACTION_DOWN, 150, 150);
        pad.hold.run();
        drag.send(SECOND_DOWN, 150, 150, 250, 150)
            .send(MotionEvent.ACTION_MOVE, 150, 350, 250, 350)
            .send(SECOND_UP, 150, 350, 250, 350).send(MotionEvent.ACTION_UP, 150, 350);
      });
      drain(pad);
      check(buttons(events, 0, true) == 1 && buttons(events, 0, false) == 1
          && buttons(events, 2, true) == 0, "adding a second finger releases drag without extra clicks");
      events.clear();
      test.runOnMainSync(() -> {
        new Gesture(pad).send(MotionEvent.ACTION_DOWN, 150, 150);
        pad.hold.run();
        pad.close();
      });
      check(pad.worker.awaitTermination(3, TimeUnit.SECONDS), "closed touchpad stops its input worker");
      check(buttons(events, 0, true) == 1 && buttons(events, 0, false) == 1,
          "closing a fullscreen pad never leaves the remote button held");
      check(errors.isEmpty(), "native input sender completed without asynchronous failures");
    } finally {
      test.runOnMainSync(pad::close);
      pad.worker.awaitTermination(3, TimeUnit.SECONDS);
    }
  }

  static String run(Instrumentation test) throws Exception {
    viewportGestures(test);
    viewportDragging(test);
    touchpadGestures(test);
    TouchHapticsProbe.run(test);
    RemotePointerProbe.run();
    return "PASS: fullscreen coordinate mapping, letterbox exclusion, pinch/pan, gesture settings,"
        + " three-finger keyboard, fullscreen long-press drag, native input ordering,"
        + " cancellation, touchpad movement/scroll/click, haptics and drag release";
  }

  private static void viewportDragging(Instrumentation test) {
    test.runOnMainSync(() -> {
      ViewportFixture f = new ViewportFixture(test.getTargetContext(), true);
      VideoViewport v = f.viewport;
      List<float[]> dragEvents = new ArrayList<>();
      VideoViewport.DragListener listener = new VideoViewport.DragListener() {
        public void start(float x, float y) { dragEvents.add(new float[] {0, x, y}); }
        public void move(float x, float y) { dragEvents.add(new float[] {1, x, y}); }
        public void end(float x, float y) { dragEvents.add(new float[] {2, x, y}); }
      };
      v.setRemoteGestures(true, true, (x,y) -> f.taps.add(new float[] {x,y}),
          f.keyboards::incrementAndGet, listener);
      new Gesture(v).send(MotionEvent.ACTION_DOWN, 300, 300);
      v.hold.run();
      check(dragEvents.isEmpty(), "no drag before source dimensions arrive");
      v.fit(1920, 1080);
      Gesture drag = new Gesture(v).send(MotionEvent.ACTION_DOWN, 150, 215.625f);
      v.hold.run();
      v.hold.run();
      drag.send(MotionEvent.ACTION_MOVE, 450, 384.375f).send(MotionEvent.ACTION_UP, 600, 600);
      check(dragEvents.size() == 3, "hold starts once, moves, then ends without an extra click");
      near(dragEvents.get(0)[1], .25f, "drag starts at normalized source x");
      near(dragEvents.get(0)[2], .25f, "drag starts at normalized source y");
      near(dragEvents.get(1)[1], .75f, "drag follows normalized source x");
      near(dragEvents.get(1)[2], .75f, "drag follows normalized source y");
      near(dragEvents.get(2)[1], 1, "release includes final x");
      near(dragEvents.get(2)[2], 1, "release outside source clamps to screen edge");
      check(f.taps.isEmpty(), "drag release never synthesizes a click");
      near(v.zoom, 1, "remote drag does not change zoom");
      near(v.panX, 0, "remote drag does not pan the image");

      dragEvents.clear();
      drag = new Gesture(v).send(MotionEvent.ACTION_DOWN, 300, 50);
      v.hold.run();
      drag.send(MotionEvent.ACTION_UP, 300, 50);
      check(dragEvents.isEmpty(), "holding black bars never presses the remote mouse");
      drag = new Gesture(v).send(MotionEvent.ACTION_DOWN, 150, 300)
          .send(MotionEvent.ACTION_MOVE, 450, 300);
      v.hold.run();
      drag.send(MotionEvent.ACTION_UP, 450, 300);
      check(dragEvents.isEmpty(), "moving before hold timeout cancels drag recognition");

      for (int interruption = 0; interruption < 6; interruption++) {
        v.setRemoteGestures(true, true, (x,y) -> f.taps.add(new float[] {x,y}),
            f.keyboards::incrementAndGet, listener);
        dragEvents.clear();
        drag = new Gesture(v).send(MotionEvent.ACTION_DOWN, 300, 300);
        v.hold.run();
        switch (interruption) {
          case 0: drag.send(MotionEvent.ACTION_CANCEL, 300, 300); break;
          case 1: drag.send(SECOND_DOWN, 300, 300, 400, 300); break;
          case 2: v.onWindowFocusChanged(false); break;
          case 3: v.setRemoteGestures(true, false, null, null, null); break;
          case 4: v.setRemoteGestures(false, true, null, null, null); break;
          default: v.reset();
        }
        drag.send(MotionEvent.ACTION_CANCEL, 300, 300);
        v.hold.run();
        check(dragEvents.size() == 2 && dragEvents.get(1)[0] == 2,
            "interruption releases drag exactly once: " + interruption);
      }
      v.setRemoteGestures(true, true, null, null, listener);
      v.zoom = 2;
      v.panX = 50;
      v.fit(1920, 1080);
      dragEvents.clear();
      drag = new Gesture(v).send(MotionEvent.ACTION_DOWN, 450, 300);
      v.hold.run();
      drag.send(MotionEvent.ACTION_UP, 450, 300);
      near(dragEvents.get(0)[1], 7f/12f, "drag inverts zoom and pan into source coordinates");
      check(f.taps.isEmpty(), "interruptions and zoomed drags never emit clicks");
    });
  }

  private static View findVisible(View root, String label) {
    if (root.getVisibility() != View.VISIBLE) return null;
    if (label.contentEquals(root.getContentDescription() == null ? "" : root.getContentDescription())
        || root instanceof TextView && label.contentEquals(((TextView) root).getText())) return root;
    if (root instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) root;
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = findVisible(group.getChildAt(i), label);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static void click(View root, String label) {
    View target = findVisible(root, label);
    check(target != null, "visible fullscreen control: " + label);
    check(target.isEnabled(), "enabled fullscreen control: " + label);
    if (target instanceof CompoundButton) {
      CompoundButton toggle = (CompoundButton) target;
      boolean previous = toggle.isChecked();
      toggle.performClick();
      check(toggle.isChecked() != previous, "toggle fullscreen setting: " + label);
    } else check(target.performClick(), "click fullscreen control: " + label);
  }

  private static void settleStage(FrameLayout stage, int width, int height) {
    // A size listener updates child params during layout. Force each pass so View's measure
    // cache cannot reuse the real window's old dimensions when exercising a synthetic resize.
    for (int pass = 0; pass < 3; pass++) {
      forceLayout(stage);
      size(stage, width, height);
    }
  }

  private static void forceLayout(View view) {
    view.forceLayout();
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) forceLayout(group.getChildAt(i));
    }
  }

  private static boolean dismissImmersiveHint(Instrumentation test) {
    AccessibilityNodeInfo root = test.getUiAutomation().getRootInActiveWindow();
    if (root == null) return false;
    try {
      for (String label : new String[] {"Got it", "GOT IT", "知道了", "我知道了"}) {
        for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(label)) {
          CharSequence owner = node.getPackageName(), text = node.getText();
          boolean systemUi = "com.android.systemui".contentEquals(owner == null ? "" : owner)
              || "android".contentEquals(owner == null ? "" : owner);
          if (systemUi && text != null && label.contentEquals(text) && node.isVisibleToUser()
              && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        }
      }
      return false;
    } finally {
      root.recycle();
    }
  }

  private static void capture(Instrumentation test, String name) throws Exception {
    test.waitForIdleSync();
    Thread.sleep(650);
    if (dismissImmersiveHint(test)) {
      test.waitForIdleSync();
      Thread.sleep(350);
    }
    Bitmap screenshot = test.getUiAutomation().takeScreenshot();
    check(screenshot != null, "capture " + name);
    File folder = new File(test.getTargetContext().getExternalFilesDir(null), "ui-check");
    check(folder.isDirectory() || folder.mkdirs(), "create UI check folder");
    try (FileOutputStream output = new FileOutputStream(new File(folder, name + ".png"))) {
      check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output), "save " + name);
    } finally {
      screenshot.recycle();
    }
  }

  /** Clearly labeled synthetic frame makes aspect ratio and clipping visible in screenshots. */
  private static Bitmap demoFrame() {
    Bitmap bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888);
    bitmap.setDensity(Bitmap.DENSITY_NONE);
    Canvas canvas = new Canvas(bitmap);
    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    for (int row = 0; row < 12; row++) {
      for (int col = 0; col < 22; col++) {
        paint.setColor((row + col) % 2 == 0 ? 0xff253348 : 0xff293a51);
        canvas.drawRect(col * 90, row * 90, (col + 1) * 90, (row + 1) * 90, paint);
      }
    }
    paint.setColor(0xffa99deb);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(4);
    canvas.drawRect(8, 8, 1912, 1072, paint);
    paint.setStyle(Paint.Style.FILL);
    paint.setTextAlign(Paint.Align.CENTER);
    paint.setTextSize(72);
    paint.setColor(0xffeef0fa);
    canvas.drawText("LanLink", 960, 475, paint);
    paint.setTextSize(34);
    canvas.drawText("模拟远程画面 · 1920 × 1080", 960, 555, paint);
    paint.setTextSize(26);
    paint.setColor(0xffaeb9d2);
    canvas.drawText("UI TEST FIXTURE", 960, 618, paint);
    return bitmap;
  }

  private static final class FullscreenFixture implements AutoCloseable {
    final Dialog dialog;
    final LinearLayout outer, box;
    final FrameLayout stage;
    final VideoViewport viewport;
    final TouchpadView normalPad;
    final TextView header, hiddenFooter;
    final RemoteFullscreen fullscreen;
    final Bitmap frame;
    final List<JSONObject> input = new CopyOnWriteArrayList<>();
    final List<Exception> errors = new CopyOnWriteArrayList<>();
    final AtomicInteger restoredInsets = new AtomicInteger();
    final int orientation;
    final boolean oldKeepScreenOn;

    FullscreenFixture(Activity activity, boolean supportedPads) {
      orientation = activity.getRequestedOrientation();
      dialog = new Dialog(activity, Ui.dialogTheme());
      outer = new LinearLayout(activity);
      outer.setOrientation(LinearLayout.VERTICAL);
      box = new LinearLayout(activity);
      box.setOrientation(LinearLayout.VERTICAL);
      box.setPadding(7, 11, 13, 17);
      outer.addView(box, new LinearLayout.LayoutParams(-1, -1));
      header = new TextView(activity);
      header.setText("Fixture remote toolbar");
      box.addView(header, new LinearLayout.LayoutParams(-1, 40));
      stage = new FrameLayout(activity);
      viewport = new VideoViewport(activity, new TextureView(activity), new ImageView(activity), true);
      frame = demoFrame();
      viewport.image.setImageBitmap(frame);
      viewport.texture.setVisibility(View.GONE);
      stage.addView(viewport, new FrameLayout.LayoutParams(-1, -1));
      viewport.fit(1920, 1080);
      box.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
      normalPad = new TouchpadView(activity, input::add, errors::add);
      box.addView(normalPad, new LinearLayout.LayoutParams(-1, 80));
      hiddenFooter = new TextView(activity);
      hiddenFooter.setVisibility(View.INVISIBLE);
      box.addView(hiddenFooter, new LinearLayout.LayoutParams(-1, 20));
      RemoteKeyboardView keyboard = new RemoteKeyboardView(activity, new RemoteKeyboardView.Sender() {
        public void text(String text) {}
        public void key(String code, boolean down) {}
      });
      box.addView(keyboard, new LinearLayout.LayoutParams(1, 1));
      dialog.setContentView(outer);
      fullscreen = new RemoteFullscreen(activity, dialog, outer, box, stage, viewport, keyboard,
          normalPad, supportedPads, input::add, errors::add, restoredInsets::incrementAndGet);
      dialog.show();
      dialog.getWindow().setLayout(-1, -1);
      oldKeepScreenOn = (dialog.getWindow().getAttributes().flags
          & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0;
      settleStage(stage, 1200, 600);
    }

    TouchpadView pad(boolean left) {
      for (int i = 0; i < stage.getChildCount(); i++) {
        View child = stage.getChildAt(i);
        if (child instanceof TouchpadView && child.getContentDescription() != null
            && child.getContentDescription().toString().startsWith(left ? "左触控板" : "右触控板"))
          return (TouchpadView) child;
      }
      throw new AssertionError("fullscreen touchpad created");
    }

    ScrollView menu() {
      for (int i = 0; i < stage.getChildCount(); i++)
        if (stage.getChildAt(i) instanceof ScrollView) return (ScrollView) stage.getChildAt(i);
      throw new AssertionError("fullscreen menu created");
    }

    void preservedSurfaces() {
      check(viewport.getParent() == stage && viewport.texture.getParent() == viewport
          && viewport.image.getParent() == viewport,
          "fullscreen keeps the same video viewport and render surface parents");
    }

    void assertFittedFrame() {
      int width = viewport.getWidth(), height = viewport.getHeight();
      check(width > 0 && height > 0, "fitted stream has a measured viewport");
      near(viewport.zoom, 1, "initial fullscreen and menu preview preserve fitted scale");
      float sourceWidth = frame.getWidth(), sourceHeight = frame.getHeight();
      float scale = Math.min(width / sourceWidth, height / sourceHeight);
      float[] bounds = {0, 0, sourceWidth, 0, 0, sourceHeight, sourceWidth, sourceHeight};
      viewport.image.getImageMatrix().mapPoints(bounds);
      float drawnWidth = bounds[2] - bounds[0], drawnHeight = bounds[5] - bounds[1];
      near(drawnWidth / sourceWidth, scale, "stream uses the largest fitting horizontal scale");
      near(drawnHeight / sourceHeight, scale, "stream uses the same vertical scale without stretching");
      near(bounds[0], (width - drawnWidth) / 2, "fitted stream is centered horizontally");
      near(bounds[1], (height - drawnHeight) / 2, "fitted stream is centered vertically");
      near(bounds[0], bounds[4], "fitted stream has no horizontal shear");
      near(bounds[1], bounds[3], "fitted stream has no vertical shear");
      check(bounds[0] >= -.01f && bounds[1] >= -.01f
          && bounds[6] <= width + .01f && bounds[7] <= height + .01f,
          "all four edges of the source frame remain inside the viewport without cropping");
      check(Math.abs(drawnWidth - width) < .01f || Math.abs(drawnHeight - height) < .01f,
          "fitted stream fills at least one viewport dimension at its maximum aspect-preserving size");
      float[] textureBounds = {0, 0, width, 0, 0, height, width, height};
      viewport.texture.getTransform(new Matrix()).mapPoints(textureBounds);
      for (int i = 0; i < bounds.length; i++)
        near(textureBounds[i], bounds[i], "video and still-frame renderers preserve identical fitted edges");
    }

    void assertNoMenuButton() {
      check(findVisible(stage, "全屏菜单") == null && findVisible(stage, "‹  菜单") == null,
          "fullscreen presents no on-screen menu button");
    }

    void assertMenuLayout(int width, int height) {
      settleStage(stage, width, height);
      ScrollView menu = menu();
      String dimensions = " [requested=" + width + "x" + height + ", stage="
          + stage.getWidth() + "x" + stage.getHeight() + ", preview="
          + viewport.getLeft() + "," + viewport.getTop() + " " + viewport.getWidth()
          + "x" + viewport.getHeight() + ", previewParams=" + viewport.getLayoutParams().width
          + "x" + viewport.getLayoutParams().height + ", menu=" + menu.getLeft() + ","
          + menu.getTop() + " " + menu.getWidth() + "x" + menu.getHeight() + "]";
      check(fullscreen.isActive() && fullscreen.isMenuVisible(), "first Back keeps the session active");
      check(viewport.getWidth() > 0 && viewport.getWidth() < width
          && viewport.getHeight() < height, "first Back shrinks the streaming image" + dimensions);
      check(viewport.getLeft() < menu.getLeft()
          && viewport.getRight() <= menu.getLeft(),
          "stream preview sits left of the settings panel" + dimensions);
      check(menu.getRight() <= width && menu.getBottom() <= height,
          "menu stays inside fullscreen bounds after resize" + dimensions);
      check(pad(true).getVisibility() == View.GONE && pad(false).getVisibility() == View.GONE,
          "menu hides both controller touchpads");
      preservedSurfaces();
      assertFittedFrame();
    }

    public void close() {
      fullscreen.close();
      normalPad.close();
      dialog.dismiss();
      viewport.image.setImageDrawable(null);
      if (!frame.isRecycled()) frame.recycle();
    }
  }

  static String runUi(Instrumentation test, Activity activity) throws Exception {
    FullscreenTransitionProbe.run(test, activity);
    SharedPreferences prefs = activity.getSharedPreferences("remote_fullscreen", 0);
    String[] keys = {"left_touchpad", "right_touchpad", "pinch_zoom"};
    boolean[] present = new boolean[keys.length], saved = new boolean[keys.length];
    for (int i = 0; i < keys.length; i++) {
      present[i] = prefs.contains(keys[i]);
      saved[i] = prefs.getBoolean(keys[i], true);
    }
    prefs.edit().putBoolean(keys[0], true).putBoolean(keys[1], true)
        .putBoolean(keys[2], true).commit();
    FullscreenFixture[] holder = {null};
    try {
      test.runOnMainSync(() -> click(activity.getWindow().getDecorView(), "控制中心页面"));
      capture(test, "control-center-features");
      test.runOnMainSync(() -> click(activity.getWindow().getDecorView(), "设备页面"));
      test.runOnMainSync(() -> {
        FullscreenFixture f = holder[0] = new FullscreenFixture(activity, true);
        check(!f.fullscreen.handleBack(), "ordinary viewer leaves Back handling to its owner");
        f.fullscreen.enter();
        settleStage(f.stage, 1200, 600);
        check(f.fullscreen.isActive() && !f.fullscreen.isMenuVisible(), "enter opens full stream");
        check(activity.getRequestedOrientation() == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            "fullscreen requests sensor-aware landscape orientation");
        check(f.viewport.getWidth() == 1200 && f.viewport.getHeight() == 600,
            "stream covers the fullscreen stage");
        check(f.header.getVisibility() == View.GONE && f.normalPad.getVisibility() == View.GONE,
            "fullscreen removes ordinary toolbar and touchpad from the layout");
        check(f.pad(true).getVisibility() == View.VISIBLE && f.pad(false).getVisibility() == View.VISIBLE,
            "both fullscreen controller touchpads start enabled");
        check(f.pad(true).getRight() < f.pad(false).getLeft(), "side touchpads leave the stream center clear");
        for (TouchpadView pad : new TouchpadView[] {f.pad(true), f.pad(false)}) {
          FrameLayout.LayoutParams layout = (FrameLayout.LayoutParams) pad.getLayoutParams();
          check((layout.gravity & android.view.Gravity.VERTICAL_GRAVITY_MASK) == android.view.Gravity.BOTTOM,
              "fullscreen touchpads are anchored to the bottom");
          check(pad.getBottom() == f.stage.getHeight() - layout.bottomMargin,
              "fullscreen touchpad retains a bottom safe margin");
        }
        Bitmap blackBars = Bitmap.createBitmap(1200, 600, Bitmap.Config.ARGB_8888);
        f.viewport.draw(new Canvas(blackBars));
        check(blackBars.getPixel(1, 1) == Color.BLACK, "fullscreen empty area is pure black");
        blackBars.recycle();
        check(!f.viewport.followCursor, "fullscreen direct touch suspends cursor-driven panning");
        f.preservedSurfaces();
        f.assertFittedFrame();
        f.assertNoMenuButton();
        tap(f.viewport, 600, 300);
      });
      holder[0].fullscreen.pointer.worker.submit(() -> {}).get(3, TimeUnit.SECONDS);
      check(buttons(holder[0].input, 0, true) == 1 && buttons(holder[0].input, 0, false) == 1,
          "fullscreen stream sends a balanced direct tap");
      capture(test, "fullscreen-controller");
      test.runOnMainSync(() -> {
        holder[0].assertFittedFrame();
        holder[0].assertNoMenuButton();
      });
      test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
      test.waitForIdleSync();
      // Bounds transitions deliberately keep the previous frame while the menu animates in.
      Thread.sleep(400);
      test.waitForIdleSync();
      test.runOnMainSync(() -> {
        FullscreenFixture f = holder[0];
        check(f.dialog.isShowing() && f.fullscreen.isActive() && f.fullscreen.isMenuVisible(),
            "real system Back reveals the fullscreen menu without closing the viewer");
        f.assertMenuLayout(1200, 600);
        check(findVisible(f.stage, "设置") != null && findVisible(f.stage, "退出全屏") != null,
            "first Back exposes settings and explicit fullscreen exit");
        tap(f.viewport, f.viewport.getWidth() / 2f, f.viewport.getHeight() / 2f);
        f.assertMenuLayout(960, 540);
        f.assertMenuLayout(1200, 600);
      });
      holder[0].fullscreen.pointer.worker.submit(() -> {}).get(3, TimeUnit.SECONDS);
      check(buttons(holder[0].input, 0, true) == 1,
          "stream preview does not send remote input while menu is open");
      capture(test, "fullscreen-menu");
      test.runOnMainSync(() -> {
        FullscreenFixture f = holder[0];
        click(f.stage, "设置");
        check(findVisible(f.stage, "全屏设置") != null, "settings open inside the fullscreen panel");
      });
      capture(test, "fullscreen-settings");
      test.runOnMainSync(() -> {
        FullscreenFixture f = holder[0];
        click(f.stage, "左侧触控板");
        click(f.stage, "右侧触控板");
        f.viewport.zoom = 3;
        f.viewport.fit(1920, 1080);
        click(f.stage, "双指缩放");
        near(f.viewport.zoom, 1, "turning off pinch restores the fitted frame");
        check(!prefs.getBoolean(keys[0], true) && !prefs.getBoolean(keys[1], true)
            && !prefs.getBoolean(keys[2], true), "fullscreen switches persist independently");
        check(f.fullscreen.handleBack() && f.fullscreen.isMenuVisible()
            && findVisible(f.stage, "设置") != null, "Back from settings returns to the menu");
        click(f.stage, "继续全屏");
        settleStage(f.stage, 1200, 600);
        check(!f.fullscreen.isMenuVisible() && f.fullscreen.isActive(), "resume keeps fullscreen active");
        check(f.pad(true).getVisibility() == View.GONE && f.pad(false).getVisibility() == View.GONE,
            "disabled pads remain hidden when streaming resumes");
        f.assertFittedFrame();
        f.assertNoMenuButton();
        new Gesture(f.viewport).send(MotionEvent.ACTION_DOWN, 500, 300)
            .send(SECOND_DOWN, 500, 300, 700, 300)
            .send(MotionEvent.ACTION_MOVE, 400, 300, 800, 300)
            .send(SECOND_UP, 400, 300, 800, 300).send(MotionEvent.ACTION_UP, 400, 300);
        near(f.viewport.zoom, 1, "settings disable actual two-finger scale input");
        check(f.fullscreen.handleBack(), "system Back handling reopens fullscreen menu");
        settleStage(f.stage, 1200, 600);
        click(f.stage, "退出全屏");
        check(!f.fullscreen.isActive() && f.dialog.isShowing(), "explicit exit returns to the remote viewer");
        check(f.header.getVisibility() == View.VISIBLE && f.normalPad.getVisibility() == View.VISIBLE
            && f.hiddenFooter.getVisibility() == View.INVISIBLE,
            "fullscreen exit restores each previous toolbar visibility");
        check(f.box.getPaddingLeft() == 7 && f.box.getPaddingTop() == 11
            && f.box.getPaddingRight() == 13 && f.box.getPaddingBottom() == 17,
            "fullscreen exit restores viewer spacing");
        check(activity.getRequestedOrientation() == f.orientation && f.restoredInsets.get() == 1,
            "fullscreen exit restores orientation and ordinary insets");
        check(f.viewport.followCursor, "fullscreen exit restores cursor follow preference");
        check(((f.dialog.getWindow().getAttributes().flags
            & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0) == f.oldKeepScreenOn,
            "fullscreen exit restores the previous screen-awake flag");
        f.preservedSurfaces();
        f.close();
        holder[0] = null;

        FullscreenFixture reopened = holder[0] = new FullscreenFixture(activity, true);
        reopened.fullscreen.enter();
        settleStage(reopened.stage, 1200, 600);
        check(reopened.pad(true).getVisibility() == View.GONE && reopened.pad(false).getVisibility() == View.GONE,
            "new remote viewer restores saved left and right pad preferences");
        reopened.fullscreen.showMenu();
        click(reopened.stage, "设置");
        check(!((CompoundButton) findVisible(reopened.stage, "双指缩放")).isChecked(),
            "new remote viewer restores saved pinch preference");
        reopened.close();
        holder[0] = null;

        FullscreenFixture phone = holder[0] = new FullscreenFixture(activity, false);
        phone.fullscreen.enter();
        settleStage(phone.stage, 1200, 600);
        check(phone.pad(true).getVisibility() == View.GONE && phone.pad(false).getVisibility() == View.GONE,
            "devices without relative input do not expose inert controller pads");
        phone.fullscreen.showMenu();
        click(phone.stage, "设置");
        check(!findVisible(phone.stage, "左侧触控板").isEnabled()
            && !findVisible(phone.stage, "右侧触控板").isEnabled(),
            "unsupported controller pad settings clearly remain disabled");
        phone.close();
        holder[0] = null;
      });
      return "PASS: animated menu/resume, rapid reversal, in-flight exit, fullscreen entry,"
          + " bottom-corner touchpads, pure black letterbox, left preview/right menu, Back navigation, saved switches,"
          + " no menu overlay button, maximum uncropped aspect-ratio fit, responsive layout,"
          + " stable render surfaces and viewer restoration";
    } finally {
      test.runOnMainSync(() -> { if (holder[0] != null) holder[0].close(); });
      SharedPreferences.Editor restore = prefs.edit();
      for (int i = 0; i < keys.length; i++) {
        if (present[i]) restore.putBoolean(keys[i], saved[i]); else restore.remove(keys[i]);
      }
      restore.commit();
    }
  }
}
