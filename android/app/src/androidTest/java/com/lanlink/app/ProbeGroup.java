package com.lanlink.app;

import android.content.SharedPreferences;
import java.util.*;

/** Fixture connections authenticate into an actual temporary group, then restore the emulator. */
final class ProbeGroup implements AutoCloseable {
  private final LinkService service;
  private final SharedPreferences prefs;
  private final Map<String, ?> saved;
  private final String token;
  ProbeGroup(LinkService service, String next) throws Exception {
    this.service = service; prefs = service.getSharedPreferences("identity", 0);
    saved = new HashMap<>(prefs.getAll()); token = service.token;
    service.join(next, null);
  }
  public void close() throws Exception {
    service.stopInbound();
    for (Wire peer : new ArrayList<>(service.peers.values())) peer.close();
    SharedPreferences.Editor edit = prefs.edit().clear();
    for (Map.Entry<String, ?> entry : saved.entrySet()) {
      String key = entry.getKey(); Object value = entry.getValue();
      if (value instanceof String) edit.putString(key, (String) value);
      else if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
      else if (value instanceof Integer) edit.putInt(key, (Integer) value);
      else if (value instanceof Long) edit.putLong(key, (Long) value);
      else if (value instanceof Float) edit.putFloat(key, (Float) value);
      else if (value instanceof Set) edit.putStringSet(key, new HashSet<>((Set<String>) value));
    }
    edit.commit(); service.token = token; service.group = new GroupKeys(service, prefs);
  }
}
