package com.lanlink.app;

import android.app.*;
import java.lang.reflect.*;

final class RecoveryProbe {
  static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  static void run(Instrumentation test, LinkService service) throws Exception {
    String[] permissions = service.getPackageManager().getPackageInfo(service.getPackageName(),
        android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions;
    check(java.util.Arrays.asList(permissions).contains("android.permission.SYSTEM_ALERT_WINDOW"),
        "optional edge glow declares its overlay permission");
    ProjectionIdle idle = new ProjectionIdle();
    check(!idle.expired(false, 100, 0), "new projection waits for first use");
    idle.expired(true, 200, 60_000);
    check(!idle.expired(false, 300, 60_000), "disconnect starts idle deadline");
    check(!idle.expired(false, 60_299, 60_000), "deadline not early");
    check(idle.expired(false, 60_300, 60_000), "deadline expires");
    idle.expired(true, 60_400, 60_000);
    check(!idle.expired(false, 70_000, 60_000), "reconnect cancels previous deadline");
    check(!idle.expired(false, 9_000_000, -1), "never keeps authorization");
    check(idle.expired(false, 9_000_000, 0), "immediate stops idle projection");
    long previous = service.screenIdleDelay();
    for (long delay : ProjectionIdle.OPTIONS) {
      service.setScreenIdleDelay(delay);
      check(service.screenIdleDelay() == delay, "saved delay");
    }
    service.setScreenIdleDelay(previous);
  }
}
