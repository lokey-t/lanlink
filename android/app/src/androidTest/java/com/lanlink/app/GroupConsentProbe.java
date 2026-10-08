package com.lanlink.app;

import android.content.SharedPreferences;
import java.io.IOException;
import java.net.Socket;
import java.util.*;
import org.json.*;

/** Emulator-only checks against the real Android permission implementation. */
final class GroupConsentProbe {
  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  static String run(LinkService service) throws Exception {
    SharedVideoSourceProbe.run();
    check(LinkService.fileChunkLimit(Wire.obj()) == 192 * 1024, "legacy chunk size is preserved");
    check(LinkService.fileChunkLimit(Wire.obj("maxBytes", 256)) == 256, "weak-network chunk request is honored");
    for (Object invalid : new Object[] {0, -1, 196609, 2.5, "256"}) {
      boolean rejected = false;
      try { LinkService.fileChunkLimit(Wire.obj("maxBytes", invalid)); } catch (IOException expected) { rejected = true; }
      check(rejected, "invalid file chunk limit is rejected");
    }
    SharedPreferences prefs = service.getSharedPreferences("identity", 0);
    Map<String, ?> before = prefs.getAll();
    DeviceConsent savedConsent = service.consent;
    ArrayList<Wire> peers = new ArrayList<>();
    try {
      prefs.edit().remove("allow_input").remove("allow_screen").remove("allow_clipboard")
          .putBoolean("root_mode", false).putBoolean("adb_revoked", false).commit();
      service.consent = new DeviceConsent("{}");
      for (String platform : new String[] {"darwin", "win32", "android", "android"}) {
        Wire peer = new Wire(new Socket(), Wire.obj(), new byte[32], service);
        peer.id = UUID.randomUUID().toString();
        peer.info = Wire.obj("name", "组内测试 " + platform, "platform", platform);
        peers.add(peer);
        boolean rejected = false;
        try { service.handle(peer, "permission-status", Wire.obj()); }
        catch (IOException expected) { rejected = true; }
        check(rejected, "unknown connection cannot query or use group defaults");
        service.peers.put(peer.id, peer);
        JSONObject report = service.handle(peer, "permission-status", Wire.obj());
        for (String key : DeviceConsent.KEYS) {
          JSONObject row = report.getJSONObject("permissions").getJSONObject(key);
          check(row.getBoolean("peerAllowed") && row.getBoolean("appEnabled"), "first group request defaults to allow " + key);
          service.consent.require(peer.id, "group fixture", key);
        }
        check(!report.getJSONObject("permissions").getJSONObject("shutdown").getBoolean("peerAllowed"), "unsupported permission is never implicitly allowed");
        check(service.handle(peer, "permission-request", Wire.obj()).getBoolean("ready"), "first request immediately ready");
        check(service.consent.peek() == null, "no application approval prompt for trusted peers");
        check(report.getJSONObject("engine").getBoolean("ready") ==
            (service.shell.healthy() && service.shell.lastInputError == null && service.adbAuthorized()), "engine readiness remains dependent on live system helper");
      }
      Wire a = peers.get(0), b = peers.get(1);
      Wire replacement = new Wire(new Socket(), Wire.obj(), new byte[32], service);
      replacement.id = a.id; replacement.info = a.info;
      service.peers.put(a.id, replacement);
      boolean staleDenied = false;
      try { service.handle(a, "release", Wire.obj()); } catch (IOException expected) { staleDenied = true; }
      check(staleDenied, "old connection cannot release replacement connection ownership");
      service.peers.put(a.id, a); replacement.close();
      service.setDevicePermission(a.id, "screen", false);
      check(!service.consent.allowed(a.id, "screen") && service.consent.allowed(a.id, "input") && service.consent.allowed(a.id, "clipboard"), "disabling one feature preserves the other defaults");
      check(service.consent.allowed(b.id, "screen"), "restriction cannot affect another computer");
      service.revokeConsent(a.id);
      DeviceConsent reloaded = new DeviceConsent(prefs.getString("device_grants", "{}"));
      for (String key : DeviceConsent.KEYS) check(!reloaded.allowed(a.id, key), "explicit revocation survives restart " + key);
      check(reloaded.allowed(b.id, "input"), "other computer remains allowed after reload");
      boolean denied = false;
      try { service.consent.require(a.id, "revoked fixture", "input"); }
      catch (IOException expected) { denied = expected.getMessage().startsWith("CONSENT_DENIED:"); }
      check(denied && service.consent.peek() == null, "revoked operations fail without repeated prompts");
      service.handle(a, "permission-request", Wire.obj("permissions", new JSONArray().put("input")));
      DeviceConsent.Request pending = service.consent.peek();
      check(pending != null && pending.id.equals(a.id), "explicit request can restore revoked access");
      service.decideConsent(pending, true, Wire.obj("input", true, "screen", false, "clipboard", false));
      check(service.consent.allowed(a.id, "input") && !service.consent.allowed(a.id, "screen"), "restored selection preserves partial restrictions");
      service.setAllowed("input", false);
      JSONObject restricted = service.handle(b, "permission-status", Wire.obj());
      check(restricted.getJSONObject("permissions").getJSONObject("input").getBoolean("peerAllowed") &&
          !restricted.getJSONObject("permissions").getJSONObject("input").getBoolean("appEnabled"), "explicit app disable remains effective despite group defaults");
      check(!new DeviceConsent("{\"invalid\":null}").allowed("invalid", "input"), "malformed explicit overrides fail closed");
      return "PASS: four independent group peers, first-use defaults, explicit per-device/app restrictions, persistent revoke, restored access, live system-helper readiness and shared multi-viewer encoder lifecycle";
    } finally {
      for (Wire peer : peers) { service.peers.remove(peer.id, peer); service.permissionNotice.removePeer(peer.id); peer.close(); }
      service.consent = savedConsent;
      SharedPreferences.Editor restore = prefs.edit();
      for (String key : new String[] {"allow_input", "allow_screen", "allow_clipboard", "root_mode", "adb_revoked", "device_grants", "paused_peers"}) {
        Object value = before.get(key);
        if (value == null) restore.remove(key);
        else if (value instanceof Boolean) restore.putBoolean(key, (Boolean) value);
        else if (value instanceof String) restore.putString(key, (String) value);
        else if (value instanceof Set) restore.putStringSet(key, new HashSet<>((Set<String>) value));
      }
      restore.commit(); service.dismissPermissionNotice();
    }
  }
}
