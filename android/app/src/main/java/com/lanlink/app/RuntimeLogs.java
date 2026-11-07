package com.lanlink.app;

import static com.lanlink.app.Wire.obj;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

final class RuntimeLogs {
  private static final int LIMIT = 100;
  private final Deque<JSONObject> entries = new ArrayDeque<>();

  synchronized void add(String message) {
    entries.addFirst(obj("time", System.currentTimeMillis(), "message", line(message, 300)));
    if (entries.size() > LIMIT) entries.removeLast();
  }

  synchronized JSONArray snapshot() {
    return new JSONArray(entries);
  }

  static JSONObject collect(LinkService service) throws Exception {
    GroupKeys group = service.group;
    String token = service.token;
    Instant started = Instant.now();
    StringBuilder text = new StringBuilder("LanLink group logs\nExported: ").append(started).append('\n');
    heading(text, service.deviceId, service.name);
    appendEntries(text, service.runtimeLogs.snapshot());

    JSONObject devices = service.authorizationDevices();
    List<String> ids = new ArrayList<>();
    Map<String, Future<JSONArray>> requests = new HashMap<>();
    ExecutorService workers = Executors.newFixedThreadPool(Math.max(1, service.peers.size()));
    boolean incomplete = false;
    try {
      for (Iterator<String> it = devices.keys(); it.hasNext(); ) {
        String id = it.next();
        ids.add(id);
        Wire peer = service.peers.get(id);
        if (peer == null || !peer.connected || !peer.matchesGroupToken(token)) continue;
        requests.put(id, workers.submit(() -> {
          JSONArray logs = peer.requestDirect("runtime-logs", obj(), 5000).getJSONArray("logs");
          if (service.peers.get(id) != peer || group.excluded(id)) throw new IOException();
          return logs;
        }));
      }
      for (String id : ids) {
        heading(text, id, devices.getJSONObject(id).optString("name"));
        Future<JSONArray> request = requests.get(id);
        if (request == null) {
          incomplete = true;
          text.append("Offline\n");
        } else {
          try { appendEntries(text, request.get()); }
          catch (ExecutionException error) {
            incomplete = true;
            text.append("Logs unavailable (request failed or device needs an update)\n");
          }
        }
      }
    } finally {
      workers.shutdownNow();
    }
    if (service.group != group || !Objects.equals(service.token, token))
      throw new IOException(Language.t("组已发生变化，请重新导出日志"));
    String name = "LanLink-logs-" + started.truncatedTo(ChronoUnit.SECONDS).toString().replace(':', '-') + ".txt";
    return obj("name", name, "text", text.toString(), "incomplete", incomplete);
  }

  private static void heading(StringBuilder text, String id, String name) {
    text.append("\n=== ").append(name.isEmpty() ? "Device" : line(name, 80))
        .append(" (").append(line(id, 80)).append(") ===\n");
  }

  private static void appendEntries(StringBuilder text, JSONArray logs) {
    int start = text.length();
    for (int i = 0; i < Math.min(logs.length(), LIMIT); i++) {
      JSONObject entry = logs.optJSONObject(i);
      if (entry == null || !(entry.opt("time") instanceof Number) || !(entry.opt("message") instanceof String)) continue;
      long time = entry.optLong("time", -1);
      if (time < 0 || time > 8640000000000000L) continue;
      text.append(Instant.ofEpochMilli(time)).append("  ").append(line(entry.optString("message"), 300)).append('\n');
    }
    if (text.length() == start) text.append("No activity recorded\n");
  }

  private static String line(String value, int limit) {
    return value.substring(0, Math.min(value.length(), limit)).replaceAll("[\\p{Cc}\\p{Cf}\\u2028\\u2029]", " ");
  }
}
