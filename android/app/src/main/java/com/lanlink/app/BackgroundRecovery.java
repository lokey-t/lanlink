package com.lanlink.app;

import static com.lanlink.app.Wire.obj;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import java.util.UUID;
import org.json.JSONObject;

/** Persists the service session before it can be killed, then reports evidence on the next start. */
final class BackgroundRecovery {
  private static final long MAX_GUIDE_AGE = 7 * 24 * 60 * 60 * 1000L;
  private final Context context;
  private final SharedPreferences prefs;
  final String sessionId = UUID.randomUUID().toString();
  private JSONObject recovery;
  private JSONObject policy;
  private boolean foreground, connected, stopped;
  private long lastSample;

  BackgroundRecovery(Context context) {
    this.context = context.getApplicationContext();
    prefs = context.getSharedPreferences("background-recovery", Context.MODE_PRIVATE);
    policy = readPolicy(this.context);
    recovery = previousRecovery();
    if (prefs.getBoolean("running", false)) detectPreviousSession();
    long now = System.currentTimeMillis();
    // commit is intentional: the session must exist on disk before a peer can receive its ID.
    prefs.edit().putString("session", sessionId).putInt("pid", Process.myPid())
        .putLong("startedAt", now).putLong("lastSeen", now)
        .putLong("elapsed", SystemClock.elapsedRealtime()).putInt("boot", bootCount())
        .putBoolean("running", true).putBoolean("foreground", false).putBoolean("connected", false)
        .putBoolean("restricted", policy.optBoolean("backgroundRestricted"))
        .putBoolean("batteryExempt", policy.optBoolean("batteryExempt"))
        .putString("recovery", recovery == null ? "" : recovery.toString()).commit();
  }

  private JSONObject previousRecovery() {
    try { return new JSONObject(prefs.getString("recovery", "")); }
    catch (Exception ignored) { return null; }
  }

  private int bootCount() {
    return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
  }

  private void detectPreviousSession() {
    String previous = prefs.getString("session", "");
    if (previous.isEmpty()) return;
    long started = prefs.getLong("startedAt", 0), now = System.currentTimeMillis();
    boolean background = !prefs.getBoolean("foreground", false);
    int priorBoot = prefs.getInt("boot", -1), boot = bootCount();
    boolean rebooted = priorBoot >= 0 && boot >= 0 ? priorBoot != boot
        : SystemClock.elapsedRealtime() < prefs.getLong("elapsed", 0);
    int exitReason = -1;
    long at = prefs.getLong("lastSeen", started);
    if (Build.VERSION.SDK_INT >= 30) {
      try {
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        if (manager != null) for (ApplicationExitInfo exit : manager.getHistoricalProcessExitReasons(
            context.getPackageName(), prefs.getInt("pid", 0), 16)) {
          // Ignore the shell helper and older process records, including PID reuse after reboot.
          if (!context.getPackageName().equals(exit.getProcessName()) || exit.getTimestamp() < started)
            continue;
          exitReason = exit.getReason();
          at = exit.getTimestamp();
          break;
        }
      } catch (RuntimeException ignored) { /* API 29 and some OEMs supply no exit evidence. */ }
    }
    boolean restricted = prefs.getBoolean("restricted", false);
    String reason = BackgroundRecoveryPolicy.reason(exitReason, background, restricted && !rebooted);
    recovery = obj("id", UUID.randomUUID().toString(), "previousSessionId", previous,
        "reason", reason, "at", at, "detectedAt", now, "wasBackground", background,
        "evidence", exitReason == -1 ? "unclean-session" : "android-exit-info",
        "exitReason", exitReason, "backgroundRestrictedAtExit", restricted,
        "batteryExemptAtExit", prefs.getBoolean("batteryExempt", false), "rebooted", rebooted,
        "guide", now - at < MAX_GUIDE_AGE && BackgroundRecoveryPolicy.offerGuide(reason, exitReason,
            background, prefs.getBoolean("connected", false), rebooted));
  }

  static JSONObject readPolicy(Context context) {
    PowerManager power = context.getSystemService(PowerManager.class);
    ActivityManager activity = context.getSystemService(ActivityManager.class);
    return obj("batteryExempt", power != null && power.isIgnoringBatteryOptimizations(context.getPackageName()),
        "backgroundRestricted", activity != null && activity.isBackgroundRestricted(),
        "powerSave", power != null && power.isPowerSaveMode(), "manufacturer", Build.MANUFACTURER,
        "model", Build.MODEL, "autoStart", "unknown");
  }

  synchronized void sample(boolean force) {
    if (stopped) return;
    long elapsed = SystemClock.elapsedRealtime();
    if (!force && elapsed - lastSample < 30_000) return;
    lastSample = elapsed;
    policy = readPolicy(context);
    prefs.edit().putLong("lastSeen", System.currentTimeMillis()).putLong("elapsed", elapsed)
        .putBoolean("foreground", foreground).putBoolean("connected", connected)
        .putBoolean("restricted", policy.optBoolean("backgroundRestricted"))
        .putBoolean("batteryExempt", policy.optBoolean("batteryExempt")).apply();
  }

  synchronized void foreground(boolean value) {
    if (foreground == value) return;
    foreground = value;
    sample(true);
  }

  synchronized void connected(boolean value) {
    if (connected == value) return;
    connected = value;
    sample(true);
  }

  synchronized void stopped() {
    stopped = true;
    prefs.edit().putBoolean("running", false).commit();
  }

  synchronized JSONObject policy() { return policy; }
  synchronized JSONObject recovery() { return recovery; }

  synchronized JSONObject pendingGuide() {
    if (recovery == null || !recovery.optBoolean("guide")
        || recovery.optString("id").equals(prefs.getString("guideSeen", ""))
        || System.currentTimeMillis() - recovery.optLong("detectedAt") > MAX_GUIDE_AGE) return null;
    return recovery;
  }

  synchronized void guideShown(String id) { prefs.edit().putString("guideSeen", id).commit(); }

  static String manufacturer() {
    return BackgroundRecoveryPolicy.manufacturer(Build.MANUFACTURER, Build.BRAND);
  }

  static String guidance() {
    switch (manufacturer()) {
      case "xiaomi": return Language.t("小米 / Redmi / POCO：在应用信息的省电策略中选择「无限制」，并在权限或手机管家中允许 LanLink 自启动。菜单名称可能因 MIUI / HyperOS 版本不同而变化。");
      case "huawei": return Language.t("华为 / 荣耀：在应用启动管理中找到 LanLink，关闭自动管理，并允许自启动、关联启动和后台活动。部分机型在电池设置中提供这些选项。");
      case "oppo": return Language.t("OPPO / realme / 一加：在应用信息的电池管理中允许后台活动，并在自启动管理中允许 LanLink 自启动。菜单名称可能因系统版本不同而变化。");
      case "vivo": return Language.t("vivo / iQOO：在电池后台耗电管理中允许 LanLink 后台高耗电，并在权限管理中允许自启动。");
      case "samsung": return Language.t("三星：在应用信息的电池选项中选择「不受限制」，并将 LanLink 加入「从不休眠的应用」。如系统没有自启动开关，无需另行设置。");
      default: return Language.t("在应用信息的电池设置中允许后台运行或选择「不受限制」。如手机提供自启动管理，请允许 LanLink 自启动；没有此选项时可跳过。");
    }
  }

  static boolean openBatterySettings(Activity activity) {
    return launch(activity, new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        || openAppSettings(activity);
  }

  static boolean openStartupSettings(Activity activity) {
    // OEM screens are optional, undocumented entry points. Never assume their presence or success.
    String[][] components;
    switch (manufacturer()) {
      case "xiaomi": components = new String[][] {{"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"}}; break;
      case "huawei": components = new String[][] {
          {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
          {"com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"}}; break;
      case "oppo": components = new String[][] {
          {"com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"},
          {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"}}; break;
      case "vivo": components = new String[][] {{"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"}}; break;
      default: components = new String[0][];
    }
    for (String[] component : components)
      if (launch(activity, new Intent().setComponent(new ComponentName(component[0], component[1]))
          .putExtra("package_name", activity.getPackageName()))) return true;
    return openAppSettings(activity);
  }

  private static boolean openAppSettings(Activity activity) {
    return launch(activity, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:" + activity.getPackageName()))) || launch(activity, new Intent(Settings.ACTION_SETTINGS));
  }

  private static boolean launch(Activity activity, Intent intent) {
    try { activity.startActivity(intent); return true; }
    catch (ActivityNotFoundException | SecurityException ignored) { return false; }
  }
}
