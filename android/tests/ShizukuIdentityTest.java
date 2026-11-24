package com.lanlink.app;

/** Standalone regression tests for both Shizuku launch identities and slow startup. */
public final class ShizukuIdentityTest {
  private static void check(boolean value, String description) {
    if (!value) throw new AssertionError(description);
  }

  public static void main(String[] args) {
    check(ShellIdentity.accepts(true, false, 0, 0), "Root-started Shizuku works in non-Root mode");
    check(ShellIdentity.accepts(true, false, 2000, 2000), "ADB-started Shizuku works in non-Root mode");
    check(!ShellIdentity.accepts(true, false, 2000, 0), "Shizuku helper must match granted shell identity");
    check(!ShellIdentity.accepts(true, false, 0, 2000), "Shizuku helper must match granted root identity");
    check(!ShellIdentity.accepts(true, false, -1, 0), "revoked Shizuku cannot reconnect as root");
    check(!ShellIdentity.accepts(true, false, -1, 2000), "revoked Shizuku cannot reconnect as shell");
    check(!ShellIdentity.accepts(true, false, -1, -1), "unknown identity never authorizes a helper");
    check(!ShellIdentity.shizukuUid(10000), "ordinary app identity is never privileged");
    check(ShellIdentity.accepts(false, false, -1, 2000), "normal non-Root ADB remains supported");
    check(!ShellIdentity.accepts(false, false, -1, 0), "ADB path cannot claim root in non-Root mode");
    check(ShellIdentity.accepts(false, true, -1, 0), "explicit Root mode retains its original identity check");
    check(!ShizukuStartupPolicy.timedOut(12000, 0, false), "do not cancel a service after 12 seconds");
    check(!ShizukuStartupPolicy.timedOut(30000, 0, false), "allow Shizuku's full 30-second startup window");
    check(ShizukuStartupPolicy.timedOut(35000, 0, false), "service binding has a bounded timeout");
    check(!ShizukuStartupPolicy.timedOut(40000, 30000, true), "helper gets a separate connection window");
    check(ShizukuStartupPolicy.timedOut(45000, 30000, true), "helper connection has a bounded timeout");
    System.out.println("Shizuku root/shell identity and startup regression checks passed");
  }
}
