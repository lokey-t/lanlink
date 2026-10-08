package com.lanlink.app;

import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONObject;

/**
 * Drives the real pairing code path (Pairing.join + LinkService.joinGroup) against a live desktop
 * peer reachable over the emulator's LAN path, then proves the encrypted link with a real RPC.
 */
final class PairJoinProbe {
  static String run(Instrumentation test, LinkService service, Bundle args) throws Exception {
    String code = args.getString("pairJoinCode"), id = args.getString("pairJoinId");
    if (code == null || !code.matches("[0-9]{8}") || id == null || !id.matches("[a-f0-9]{32}"))
      throw new AssertionError("invalid invite: code=" + code + " id=" + id);
    long until = System.currentTimeMillis() + 10000;
    while (service.pairing == null && System.currentTimeMillis() < until) Thread.sleep(50);
    if (service.pairing == null) throw new AssertionError("pairing service not ready");

    boolean dissolve = service.groupEstablished();
    String expectedGroup = service.group.data.optString("id");
    // The emulator is behind NAT, so its UDP broadcast cannot reach the Mac host.
    // A supplied host/port keeps the same authenticated SRP exchange and group-join
    // path while using the emulator's documented 10.0.2.2 host bridge.
    String host = args.getString("pairJoinHost");
    int port;
    try { port = Integer.parseInt(args.getString("pairJoinPort", "0")); }
    catch (NumberFormatException ignored) { port = 0; }
    JSONObject result = host != null && port > 0
        ? Pairing.exchange(host, port, id, code, service.group.own())
        : service.pairing.join(code, id);
    service.joinGroup(result, dissolve, expectedGroup);

    long deadline = System.currentTimeMillis() + 25000;
    while (service.peers.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(100);

    StringBuilder out = new StringBuilder("PAIR_JOIN\n");
    out.append("invite.id=").append(id).append('\n');
    out.append("dissolved.current.group=").append(dissolve).append('\n');
    out.append("peer.endpoint=").append(result.optString("host")).append(':').append(result.optInt("port")).append('\n');
    out.append("peers=").append(service.peers.size()).append('\n');
    Wire first = null;
    for (Wire p : service.peers.values()) {
      if (first == null) first = p;
      out.append("peer: name=")
          .append(p.info.optString("name"))
          .append(" platform=")
          .append(p.info.optString("platform"))
          .append(" address=")
          .append(p.socket.getInetAddress().getHostAddress())
          .append(':')
          .append(p.socket.getPort())
          .append('\n');
    }
    if (first == null) throw new AssertionError("no encrypted peer after pairing");
    JSONObject report = first.request("permission-status", Wire.obj());
    out.append("rpc.permission-status=ok fields=").append(report.length()).append('\n');
    // Hold the link open so the desktop side can finish its native QUIC handshake and exercise a
    // product request over QUIC (desktop requests route through QUIC whenever quicConn exists).
    long quicDeadline = System.currentTimeMillis() + 20000;
    boolean quicUp = false;
    while (System.currentTimeMillis() < quicDeadline) {
      for (Wire p : service.peers.values()) if (p.quicConn != 0) quicUp = true;
      if (quicUp) break;
      Thread.sleep(200);
    }
    out.append("android.quicConn=").append(quicUp).append('\n');
    Thread.sleep(6000);
    out.append("PASS: real 8-digit pairing -> encrypted group link -> authenticated RPC\n");
    return out.toString();
  }
}
