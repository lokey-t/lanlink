package com.lanlink.app;

import java.io.IOException;
import java.util.*;
import org.json.*;

/** Authenticated device consent, independent from Android system permissions. */
final class DeviceConsent {
  static final String[] KEYS = {"input", "screen", "clipboard"};

  static final class Request {
    final String id, requestId, name;
    final long expires;

    Request(String id, String name, long now) {
      this.id = id;
      this.name = name;
      requestId = UUID.randomUUID().toString();
      expires = now + 120000;
    }
  }

  private JSONObject grants;
  private final LinkedHashMap<String, Request> pending = new LinkedHashMap<>();
  private final Map<String, Long> denied = new HashMap<>();

  DeviceConsent(String saved) {
    try {
      grants = new JSONObject(saved);
    } catch (Exception e) {
      grants = new JSONObject();
    }
  }

  synchronized boolean allowed(String id, String key) {
    JSONObject g = grants.optJSONObject(id);
    return g != null && g.optBoolean(key);
  }

  synchronized boolean hasGrant(String id) {
    return grants.has(id);
  }
  synchronized boolean rejected(String id) {peek();return denied.containsKey(id);}
  synchronized boolean pending(String id) {peek();return pending.containsKey(id);}
  synchronized void request(String id, String name) throws IOException {
    peek();
    if (pending.containsKey(id)) return;
    if (denied.containsKey(id)) throw new IOException("CONSENT_DENIED: 手机暂未同意，请稍后再试");
    if (pending.size() >= 8) throw new IOException("待确认请求过多");
    pending.put(id, new Request(id, name, System.currentTimeMillis()));
  }

  synchronized Request peek() {
    long now = System.currentTimeMillis();
    pending
        .values()
        .removeIf(
            r -> {
              if (now >= r.expires) {
                denied.put(r.id, now + 30000);
                return true;
              }
              return false;
            });
    denied.values().removeIf(until -> now >= until);
    return pending.isEmpty() ? null : pending.values().iterator().next();
  }

  synchronized void require(String id, String name, String key) throws IOException {
    peek();
    if (grants.has(id)) {
      if (!allowed(id, key)) throw new IOException("CONSENT_DENIED: 此设备未获准使用该功能");
      return;
    }
    if (denied.containsKey(id)) throw new IOException("CONSENT_DENIED: 手机暂未同意，请稍后重新发起");
    if (!pending.containsKey(id)) {
      if (pending.size() >= 8) throw new IOException("待确认请求过多");
      pending.put(id, new Request(id, name, System.currentTimeMillis()));
    }
    throw new IOException("CONSENT_PENDING: 等待手机同意首次控制");
  }

  synchronized String decide(String id, String requestId, boolean accept, JSONObject permissions)
      throws IOException {
    peek();
    Request r = pending.get(id);
    if (r == null || !r.requestId.equals(requestId)) throw new IOException("请求已结束，请让对方重新发起");
    if (accept) {
      JSONObject g = new JSONObject();
      try {
        for (String key : KEYS) g.put(key, permissions.optBoolean(key));
        grants.put(id, g);
      } catch (JSONException e) {
        throw new IOException(e);
      }
    } else denied.put(id, System.currentTimeMillis() + 120000);
    pending.remove(id);
    return grants.toString();
  }

  synchronized void disconnect(String id) {
    pending.remove(id);
  }

  synchronized String setPermission(String id, String key, boolean enabled) throws IOException {
    if (!Arrays.asList(KEYS).contains(key)) throw new IOException("未知权限");
    try {
      JSONObject grant = grants.optJSONObject(id);
      if (grant == null) {
        grant = new JSONObject();
        for (String entry : KEYS) grant.put(entry, false);
      }
      grant.put(key, enabled);
      grants.put(id, grant);
      pending.remove(id);
      denied.remove(id);
      return grants.toString();
    } catch (JSONException error) {
      throw new IOException(error);
    }
  }

  synchronized String revoke(String id) {
    grants.remove(id);
    pending.remove(id);
    denied.put(id, System.currentTimeMillis() + 30000);
    return grants.toString();
  }

  synchronized void reset() {
    grants = new JSONObject();
    pending.clear();
    denied.clear();
  }
}
