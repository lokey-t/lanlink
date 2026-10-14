package com.lanlink.app;

import java.util.Locale;

/** Android-independent decisions: an unclean exit alone does not prove a battery-policy kill. */
final class BackgroundRecoveryPolicy {
  static String reason(int exitReason, boolean wasBackground, boolean restricted) {
    // ApplicationExitInfo values are stable Android API constants, available from API 30.
    switch (exitReason) {
      case 3: return "low-memory";
      case 4: case 5: case 6: return "crash";
      case 10: case 11: return "user-stop";
      case -1: case 0: case 2:
        return wasBackground && restricted ? "restricted" : "unknown";
      default: return "unknown"; // Resource use, updates and permission changes are not battery evidence.
    }
  }

  static boolean offerGuide(String reason, int exitReason, boolean wasBackground,
      boolean connected, boolean rebooted) {
    return wasBackground && connected && !rebooted
        && ("restricted".equals(reason)
            || "unknown".equals(reason) && (exitReason == -1 || exitReason == 0 || exitReason == 2));
  }

  static String manufacturer(String manufacturer, String brand) {
    String value = ((manufacturer == null ? "" : manufacturer) + " " + (brand == null ? "" : brand))
        .toLowerCase(Locale.ROOT);
    if (value.contains("xiaomi") || value.contains("redmi") || value.contains("poco")) return "xiaomi";
    if (value.contains("huawei") || value.contains("honor")) return "huawei";
    if (value.contains("oppo") || value.contains("realme") || value.contains("oneplus")) return "oppo";
    if (value.contains("vivo") || value.contains("iqoo")) return "vivo";
    if (value.contains("samsung")) return "samsung";
    return "other";
  }
}
