package com.lanlink.app;

import android.app.*;
import android.graphics.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import java.io.IOException;
import java.lang.reflect.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;

final class RemoteInteractionProbe {
  public static final class DeniedInput {
    public boolean inject(InputEvent event, int mode) {
      throw new SecurityException("fixture INJECT_EVENTS");
    }
  }

  private static void check(boolean value, String detail) {
    if (!value) throw new AssertionError(detail);
  }

  private static MotionEvent event(long time, int action, float... positions) {
    int count = positions.length / 2;
    MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[count];
    MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
    for (int i = 0; i < count; i++) {
      properties[i] = new MotionEvent.PointerProperties();
      properties[i].id = i;
      properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
      coords[i] = new MotionEvent.PointerCoords();
      coords[i].x = positions[2 * i];
      coords[i].y = positions[2 * i + 1];
      coords[i].pressure = 1;
    }
    return MotionEvent.obtain(
        time,
        time,
        action,
        count,
        properties,
        coords,
        0,
        0,
        1,
        1,
        0,
        0,
        InputDevice.SOURCE_TOUCHSCREEN,
        0);
  }

  private static void touch(VideoViewport viewport, long t, int action, float... xy) {
    MotionEvent e = event(t, action, xy);
    viewport.onTouchEvent(e);
    e.recycle();
  }

  private static void conflict(Method handle, LinkService service, Wire peer, String method)
      throws Exception {
    try {
      handle.invoke(service, peer, method, Wire.obj());
      throw new AssertionError("incoming " + method + " allowed");
    } catch (InvocationTargetException e) {
      check(
          e.getCause().getMessage().startsWith("CONTROL_CONFLICT:"),
          "direction error for " + method);
    }
  }

  static void run(Instrumentation test, Activity activity, LinkService service) throws Exception {
    Field manager = ShellAgent.class.getDeclaredField("manager"),
        inject = ShellAgent.class.getDeclaredField("inject");
    manager.setAccessible(true);
    inject.setAccessible(true);
    Object previousManager = manager.get(null), previousInject = inject.get(null);
    try {
      manager.set(null, new DeniedInput());
      inject.set(null, DeniedInput.class.getMethod("inject", InputEvent.class, int.class));
      Method post = ShellAgent.class.getDeclaredMethod("post", InputEvent.class);
      post.setAccessible(true);
      try {
        post.invoke(null, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_UNKNOWN));
        throw new AssertionError("security error swallowed");
      } catch (InvocationTargetException denied) {
        check(
            denied.getCause().getMessage().contains("USB 调试（安全设置）"),
            "reflection security cause translated into actionable guidance");
      }
    } finally {
      manager.set(null, previousManager);
      inject.set(null, previousInject);
    }
    test.runOnMainSync(
        () -> {
          FrameLayout parent = new FrameLayout(activity);
          ImageView image = new ImageView(activity);
          Bitmap bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888);
          image.setImageBitmap(bitmap);
          TextureView texture = new TextureView(activity);
          VideoViewport viewport = new VideoViewport(activity, texture, image, true);
          parent.addView(viewport, new FrameLayout.LayoutParams(600, 600));
          viewport.layout(0, 0, 600, 600);
          viewport.fit(1920, 1080);
          long t = SystemClock.uptimeMillis();
          touch(viewport, t, 0, 300, 300);
          touch(viewport, t + 20, 2, 300, 350);
          touch(viewport, t + 30, 1, 300, 350);
          check(Math.abs(viewport.panY - 50) < 1, "single finger repositions fitted display");
          viewport.reset();
          touch(viewport, t + 100, 0, 250, 300);
          touch(viewport, t + 110, MotionEvent.ACTION_POINTER_DOWN | (1 << 8), 250, 300, 350, 300);
          touch(viewport, t + 120, 2, 220, 300, 420, 300);
          check(
              Math.abs(viewport.zoom - 2) < .01 && Math.abs(viewport.panX - 20) < 1,
              "pinch zoom preserves translated focus");
          touch(viewport, t + 130, 2, 240, 300, 440, 300);
          check(Math.abs(viewport.panX - 40) < 1, "two finger drag pans without scaling");
          Matrix video = texture.getTransform(new Matrix()), jpeg = image.getImageMatrix();
          float[] a = {0, 0, 600, 600}, b = {0, 0, 1920, 1080};
          video.mapPoints(a);
          jpeg.mapPoints(b);
          for (int i = 0; i < 4; i++)
            check(Math.abs(a[i] - b[i]) < 1, "H264/JPEG share viewport coordinates");
          touch(viewport, t + 140, 2, 2000, 2000, 2200, 2000);
          check(viewport.panX <= 300.1 && viewport.panY <= 37.6, "overscroll remains bounded");
          viewport.reset();
          check(
              viewport.zoom == 1 && viewport.panX == 0 && viewport.panY == 0, "reset fits display");
          touch(viewport, t + 160, MotionEvent.ACTION_CANCEL, 300, 300);
          viewport.zoom = 3;
          viewport.fit(1920, 1080);
          viewport.cursor(.95f, .95f);
          float[] cursor = {1920 * .95f, 1080 * .95f};
          image.getImageMatrix().mapPoints(cursor);
          check(
              cursor[0] >= 0 && cursor[0] <= 600 && cursor[1] >= 0 && cursor[1] <= 600,
              "follow keeps remote cursor inside zoomed viewport");
          float savedX = viewport.panX, savedY = viewport.panY;
          viewport.followCursor = false;
          viewport.cursor(.05f, .05f);
          check(
              viewport.panX == savedX && viewport.panY == savedY,
              "disabled cursor follow preserves manual view");
          viewport.followCursor = true;
          touch(viewport, t + 200, 0, 300, 300);
          viewport.cursor(.05f, .05f);
          check(
              viewport.panX == savedX && viewport.panY == savedY, "manual gesture suspends follow");
          touch(viewport, t + 220, MotionEvent.ACTION_CANCEL, 300, 300);
          viewport.cursor(.05f, .05f);
          check(
              viewport.panX > savedX && viewport.panY > savedY,
              "cursor moving to opposite edge pans view");
          bitmap.recycle();
        });
    // Use the real encrypted request transport; hold the remote acquire response to expose races.
    String remoteId = "22222222-2222-4222-8222-222222222222";
    byte[] key = new byte[32];
    CountDownLatch acquired = new CountDownLatch(1), released = new CountDownLatch(1);
    AtomicReference<String> acquireId = new AtomicReference<>();
    AtomicReference<Throwable> outcome = new AtomicReference<>();
    Method handle =
        LinkService.class.getDeclaredMethod("handle", Wire.class, String.class, JSONObject.class);
    handle.setAccessible(true);
    Wire local = null, remote = null;
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
      Socket accepted = server.accept();
      local =
          new Wire(
              client,
              Wire.obj("id", service.deviceId, "name", "fixture", "capabilities", Wire.obj()),
              key,
              service);
      remote =
          new Wire(
              accepted,
              Wire.obj(
                  "id", remoteId, "name", "race fixture", "capabilities", Wire.obj("input", true)),
              key,
              new Wire.Listener() {
                public void ready(Wire p) {}

                public void closed(Wire p) {}

                public void message(Wire p, JSONObject message) {
                  try {
                    if ("ping".equals(message.optString("type"))) p.send(Wire.obj("type", "pong"));
                    if (!"request".equals(message.optString("type"))) return;
                    if ("acquire".equals(message.optString("method"))) {
                      acquireId.set(message.getString("id"));
                      acquired.countDown();
                    } else {
                      p.send(
                          Wire.obj(
                              "type",
                              "response",
                              "id",
                              message.getString("id"),
                              "result",
                              Wire.obj()));
                      if ("release".equals(message.optString("method"))) released.countDown();
                    }
                  } catch (Exception e) {
                    outcome.set(e);
                  }
                }
              });
      local.outgoing = true;
      local.start();
      remote.start();
      long deadline = System.currentTimeMillis() + 4000;
      while (!service.peers.containsKey(remoteId) && System.currentTimeMillis() < deadline)
        Thread.sleep(20);
      check(service.peers.containsKey(remoteId), "race fixture authenticated");
      Thread control =
          new Thread(
              () -> {
                try {
                  service.control(remoteId, true);
                } catch (Throwable error) {
                  outcome.set(error);
                }
              });
      control.start();
      check(acquired.await(3, TimeUnit.SECONDS), "outgoing acquire pending on transport");
      for (String operation : new String[] {"acquire", "videoOpen", "screen"})
        conflict(handle, service, local, operation);
      service.releaseControl();
      try {
        service.control(remoteId, true);
        throw new AssertionError("cancelled pending request reused");
      } catch (IOException e) {
        check(
            e.getMessage().startsWith("CONTROL_CONFLICT:"), "pending lock retained during cancel");
      }
      remote.send(
          Wire.obj("type", "response", "id", acquireId.get(), "result", Wire.obj("input", true)));
      control.join(4000);
      check(
          !control.isAlive() && released.await(1, TimeUnit.SECONDS),
          "late acquire is released before unlocking");
      check(
          service.active == null
              && outcome.get() != null
              && outcome.get().getMessage().startsWith("CONTROL_ENDED:"),
          "cancelled acquire never activates");
      service.beginViewing(remoteId);
      for (String operation : new String[] {"acquire", "videoOpen", "screen"})
        conflict(handle, service, local, operation);
      service.endViewing(remoteId);
      Field owner = LinkService.class.getDeclaredField("owner"),
          until = LinkService.class.getDeclaredField("ownerUntil");
      owner.setAccessible(true);
      until.setAccessible(true);
      owner.set(service, remoteId);
      until.setLong(service, System.currentTimeMillis() + 4000);
      try {
        service.beginViewing(remoteId);
        throw new AssertionError("incoming owner allowed reciprocal viewer");
      } catch (IOException e) {
        check(
            e.getMessage().startsWith("CONTROL_CONFLICT:"),
            "incoming owner blocks outgoing viewer");
      } finally {
        owner.set(service, null);
        until.setLong(service, 0);
      }
    } finally {
      service.endViewing(remoteId);
      if (local != null) local.close();
      if (remote != null) remote.close();
    }
  }
}
