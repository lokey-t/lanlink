package com.lanlink.app;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Real SRP pairing and authenticated connection, tunneled through emulator loopback. */
final class PairingFlowProbe {
  static Object field(Object value, String name) throws Exception {
    Field f = value.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return f.get(value);
  }

  static void check(boolean value, String label) {
    if (!value) throw new AssertionError(label);
  }

  static View find(View v, String label) {
    if (v instanceof TextView && label.contentEquals(((TextView) v).getText())) return v;
    if (v instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
        View r = find(((ViewGroup) v).getChildAt(i), label);
        if (r != null) return r;
      }
    return null;
  }

  static void pipe(Socket from, Socket to) {
    new Thread(
            () -> {
              try {
                from.getInputStream().transferTo(to.getOutputStream());
              } catch (Exception ignored) {
              } finally {
                try {
                  from.close();
                  to.close();
                } catch (Exception ignored) {
                }
              }
            },
            "pair-test-pipe")
        .start();
  }

  static void proxy(ServerSocket server, int target, Set<Socket> sockets) {
    new Thread(
            () -> {
              while (!server.isClosed())
                try {
                  Socket local = server.accept(), remote = new Socket("10.0.2.2", target);
                  sockets.add(local);
                  sockets.add(remote);
                  pipe(local, remote);
                  pipe(remote, local);
                } catch (Exception ignored) {
                }
            },
            "pair-test-proxy")
        .start();
  }

  static String run(Instrumentation test, Activity activity, LinkService service, Bundle args)
      throws Exception {
    int peerPort = Integer.parseInt(args.getString("pairFlowPort")),
        pairPort = Integer.parseInt(args.getString("pairFlowInvitePort"));
    String id = args.getString("pairFlowId"),
        code = args.getString("pairFlowCode"),
        peerId = args.getString("pairFlowPeer");
    check(id.matches("[a-f0-9]{32}") && code.matches("[0-9]{8}"), "valid test invite");
    long until = System.currentTimeMillis() + 10000;
    while (service.pairing == null && System.currentTimeMillis() < until) Thread.sleep(30);
    service.join(
        Wire.token(), null); // Dedicated disposable emulator identity, never a personal phone.
    Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    try (ServerSocket peerProxy = new ServerSocket(peerPort);
        ServerSocket inviteProxy = new ServerSocket(0);
        DatagramSocket udp = new DatagramSocket(null)) {
      proxy(peerProxy, peerPort, sockets);
      proxy(inviteProxy, pairPort, sockets);
      udp.setReuseAddress(true);
      udp.bind(new InetSocketAddress("127.0.0.1", Pairing.DISCOVERY_PORT));
      new Thread(
              () -> {
                while (!udp.isClosed())
                  try {
                    DatagramPacket packet = new DatagramPacket(new byte[512], 512);
                    udp.receive(packet);
                    JSONObject query =
                        new JSONObject(
                            new String(
                                packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8));
                    byte[] response =
                        Wire.obj(
                                "kind",
                                "lanlink-pair-offer-v2",
                                "nonce",
                                query.getString("nonce"),
                                "id",
                                id,
                                "port",
                                inviteProxy.getLocalPort())
                            .toString()
                            .getBytes(StandardCharsets.UTF_8);
                    udp.send(
                        new DatagramPacket(
                            response, response.length, packet.getAddress(), packet.getPort()));
                  } catch (Exception ignored) {
                  }
              },
              "pair-test-discovery")
          .start();
      test.runOnMainSync(
          () ->
              ((MainActivity) activity)
                  .onActivityResult(
                      32,
                      Activity.RESULT_OK,
                      new Intent()
                          .putExtra(
                              "pairingLink", "lanlink://pair?v=2&code=" + code + "&id=" + id)));
      until = System.currentTimeMillis() + 15000;
      while ((!service.peers.containsKey(peerId)
              || field(activity, "pairDialog") != null)
          && System.currentTimeMillis() < until) Thread.sleep(50);
      check(
          service.peers.containsKey(peerId),
          "QR automatically pairs and establishes encrypted transport");
      check(
          field(activity, "pairDialog") == null && "devices".equals(field(activity, "page")),
          "connected UI returns home");
      check(field(activity,"consentDialog")==null,"pairing alone never asks control consent");
      check(service.consent.allowed(peerId,"input"),"paired group members are allowed by default");
      service.revokeConsent(peerId);
      service.consent.request(peerId,"配对测试电脑");
      java.lang.reflect.Method update=MainActivity.class.getDeclaredMethod("update");update.setAccessible(true);
      test.runOnMainSync(()->{try{update.invoke(activity);}catch(Exception e){throw new RuntimeException(e);}});
      Dialog consent = (Dialog) field(activity, "consentDialog");
      check(
          consent != null && consent.isShowing(),
          "explicit request to restore revoked access is visible before control");
      check(field(service, "owner") == null, "connecting never acquires input");
      test.waitForIdleSync();
      Thread.sleep(700);
      check(
          consent.getWindow().getDecorView().hasWindowFocus(),
          "consent is in front after pairing page closes");
      File folder = new File(activity.getExternalFilesDir(null), "ui-check");
      folder.mkdirs();
      android.graphics.Bitmap shot = test.getUiAutomation().takeScreenshot();
      try (FileOutputStream out =
          new FileOutputStream(new File(folder, "first-connected-consent-0314.png"))) {
        shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
      } finally {
        shot.recycle();
      }
      test.runOnMainSync(() -> find(consent.getWindow().getDecorView(), "同意并记住").performClick());
      check(
          service.consent.allowed(peerId, "input"),
          "explicit restoration decision persists");
      service.offerInitialConsent(service.peers.get(peerId));
      check(service.consent.peek() == null, "saved consent never re-prompts");
      return "PASS: scanned invitation auto-submits, real SRP exchange + encrypted connection,"
          + " return home, group access allowed by default, explicit revoked-access restoration and remembered decision.";
    } finally {
      for (Socket socket : sockets)
        try {
          socket.close();
        } catch (Exception ignored) {
        }
    }
  }
}
