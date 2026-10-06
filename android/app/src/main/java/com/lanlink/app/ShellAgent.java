package com.lanlink.app;

import static com.lanlink.app.Wire.obj;

import android.os.SystemClock;
import android.view.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

// Launched only by the user's adb shell or su command, not by a normal app UID.
public final class ShellAgent {
  private static Object manager;
  private static android.net.LocalServerSocket singleton;
  private static Method inject;
  private static final Set<Integer> held = new HashSet<>();
  private static final Map<Integer, Long> keyTimes = new HashMap<>();
  private static final Map<Integer, Integer> repeats = new HashMap<>();
  private static int buttons = 0, meta = 0;
  private static boolean finger;
  private static VirtualHid hid;
  private static long hidRetryAt;
  private static float x = 100, y = 100;
  private static long downTime = 0, lease = 0;
  private static Process videoProcess;

  public static int keyCode(String code) {
    if (code.matches("Key[A-Z]")) return KeyEvent.keyCodeFromString("KEYCODE_" + code.substring(3));
    if (code.matches("Digit[0-9]"))
      return KeyEvent.keyCodeFromString("KEYCODE_" + code.substring(5));
    if (code.matches("Numpad[0-9]"))
      return KeyEvent.keyCodeFromString("KEYCODE_NUMPAD_" + code.substring(6));
    if (code.matches("F([1-9]|1[0-2])")) return KeyEvent.keyCodeFromString("KEYCODE_" + code);
    switch (code) {
      case "ControlLeft":
        return 113;
      case "ControlRight":
        return 114;
      case "ShiftLeft":
        return 59;
      case "ShiftRight":
        return 60;
      case "AltLeft":
        return 57;
      case "AltRight":
        return 58;
      case "MetaLeft":
        return 117;
      case "MetaRight":
        return 118;
      case "Enter":
        return 66;
      case "Escape":
        return 111;
      case "Backspace":
        return 67;
      case "Delete":
        return 112;
      case "Tab":
        return 61;
      case "Space":
        return 62;
      case "ArrowLeft":
        return 21;
      case "ArrowRight":
        return 22;
      case "ArrowUp":
        return 19;
      case "ArrowDown":
        return 20;
      case "Home":
        return 122;
      case "End":
        return 123;
      case "PageUp":
        return 92;
      case "PageDown":
        return 93;
      case "Insert":
        return 124;
      case "CapsLock":
        return 115;
      case "Semicolon":
        return 74;
      case "Equal":
        return 70;
      case "Comma":
        return 55;
      case "Minus":
        return 69;
      case "Period":
        return 56;
      case "Slash":
        return 76;
      case "Backquote":
        return 68;
      case "BracketLeft":
        return 71;
      case "BracketRight":
        return 72;
      case "Backslash":
        return 73;
      case "Quote":
        return 75;
      default:
        return 0;
    }
  }

  private static void post(InputEvent event) throws Exception {
    try {
      if (!Boolean.TRUE.equals(inject.invoke(manager, event, 0))) throw new IOException("系统拒绝输入注入");
    } catch (InvocationTargetException error) {
      Throwable cause = error.getCause();
      if (cause instanceof SecurityException)
        throw new IOException(
            "PERMISSION_REQUIRED: 系统禁止模拟输入（INJECT_EVENTS）。请在开发者选项开启「USB 调试（安全设置）」或厂商的模拟输入权限。",
            cause);
      throw new IOException(
          "系统输入接口失败："
              + (cause == null
                  ? error.getClass().getSimpleName()
                  : cause.getClass().getSimpleName() + ": " + String.valueOf(cause.getMessage())),
          error);
    } finally {
      if (event instanceof MotionEvent) ((MotionEvent) event).recycle();
    }
  }

  private static void key(int code, boolean down) throws Exception {
    long now = SystemClock.uptimeMillis();
    if (down) {
      if (held.add(code)) {
        keyTimes.put(code, now);
        repeats.put(code, 0);
      } else repeats.put(code, repeats.getOrDefault(code, 0) + 1);
    } else held.remove(code);
    long started = keyTimes.getOrDefault(code, now);
    int repeat = down ? repeats.getOrDefault(code, 0) : 0;
    meta = 0;
    if (held.contains(113) || held.contains(114)) meta |= KeyEvent.META_CTRL_ON;
    if (held.contains(59) || held.contains(60)) meta |= KeyEvent.META_SHIFT_ON;
    if (held.contains(57) || held.contains(58)) meta |= KeyEvent.META_ALT_ON;
    if (held.contains(117) || held.contains(118)) meta |= KeyEvent.META_META_ON;
    if (hid == null || !hid.key(code, down))
      post(
          new KeyEvent(
            started,
            now,
            down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP,
            code,
            repeat,
            meta,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            KeyEvent.FLAG_FROM_SYSTEM,
            InputDevice.SOURCE_KEYBOARD));
    if (!down) {
      keyTimes.remove(code);
      repeats.remove(code);
    }
  }

  private static void motion(int action, float wheel) throws Exception {
    motion(action, wheel, 0);
  }

  private static void motion(int action, float wheel, int actionButton) throws Exception {
    long now = SystemClock.uptimeMillis();
    MotionEvent.PointerProperties prop = new MotionEvent.PointerProperties();
    prop.id = 0;
    prop.toolType = finger ? MotionEvent.TOOL_TYPE_FINGER : MotionEvent.TOOL_TYPE_MOUSE;
    MotionEvent.PointerCoords point = new MotionEvent.PointerCoords();
    point.x = x;
    point.y = y;
    point.pressure = buttons == 0 ? 0 : 1;
    point.setAxisValue(MotionEvent.AXIS_VSCROLL, wheel);
    MotionEvent event =
        MotionEvent.obtain(
            downTime,
            now,
            action,
            1,
            new MotionEvent.PointerProperties[] {prop},
            new MotionEvent.PointerCoords[] {point},
            meta,
            finger ? 0 : buttons,
            1,
            1,
            0,
            0,
            finger ? InputDevice.SOURCE_TOUCHSCREEN : InputDevice.SOURCE_MOUSE,
            0);
    if (actionButton != 0)
      MotionEvent.class.getMethod("setActionButton", int.class).invoke(event, actionButton);
    MotionEvent.class.getMethod("setDisplayId", int.class).invoke(event, Display.DEFAULT_DISPLAY);
    post(event);
  }

  private static synchronized void release() {
    for (int code : new ArrayList<>(held))
      try {
        key(code, false);
      } catch (Exception ignored) {
      }
    held.clear();
    if (buttons != 0) {
      if (hid != null) {
        for (int b = 0; b < 3; b++) {
          int mask = b == 0 ? MotionEvent.BUTTON_PRIMARY : b == 2 ? MotionEvent.BUTTON_SECONDARY : MotionEvent.BUTTON_TERTIARY;
          if ((buttons & mask) != 0)
            try { hid.button(b, false); } catch (Exception ignored) {}
        }
      }
      buttons = 0;
      if (hid == null)
        try { motion(MotionEvent.ACTION_UP, 0); } catch (Exception ignored) {}
    }
    finger = false;
    if (hid != null) { hid.close(); hid = null; }
  }

  private static synchronized void handle(JSONObject m) throws Exception {
    lease = SystemClock.uptimeMillis() + 3500;
    String method = m.getString("method");
    if (method.equals("heartbeat")) return;
    if (method.equals("startVideo")) {
      int port = m.getInt("port");
      String secret = m.getString("secret");
      if (port < 1 || port > 65535 || Wire.parseToken(secret).length != 32)
        throw new SecurityException("Invalid capture receiver");
      String apk = m.getString("apkPath");
      if (!apk.startsWith("/data/app/") || !apk.endsWith("/base.apk")
          || !new File(apk).isFile()) throw new SecurityException("Invalid capture APK");
      if (videoProcess != null) videoProcess.destroy();
      ProcessBuilder capture = new ProcessBuilder("/system/bin/app_process", "/system/bin",
          "com.lanlink.app.RootStream", String.valueOf(port))
          .redirectOutput(new File("/dev/null")).redirectError(new File("/dev/null"));
      // APK updates change sourceDir while this persistent helper may remain alive.
      capture.environment().put("CLASSPATH", apk);
      capture.environment().put("LANLINK_VIDEO_SECRET", secret);
      videoProcess = capture.start();
      return;
    }
    if (method.equals("activatePointer")) {
      if (hid != null && !hid.alive()) { hid.close(); hid = null; }
      if (hid == null && SystemClock.uptimeMillis() >= hidRetryAt) {
        hid = VirtualHid.open();
        if (hid == null) hidRetryAt = SystemClock.uptimeMillis() + 4000;
      }
      return;
    }
    if (method.equals("probe")) {
      key(KeyEvent.KEYCODE_UNKNOWN, false);
      return;
    }
    if (method.equals("release")) {
      release();
      return;
    }
    if (method.equals("paste")) {
      key(KeyEvent.KEYCODE_PASTE, true);
      key(KeyEvent.KEYCODE_PASTE, false);
      return;
    }
    if (!method.equals("input")) throw new IOException("无效操作");
    JSONObject a = m.getJSONObject("args");
    String kind = a.getString("kind");
    switch (kind) {
      case "navigation":
        String action = a.optString("action");
        int navigation =
            action.equals("back")
                ? KeyEvent.KEYCODE_BACK
                : action.equals("home")
                    ? KeyEvent.KEYCODE_HOME
                    : action.equals("recents") ? KeyEvent.KEYCODE_APP_SWITCH : 0;
        if (navigation == 0) throw new IOException("无效导航操作");
        key(navigation, true);
        key(navigation, false);
        break;
      case "move":
        double nx = a.getDouble("x"), ny = a.getDouble("y");
        if (!Double.isFinite(nx) || !Double.isFinite(ny) || nx < 0 || nx > 1 || ny < 0 || ny > 1)
          throw new IOException("无效坐标");
        x = (float) nx * (m.getInt("width") - 1);
        y = (float) ny * (m.getInt("height") - 1);
        if (m.optBoolean("pointer", true) && hid != null) {
          hid.move(a, m.getInt("width"), m.getInt("height"));
          break;
        }
        // Remote-window hover only positions the next touch; do not create an OS mouse cursor.
        if (buttons != 0) motion(MotionEvent.ACTION_MOVE, 0);
        break;
      case "button":
        int b = a.getInt("button");
        if (b < 0 || b > 2) throw new IOException("无效鼠标按钮");
        int mask =
            b == 0
                ? MotionEvent.BUTTON_PRIMARY
                : b == 2 ? MotionEvent.BUTTON_SECONDARY : MotionEvent.BUTTON_TERTIARY;
        boolean down = a.getBoolean("down");
        if (m.optBoolean("pointer", true) && hid != null) {
          if (down != ((buttons & mask) != 0)) {
            hid.button(b, down);
            if (down) buttons |= mask;
            else buttons &= ~mask;
          }
          break;
        }
        if (!m.optBoolean("pointer", true) && b != 0) {
          key(b == 2 ? KeyEvent.KEYCODE_BACK : KeyEvent.KEYCODE_HOME, down);
          break;
        }
        int previousButtons = buttons;
        if (down && previousButtons == 0) finger = b == 0;
        if (down) {
          if (buttons == 0) downTime = SystemClock.uptimeMillis();
          buttons |= mask;
        } else buttons &= ~mask;
        if (finger) {
          if (previousButtons == 0) motion(MotionEvent.ACTION_DOWN, 0);
          else if (buttons == 0) motion(MotionEvent.ACTION_UP, 0);
        } else {
          if (down && previousButtons == 0) motion(MotionEvent.ACTION_DOWN, 0);
          motion(
              down ? MotionEvent.ACTION_BUTTON_PRESS : MotionEvent.ACTION_BUTTON_RELEASE, 0, mask);
          if (!down && buttons == 0) motion(MotionEvent.ACTION_UP, 0);
        }
        if (buttons == 0) finger = false;
        break;
      case "wheel":
        int delta = a.getInt("delta");
        if (Math.abs(delta) > 2400) throw new IOException("无效滚动");
        if (m.optBoolean("pointer", true) && hid != null) {
          hid.wheel(delta);
          break;
        }
        if (buttons == 0 && delta != 0) {
          float previousY = y;
          finger = true;
          buttons = 1;
          downTime = SystemClock.uptimeMillis();
          try {
            motion(MotionEvent.ACTION_DOWN, 0);
            float distance =
                Math.max(-.3f, Math.min(.3f, delta / 120f * .12f)) * m.getInt("height");
            for (int step = 1; step <= 6; step++) {
              SystemClock.sleep(8);
              y = Math.max(1, Math.min(m.getInt("height") - 2, previousY + distance * step / 6));
              motion(MotionEvent.ACTION_MOVE, 0);
            }
          } finally {
            buttons = 0;
            motion(MotionEvent.ACTION_UP, 0);
            finger = false;
            y = previousY;
          }
        }
        break;
      case "key":
        int code = keyCode(a.getString("code"));
        if (code == 0) throw new IOException("不支持的按键");
        key(code, a.getBoolean("down"));
        break;
      default:
        throw new IOException("无效输入");
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length == 1 && args[0].equals("--check-hid")) {
      android.os.Looper.prepare();
      VirtualHid candidate = VirtualHid.open();
      System.out.println(obj("virtualHid", candidate != null).toString());
      if (candidate != null) candidate.close();
      return;
    }
    if (args.length == 1 && args[0].equals("--check-input")) {
      android.os.Looper.prepare();
      initializeInput();
      JSONObject report = new JSONObject();
      try {
        motion(MotionEvent.ACTION_CANCEL, 0);
        report.put("cancel", "ok");
      } catch (Exception error) {
        report.put("cancel", error.toString());
        Throwable cause = error.getCause();
        if (cause != null) report.put("cause", cause.toString());
      }
      try {
        key(KeyEvent.KEYCODE_UNKNOWN, false);
        report.put("keyRelease", "ok");
      } catch (Exception error) {
        report.put("keyRelease", error.toString());
      }
      try {
        motion(MotionEvent.ACTION_HOVER_MOVE, 0);
        report.put("hover", "ok");
      } catch (Exception error) {
        report.put("hover", error.toString());
      }
      System.out.println(report.toString());
      return;
    }
    if (args.length != 2) throw new IllegalArgumentException("port token required");
    // Avoid two persistent helpers repeatedly replacing one another's connection.
    try {
      singleton =
          new android.net.LocalServerSocket(
              "lanlink-shell-v10-" + android.os.Process.myUid() + "-" + Integer.parseInt(args[0]));
    } catch (IOException alreadyRunning) {
      return;
    }
    android.os.Looper.prepare();
    initializeInput();
    runConnection(args);
  }

  private static void initializeInput() throws Exception {
    Class<?> clazz;
    try {
      clazz = Class.forName("android.hardware.input.InputManagerGlobal");
    } catch (ClassNotFoundException e) {
      clazz = Class.forName("android.hardware.input.InputManager");
    }
    Method get = clazz.getDeclaredMethod("getInstance");
    get.setAccessible(true);
    manager = get.invoke(null);
    inject = clazz.getMethod("injectInputEvent", InputEvent.class, int.class);
    inject.setAccessible(true);
  }

  private static void runConnection(String[] args) throws Exception {
    ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
    watchdog.scheduleAtFixedRate(
        () -> {
          if (SystemClock.uptimeMillis() > lease) release();
        },
        1,
        1,
        TimeUnit.SECONDS);
    while (true) {
      try (Socket socket = new Socket()) {
        socket.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), 2000);
        socket.setSoTimeout(4000);
        BufferedReader in =
            new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter out =
            new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        ShellAuth.client(args[1], in, out);
        socket.setSoTimeout(0);
        String line;
        while ((line = ShellAuth.line(in)) != null) {
          JSONObject result;
          try {
            if (line.length() > 6000000) throw new IOException("消息过大");
            JSONObject message = new JSONObject(line);
            if (message.optString("method").equals("exit")) {
              release();
              System.exit(0);
            }
            if(message.optString("method").equals("clipboard-get"))result=ShellClipboard.get().put("ok",true).put("uid",android.os.Process.myUid());
            else if(message.optString("method").equals("clipboard-set")){ShellClipboard.set(message.optString("text"));result=obj("ok",true,"uid",android.os.Process.myUid());}
            else{handle(message);
            result = obj("ok", true, "uid", android.os.Process.myUid());
            if (message.optString("method").equals("activatePointer"))
              result.put("nativePointer", hid != null);
            }
          } catch (Exception e) {
            result = obj("error", e.getMessage() == null ? "系统输入失败" : e.getMessage());
          }
          out.write(result.toString() + "\n");
          out.flush();
        }
      } catch (Exception disconnected) {
        // Only reconnect to the mutually authenticated local application.
      } finally {
        release();
      }
      Thread.sleep(1500);
    }
  }
}
