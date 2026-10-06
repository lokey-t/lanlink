package com.lanlink.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

/** Only this application's operations are changed; preserve existing user settings on exit. */
final class RootPermissions {
  private static final String[] OPS = {"READ_CLIPBOARD", "WRITE_CLIPBOARD"};

  private static String command(String value) throws Exception {
    Process process = new ProcessBuilder("su", "-c", value).redirectErrorStream(true).start();
    if (!process.waitFor(4, TimeUnit.SECONDS)) {
      process.destroy();
      throw new IOException("Root 权限设置超时");
    }
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (InputStream input = process.getInputStream()) {
      byte[] buffer = new byte[1024];
      int n;
      while ((n = input.read(buffer)) > 0 && output.size() < 8192) output.write(buffer, 0, n);
    }
    if (process.exitValue() != 0) throw new IOException("系统不支持此 Root 权限设置");
    return output.toString("UTF-8");
  }

  static void enable(Context context) {
    SharedPreferences prefs = context.getSharedPreferences("root-permissions", 0);
    for (String op : OPS)
      try {
        if (!prefs.contains(op)) {
          String previous = command("cmd appops get com.lanlink.app " + op);
          Matcher match =
              Pattern.compile(": (allow|ignore|deny|default|foreground|errored)").matcher(previous);
          prefs.edit().putString(op, match.find() ? match.group(1) : "default").commit();
        }
        command("cmd appops set com.lanlink.app " + op + " allow");
      } catch (Exception ignored) {
        /* Readiness checks still use real system state. */
      }
    for (String permission :
        new String[] {"android.permission.CAMERA", "android.permission.POST_NOTIFICATIONS"})
      try {
        if (context.checkSelfPermission(permission)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
          if (!prefs.contains(permission)) prefs.edit().putBoolean(permission, true).commit();
          command("pm grant com.lanlink.app " + permission);
        }
      } catch (Exception ignored) {
      }
  }

  static void restore(Context context) {
    SharedPreferences prefs = context.getSharedPreferences("root-permissions", 0);
    for (String op : OPS)
      if (prefs.contains(op))
        try {
          String mode = prefs.getString(op, "default");
          if (!mode.matches("allow|ignore|deny|default|foreground|errored")) mode = "default";
          command("cmd appops set com.lanlink.app " + op + " " + mode);
          prefs.edit().remove(op).commit();
        } catch (Exception ignored) {
        }
    // Runtime permissions remain user-revocable in system settings. Revoking them here
    // would kill this process before the input helper and capture session are shut down.
  }
}
