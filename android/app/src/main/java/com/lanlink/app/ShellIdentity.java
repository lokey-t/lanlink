package com.lanlink.app;

/** Shizuku may be started by shell or root without selecting LanLink's Root mode. */
final class ShellIdentity {
  static boolean shizukuUid(int uid) { return uid == 0 || uid == 2000; }

  static boolean accepts(boolean shizuku, boolean rootMode, int grantedUid, int incomingUid) {
    return shizuku
        ? shizukuUid(grantedUid) && incomingUid == grantedUid
        : incomingUid == (rootMode ? 0 : 2000);
  }
}
