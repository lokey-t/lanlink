package com.lanlink.app;

import android.app.Instrumentation;
import android.os.SystemClock;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Tests the real encrypted input-only control lease without operating an actual remote device. */
final class InputOnlyTouchpadProbe {
  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  private static void await(BooleanSupplier condition, String message) throws Exception {
    long deadline = SystemClock.uptimeMillis() + 4000;
    while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < deadline) Thread.sleep(20);
    check(condition.getAsBoolean(), message);
  }

  private static JSONObject permissions(boolean inputConsent, boolean inputReady) {
    return Wire.obj(
        "permissions",
        Wire.obj(
            "input", Wire.obj("appEnabled", true, "systemGranted", inputReady, "peerAllowed", inputConsent),
            "screen", Wire.obj("appEnabled", false, "systemGranted", false, "peerAllowed", false),
            "clipboard", Wire.obj("appEnabled", true, "systemGranted", true, "peerAllowed", false)));
  }

  private static boolean outgoingIdle(LinkService service) {
    try {
      Field pending = LinkService.class.getDeclaredField("outgoingPending");
      Field releasing = LinkService.class.getDeclaredField("outgoingReleasing");
      pending.setAccessible(true);
      releasing.setAccessible(true);
      synchronized (service) {
        return pending.get(service) == null && !releasing.getBoolean(service);
      }
    } catch (ReflectiveOperationException error) {
      throw new AssertionError(error);
    }
  }

  private static final class Fixture implements AutoCloseable, Wire.Listener {
    final LinkService service;
    final String id = UUID.randomUUID().toString();
    final JSONObject report, capabilities;
    final List<JSONObject> requests = new CopyOnWriteArrayList<>();
    final AtomicInteger releases = new AtomicInteger();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final AtomicReference<String> pendingAcquire = new AtomicReference<>();
    final CountDownLatch acquired = new CountDownLatch(1);
    final boolean holdAcquire;
    Wire local, remote;

    Fixture(LinkService service, boolean inputConsent, boolean inputReady, boolean holdAcquire,
        boolean relativeInput) throws Exception {
      this.service = service;
      this.report = permissions(inputConsent, inputReady);
      this.holdAcquire = holdAcquire;
      capabilities = Wire.obj("input", true, "relativeInput", relativeInput, "controlPrepare", true);
      try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
        Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
        Socket accepted = server.accept();
        byte[] key = new byte[32];
        local = new Wire(client,
            Wire.obj("id", service.deviceId, "name", "input-only controller fixture", "capabilities", Wire.obj()),
            key, service);
        remote = new Wire(accepted,
            Wire.obj("id", id, "name", "input-only remote fixture", "capabilities", capabilities),
            key, this);
        local.outgoing = true;
        local.start();
        remote.start();
        await(() -> service.peers.get(id) == local && local.info.has("capabilities"),
            "input-only fixture authenticated with capabilities");
      } catch (Throwable error) {
        if (local != null) local.close();
        if (remote != null) remote.close();
        throw error;
      }
    }

    public void ready(Wire peer) {}

    public void closed(Wire peer) {}

    public void message(Wire peer, JSONObject message) {
      try {
        if ("ping".equals(message.optString("type"))) peer.send(Wire.obj("type", "pong"));
        if (!"request".equals(message.optString("type"))) return;
        requests.add(message);
        String method = message.optString("method");
        if ("acquire".equals(method)) {
          pendingAcquire.set(message.getString("id"));
          acquired.countDown();
          if (holdAcquire) return;
        }
        respond(message.getString("id"),
            "permission-status".equals(method) ? report : "acquire".equals(method) ? capabilities : Wire.obj());
        if ("release".equals(method)) releases.incrementAndGet();
      } catch (Throwable error) {
        failure.compareAndSet(null, error);
      }
    }

    void respond(String requestId, JSONObject result) throws Exception {
      remote.send(Wire.obj("type", "response", "id", requestId, "result", result));
    }

    int count(String method) {
      int count = 0;
      for (JSONObject request : requests) if (method.equals(request.optString("method"))) count++;
      return count;
    }

    void assertInputOnly() throws Exception {
      check(failure.get() == null, "fixture transport remains healthy: " + failure.get());
      for (JSONObject request : requests) {
        String method = request.optString("method");
        check(!method.startsWith("video") && !method.startsWith("screen"),
            "touchpad must not request a screen stream: " + method);
        JSONObject args = request.optJSONObject("args");
        if ("prepare".equals(method) || "acquire".equals(method)) {
          check(args != null && args.has("screen") && !args.getBoolean("screen"),
              method + " explicitly uses screen=false");
          check(args.optBoolean("newSession"), method + " preserves explicit newSession");
        }
        if ("permission-prepare".equals(method) || "permission-request".equals(method)) {
          JSONArray requested = args == null ? null : args.optJSONArray("permissions");
          check(requested != null && requested.length() == 1 && "input".equals(requested.getString(0)),
              method + " only asks for input permission");
        }
      }
    }

    public void close() throws Exception {
      try {
        if (id.equals(service.active) || !outgoingIdle(service)) {
          service.releaseControl();
          String pending = pendingAcquire.get();
          if (holdAcquire && pending != null) respond(pending, capabilities);
          await(() -> outgoingIdle(service), "fixture releases pending control on cleanup");
        }
      } finally {
        if (local != null) local.close();
        if (remote != null) remote.close();
        service.peerAccess.remove(id);
        service.accessRequested.remove(id);
      }
    }
  }

  static String run(Instrumentation test, LinkService service) throws Exception {
    test.waitForIdleSync();
    check(service.active == null && outgoingIdle(service), "probe requires an idle control session");
    String previousEndedPeer = service.endedPeer;
    long previousEndedAt = service.endedAt;
    try {
      try (Fixture remote = new Fixture(service, true, true, false, true)) {
        service.controlTouchpad(remote.id, true);
        check(remote.id.equals(service.active), "input permission alone activates native touchpad");
        check(remote.count("prepare") == 1 && remote.count("acquire") == 1,
            "input-only session prepares and acquires exactly once");
        check(remote.count("permission-prepare") == 0 && remote.count("permission-request") == 0,
            "missing screen authorization never prompts for any permission");
        remote.assertInputOnly();
        service.releaseControl();
        await(() -> remote.releases.get() == 1 && outgoingIdle(service),
            "local exit releases the native input lease");
        check(service.active == null, "local exit clears active touchpad");
        remote.assertInputOnly();
      }

      try (Fixture remote = new Fixture(service, false, true, false, true)) {
        try {
          service.controlTouchpad(remote.id, true);
          throw new AssertionError("touchpad accepted missing peer input consent");
        } catch (IOException denied) {
          check(denied.getMessage().startsWith("CONSENT_"), "input consent is still enforced");
        }
        check(service.active == null && remote.count("prepare") == 0 && remote.count("acquire") == 0,
            "denied input consent never acquires native input");
        check(remote.count("permission-request") == 1, "missing input consent requests only input");
        remote.assertInputOnly();
      }

      try (Fixture remote = new Fixture(service, true, false, false, true)) {
        try {
          service.controlTouchpad(remote.id, true);
          throw new AssertionError("touchpad accepted missing system input permission");
        } catch (IOException denied) {
          check(denied.getMessage().startsWith("PERMISSION_REQUIRED:"), "system input readiness is still enforced");
        }
        check(service.active == null && remote.count("prepare") == 0 && remote.count("acquire") == 0,
            "unready input never acquires native input");
        check(remote.count("permission-prepare") == 1, "system readiness prepares only input");
        remote.assertInputOnly();
      }

      try (Fixture remote = new Fixture(service, true, true, true, true)) {
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread control = new Thread(() -> {
          try { service.controlTouchpad(remote.id, true); }
          catch (Throwable error) { outcome.set(error); }
        }, "input-only-cancel-probe");
        control.start();
        check(remote.acquired.await(4, TimeUnit.SECONDS), "input-only acquire can be held pending");
        service.releaseControl();
        remote.respond(remote.pendingAcquire.get(), remote.capabilities);
        control.join(4000);
        check(!control.isAlive() && outcome.get() instanceof IOException
                && outcome.get().getMessage().startsWith("CONTROL_ENDED:"),
            "cancelling pending touchpad prevents late activation");
        await(() -> remote.releases.get() == 1 && outgoingIdle(service),
            "late input-only acquire is released before unlocking");
        check(service.active == null, "cancelled touchpad is never active");
        remote.assertInputOnly();
      }

      try (Fixture remote = new Fixture(service, true, true, false, true)) {
        service.controlTouchpad(remote.id, true);
        long endedSince = System.currentTimeMillis();
        remote.remote.send(Wire.obj("type", "control-ended"));
        await(() -> service.active == null && remote.releases.get() == 1 && outgoingIdle(service),
            "remote session termination releases input-only control");
        check(remote.id.equals(service.endedPeer) && service.endedAt >= endedSince,
            "remote termination remains visible to touchpad UI");
        remote.assertInputOnly();
      }

      try (Fixture remote = new Fixture(service, true, true, true, true)) {
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread control = new Thread(() -> {
          try { service.controlTouchpad(remote.id, true); }
          catch (Throwable error) { outcome.set(error); }
        }, "input-only-remote-end-probe");
        control.start();
        check(remote.acquired.await(4, TimeUnit.SECONDS), "remote-ending fixture holds acquire pending");
        remote.remote.send(Wire.obj("type", "control-ended"));
        await(() -> remote.id.equals(service.endedPeer), "remote termination reaches pending controller");
        remote.respond(remote.pendingAcquire.get(), remote.capabilities);
        control.join(4000);
        check(!control.isAlive() && outcome.get() instanceof IOException
                && outcome.get().getMessage().startsWith("CONTROL_ENDED:"),
            "remote termination cancels a pending touchpad acquire");
        await(() -> remote.releases.get() == 1 && outgoingIdle(service),
            "remote-ended late acquire is released before unlocking");
        check(service.active == null, "remote-ended pending touchpad is never active");
        remote.assertInputOnly();
      }

      try (Fixture remote = new Fixture(service, true, true, false, false)) {
        try {
          service.controlTouchpad(remote.id, true);
          throw new AssertionError("touchpad accepted unsupported relative input");
        } catch (IOException unsupported) {
          check(unsupported.getMessage().contains("不支持原生触控板"),
              "devices without native relative input are rejected");
        }
        check(remote.count("prepare") == 0 && remote.count("acquire") == 0,
            "unsupported device receives no control lease request");
        remote.assertInputOnly();
      }
      return "PASS: encrypted input-only touchpad acquire/release, no screen permission or stream, "
          + "input consent/readiness, cancellation, active/pending remote termination and native-capability gate";
    } finally {
      service.endedPeer = previousEndedPeer;
      service.endedAt = previousEndedAt;
    }
  }
}
