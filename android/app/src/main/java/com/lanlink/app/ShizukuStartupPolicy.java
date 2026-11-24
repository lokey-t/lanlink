package com.lanlink.app;

final class ShizukuStartupPolicy {
  // Shizuku's own UserService startup allowance is 30 seconds.
  static final long BIND_TIMEOUT = 35000;
  static final long HELPER_TIMEOUT = 15000;
  static final long RETRY_DELAY = 12000;

  static boolean timedOut(long now, long started, boolean connected) {
    return now - started >= (connected ? HELPER_TIMEOUT : BIND_TIMEOUT);
  }
}
