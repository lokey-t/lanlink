package com.lanlink.app;

import android.app.Activity;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Disposable-emulator UI and input-only session check against a loopback Wire endpoint. */
final class TouchpadCenterProbe {
  private static final String ONLINE = "cdcdcdcd-cdcd-4dcd-8dcd-cdcdcdcdcdcd";
  private static final String OFFLINE = "dededede-dede-4ede-8ede-dededededede";
  private static final String UNSUPPORTED = "efefefef-efef-4fef-8fef-efefefefefef";
  private final Instrumentation test;
  private final Activity activity;
  private final LinkService service;
  private final AtomicInteger acquired = new AtomicInteger(), released = new AtomicInteger();
  private final CopyOnWriteArrayList<JSONObject> requests = new CopyOnWriteArrayList<>();
  private volatile boolean inputAllowed = true;
  private Wire phone, desktop;

  private TouchpadCenterProbe(Instrumentation test, Activity activity, LinkService service) {
    this.test = test;
    this.activity = activity;
    this.service = service;
  }

  static String run(Instrumentation test, Activity activity, LinkService service) throws Exception {
    return new TouchpadCenterProbe(test, activity, service).run();
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static void await(BooleanSupplier condition, String message) throws Exception {
    long end = SystemClock.elapsedRealtime() + 6000;
    while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < end) Thread.sleep(30);
    check(condition.getAsBoolean(), message);
  }

  private static Object field(Object target, String name) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static void put(Object target, String name, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private View find(View view, String label) {
    if (label.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())
        || view instanceof TextView && label.contentEquals(((TextView) view).getText())) return view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = find(group.getChildAt(i), label);
        if (found != null) return found;
      }
    }
    return null;
  }

  private void render(String page, String selection) throws Exception {
    Method render = MainActivity.class.getDeclaredMethod("renderPage");
    render.setAccessible(true);
    test.runOnMainSync(() -> {
      try {
        put(activity, "page", page);
        put(activity, "centerSelection", selection);
        render.invoke(activity);
      } catch (Exception failure) { throw new RuntimeException(failure); }
    });
    test.waitForIdleSync();
    Thread.sleep(120);
  }

  private JSONObject access() {
    JSONObject rows = new JSONObject();
    for (String name : new String[]{"input", "screen", "clipboard", "shutdown"}) {
      try {
        rows.put(name, Wire.obj("supported", true, "appEnabled", true, "systemGranted", true,
            "peerAllowed", name.equals("input") && inputAllowed));
      } catch (Exception impossible) { throw new AssertionError(impossible); }
    }
    return Wire.obj("permissions", rows);
  }

  private JSONObject capabilities() {
    return Wire.obj("relativeInput", true, "input", true, "controlPrepare", true);
  }

  private void connectFixture() throws Exception {
    byte[] key = new byte[32];
    new java.security.SecureRandom().nextBytes(key);
    try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      Socket client = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
      Socket server = listener.accept();
      Wire.Listener idle = new Wire.Listener() {
        public void ready(Wire peer) {}
        public void message(Wire peer, JSONObject message) {}
        public void closed(Wire peer) {}
      };
      phone = new Wire(client, Wire.obj("id", service.deviceId, "name", "触控板测试手机"), key, idle);
      desktop = new Wire(server, Wire.obj("id", ONLINE, "name", "原生触控板测试电脑",
          "capabilities", capabilities()), key, new Wire.Listener() {
        public void ready(Wire peer) {}
        public void closed(Wire peer) {}
        public void message(Wire peer, JSONObject message) {
          try {
            if (message.optString("type").equals("ping")) {
              peer.send(Wire.obj("type", "pong"));
              return;
            }
            if (!message.optString("type").equals("request")) return;
            requests.add(message);
            String method = message.optString("method");
            JSONObject result = Wire.obj();
            if (method.equals("permission-status")) result = access();
            if (method.equals("acquire")) { acquired.incrementAndGet(); result = capabilities(); }
            if (method.equals("release")) released.incrementAndGet();
            peer.send(Wire.obj("type", "response", "id", message.optString("id"), "result", result));
          } catch (Exception failure) { throw new RuntimeException(failure); }
        }
      });
      phone.start();
      desktop.start();
      await(() -> phone.connected && desktop.connected, "loopback protocol connected");
      phone.info = Wire.obj("name", "原生触控板测试电脑", "platform", "windows", "capabilities", capabilities());
      service.peers.put(ONLINE, phone);
      service.peerAccess.put(ONLINE, access());
    }
  }

  private void capture(String name) throws Exception {
    test.waitForIdleSync();
    Thread.sleep(250);
    Bitmap image = test.getUiAutomation().takeScreenshot();
    check(image != null, "screenshot " + name);
    File folder = new File(activity.getExternalFilesDir(null), "ui-check");
    check(folder.isDirectory() || folder.mkdirs(), "screenshot folder");
    try (FileOutputStream output = new FileOutputStream(new File(folder, name + ".png"))) {
      check(image.compress(Bitmap.CompressFormat.PNG, 100, output), "save " + name);
    } finally { image.recycle(); }
  }

  private void checkTiles() {
    View decor = activity.getWindow().getDecorView();
    float density = activity.getResources().getDisplayMetrics().density;
    int y = -1, height = -1;
    for (String label : new String[]{"文件暂存区", "剪贴板", "触控板，已选择"}) {
      View view = find(decor, label);
      check(view instanceof LinearLayout && view.isClickable(), "function tile " + label);
      LinearLayout tile = (LinearLayout) view;
      check(tile.getChildCount() == 3 && tile.getBackground() != null, "tile owns an outlined surface");
      ViewGroup badge = (ViewGroup) tile.getChildAt(0);
      View icon = badge.getChildAt(0);
      View title = tile.getChildAt(2);
      check(Math.abs((icon.getLeft() + icon.getRight()) / 2f - badge.getWidth() / 2f) <= 1,
          "icon centered horizontally " + label);
      check(Math.abs((icon.getTop() + icon.getBottom()) / 2f - badge.getHeight() / 2f) <= 1,
          "icon centered vertically " + label);
      check(Math.abs((badge.getTop() + title.getBottom()) / 2f - tile.getHeight() / 2f) <= 2 * density,
          "icon and label centered together " + label);
      int[] location = new int[2];
      tile.getLocationOnScreen(location);
      if (y != -1) check(y == location[1] && height == tile.getHeight(), "three tiles align");
      y = location[1];
      height = tile.getHeight();
      Bitmap bitmap = Bitmap.createBitmap(badge.getWidth(), badge.getHeight(), Bitmap.Config.ARGB_8888);
      try {
        badge.draw(new Canvas(bitmap));
        check(bitmap.getPixel(0, badge.getHeight() / 2)
                != bitmap.getPixel(Math.round(5 * density), badge.getHeight() / 2),
            "visible icon outline differs from its interior " + label);
      } finally { bitmap.recycle(); }
    }
  }

  private void checkSurfaceOpacity() {
    float density = activity.getResources().getDisplayMetrics().density;
    int size = Math.round(120 * density);
    TouchpadView pad = new TouchpadView(activity, event -> {});
    Bitmap image = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(image);
    try {
      pad.layout(0, 0, size, size);
      pad.setCompactDark(true);
      pad.draw(canvas);
      check((image.getPixel(size / 4, size / 2) >>> 24) == 255,
          "standalone compact pad remains opaque by default");
      pad.setSurfaceOpacity(.55f);
      image.eraseColor(android.graphics.Color.TRANSPARENT);
      pad.draw(canvas);
      int fillAlpha = image.getPixel(size / 4, size / 2) >>> 24;
      check(fillAlpha >= 139 && fillAlpha <= 141, "side pad fill is translucent");
      check((image.getPixel(size / 2, size / 2) >>> 24) == 255,
          "translucent pad keeps center grip marks clear");
      check((image.getPixel(Math.round(density), size / 2) >>> 24) > 210,
          "translucent pad keeps its outline clear");
      pad.touching = true;
      image.eraseColor(android.graphics.Color.TRANSPARENT);
      pad.draw(canvas);
      int pressedAlpha = image.getPixel(size / 4, size / 2) >>> 24;
      check(pressedAlpha > fillAlpha && pressedAlpha < 180,
          "pressed pad slightly strengthens its fill without hiding the stream");
    } finally {
      pad.close();
      image.recycle();
    }
  }

  private String run() throws Exception {
    SharedPreferences prefs = activity.getSharedPreferences("identity", 0);
    String originalKnown = prefs.getString("known_devices", "{}");
    String originalPage = (String) field(activity, "page");
    String originalSelection = (String) field(activity, "centerSelection");
    TouchpadDialog[] session = {null};
    check(service.active == null && field(activity, "viewer") == null,
        "touchpad UI probe requires no existing control session");
    try {
      connectFixture();
      JSONObject known = new JSONObject(originalKnown);
      known.put(OFFLINE, Wire.obj("name", "离线触控板电脑", "platform", "darwin",
          "capabilities", Wire.obj("relativeInput", true)));
      known.put(UNSUPPORTED, Wire.obj("name", "不支持触控板的设备", "platform", "windows",
          "capabilities", Wire.obj("relativeInput", false)));
      prefs.edit().putString("known_devices", known.toString()).commit();
      render("center", "touchpad");
      test.runOnMainSync(() -> {
        checkTiles();
        checkSurfaceOpacity();
        View decor = activity.getWindow().getDecorView();
        check(find(decor, "原生触控板测试电脑") != null, "supported online device listed");
        View offline = find(decor, "离线触控板电脑，离线 · 等待设备上线");
        check(offline != null && !offline.isClickable(), "known supported offline device cannot start control");
        check(find(decor, "不支持触控板的设备") == null, "unsupported capability is filtered");
        check(find(decor, "在线 · 已授权") != null, "only input permission needed for ready state");
      });
      inputAllowed = false;
      service.peerAccess.put(ONLINE, access());
      render("center", "touchpad");
      test.runOnMainSync(() -> check(find(activity.getWindow().getDecorView(), "在线 · 点击请求授权") != null,
          "ungranted native input is explained"));
      inputAllowed = true;
      service.peerAccess.put(ONLINE, access());
      render("center", "touchpad");
      capture("control-center-touchpad");
      test.runOnMainSync(() -> {
        View row = find(activity.getWindow().getDecorView(),
            "原生触控板测试电脑，在线 · 已授权，打开全屏触控板");
        check(row != null && row.performClick(), "open touchpad from the control center");
        try { session[0] = (TouchpadDialog) field(activity, "viewer"); }
        catch (Exception failure) { throw new RuntimeException(failure); }
      });
      await(() -> ONLINE.equals(service.active) && acquired.get() > 0, "input-only control acquired");
      test.waitForIdleSync();
      capture("touchpad-session");
      test.runOnMainSync(() -> {
        try {
          TouchpadView pad = (TouchpadView) field(session[0], "pad");
          check(pad.isEnabled() && pad.getHeight() > 120, "full-screen pad enabled after claim");
          check(find(session[0].getWindow().getDecorView(), "打开远程键盘") != null, "native keyboard entry");
          long now = SystemClock.uptimeMillis();
          MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 50, 50, 0);
          MotionEvent move = MotionEvent.obtain(now, now + 30, MotionEvent.ACTION_MOVE, 110, 80, 0);
          MotionEvent up = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, 110, 80, 0);
          try { pad.onTouchEvent(down); pad.onTouchEvent(move); pad.onTouchEvent(up); }
          finally { down.recycle(); move.recycle(); up.recycle(); }
        } catch (Exception failure) { throw new RuntimeException(failure); }
      });
      await(() -> {
        for (JSONObject request : requests) {
          JSONObject args = request.optJSONObject("args");
          if (request.optString("method").equals("input") && args != null
              && args.optString("kind").equals("relative")) return true;
        }
        return false;
      }, "touch gesture reaches native relative input protocol");
      for (JSONObject request : requests) {
        String method = request.optString("method");
        JSONObject args = request.optJSONObject("args");
        if (method.equals("prepare") || method.equals("acquire"))
          check(args != null && args.has("screen") && !args.optBoolean("screen"), "touchpad never claims screen access");
        check(!method.equals("screen") && !method.toLowerCase(java.util.Locale.ROOT).contains("video"),
            "touchpad creates no video stream");
      }
      test.runOnMainSync(session[0]::dismiss);
      await(() -> released.get() > 0 && !ONLINE.equals(service.active), "dismiss releases the native control lease");
      // Reopen, drop the actual encrypted connection, then exercise the visible recovery action.
      test.runOnMainSync(() -> { session[0] = new TouchpadDialog(activity, service, phone); session[0].show(); });
      await(() -> ONLINE.equals(service.active) && acquired.get() > 1, "second touchpad session acquired");
      phone.close();desktop.close();
      Thread.sleep(850);test.waitForIdleSync();capture("touchpad-offline");
      test.runOnMainSync(() -> {
        View back = find(session[0].getWindow().getDecorView(), "返回设备列表");
        check(back != null && back.isEnabled(), "offline session offers an effective return action");
        check(back.performClick() && !session[0].isShowing(), "offline return action closes the stale session");
      });
      connectFixture();
      test.runOnMainSync(() -> { session[0] = new TouchpadDialog(activity, service, phone); session[0].show(); });
      await(() -> ONLINE.equals(service.active) && acquired.get() > 2, "fresh connection works after returning to list");
      test.runOnMainSync(session[0]::dismiss);
      return "PASS touchpad center: three aligned outlined tiles, capability/offline/permission filtering, "
          + "input-only loopback claim, native relative input, keyboard entry, clean release, offline recovery and new-connection reuse; "
          + "screenshots: ui-check/control-center-touchpad.png, ui-check/touchpad-session.png";
    } finally {
      if (session[0] != null) test.runOnMainSync(session[0]::dismiss);
      if (phone != null) service.peers.remove(ONLINE, phone);
      service.peerAccess.remove(ONLINE);
      if (phone != null) phone.close();
      if (desktop != null) desktop.close();
      prefs.edit().putString("known_devices", originalKnown).commit();
      render(originalPage, originalSelection);
    }
  }
}
