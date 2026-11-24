package com.lanlink.app;

import java.io.IOException;
import org.json.JSONObject;

/** Keeps ADB and Shizuku independently authenticated and selects a usable transport. */
public final class ShellChannel implements AutoCloseable {
  private final ShellTransport adb, shizuku;
  private ShellTransport active;
  private ShellTransport pointerTransport;
  private boolean rootWanted;
  public volatile String lastInputError;
  public volatile boolean inputVerified;

  public ShellChannel(LinkService service) {
    adb = new ShellTransport(service, false);
    shizuku = new ShellTransport(service, true);
    active = adb;
  }

  private ShellTransport select() {
    if (rootWanted) active = adb;
    else if (!active.ready() || active.lastInputError != null) {
      ShellTransport other = active == adb ? shizuku : adb;
      if (other.ready() && other.lastInputError == null) active = other;
    }
    lastInputError = active.lastInputError;
    inputVerified = active.inputVerified;
    return active;
  }

  public synchronized boolean ready() { return select().ready(); }
  public synchronized boolean rootReady() { return adb.rootReady(); }
  boolean adbReady() { return adb.ready() && !adb.rootReady(); }
  boolean shizukuReady() { return shizuku.ready(); }
  synchronized boolean adbAudioReady() { return select().adbAudioReady(); }
  public synchronized boolean recovering() { return select().recovering(); }
  synchronized boolean supportsInputBatch() { return select().supportsInputBatch(); }
  static String motionLine(JSONObject message) { return ShellTransport.motionLine(message); }
  public synchronized String backend() {
    ShellTransport selected = select();
    return !selected.ready() ? "unavailable" : selected == shizuku ? "shizuku" : selected.rootReady() ? "root" : "adb";
  }

  public synchronized JSONObject request(JSONObject message) throws Exception {
    ShellTransport selected = select();
    try {
      if (message != null && message.optString("method").startsWith("input")
          && message.optBoolean("pointer", true) && pointerTransport != null && pointerTransport != selected) {
        JSONObject pointer = selected.request(Wire.obj("method", "activatePointer"));
        if (!pointer.optBoolean("nativePointer"))
          throw new IOException("PERMISSION_REQUIRED: 备用控制通道无法启用系统鼠标，请重新连接控制");
        pointerTransport = selected;
      }
      JSONObject result = selected.request(message);
      if (message != null && message.optString("method").equals("activatePointer")) pointerTransport = selected;
      if (message != null && message.optString("method").equals("release")) pointerTransport = null;
      return result;
    } catch (Exception failure) {
      ShellTransport backup = select();
      if (backup != selected && backup.ready()) {
        // Probing has no input side effects and can finish on the backup now.
        if (message != null && (message.optString("method").equals("probe")
            || message.optString("method").equals("heartbeat"))) return backup.request(message);
        throw new IOException("INPUT_RECOVERING: 已切换至备用控制通道，请重试当前操作", failure);
      }
      throw failure;
    }
    finally { select(); }
    // Select the backup for subsequent requests. Never replay a click, paste or
    // video start whose reply may have been lost after execution.
  }

  public synchronized boolean healthy() {
    try {
      ShellTransport selected = select();
      if (selected.healthy() && selected.lastInputError == null) return true;
      ShellTransport backup = select();
      return backup != selected && backup.healthy() && backup.lastInputError == null;
    } finally { select(); }
  }
  public JSONObject bootstrap() throws IOException { return adb.bootstrap(); }
  JSONObject shizukuBootstrap() throws IOException { return shizuku.bootstrap(); }
  public String command() { return adb.command(); }
  public void root() throws Exception {
    synchronized (this) { rootWanted = true; active = adb; }
    adb.root();
  }
  public synchronized void disableRoot() { adb.disableRoot(); rootWanted = false; select(); }
  public synchronized void revokeAdb() { adb.revokeAdb(); select(); }
  synchronized void revokeShizuku() { shizuku.revokeAdb(); select(); }
  public synchronized void close() { adb.close(); shizuku.close(); }
}
