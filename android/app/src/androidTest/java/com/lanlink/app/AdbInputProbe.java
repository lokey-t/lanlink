package com.lanlink.app;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Disposable-emulator-only exercise of real encrypted ADB bootstrap and shell input. */
final class AdbInputProbe {
  static boolean inputDevicePresent(String name) {
    for (int id : android.view.InputDevice.getDeviceIds()) {
      android.view.InputDevice device = android.view.InputDevice.getDevice(id);
      if (device != null && name.equals(device.getName()) && device.isExternal()) return true;
    }
    return false;
  }

  static String run(Instrumentation test, Activity activity, LinkService service, Bundle args)
      throws Exception {
    android.app.UiAutomation ui = test.getUiAutomation();
    android.accessibilityservice.AccessibilityServiceInfo uiInfo = ui.getServiceInfo();
    uiInfo.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
    ui.setServiceInfo(uiInfo);
    java.util.concurrent.atomic.AtomicInteger
        clicks = new java.util.concurrent.atomic.AtomicInteger(),
        keys = new java.util.concurrent.atomic.AtomicInteger(),
        externalKeys = new java.util.concurrent.atomic.AtomicInteger(),
        moves = new java.util.concurrent.atomic.AtomicInteger();
    android.widget.Button[] target = new android.widget.Button[1];
    StringBuffer digitEvents = new StringBuffer();
    long[] digitDownTime = {0};
    java.util.concurrent.atomic.AtomicInteger badKeyTimes =
        new java.util.concurrent.atomic.AtomicInteger();
    test.runOnMainSync(
        () -> {
          android.widget.FrameLayout frame = new android.widget.FrameLayout(activity);
          target[0] = new android.widget.Button(activity);
          target[0].setText("ADB 输入验证区域");
          target[0].setFocusableInTouchMode(true);
          target[0].setFilterTouchesWhenObscured(true);
          android.widget.FrameLayout.LayoutParams lp =
              new android.widget.FrameLayout.LayoutParams(500, 400, android.view.Gravity.CENTER);
          frame.addView(target[0], lp);
          activity.setContentView(frame);
          target[0].requestFocus();
          target[0].setOnClickListener(v -> clicks.incrementAndGet());
          target[0].setOnKeyListener(
              (v, key, event) -> {
                if (key == android.view.KeyEvent.KEYCODE_1) {
                  if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                    digitEvents.append(event.getRepeatCount() == 0 ? 'D' : 'R');
                    digitDownTime[0] = event.getDownTime();
                  } else {
                    digitEvents.append('U');
                    if (event.getDownTime() != digitDownTime[0]) badKeyTimes.incrementAndGet();
                  }
                  return true; // Like a phone IME consuming a candidate-selection key.
                }
                if (key == android.view.KeyEvent.KEYCODE_A) {
                  keys.incrementAndGet();
                  android.view.InputDevice source = event.getDevice();
                  if (source != null && source.isExternal()
                      && "LanLink Virtual Keyboard".equals(source.getName()))
                    externalKeys.incrementAndGet();
                  return true;
                }
                return false;
              });
          target[0].setOnTouchListener(
              (v, event) -> {
                if (event.getActionMasked() == android.view.MotionEvent.ACTION_MOVE)
                  moves.incrementAndGet();
                return false;
              });
        });
    SharedPreferences preferences = service.getSharedPreferences("identity", 0);
    Map<String, ?> saved = new HashMap<>(preferences.getAll());
    DeviceConsent original = service.consent;
    CountDownLatch done = new CountDownLatch(1);
    String[] failure = {null};
    Wire peer = null;
    try {
      service.consent = new DeviceConsent("{}");
      service.setAllowed("input", true);
      service.setAllowed("screen", false);
      JSONObject identity =
          Wire.obj(
              "id",
              service.deviceId,
              "name",
              "ADB smoke Android",
              "capabilities",
              service.capabilities());
      peer =
          new Wire(
              new Socket("10.0.2.2", Integer.parseInt(args.getString("adbSmokePort"))),
              identity,
              Wire.parseToken(args.getString("adbSmokeToken")),
              new Wire.Listener() {
                public void ready(Wire p) {
                  service.ready(p);
                  try {
                    try {
                      service.consent.require(p.id, "ADB smoke desktop", "input");
                    } catch (java.io.IOException expected) {
                    }
                    DeviceConsent.Request request = service.consent.peek();
                    if (request == null) throw new AssertionError("fixture consent missing");
                    service.consent.decide(
                        p.id,
                        request.requestId,
                        true,
                        Wire.obj("input", true, "screen", false, "clipboard", false));
                  } catch (Exception e) {
                    failure[0] = e.toString();
                    done.countDown();
                  }
                }

                public void message(Wire p, JSONObject m) {
                  if ("request".equals(m.optString("type")) && "testStreamEnable".equals(m.optString("method"))) {
                    try {
                      boolean enabled = m.getJSONObject("args").optBoolean("enabled");
                      service.consent.setPermission(p.id, "screen", enabled);
                      service.setAllowed("screen", enabled);
                      p.send(Wire.obj("type", "response", "id", m.optString("id"), "result", Wire.obj()));
                    } catch (Exception error) { failure[0] = error.toString(); done.countDown(); }
                    return;
                  }
                  if ("request".equals(m.optString("type")) && "testReverseLink".equals(m.optString("method"))) {
                    service.jobs.execute(() -> {
                      try {
                        if (p.fastPath == null || !p.fastPath.connected) throw new AssertionError("Missing phone-to-desktop auxiliary route");
                        p.request("acquire", Wire.obj());
                        p.request("input", Wire.obj("kind", "relative", "dx", 5, "dy", 2));
                        p.request("release", Wire.obj());
                        JSONObject info = p.request("videoOpen", Wire.obj("h264", false));
                        try {
                          if (info.optInt("usbPort") < 1) throw new AssertionError("Missing ADB reverse video tunnel");
                          JSONObject route = new JSONObject(info.toString()).put("port", info.getInt("usbPort"));
                          try (VideoWire video = VideoWire.connect("127.0.0.1", route)) {
                            VideoWire.Frame frame = video.next();
                            if (frame.width != 20 || frame.height != 10) throw new AssertionError("Reverse video dimensions");
                          }
                        } finally { p.request("videoStop", Wire.obj("id", info.getString("id"))); }
                        p.send(Wire.obj("type", "response", "id", m.optString("id"), "result", Wire.obj("ok", true)));
                      } catch (Throwable error) {
                        try { p.send(Wire.obj("type", "response", "id", m.optString("id"), "error", error.toString())); } catch (Exception ignored) {}
                      }
                    });
                    return;
                  }
                  if ("request".equals(m.optString("type"))
                      && "testSystemUi".equals(m.optString("method"))) {
                    service.jobs.execute(
                        () -> {
                          try {
                            android.app.UiAutomation automation = test.getUiAutomation();
                            android.accessibilityservice.AccessibilityServiceInfo info =
                                automation.getServiceInfo();
                            info.flags |=
                                android.accessibilityservice.AccessibilityServiceInfo
                                    .FLAG_REPORT_VIEW_IDS;
                            automation.setServiceInfo(info);
                            automation.waitForIdle(100, 3000);
                            android.view.accessibility.AccessibilityNodeInfo root =
                                automation.getRootInActiveWindow();
                            long until = android.os.SystemClock.uptimeMillis() + 3000;
                            while (root == null && android.os.SystemClock.uptimeMillis() < until) {
                              Thread.sleep(100);
                              root = automation.getRootInActiveWindow();
                            }
                            JSONObject state =
                                Wire.obj(
                                    "package",
                                    root == null ? "" : String.valueOf(root.getPackageName()));
                            if (root != null) {
                              java.util.List<android.view.accessibility.AccessibilityNodeInfo>
                                  nodes =
                                      root.findAccessibilityNodeInfosByViewId(
                                          "com.android.systemui:id/settings_button_container");
                              if (nodes.isEmpty())
                                nodes =
                                    root.findAccessibilityNodeInfosByViewId(
                                        "com.android.systemui:id/settings_button");
                              if (nodes.isEmpty()) {
                                java.util.ArrayDeque<
                                        android.view.accessibility.AccessibilityNodeInfo>
                                    queue = new java.util.ArrayDeque<>();
                                queue.add(root);
                                while (!queue.isEmpty()) {
                                  android.view.accessibility.AccessibilityNodeInfo node =
                                      queue.remove();
                                  String label = String.valueOf(node.getContentDescription());
                                  if (node.isVisibleToUser()
                                      && ("com.android.systemui:id/settings_button_container"
                                              .equals(node.getViewIdResourceName())
                                          || label.equals("打开设置。")
                                          || label.equals("Open settings.")
                                          || label.equals("设置")
                                          || label.equals("Settings"))) {
                                    nodes.add(node);
                                    break;
                                  }
                                  for (int i = 0; i < node.getChildCount(); i++) {
                                    android.view.accessibility.AccessibilityNodeInfo child =
                                        node.getChild(i);
                                    if (child != null) queue.add(child);
                                  }
                                }
                              }
                              if (!nodes.isEmpty()) {
                                android.graphics.Rect r = new android.graphics.Rect();
                                nodes.get(0).getBoundsInScreen(r);
                                android.util.DisplayMetrics dm = service.displayMetrics();
                                state.put("gearX", r.exactCenterX() / (dm.widthPixels - 1));
                                state.put("gearY", r.exactCenterY() / (dm.heightPixels - 1));
                              }
                            }
                            p.send(
                                Wire.obj(
                                    "type", "response", "id", m.optString("id"), "result", state));
                          } catch (Exception error) {
                            failure[0] = error.toString();
                            done.countDown();
                          }
                        });
                    return;
                  }
                  if ("request".equals(m.optString("type"))
                      && "testBusyUi".equals(m.optString("method"))) {
                    service.jobs.execute(
                        () -> {
                          try {
                            CountDownLatch entered = new CountDownLatch(1);
                            new android.os.Handler(android.os.Looper.getMainLooper())
                                .post(
                                    () -> {
                                      entered.countDown();
                                      android.os.SystemClock.sleep(1500);
                                    });
                            if (!entered.await(2, TimeUnit.SECONDS))
                              throw new AssertionError("UI fixture did not start");
                            p.send(
                                Wire.obj(
                                    "type",
                                    "response",
                                    "id",
                                    m.optString("id"),
                                    "result",
                                    Wire.obj("busy", true)));
                          } catch (Throwable error) {
                            failure[0] = error.toString();
                            done.countDown();
                          }
                        });
                    return;
                  }
                  if ("request".equals(m.optString("type"))
                      && "testCapture".equals(m.optString("method"))) {
                    service.jobs.execute(() -> {
                      try {
                        PrivilegedCaptureProbe.run(service);
                        p.send(Wire.obj("type", "response", "id", m.optString("id"),
                            "result", Wire.obj("captured", true)));
                      } catch (Throwable error) {
                        try { p.send(Wire.obj("type", "response", "id", m.optString("id"),
                            "error", error.toString())); } catch (Exception ignored) {}
                      }
                    });
                    return;
                  }
                  if ("request".equals(m.optString("type"))
                      && "testTarget".equals(m.optString("method"))) {
                    service.jobs.execute(
                        () -> {
                          try {
                            final int[] xy = new int[2];
                            test.runOnMainSync(() -> target[0].getLocationOnScreen(xy));
                            android.util.DisplayMetrics metrics = service.displayMetrics();
                            p.send(
                                Wire.obj(
                                    "type",
                                    "response",
                                    "id",
                                    m.optString("id"),
                                    "result",
                                    Wire.obj(
                                        "x",
                                        (xy[0] + 250.0) / (metrics.widthPixels - 1),
                                        "y",
                                        (xy[1] + 200.0) / (metrics.heightPixels - 1),
                                        "clicks",
                                        clicks.get(),
                                        "keys",
                                        keys.get(),
                                        "externalKeys",
                                        externalKeys.get(),
                                        "digitEvents",
                                        digitEvents.toString(),
                                        "badKeyTimes",
                                        badKeyTimes.get(),
                                        "moves",
                                        moves.get(),
                                        "externalMouse",
                                        inputDevicePresent("LanLink Virtual Mouse"),
                                        "externalKeyboard",
                                        inputDevicePresent("LanLink Virtual Keyboard"),
                                        "targetFocused",
                                        target[0].hasFocus(),
                                        "windowFocused",
                                        activity.hasWindowFocus())));
                          } catch (Exception error) {
                            failure[0] = error.toString();
                            done.countDown();
                          }
                        });
                    return;
                  }
                  if ("request".equals(m.optString("type"))
                      && "testReconnect".equals(m.optString("method"))) {
                    service.jobs.execute(
                        () -> {
                          try {
                            JSONObject before = service.shell.bootstrap();
                            service.shell.close();
                            service.shell = new ShellChannel(service);
                            JSONObject after = service.shell.bootstrap();
                            if (before.getInt("port") != after.getInt("port")
                                || !before.getString("secret").equals(after.getString("secret")))
                              throw new AssertionError("persistent binding changed");
                            long deadline = System.currentTimeMillis() + 10000;
                            while (!service.shell.healthy()
                                && System.currentTimeMillis() < deadline) Thread.sleep(100);
                            if (!service.shell.healthy())
                              throw new AssertionError("helper failed to reconnect without ADB");
                            p.send(
                                Wire.obj(
                                    "type",
                                    "response",
                                    "id",
                                    m.optString("id"),
                                    "result",
                                    Wire.obj("reconnected", true)));
                          } catch (Throwable error) {
                            failure[0] = error.toString();
                            done.countDown();
                          }
                        });
                    return;
                  }
                  if ("request".equals(m.optString("type"))
                      && "testDone".equals(m.optString("method"))) {
                    try {
                      if (!service.shell.healthy())
                        throw new AssertionError("ADB helper did not answer heartbeat");
                      p.send(
                          Wire.obj(
                              "type",
                              "response",
                              "id",
                              m.optString("id"),
                              "result",
                              Wire.obj("ok", true)));
                    } catch (Throwable error) {
                      failure[0] = error.toString();
                    }
                    done.countDown();
                  } else service.message(p, m);
                }

                public void closed(Wire p) {
                  service.closed(p);
                }
              });
      peer.outgoing = true;
      peer.start();
      if (!done.await(75, TimeUnit.SECONDS))
        throw new AssertionError("ADB fixture timed out: " + peer.failure);
      if (failure[0] != null) throw new AssertionError(failure[0]);
      return "PASS: encrypted ADB bootstrap, desktop helper launch, verified live shell heartbeat"
          + " and remote input";
    } finally {
      if (peer != null) peer.close();
      service.shell.close();
      service.consent = original;
      SharedPreferences.Editor editor = preferences.edit().clear();
      for (Map.Entry<String, ?> entry : saved.entrySet()) {
        Object value = entry.getValue();
        String key = entry.getKey();
        if (value instanceof String) editor.putString(key, (String) value);
        else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) editor.putInt(key, (Integer) value);
        else if (value instanceof Long) editor.putLong(key, (Long) value);
        else if (value instanceof Float) editor.putFloat(key, (Float) value);
        else if (value instanceof Set) editor.putStringSet(key, new HashSet<>((Set<String>) value));
      }
      if (!editor.commit()) throw new AssertionError("fixture preference restore failed");
    }
  }
}
