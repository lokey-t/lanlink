package com.lanlink.app;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

/** Two actual encrypted desktop connections to the same disposable Android instance. */
final class MultiComputerProbe {
  static String run(Instrumentation test, Activity activity, LinkService service, Bundle args) throws Exception {
    SharedPreferences prefs = service.getSharedPreferences("identity", 0);
    Map<String, ?> saved = new HashMap<>(prefs.getAll());
    DeviceConsent consent = service.consent;
    ArrayList<Wire> connections = new ArrayList<>();
    CountDownLatch done = new CountDownLatch(1);
    AtomicInteger keys = new AtomicInteger();
    AtomicInteger motion = new AtomicInteger();
    test.runOnMainSync(() -> {
      android.widget.Button target = new android.widget.Button(activity);
      target.setText("多电脑与双屏串流验证"); target.setFocusableInTouchMode(true);
      activity.setContentView(target); target.requestFocus();
      target.setOnKeyListener((view, code, event) -> { if (code == android.view.KeyEvent.KEYCODE_A) { keys.incrementAndGet(); return true; } return false; });
      target.setOnHoverListener((view, event) -> { motion.incrementAndGet(); return true; });
    });
    try {
      prefs.edit().remove("allow_input").remove("allow_screen").remove("allow_clipboard")
          .putBoolean("root_mode", false).putBoolean("adb_revoked", false).commit();
      service.consent = new DeviceConsent("{}");
      JSONObject caps = service.capabilities(); caps.put("memberKey", service.group.own().getString("key"));
      JSONObject identity = Wire.obj("id", service.deviceId, "name", "Multi-computer Android", "capabilities", caps);
      for (String port : args.getString("multiComputerPorts").split(",")) {
        Wire connection = new Wire(new Socket("10.0.2.2", Integer.parseInt(port)), identity,
            Wire.parseToken(args.getString("multiComputerToken")), new Wire.Listener() {
          public void ready(Wire peer) { service.ready(peer); }
          public void closed(Wire peer) { service.closed(peer); }
          public void message(Wire peer, JSONObject message) {
            String operation = message.optString("method");
            if (!"request".equals(message.optString("type")) || !operation.startsWith("probe-")) { service.message(peer, message); return; }
            service.jobs.execute(() -> {
              try {
                JSONObject result = Wire.obj();
                if (operation.equals("probe-counts")) {
                  JSONArray mice = new JSONArray();
                  for (int device : android.view.InputDevice.getDeviceIds()) {
                    android.view.InputDevice input = android.view.InputDevice.getDevice(device);
                    if (input != null && input.getName().equals("LanLink Virtual Mouse")) mice.put(device);
                  }
                  result = Wire.obj("keys", keys.get(), "motion", motion.get(), "mice", mice, "consentPending", service.consent.peek() != null, "layout", service.groupLayout.layout(), "masterSelection", service.groupLayout.master());
                }
                else if (operation.equals("probe-layout-place")) { JSONObject point = message.getJSONObject("args"); service.groupLayout.place(point.getString("id"), point.getInt("x"), point.getInt("y")); }
                else if (operation.equals("probe-master")) service.groupLayout.chooseMaster(message.getJSONObject("args").getString("id"));
                else if (operation.equals("probe-permission")) {
                  JSONObject change = message.getJSONObject("args");
                  service.setDevicePermission(change.getString("id"), change.getString("key"), change.getBoolean("enabled"));
                } else if (!operation.equals("probe-done")) throw new IllegalArgumentException("Unknown test operation");
                peer.send(Wire.obj("type", "response", "id", message.optString("id"), "result", result));
                if (operation.equals("probe-done")) done.countDown();
              } catch (Exception error) {
                try { peer.send(Wire.obj("type", "response", "id", message.optString("id"), "error", error.toString())); } catch (Exception ignored) {}
              }
            });
          }
        });
        connection.outgoing = true; connections.add(connection); connection.start();
      }
      if (!done.await(120, TimeUnit.SECONDS)) throw new AssertionError("Two-computer fixture timed out");
      return "PASS: two authenticated computers connected to one Android instance; shared system helper and actual video/input exercise completed";
    } finally {
      service.stopInbound(); service.stopProjection();
      for (Wire peer : connections) peer.close();
      service.consent = consent;
      SharedPreferences.Editor restore = prefs.edit().clear();
      for (Map.Entry<String, ?> entry : saved.entrySet()) {
        String key = entry.getKey(); Object value = entry.getValue();
        if (value instanceof String) restore.putString(key, (String) value);
        else if (value instanceof Boolean) restore.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) restore.putInt(key, (Integer) value);
        else if (value instanceof Long) restore.putLong(key, (Long) value);
        else if (value instanceof Float) restore.putFloat(key, (Float) value);
        else if (value instanceof Set) restore.putStringSet(key, new HashSet<>((Set<String>) value));
      }
      restore.commit();
    }
  }
}
