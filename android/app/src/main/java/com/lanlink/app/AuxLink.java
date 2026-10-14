package com.lanlink.app;

import java.net.*;
import java.util.*;
import org.json.JSONObject;

/** Cable control supplements a live paired LAN session; it grants no permissions. */
final class AuxLink implements AutoCloseable, Wire.Listener {
  static final Set<String> CONTROL = new HashSet<>(Arrays.asList(
      "input", "text", "acquire", "release", "keepControl", "inputReset"));
  final LinkService service;
  final Wire primary;
  final ServerSocket server;
  final String secret = Wire.token();
  volatile Wire wire;
  volatile boolean closed;

  AuxLink(LinkService service, Wire primary, JSONObject identity) throws Exception {
    this.service = service; this.primary = primary;
    server = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"));
    server.setSoTimeout(6000);
    new Thread(() -> {
      try {
        Socket socket = server.accept();
        synchronized (this) {
          if (closed || !valid()) { socket.close(); return; }
          wire = new Wire(socket, identity, Wire.parseToken(secret), this);
          wire.start();
        }
      } catch (Exception ignored) { close(); }
      finally { try { server.close(); } catch (Exception ignored) {} }
    }, "lanlink-usb-control").start();
  }
  boolean valid() {
    return !closed && primary.connected && service.peers.get(primary.id) == primary
        && primary.matchesGroupToken(service.token) && !service.group.excluded(primary.id);
  }
  JSONObject info() { return Wire.obj("port", server.getLocalPort(), "secret", secret); }
  public void ready(Wire peer) { if (!valid() || !primary.id.equals(peer.id)) close(); }
  public void message(Wire peer, JSONObject message) {
    if (!valid() || !primary.id.equals(peer.id)) { close(); return; }
    if (!message.optString("type").equals("request")) return;
    String method = message.optString("method");
    try {
      if (method.equals("linkPing") || method.equals("linkUse")) {
        if (method.equals("linkUse")) synchronized (service) {
          synchronized (this) {
            if (!valid() || wire != peer) throw new java.io.IOException("辅助通道已结束");
            primary.useFastPath(peer);
          }
        }
        peer.send(Wire.obj("type", "response", "id", message.optString("id"), "result", Wire.obj()));
      } else if (CONTROL.contains(method)) service.message(primary, message, peer);
      else peer.send(Wire.obj("type", "response", "id", message.optString("id"), "error", "辅助通道不支持此操作"));
    } catch (Exception ignored) { close(); }
  }
  public void closed(Wire peer) { close(); }
  public synchronized void close() {
    if (closed) return;
    closed = true;
    if (primary.clearFastPath(wire)) service.auxInputLost(primary);
    if (wire != null) wire.close();
    try { server.close(); } catch (Exception ignored) {}
    service.auxLinks.remove(this);
  }
}
