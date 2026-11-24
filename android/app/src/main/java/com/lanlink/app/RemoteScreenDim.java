package com.lanlink.app;

import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/** Runs in the ADB/Root capture helper; only the physical panel is dimmed. */
final class RemoteScreenDim implements AutoCloseable {
  private static final float FLOOR = 0.001f;
  private static final long LEASE_MS = 3000, DELAY_MS = 1500, FADE_MS = 3000;
  private long session, until, started;
  private long touchedSession;
  private boolean touched, overridden, unsupported;
  private float original, last;
  private Object display;
  private Method temporary;
  private Process touchMonitor;
  private long nextProbe;

  synchronized void keep(long id) {
    if (id <= 0) { end(); return; }
    long now = SystemClock.elapsedRealtime();
    if (session != id) {
      end();
      session = id;
      touched = touchedSession == id;
    }
    until = now + LEASE_MS;
  }

  void tick() {
    final long expected;
    synchronized (this) {
      if (session == 0) return;
      long now = SystemClock.elapsedRealtime();
      if (now >= until) { end(); return; }
      if (touched || unsupported) return;
      if (touchMonitor != null) {
        if (!touchMonitor.isAlive()) { end(); unsupported = true; return; }
        if (now - started < DELAY_MS) return;
        float progress = Math.min(1, (now - started - DELAY_MS) / (float) FADE_MS);
        float value = FLOOR + (original - FLOOR) * (1 - progress) * (1 - progress);
        if (!overridden || Math.abs(value - last) > 0.002f || value == FLOOR && last != FLOOR) {
          try { set(value); last = value; overridden = true; }
          catch (Exception error) { end(); unsupported = true; }
        }
        return;
      }
      if (now < nextProbe) return;
      nextProbe = now + LEASE_MS;
      expected = session;
    }
    try {
      String path = touchscreen();
      synchronized (this) {
        if (session != expected || SystemClock.elapsedRealtime() >= until) return;
        if (path == null) { unsupported = true; return; }
        prepareDisplay();
        if (!Float.isFinite(original) || original <= FLOOR) return;
        Process monitor = new ProcessBuilder("/system/bin/getevent", "-l", path)
            .redirectError(new File("/dev/null")).start();
        touchMonitor = monitor;
        started = SystemClock.elapsedRealtime();
        Thread worker = new Thread(() -> listen(monitor), "lanlink-local-touch");
        worker.setDaemon(true);
        worker.start();
      }
    } catch (Exception error) {
      synchronized (this) { if (session == expected) { end(); unsupported = true; } }
    }
  }

  private void listen(Process monitor) {
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(monitor.getInputStream()))) {
      for (String event; (event = reader.readLine()) != null; ) {
        String text = event.trim();
        if (text.contains("ABS_MT_TRACKING_ID") && !text.endsWith("ffffffff")
            || text.contains("BTN_TOUCH") && text.endsWith("DOWN")) {
          synchronized (this) {
            if (touchMonitor == monitor) {
              touched = true;
              touchedSession = session;
              restore();
            }
          }
          return;
        }
      }
    } catch (Exception ignored) {
    } finally { monitor.destroy(); }
  }

  private static String touchscreen() throws Exception {
    Process probe = new ProcessBuilder("/system/bin/getevent", "-lp")
        .redirectError(new File("/dev/null")).start();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(probe.getInputStream()))) {
      String path = null;
      boolean direct = false, touch = false;
      for (String line; (line = reader.readLine()) != null; ) {
        if (line.startsWith("add device ")) {
          if (path != null && direct && touch) return path;
          String candidate = line.substring(line.lastIndexOf(' ') + 1);
          path = candidate.matches("/dev/input/event[0-9]+") ? candidate : null;
          direct = touch = false;
        }
        direct |= line.contains("INPUT_PROP_DIRECT");
        touch |= line.contains("ABS_MT_TRACKING_ID") || line.contains("BTN_TOUCH");
      }
      return direct && touch ? path : null;
    } finally { probe.destroy(); probe.waitFor(200, TimeUnit.MILLISECONDS); }
  }

  private void prepareDisplay() throws Exception {
    Class<?> global = Class.forName("android.hardware.display.DisplayManagerGlobal");
    display = global.getMethod("getInstance").invoke(null);
    try { temporary = global.getMethod("setTemporaryBrightness", int.class, float.class); }
    catch (NoSuchMethodException oldAndroid) { temporary = global.getMethod("setTemporaryBrightness", float.class); }
    try {
      Object info = global.getMethod("getBrightnessInfo", int.class).invoke(display, 0);
      original = info.getClass().getField("brightness").getFloat(info);
    } catch (ReflectiveOperationException error) {
      original = ((Number) global.getMethod("getBrightness", int.class).invoke(display, 0)).floatValue();
    }
  }

  private void set(float level) throws Exception {
    if (temporary.getParameterCount() == 2) temporary.invoke(display, 0, level);
    else temporary.invoke(display, level);
  }

  private void restore() {
    if (!overridden) return;
    try { set(Float.NaN); overridden = false; }
    catch (Exception ignored) {
      try { set(original); overridden = false; } catch (Exception failed) {}
    }
  }

  private synchronized void end() {
    restore();
    if (touchMonitor != null) touchMonitor.destroy();
    touchMonitor = null;
    session = 0;
    nextProbe = 0;
  }

  public void close() { end(); }
}
