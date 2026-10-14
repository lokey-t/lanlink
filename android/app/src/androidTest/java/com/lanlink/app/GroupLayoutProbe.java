package com.lanlink.app;

import android.app.*;
import android.content.*;
import java.util.*;
import org.json.*;

final class GroupLayoutProbe {
  static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
  static JSONObject graph(String group, long revision, String origin, JSONObject positions) { return Wire.obj("group", group, "revision", revision, "origin", origin, "positions", positions); }
  static JSONObject point(int x, int y) { return Wire.obj("x", x, "y", y); }
  static String run(Instrumentation test, Activity activity, LinkService service) throws Exception {
    SharedPreferences prefs = service.getSharedPreferences("identity", 0);
    String oldKnown = prefs.getString("known_devices", null), oldLayout = prefs.getString("layout", null), oldMaster = prefs.getString("masterSelection", null);
    String mac = "11111111-1111-4111-8111-111111111111", windows = "22222222-2222-4222-8222-222222222222", mobile = "33333333-3333-4333-8333-333333333333";
    String group = service.group.data.getString("id"), own = service.deviceId;
    JSONObject known = Wire.obj(mac, Wire.obj("name", "Mac fixture", "platform", "darwin"), windows, Wire.obj("name", "Windows fixture", "platform", "win32"), mobile, Wire.obj("name", "Second Android", "platform", "android"));
    try {
      prefs.edit().putString("known_devices", known.toString()).remove("layout").remove("masterSelection").commit();
      GroupLayout state = service.groupLayout;
      check(state.layout().optLong("revision") == 0 && state.master() == null, "empty phone does not invent a master or graph");
      state.place(own, 0, 0);
      check(state.layout().getJSONObject("positions").length() == 1 && state.master().getString("id").equals(mac), "first placement adds only its chosen device and persists independent default desktop master");
      state.chooseMaster(mac); long explicitRevision = state.master().optLong("revision");
      check(explicitRevision > 1 && !state.applyMaster(Wire.obj("group", group, "id", windows, "revision", 1, "origin", windows)), "explicit confirmation of same master outranks delayed legacy defaults");
      state.chooseMaster(mac); check(state.master().optLong("revision") > explicitRevision, "each explicit master selection persists a new revision");
      state.place(mac, -1, 0); state.place(windows, 1, 0); state.place(mobile, 2, 0);
      JSONObject before = state.layout().getJSONObject("positions");
      check(before.length() == 4 && before.getJSONObject(mobile).getInt("x") == 2, "multilevel graph preserves prior incremental placements");
      state.chooseMaster(windows);
      check(state.layout().getJSONObject("positions").toString().equals(before.toString()), "master change never reorders the graph");
      check(new GroupLayout(service).master().getString("id").equals(windows), "master selection persists across state recreation");
      try { state.requireMaster(mac, true); throw new AssertionError("nonmaster pointer accepted"); } catch (java.io.IOException expected) { check(expected.getMessage().startsWith("MASTER_REQUIRED:"), "nonmaster pointer error"); }
      state.requireMaster(mac, false); state.requireMaster(windows, true);
      try { state.chooseMaster(mobile); throw new AssertionError("Android became global pointer master"); } catch (java.io.IOException expected) {}
      long revision = System.currentTimeMillis() + 100;
      check(state.applyLayout(graph(group, revision, mac, Wire.obj(own, point(0, 0), mac, point(-1, 0)))), "newer graph accepted");
      check(state.applyLayout(graph(group, revision, windows, Wire.obj(own, point(0, 0), windows, point(0, 1)))), "equal revision higher origin wins");
      check(!state.applyLayout(graph(group, revision, mac, before)), "equal revision lower origin cannot overwrite");
      check(!state.applyLayout(graph("other-group", revision + 1, windows, before)), "cross-group update rejected");
      check(!state.applyLayout(graph(group, revision + 1, "unknown", before)), "unknown origin rejected");
      check(!state.applyLayout(graph(group, System.currentTimeMillis() + 86401000, mac, before)), "far future revision rejected");
      check(!state.applyLayout(graph(group, revision + 1, mac, Wire.obj(own, point(0, 0), mac, point(0, 0)))), "overlapping positions rejected");
      check(!state.applyLayout(graph(group, revision + 1, mac, Wire.obj(own, Wire.obj("x", .5, "y", 0)))), "fractional position rejected");
      check(!state.applyLayout(graph(group, revision + 1, mac, Wire.obj(own, point(33, 0)))), "out of range rejected");
      check(!state.applyLayout(graph(group, revision + 1, mac, Wire.obj("unknown", point(0, 0)))), "unknown device rejected");
      JSONObject tooMany = Wire.obj(), manyDevices = Wire.obj(); for (int i = 0; i < 65; i++) { tooMany.put("device-" + i, point(i - 32, 0)); manyDevices.put("device-" + i, Wire.obj()); }
      try { GroupLayout.positions(tooMany, manyDevices); throw new AssertionError("65 positions accepted"); } catch (java.io.IOException expected) {}
      check(GroupLayout.positions(Wire.obj(mac, point(-32, 32)), state.devices()).getJSONObject(mac).getInt("x") == -32, "integer coordinate boundaries accepted");
      JSONObject invalid = graph(group, revision + 1, mac, before); invalid.put("revision", "123"); check(!state.applyLayout(invalid), "revision strings rejected");
      JSONObject rejected = Wire.obj("group", group, "revision", revision + 1, "origin", own, "id", mobile); check(!state.applyMaster(rejected), "remote Android master rejected");
      JSONObject master = Wire.obj("group", group, "revision", revision + 2, "origin", own, "id", mac); check(state.applyMaster(master), "phone origin may choose a desktop master");
      JSONObject packet = state.attach(Wire.obj("type", "ping")); check(packet.has("layout") && packet.has("masterSelection"), "heartbeat carries both persisted selections");
      Wire cached = new Wire(new java.net.Socket(), Wire.obj(), Wire.parseToken(service.token), AuxIsolationProbe.IDLE);
      JSONObject initial = state.heartbeat(cached, "ping"); check(initial.has("layout"), "new and legacy peers receive full graph");
      state.noteVersion(cached, Wire.obj("layoutVersion", initial.getJSONObject("layoutVersion")));
      check(!state.heartbeat(cached, "pong").has("layout"), "matching acknowledged version suppresses redundant full graph");
      state.noteVersion(cached, Wire.obj("layoutVersion", Wire.obj("group", group, "revision", Long.MAX_VALUE, "origin", own)));
      check(!state.heartbeat(cached, "ping").has("layout"), "invalid version cannot overwrite a valid acknowledgment");
      state.place(mac, -1, 0); check(state.heartbeat(cached, "ping").has("layout"), "local change retransmits graph until acknowledged");
      Wire reconnect = new Wire(new java.net.Socket(), Wire.obj(), Wire.parseToken(service.token), AuxIsolationProbe.IDLE);
      check(state.heartbeat(reconnect, "ping").has("layout"), "new wire never inherits an old acknowledgment"); cached.close(); reconnect.close();
      state.remove(windows); check(!state.layout().getJSONObject("positions").has(windows), "remove only removes target position");
      state.place(mobile, 2, 0); long prior = state.layout().optLong("revision"); known.remove(mobile); prefs.edit().putString("known_devices", known.toString()).commit(); state.reconcile();
      check(!state.layout().getJSONObject("positions").has(mobile) && state.layout().optLong("revision") > prior, "removed device pruned with newer revision");
      state.place(own, 0, 0); state.place(mac, -1, 0); state.place(windows, 1, 0);
      Wire stale = new Wire(new java.net.Socket(), Wire.obj(), Wire.parseToken(service.token), AuxIsolationProbe.IDLE); stale.id = mac; stale.connected = true;
      service.peers.put(mac, stale); String savedToken = service.token;
      try {
        service.token = Wire.token(); long originalRevision = state.layout().optLong("revision");
        service.message(stale, Wire.obj("type", "layout-update", "layout", graph(group, originalRevision + 1, mac, Wire.obj())));
        check(state.layout().optLong("revision") == originalRevision, "old group key cannot publish a graph while socket is draining");
        try { service.handle(stale, "permission-status", Wire.obj()); throw new AssertionError("old group key still executed a request"); } catch (java.io.IOException expected) {}
        service.ready(stale); check(!stale.connected, "delayed old-key handshake cannot re-enter peer map");
      } finally { service.token = savedToken; service.peers.remove(mac, stale); stale.close(); }
      test.runOnMainSync(() -> {
        try {
          java.lang.reflect.Field page = MainActivity.class.getDeclaredField("page"); page.setAccessible(true); page.set(activity, "layout");
          java.lang.reflect.Method render = MainActivity.class.getDeclaredMethod("renderPage"); render.setAccessible(true); render.invoke(activity);
        } catch (Exception error) { throw new AssertionError(error); }
      });
      Thread.sleep(500);
      android.graphics.Bitmap screenshot = test.getUiAutomation().takeScreenshot(); check(screenshot != null, "layout editor is visible");
      java.io.File folder = new java.io.File(activity.getExternalFilesDir(null), "ui-check"); folder.mkdirs();
      try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(folder, "group-layout.png"))) { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); } finally { screenshot.recycle(); }
      return "PASS: Android same-group persisted layout/master, LWW convergence, strict membership/position/time validation, multilevel incremental edits, master pointer gate and native whole-group editor";
    } finally {
      SharedPreferences.Editor restore = prefs.edit();
      if (oldKnown == null) restore.remove("known_devices"); else restore.putString("known_devices", oldKnown);
      if (oldLayout == null) restore.remove("layout"); else restore.putString("layout", oldLayout);
      if (oldMaster == null) restore.remove("masterSelection"); else restore.putString("masterSelection", oldMaster);
      restore.commit();
    }
  }
}
