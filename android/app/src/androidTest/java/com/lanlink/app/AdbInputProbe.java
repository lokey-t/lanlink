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
  static String run(Instrumentation test, Activity activity, LinkService service, Bundle args)
      throws Exception {
    java.util.concurrent.atomic.AtomicInteger
        clicks = new java.util.concurrent.atomic.AtomicInteger(),
        keys = new java.util.concurrent.atomic.AtomicInteger(),
        moves = new java.util.concurrent.atomic.AtomicInteger();
    android.widget.Button[] target = new android.widget.Button[1];
    test.runOnMainSync(
        () -> {
          android.widget.FrameLayout frame = new android.widget.FrameLayout(activity);
          target[0] = new android.widget.Button(activity);
          target[0].setText("ADB 输入验证区域");
          target[0].setFocusableInTouchMode(true);
          android.widget.FrameLayout.LayoutParams lp =
              new android.widget.FrameLayout.LayoutParams(500, 400, android.view.Gravity.CENTER);
          frame.addView(target[0], lp);
          activity.setContentView(frame);
          target[0].requestFocus();
          target[0].setOnClickListener(v -> clicks.incrementAndGet());
          target[0].setOnKeyListener(
              (v, key, event) -> {
                if (key == android.view.KeyEvent.KEYCODE_A) {
                  keys.incrementAndGet();
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
                  if ("request".equals(m.optString("type"))
                      && "testTarget".equals(m.optString("method"))) {
                    service.jobs.execute(
                        () -> {
                          try {
                            final int[] xy = new int[2];
                            test.runOnMainSync(() -> target[0].getLocationOnScreen(xy));
                            android.util.DisplayMetrics metrics =
                                service.getResources().getDisplayMetrics();
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
                                        "moves",
                                        moves.get())));
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
