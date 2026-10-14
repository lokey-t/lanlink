package com.lanlink.app;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.app.Instrumentation;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Attached-window animation checks, independent of codecs, networking and remote computers. */
final class FullscreenTransitionProbe {
  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static Object field(Object target, String name) {
    try {
      Field field = target.getClass().getDeclaredField(name);
      field.setAccessible(true);
      return field.get(target);
    } catch (ReflectiveOperationException error) {
      throw new AssertionError("read transition state: " + name, error);
    }
  }

  private static boolean flag(Object target, String name) {
    return (boolean) field(target, name);
  }

  private static void await(Instrumentation test, BooleanSupplier condition, String message)
      throws Exception {
    long deadline = SystemClock.uptimeMillis() + 6000;
    int stableFrames = 0;
    while (SystemClock.uptimeMillis() < deadline) {
      boolean[] ready = {false};
      test.runOnMainSync(() -> ready[0] = condition.getAsBoolean());
      if (ready[0]) {
        if (++stableFrames >= 5) return;
      } else stableFrames = 0;
      Thread.sleep(16);
    }
    throw new AssertionError(message);
  }

  private static void tap(View view) {
    long now = SystemClock.uptimeMillis();
    for (int action : new int[] {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
      MotionEvent event = MotionEvent.obtain(now, now + (action == MotionEvent.ACTION_UP ? 40 : 0),
          action, view.getWidth() / 2f, view.getHeight() / 2f, 0);
      try { view.dispatchTouchEvent(event); }
      finally { event.recycle(); }
    }
  }

  private static final class Fixture implements AutoCloseable {
    final Activity activity;
    final Dialog dialog;
    final LinearLayout outer, box;
    final FrameLayout stage;
    final TextureView texture;
    final ImageView image;
    final VideoViewport viewport;
    final TouchpadView normalPad;
    final TextView header;
    final RemoteFullscreen fullscreen;
    final Drawable outerBackground = new ColorDrawable(Ui.BG);
    final Drawable stageBackground = new ColorDrawable(Ui.BG);
    final Drawable viewportBackground = new ColorDrawable(Ui.BG);
    final List<Integer> frameWidths = new CopyOnWriteArrayList<>();
    final List<JSONObject> events = new CopyOnWriteArrayList<>();
    final List<Exception> errors = new CopyOnWriteArrayList<>();
    final ViewTreeObserver.OnPreDrawListener sample;
    final int orientation;
    SurfaceTexture surface;
    boolean animations;
    int restoreCount;

    Fixture(Activity activity) {
      this.activity = activity;
      orientation = activity.getRequestedOrientation();
      animations = ValueAnimator.areAnimatorsEnabled();
      dialog = new Dialog(activity, Ui.dialogTheme());
      outer = new LinearLayout(activity);
      outer.setOrientation(LinearLayout.VERTICAL);
      outer.setBackground(outerBackground);
      box = new LinearLayout(activity);
      box.setOrientation(LinearLayout.VERTICAL);
      box.setPadding(7, 11, 13, 17);
      outer.addView(box, new LinearLayout.LayoutParams(-1, -1));
      header = new TextView(activity);
      header.setText("Transition fixture");
      box.addView(header, new LinearLayout.LayoutParams(-1, 48));
      stage = new FrameLayout(activity);
      stage.setBackground(stageBackground);
      texture = new TextureView(activity);
      image = new ImageView(activity);
      image.setImageDrawable(new ColorDrawable(0xff32445b));
      viewport = new VideoViewport(activity, texture, image, true);
      viewport.setBackground(viewportBackground);
      viewport.setClipToOutline(true);
      viewport.followCursor = true;
      viewport.fit(1920, 1080);
      stage.addView(viewport, new FrameLayout.LayoutParams(-1, -1));
      box.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
      normalPad = new TouchpadView(activity, events::add, errors::add);
      box.addView(normalPad, new LinearLayout.LayoutParams(-1, 80));
      RemoteKeyboardView keyboard = new RemoteKeyboardView(activity, new RemoteKeyboardView.Sender() {
        public void text(String text) {}
        public void key(String code, boolean down) {}
      });
      box.addView(keyboard, new LinearLayout.LayoutParams(1, 1));
      dialog.setContentView(outer);
      fullscreen = new RemoteFullscreen(activity, dialog, outer, box, stage, viewport,
          keyboard, normalPad, true, events::add, errors::add, () -> {
            restoreCount++;
            outer.setOnApplyWindowInsetsListener(null);
            outer.setPadding(0, 0, 0, 0);
          });
      sample = () -> {
        frameWidths.add(viewport.getWidth());
        return true;
      };
      stage.getViewTreeObserver().addOnPreDrawListener(sample);
      dialog.show();
      dialog.getWindow().setLayout(-1, -1);
    }

    boolean settled(boolean menu) {
      if (!fullscreen.isActive() || fullscreen.isMenuVisible() != menu
          || flag(fullscreen, "transitioning") || !stage.isLaidOut()) return false;
      int width = stage.getWidth(), height = stage.getHeight();
      if (width <= 0 || height <= 0 || width < height) return false;
      if (!menu) return viewport.getLeft() == 0 && viewport.getTop() == 0
          && viewport.getWidth() == width && viewport.getHeight() == height;
      Ui ui = new Ui(activity);
      int margin = ui.dp(16);
      int menuWidth = Math.min(ui.dp(292), Math.max(ui.dp(160), Math.round(width * .32f)));
      int previewWidth = Math.max(1, width - menuWidth - margin * 3);
      int previewHeight = Math.max(1, Math.round(height * .76f));
      return viewport.getLeft() == margin && viewport.getWidth() == previewWidth
          && viewport.getHeight() == previewHeight
          && Math.abs(viewport.getTop() - (height - previewHeight) / 2) <= 1;
    }

    void sameSurfaces() {
      check(viewport.getParent() == stage && viewport.texture == texture && viewport.image == image,
          "presentation retains the original viewport and render views");
      check(texture.getParent() == viewport && image.getParent() == viewport,
          "render views remain attached to the same viewport");
      check(texture.isAvailable() && texture.getSurfaceTexture() == surface,
          "presentation retains the decoder SurfaceTexture without recreation");
    }

    void intermediateWidth(int startWidth, String message) {
      if (!animations) return;
      int endWidth = viewport.getWidth();
      check(Math.abs(startWidth - endWidth) > 10, message + " changes preview width");
      boolean intermediate = false;
      for (int width : frameWidths) {
        if (width > Math.min(startWidth, endWidth) + 1
            && width < Math.max(startWidth, endWidth) - 1) intermediate = true;
      }
      check(intermediate, message + " renders intermediate widths: " + frameWidths);
    }

    @Override public void close() {
      if (stage.getViewTreeObserver().isAlive())
        stage.getViewTreeObserver().removeOnPreDrawListener(sample);
      fullscreen.close();
      normalPad.close();
      dialog.dismiss();
    }
  }

  static String run(Instrumentation test, Activity activity) throws Exception {
    Fixture[] value = {null};
    test.runOnMainSync(() -> value[0] = new Fixture(activity));
    Fixture fixture = value[0];
    try {
      await(test, () -> fixture.texture.isAvailable() && fixture.stage.getWidth() > 0,
          "fixture obtains a real attached TextureView surface");
      test.runOnMainSync(fixture.fullscreen::enter);
      await(test, () -> fixture.settled(false) && fixture.texture.isAvailable(),
          "fullscreen entry settles after orientation and system-bar changes");
      int[] startingWidth = {0};
      test.runOnMainSync(() -> {
        fixture.surface = fixture.texture.getSurfaceTexture();
        startingWidth[0] = fixture.viewport.getWidth();
        fixture.frameWidths.clear();
        fixture.fullscreen.showMenu();
        check(field(fixture.viewport, "tapListener") == null
            && field(fixture.viewport, "dragListener") == null,
            "menu transition disables remote taps and drags immediately");
        tap(fixture.viewport);
      });
      await(test, () -> fixture.settled(true), "menu animation reaches its preview geometry");
      test.runOnMainSync(() -> {
        fixture.intermediateWidth(startingWidth[0], "showMenu");
        fixture.sameSurfaces();
        startingWidth[0] = fixture.viewport.getWidth();
        fixture.frameWidths.clear();
        fixture.fullscreen.resume();
        if (fixture.animations) check(field(fixture.viewport, "tapListener") == null,
            "resume waits until the animation ends before enabling remote input");
      });
      await(test, () -> fixture.settled(false), "resume animation fills the stage again");
      test.runOnMainSync(() -> {
        fixture.intermediateWidth(startingWidth[0], "resume");
        fixture.sameSurfaces();
        check(field(fixture.viewport, "tapListener") != null
            && field(fixture.viewport, "dragListener") != null,
            "settled fullscreen restores taps and drags");
        fixture.fullscreen.showMenu();
      });
      Thread.sleep(65);
      test.runOnMainSync(fixture.fullscreen::resume);
      Thread.sleep(50);
      test.runOnMainSync(fixture.fullscreen::showMenu);
      await(test, () -> fixture.settled(true), "rapid reversal settles at the latest menu geometry");
      test.runOnMainSync(() -> {
        fixture.sameSurfaces();
        fixture.fullscreen.resume();
      });
      Thread.sleep(65);
      test.runOnMainSync(fixture.fullscreen::exit);
      await(test, () -> !fixture.fullscreen.isActive()
          && !flag(fixture.fullscreen, "transitioning")
          && fixture.viewport.getWidth() == fixture.stage.getWidth()
          && fixture.viewport.getHeight() == fixture.stage.getHeight(),
          "exiting an in-flight transition restores ordinary geometry");
      test.runOnMainSync(() -> {
        check(fixture.outer.getBackground() == fixture.outerBackground
            && fixture.stage.getBackground() == fixture.stageBackground
            && fixture.viewport.getBackground() == fixture.viewportBackground,
            "exit restores the original background drawables");
        check(((ColorDrawable) fixture.outerBackground).getColor() == Ui.BG
            && ((ColorDrawable) fixture.stageBackground).getColor() == Ui.BG
            && ((ColorDrawable) fixture.viewportBackground).getColor() == Ui.BG,
            "fullscreen black never mutates ordinary-view colors");
        check(!flag(fixture.viewport, "remoteGestures")
            && field(fixture.viewport, "tapListener") == null
            && field(fixture.viewport, "dragListener") == null,
            "exit restores ordinary viewport gestures and removes remote input callbacks");
        check(fixture.viewport.followCursor && fixture.viewport.getClipToOutline(),
            "exit restores cursor-following and ordinary clipping");
        check(fixture.header.getVisibility() == View.VISIBLE
            && fixture.normalPad.getVisibility() == View.VISIBLE
            && fixture.box.getPaddingLeft() == 7 && fixture.box.getPaddingTop() == 11
            && fixture.box.getPaddingRight() == 13 && fixture.box.getPaddingBottom() == 17,
            "exit restores ordinary controls and padding");
        check(fixture.restoreCount == 1 && activity.getRequestedOrientation() == fixture.orientation,
            "exit restores insets and the requested orientation once");
        fixture.sameSurfaces();
        tap(fixture.viewport);
      });
      fixture.fullscreen.pointer.worker.submit(() -> {}).get(3, TimeUnit.SECONDS);
      check(fixture.events.isEmpty(), "transition and ordinary-view touches never send remote input");
      check(fixture.errors.isEmpty(), "presentation transitions produce no input errors");
      return "PASS: real menu/resume animation, rapid reversal, in-flight exit and preserved render surface"
          + (fixture.animations ? "" : " (system animations disabled; final-state checks only)");
    } finally {
      test.runOnMainSync(fixture::close);
      check(fixture.fullscreen.pointer.worker.awaitTermination(3, TimeUnit.SECONDS),
          "transition fixture releases its pointer worker");
      check(fixture.normalPad.worker.awaitTermination(3, TimeUnit.SECONDS),
          "transition fixture releases its touchpad worker");
    }
  }
}
