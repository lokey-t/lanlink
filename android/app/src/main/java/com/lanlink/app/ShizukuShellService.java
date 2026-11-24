package com.lanlink.app;

import android.os.Process;

/** Instantiated by Shizuku; never exported as an Android service. */
public final class ShizukuShellService extends IShizukuShell.Stub {
  private Thread helper;
  private volatile String error;

  public ShizukuShellService() {}

  @Override public synchronized void start(int port, String secret) {
    if (!ShellIdentity.shizukuUid(Process.myUid())) throw new SecurityException("Shizuku 运行身份不受支持");
    if (port < 1 || port > 65535 || Wire.parseToken(secret).length != 32)
      throw new IllegalArgumentException("Invalid helper binding");
    if (helper != null) return;
    // A UserService already has shell identity and unrestricted access to hidden
    // APIs. Run the helper here, without another app_process and discarded stderr.
    helper = new Thread(() -> {
      try { ShellAgent.main(new String[] {Integer.toString(port), secret}); }
      catch (Throwable failure) {
        // Never publish command-line arguments or the authenticated channel secret.
        error = failure.getClass().getSimpleName();
        android.util.Log.e("LanLinkShizuku", "Control helper startup failed", failure);
      }
      if (error == null) error = "控制助手提前退出";
    }, "lanlink-shizuku-helper");
    helper.start();
  }

  @Override public String startupError() { return error; }
  @Override public void stop() { destroy(); }
  @Override public void destroy() { System.exit(0); }
}
