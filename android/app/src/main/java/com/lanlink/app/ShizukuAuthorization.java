package com.lanlink.app;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;
import rikka.shizuku.Shizuku;

/** Local opt-in, permission callbacks and bounded recovery for the Shizuku backend. */
final class ShizukuAuthorization implements AutoCloseable {
  private static final int REQUEST = 7401;
  private final LinkService service;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Shizuku.UserServiceArgs args;
  private volatile boolean closed, binding;
  private volatile long nextAttempt;
  private volatile long bindingSince, helperSince;
  private volatile long attemptGeneration;
  private volatile boolean checkingStartup;
  private volatile IShizukuShell remote;
  private volatile String error;
  private final Shizuku.OnBinderReceivedListener received = () -> { nextAttempt = 0; poll(); };
  private final Shizuku.OnBinderDeadListener dead = () -> disconnect(false);
  private final Shizuku.OnRequestPermissionResultListener permission = (code, result) -> {
    if (code != REQUEST) return;
    if (result == PackageManager.PERMISSION_GRANTED) { error = null; nextAttempt = 0; poll(); }
    else { error = "Shizuku 授权未通过，仍可使用 ADB 授权"; disconnect(true); }
  };
  private final ServiceConnection connection = new ServiceConnection() {
    @Override public void onServiceConnected(ComponentName name, IBinder binder) {
      if (closed || !authorized() || service.rootEnabled() || service.rootModeChanging()) {
        disconnect(true); return;
      }
      IShizukuShell connected = IShizukuShell.Stub.asInterface(binder);
      remote = connected;
      helperSince = SystemClock.elapsedRealtime();
      service.runtimeLogs.add("Shizuku 用户服务已连接 (UID " + uid() + ")");
      service.jobs.execute(() -> {
        try {
          if (remote != connected || !authorized() || closed) return;
          JSONObject bootstrap = service.shell.shizukuBootstrap();
          connected.start(bootstrap.getInt("port"), bootstrap.getString("secret"));
          if (remote == connected) error = null;
        } catch (Exception e) {
          main.post(() -> {
            if (remote != connected) return;
            error = "Shizuku 通道启动失败：" + e.getMessage();
            service.runtimeLogs.add(error);
            disconnect(true);
          });
        }
      });
    }
    @Override public void onServiceDisconnected(ComponentName name) { disconnect(false); }
  };

  ShizukuAuthorization(LinkService service) {
    this.service = service;
    args = new Shizuku.UserServiceArgs(new ComponentName(service, ShizukuShellService.class))
        .daemon(false).processNameSuffix("shizuku_shell").version(BuildConfig.VERSION_CODE * 100 + 2);
    Shizuku.addBinderDeadListener(dead);
    Shizuku.addRequestPermissionResultListener(permission);
    Shizuku.addBinderReceivedListenerSticky(received);
  }

  boolean enabled() { return service.getSharedPreferences("identity", 0).getBoolean("shizuku_enabled", false); }

  boolean authorized() {
    if (closed || !enabled()) return false;
    try {
      return Shizuku.pingBinder() && !Shizuku.isPreV11()
          && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
          && ShellIdentity.shizukuUid(Shizuku.getUid());
    } catch (RuntimeException e) { return false; }
  }

  int uid() {
    try { return authorized() ? Shizuku.getUid() : -1; }
    catch (RuntimeException e) { return -1; }
  }

  String label() {
    if (!enabled()) return "未启用";
    if (service.shell.shizukuReady()) return "已就绪";
    if (error != null) return "启动失败";
    if (!authorized()) return "未授权";
    return remote == null ? "连接中" : "启动中";
  }

  String status() {
    if (!enabled()) return "未启用";
    if (error != null) return error;
    try {
      if (!Shizuku.pingBinder()) return "Shizuku 未启动，自动使用可用的 ADB 通道";
      if (Shizuku.isPreV11()) return "请更新 Shizuku";
      if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return "等待 Shizuku 授权";
      if (!ShellIdentity.shizukuUid(Shizuku.getUid())) return "Shizuku 运行身份不受支持";
    } catch (RuntimeException e) { return "Shizuku 暂不可用，可使用 ADB 授权"; }
    return service.shell.shizukuReady() ? "已就绪，可与 ADB 自动互补" : "授权已保存，正在恢复通道";
  }

  void request() {
    service.getSharedPreferences("identity", 0).edit().putBoolean("shizuku_enabled", true)
        .putBoolean("allow_input", true).apply();
    error = null;
    try {
      if (!Shizuku.pingBinder()) { service.status = status(); return; }
      if (Shizuku.isPreV11()) { service.status = status(); return; }
      if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
        if (Shizuku.shouldShowRequestPermissionRationale())
          error = "请在 Shizuku 的已授权应用中允许 LanLink";
        else Shizuku.requestPermission(REQUEST);
      } else { nextAttempt = 0; poll(); }
    } catch (RuntimeException e) { error = "Shizuku 授权失败：" + e.getMessage(); }
    service.status = status();
  }

  void disable() {
    service.getSharedPreferences("identity", 0).edit().putBoolean("shizuku_enabled", false).apply();
    disconnect(true);
    service.onShizukuDisabled();
    error = null;
  }

  void poll() {
    if (closed) return;
    if (!authorized() || service.rootEnabled() || service.rootModeChanging()) {
      if (binding) main.post(() -> disconnect(true));
      return;
    }
    if (service.shell.shizukuReady()) return;
    long now = SystemClock.elapsedRealtime();
    if (binding) {
      IShizukuShell connected = remote;
      if (!ShizukuStartupPolicy.timedOut(now, connected == null ? bindingSince : helperSince, connected != null)) {
        if (connected != null) checkStartup(connected);
        return;
      }
      long generation = attemptGeneration;
      main.post(() -> {
        if (generation != attemptGeneration || !binding || service.shell.shizukuReady()) return;
        error = remote == null ? "Shizuku 已授权，但用户服务启动超时，请重启 Shizuku 后重试或使用 ADB 授权。"
            : "Shizuku 已授权，但控制通道连接超时，可使用 ADB 授权。";
        service.runtimeLogs.add(error);
        disconnect(true);
        nextAttempt = SystemClock.elapsedRealtime() + ShizukuStartupPolicy.RETRY_DELAY;
      });
      return;
    }
    if (now < nextAttempt) return;
    nextAttempt = now + ShizukuStartupPolicy.RETRY_DELAY;
    main.post(() -> {
      if (!authorized() || closed || service.rootEnabled() || service.rootModeChanging()) return;
      if (binding) return;
      try {
        bindingSince = SystemClock.elapsedRealtime();
        attemptGeneration++;
        binding = true;
        Shizuku.bindUserService(args, connection);
      } catch (RuntimeException e) {
        binding = false;
        error = "Shizuku 连接失败，可使用 ADB 授权";
        service.runtimeLogs.add(error + " (" + e.getClass().getSimpleName() + ")");
      }
    });
  }

  private void checkStartup(IShizukuShell connected) {
    if (checkingStartup) return;
    checkingStartup = true;
    service.jobs.execute(() -> {
      try {
        String failure = connected.startupError();
        if (failure != null) main.post(() -> {
          if (remote != connected) return;
          error = "Shizuku 控制助手启动失败：" + failure;
          service.runtimeLogs.add(error);
          disconnect(true);
          nextAttempt = SystemClock.elapsedRealtime() + ShizukuStartupPolicy.RETRY_DELAY;
        });
      } catch (Exception ignored) {
        // Binder death or the bounded startup timeout handles a lost service.
      } finally { checkingStartup = false; }
    });
  }

  private void disconnect(boolean remove) {
    attemptGeneration++;
    remote = null;
    service.shell.revokeShizuku();
    if (binding) {
      binding = false;
      try { Shizuku.unbindUserService(args, connection, remove); }
      catch (RuntimeException ignored) {}
    }
  }

  @Override public void close() {
    closed = true;
    Shizuku.removeBinderReceivedListener(received);
    Shizuku.removeBinderDeadListener(dead);
    Shizuku.removeRequestPermissionResultListener(permission);
    disconnect(true);
  }
}
