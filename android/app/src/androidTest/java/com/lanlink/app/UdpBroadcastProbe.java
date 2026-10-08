package com.lanlink.app;

import android.app.Instrumentation;
import android.os.Bundle;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Diagnostic only: does not touch product state. Reports where a guest datagram can actually go,
 * so the emulator NAT behaviour for unicast vs broadcast is measured instead of assumed.
 */
final class UdpBroadcastProbe {
  static String run(Instrumentation test, Bundle args) throws Exception {
    int port = Integer.parseInt(args.getString("udpProbePort"));
    Set<String> targets = new LinkedHashSet<>();
    targets.add("10.0.2.2");
    targets.add("255.255.255.255");
    Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
    while (interfaces.hasMoreElements())
      for (InterfaceAddress a : interfaces.nextElement().getInterfaceAddresses())
        if (a.getBroadcast() != null) targets.add(a.getBroadcast().getHostAddress());

    List<String> received = new ArrayList<>();
    StringBuilder report = new StringBuilder();
    try (DatagramSocket rx = new DatagramSocket(null)) {
      rx.setReuseAddress(true);
      rx.bind(new InetSocketAddress(port));
      rx.setSoTimeout(400);
      Thread reader =
          new Thread(
              () -> {
                long until = System.currentTimeMillis() + 3000;
                byte[] buffer = new byte[256];
                while (System.currentTimeMillis() < until)
                  try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    rx.receive(packet);
                    received.add(
                        new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8)
                            + " <- "
                            + packet.getAddress().getHostAddress());
                  } catch (Exception ignored) {
                  }
              },
              "udp-probe-reader");
      reader.start();
      try (DatagramSocket tx = new DatagramSocket()) {
        tx.setBroadcast(true);
        for (String target : targets) {
          byte[] body = ("PROBE:" + target).getBytes(StandardCharsets.UTF_8);
          try {
            tx.send(new DatagramPacket(body, body.length, InetAddress.getByName(target), port));
            report.append("sent -> ").append(target).append(':').append(port).append('\n');
          } catch (Exception error) {
            report.append("send FAILED -> ").append(target).append(": ").append(error).append('\n');
          }
        }
      }
      reader.join();
    }
    report.append("guest-local loopback received ").append(received.size()).append(": ").append(received).append('\n');
    return "UDP_PROBE\n" + report;
  }
}
