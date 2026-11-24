package com.lanlink.app;

import java.io.IOException;
import org.json.JSONObject;

/** JVM regression test: compile with ShellChannel.java and android.jar only.
 * The transport doubles simulate disconnects and permission denials without a phone.
 */
public final class ShellChannelTest {
  static void check(boolean value, String description) {
    if (!value) throw new AssertionError(description);
  }
  public static void main(String[] args) throws Exception {
    ShellChannel channel = new ShellChannel(new LinkService());
    ShellTransport adb = ShellTransport.adb, shizuku = ShellTransport.shizuku;
    check(!channel.ready(), "neither authorized transport reports ready");
    adb.connected = true;
    check(channel.backend().equals("adb"), "ADB works without Shizuku");
    shizuku.connected = true;
    check(channel.backend().equals("adb"), "adding backup does not interrupt current input");
    adb.broken = true;
    check(channel.healthy() && channel.backend().equals("shizuku"), "failed heartbeat selects Shizuku");
    channel.request(null);
    check(shizuku.requests == 1, "requests use Shizuku after ADB fails");
    adb.connected = true; adb.broken = false;
    check(channel.backend().equals("shizuku"), "ADB recovery does not flap active Shizuku");
    channel.revokeShizuku();
    check(channel.ready() && channel.backend().equals("adb"), "closing Shizuku preserves ADB");
    shizuku.connected = true;
    channel.revokeAdb();
    check(channel.ready() && channel.backend().equals("shizuku"), "revoking ADB preserves Shizuku");
    adb.connected = true;
    shizuku.lastInputError = "PERMISSION_REQUIRED: denied";
    check(channel.backend().equals("adb") && channel.lastInputError == null, "denied input uses other backend");
    shizuku.lastInputError = null;
    int before = shizuku.requests;
    adb.broken = true;
    try { channel.request(null); throw new AssertionError("transport failure must surface"); }
    catch (IOException expected) { check(expected.getMessage().startsWith("INPUT_RECOVERING:"), "failover reports temporary recovery instead of missing permission"); }
    check(shizuku.requests == before, "uncertain requests are never replayed on backup");
    channel.request(null);
    check(shizuku.requests == before + 1, "next request uses backup");
    channel.root();
    check(channel.rootReady() && channel.backend().equals("root"), "Root mode selects Root transport");
    adb.connected = false;
    check(!channel.ready(), "Root mode never silently falls back to non-root");
    channel.disableRoot();
    check(channel.backend().equals("shizuku"), "leaving Root mode restores available non-root backend");
    channel.close();
    check(!adb.connected && !shizuku.connected, "service shutdown closes both transports");
    System.out.println("ShellChannel fallback and authorization checks passed");
  }
}

// These replace Android service/socket implementations for this standalone JVM test.
class LinkService {}
class Wire { static JSONObject obj(Object... args) { return null; } }
class ShellTransport {
  static ShellTransport adb, shizuku;
  boolean connected, broken, root;
  int requests;
  volatile String lastInputError;
  volatile boolean inputVerified;
  ShellTransport(LinkService service, boolean isShizuku) {
    if (isShizuku) shizuku = this; else adb = this;
  }
  boolean ready() { return connected; }
  boolean rootReady() { return root && connected; }
  boolean adbAudioReady() { return connected && !root; }
  boolean recovering() { return false; }
  boolean supportsInputBatch() { return true; }
  static String motionLine(JSONObject message) { return null; }
  JSONObject request(JSONObject message) throws IOException {
    requests++;
    if (broken) { connected = false; throw new IOException("simulated connection loss"); }
    inputVerified = true;
    return null;
  }
  boolean healthy() { if (broken) connected = false; return connected; }
  JSONObject bootstrap() { return null; }
  String command() { return ""; }
  void root() { root = connected = true; broken = false; }
  void disableRoot() { root = connected = false; }
  void revokeAdb() { connected = false; lastInputError = null; }
  void close() { connected = false; }
}
