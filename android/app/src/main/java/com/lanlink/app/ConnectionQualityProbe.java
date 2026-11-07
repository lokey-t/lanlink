package com.lanlink.app;

import android.os.SystemClock;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.WeakHashMap;
import org.json.JSONObject;

/** Bounded synthetic measurements on an authenticated device session. */
final class ConnectionQualityProbe {
  private static final int BYTES = 64 * 1024;
  private final Map<Wire, Budget> budgets = new WeakHashMap<>();
  private String payload;

  private static final class Budget {
    final long started = SystemClock.elapsedRealtime();
    int count;
  }

  synchronized JSONObject transfer(Wire peer, JSONObject args) throws IOException {
    Budget budget = budgets.get(peer);
    if (budget == null || SystemClock.elapsedRealtime() - budget.started >= 1000) {
      budget = new Budget();
      budgets.put(peer, budget);
    }
    if (++budget.count > 24) throw new IOException("QUALITY_RATE_LIMIT");
    String direction = args.optString("direction");
    if (direction.equals("download")) {
      if (payload == null) {
        byte[] bytes = new byte[BYTES];
        new SecureRandom().nextBytes(bytes);
        payload = Base64.getEncoder().encodeToString(bytes);
      }
      return Wire.obj("data", payload);
    }
    String data = args.optString("data");
    if (direction.equals("upload") && data.length() == ((BYTES + 2) / 3) * 4) {
      try {
        if (Base64.getDecoder().decode(data).length == BYTES) return Wire.obj("bytes", BYTES);
      } catch (IllegalArgumentException ignored) {}
    }
    throw new IOException("QUALITY_INVALID_PAYLOAD");
  }
}
