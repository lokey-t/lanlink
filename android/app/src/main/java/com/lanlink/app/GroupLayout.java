package com.lanlink.app;

import static com.lanlink.app.Wire.obj;
import android.content.SharedPreferences;
import java.io.IOException;
import java.util.*;
import org.json.*;

/** Same-group, persisted LWW graph. Coordinates never depend on the viewer or master. */
final class GroupLayout {
  private final LinkService service;
  GroupLayout(LinkService service) { this.service = service; }
  private SharedPreferences prefs() { return service.getSharedPreferences("identity", 0); }
  private String group() { return service.group == null ? "" : service.group.data.optString("id"); }
  private static JSONObject copy(JSONObject value) { try { return new JSONObject(value.toString()); } catch (JSONException e) { throw new IllegalArgumentException(e); } }
  private JSONObject saved(String key) { try { return new JSONObject(prefs().getString(key, "{}")); } catch (JSONException e) { return obj(); } }
  JSONObject devices() {
    JSONObject devices = service.authorizationDevices();
    if (service.deviceId != null && service.group != null && !service.group.isDissolved()) {
      try { devices.put(service.deviceId, obj("name", service.name, "platform", "android")); } catch (JSONException ignored) {}
    }
    return devices;
  }
  static boolean desktop(JSONObject device) { return device != null && ("darwin".equals(device.optString("platform")) || "win32".equals(device.optString("platform"))); }
  synchronized JSONObject layout() {
    JSONObject value = saved("layout");
    return group().equals(value.optString("group")) ? value : obj("group", group(), "revision", 0, "origin", "", "positions", obj());
  }
  synchronized JSONObject master() {
    JSONObject value = saved("masterSelection");
    return group().equals(value.optString("group")) && value.has("id") ? value : null;
  }
  static boolean integer(Object value, long min, long max) {
    if (!(value instanceof Number)) return false;
    double number = ((Number) value).doubleValue();
    return Double.isFinite(number) && number >= min && number <= max && Math.floor(number) == number;
  }
  static JSONObject positions(JSONObject value, JSONObject devices) throws IOException {
    if (value == null || value.length() > 64) throw new IOException("设备布局无效，最多支持 64 个位置");
    JSONObject result = obj(); Set<String> cells = new HashSet<>();
    for (Iterator<String> it = value.keys(); it.hasNext();) {
      String id = it.next(); JSONObject point = value.optJSONObject(id);
      if (!devices.has(id) || point == null || !integer(point.opt("x"), -32, 32) || !integer(point.opt("y"), -32, 32))
        throw new IOException("布局只能包含本组设备，坐标范围为 -32 到 32");
      int x = point.optInt("x"), y = point.optInt("y");
      if (!cells.add(x + "," + y)) throw new IOException("此位置已有设备");
      try { result.put(id, obj("x", x, "y", y)); } catch (JSONException ignored) {}
    }
    return result;
  }
  private boolean valid(JSONObject value, JSONObject devices, String expectedGroup) {
    return value != null && expectedGroup.equals(value.optString("group"))
        && integer(value.opt("revision"), 1, Math.min(9007199254740991L, System.currentTimeMillis() + 86400000L))
        && value.opt("origin") instanceof String && devices.has(value.optString("origin"));
  }
  static boolean newer(JSONObject value, JSONObject old) {
    return old == null || value.optLong("revision") > old.optLong("revision")
        || value.optLong("revision") == old.optLong("revision") && value.optString("origin").compareTo(old.optString("origin")) > 0;
  }
  boolean applyLayout(JSONObject value) throws IOException {
    String expectedGroup = group(); JSONObject devices = devices();
    synchronized (this) {
      if (!expectedGroup.equals(group()) || !valid(value, devices, expectedGroup) || !newer(value, layout())) return false;
      JSONObject positions; try { positions = positions(value.optJSONObject("positions"), devices); } catch (IOException ignored) { return false; }
      JSONObject next = obj("group", expectedGroup, "revision", value.optLong("revision"), "origin", value.optString("origin"), "positions", positions);
      if (!prefs().edit().putString("layout", next.toString()).commit()) throw new IOException("布局保存失败");
    }
    publish(); return true;
  }
  boolean applyMaster(JSONObject value) throws IOException {
    String expectedGroup = group(); JSONObject devices = devices();
    synchronized (this) {
      if (!expectedGroup.equals(group()) || !valid(value, devices, expectedGroup) || !desktop(devices.optJSONObject(value.optString("id"))) || !newer(value, master())) return false;
      JSONObject next = obj("group", expectedGroup, "revision", value.optLong("revision"), "origin", value.optString("origin"), "id", value.optString("id"));
      if (!prefs().edit().putString("masterSelection", next.toString()).commit()) throw new IOException("主控选择保存失败");
    }
    service.masterSelectionChanged(); publish(); return true;
  }
  void chooseMaster(String id) throws IOException {
    String expectedGroup = group(); JSONObject devices = devices();
    if (!desktop(devices.optJSONObject(id))) throw new IOException("请选择本组的 Windows 或 Mac 电脑");
    synchronized (this) {
      if (!expectedGroup.equals(group())) throw new IOException("组已发生变化，请重试");
      JSONObject old = master();
      JSONObject next = obj("group", expectedGroup, "id", id, "revision", Math.max(System.currentTimeMillis(), old == null ? 1 : old.optLong("revision") + 1), "origin", service.deviceId);
      if (!prefs().edit().putString("masterSelection", next.toString()).commit()) throw new IOException("主控选择保存失败");
    }
    service.masterSelectionChanged(); publish();
  }
  void place(String id, int x, int y) throws IOException { edit(id, x, y, false); }
  void remove(String id) throws IOException { edit(id, 0, 0, true); }
  private void edit(String id, int x, int y, boolean remove) throws IOException {
    String expectedGroup = group(); JSONObject devices = devices(); boolean selectedMaster = false;
    synchronized (this) {
      if (!expectedGroup.equals(group())) throw new IOException("组已发生变化，请重试");
      if (!devices.has(id)) throw new IOException("请选择本组设备");
      JSONObject old = layout(), points = copy(old.optJSONObject("positions") == null ? obj() : old.optJSONObject("positions"));
      try { if (remove) points.remove(id); else points.put(id, obj("x", x, "y", y)); } catch (JSONException ignored) {}
      points = positions(points, devices);
      JSONObject next = obj("group", expectedGroup, "revision", Math.max(System.currentTimeMillis(), old.optLong("revision") + 1), "origin", service.deviceId, "positions", points);
      SharedPreferences.Editor edit = prefs().edit().putString("layout", next.toString());
      if (!remove && master() == null) {
        ArrayList<String> ids = new ArrayList<>(); for (Iterator<String> it = devices.keys(); it.hasNext();) { String key = it.next(); if (desktop(devices.optJSONObject(key))) ids.add(key); } Collections.sort(ids);
        if (!ids.isEmpty()) { edit.putString("masterSelection", obj("group", expectedGroup, "revision", 1, "origin", service.deviceId, "id", ids.get(0)).toString()); selectedMaster = true; }
      }
      if (!edit.commit()) throw new IOException("布局保存失败");
    }
    if (selectedMaster) service.masterSelectionChanged(); publish();
  }
  void requireMaster(String id, boolean pointer) throws IOException {
    JSONObject selected = master();
    if (pointer && selected != null && !id.equals(selected.optString("id"))) throw new IOException("MASTER_REQUIRED: 请从组内选定的主控电脑移动鼠标");
  }
  void receive(JSONObject message) throws IOException {
    applyMaster(message.optJSONObject("masterSelection")); applyLayout(message.optJSONObject("layout"));
  }
  JSONObject attach(JSONObject message) {
    try { message.put("layout", layout()); JSONObject master = master(); if (master != null) message.put("masterSelection", master); } catch (JSONException ignored) {}
    return message;
  }
  void noteVersion(Wire peer, JSONObject message) {
    JSONObject value = message.optJSONObject("layoutVersion"), devices = devices();
    if (value == null || !group().equals(value.optString("group")) || !integer(value.opt("revision"), 0, System.currentTimeMillis() + 86400000L) || !(value.opt("origin") instanceof String)) return;
    long revision = value.optLong("revision"); String origin = value.optString("origin");
    if (revision == 0 ? !origin.isEmpty() : !devices.has(origin)) return;
    peer.peerLayoutVersion = obj("group", group(), "revision", revision, "origin", origin);
  }
  JSONObject heartbeat(Wire peer, String type) {
    JSONObject graph = layout(), version = obj("group", graph.optString("group"), "revision", graph.optLong("revision"), "origin", graph.optString("origin"));
    JSONObject message = obj("type", type, "layoutVersion", version), acknowledged = peer.peerLayoutVersion, selected = master();
    try {
      if (selected != null) message.put("masterSelection", selected);
      if (graph.optLong("revision") > 0 && (acknowledged == null || !version.optString("group").equals(acknowledged.optString("group")) || version.optLong("revision") != acknowledged.optLong("revision") || !version.optString("origin").equals(acknowledged.optString("origin")))) message.put("layout", graph);
    } catch (JSONException ignored) {}
    return message;
  }
  void publish() {
    JSONObject graph = layout(), selected = master();
    for (Wire peer : service.peers.values()) try {
      if (selected != null) peer.send(obj("type", "master-selection", "choice", selected));
      if (graph.optLong("revision") > 0) peer.send(obj("type", "layout-update", "layout", graph));
    } catch (Exception ignored) {}
  }
  void reconcile() throws IOException {
    JSONObject devices = devices(); boolean masterChanged = false;
    synchronized (this) {
      JSONObject old = saved("layout"), selected = saved("masterSelection");
      SharedPreferences.Editor edit = prefs().edit();
      if (!group().equals(old.optString("group"))) edit.remove("layout");
      else {
        JSONObject original = old.optJSONObject("positions"), points = original == null ? null : copy(original);
        if (points != null) {
          ArrayList<String> ids = new ArrayList<>(); for (Iterator<String> it = points.keys(); it.hasNext();) ids.add(it.next());
          for (String id : ids) if (!devices.has(id)) points.remove(id);
        }
        try {
          points = positions(points, devices);
          if (!points.toString().equals(original.toString())) old = obj("group", group(), "revision", Math.max(System.currentTimeMillis(), old.optLong("revision") + 1), "origin", service.deviceId, "positions", points);
          edit.putString("layout", old.toString());
        } catch (IOException e) { edit.remove("layout"); }
      }
      if (!group().equals(selected.optString("group")) || !desktop(devices.optJSONObject(selected.optString("id")))) { edit.remove("masterSelection"); masterChanged = selected.has("id"); }
      if (!edit.commit()) throw new IOException("组布局保存失败");
    }
    if (masterChanged) service.masterSelectionChanged();
  }
}
