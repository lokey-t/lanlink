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
  private static int buttons = 0, meta = 0;
  private static boolean finger;
  private static float x = 100, y = 100;
  private static long downTime = 0, lease = 0;

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
      if (!Boolean.TRUE.equals(inject.invoke(manager, event, 2))) throw new IOException("系统拒绝输入注入");
    } catch (InvocationTargetException error) {
      Throwable cause = error.getCause();
      if (cause instanceof SecurityException)
        throw new IOException("系统禁止模拟输入（INJECT_EVENTS）。请在开发者选项开启「USB 调试（安全设置）」或厂商的模拟输入权限。", cause);
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
    if (down) held.add(code);
    else held.remove(code);
    meta = 0;
    if (held.contains(113) || held.contains(114)) meta |= KeyEvent.META_CTRL_ON;
    if (held.contains(59) || held.contains(60)) meta |= KeyEvent.META_SHIFT_ON;
    if (held.contains(57) || held.contains(58)) meta |= KeyEvent.META_ALT_ON;
    if (held.contains(117) || held.contains(118)) meta |= KeyEvent.META_META_ON;
    long now = SystemClock.uptimeMillis();
    post(
        new KeyEvent(
            now,
            now,
            down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP,
            code,
            0,
            meta,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            KeyEvent.FLAG_FROM_SYSTEM,
            InputDevice.SOURCE_KEYBOARD));
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
      buttons = 0;
      try {
        motion(MotionEvent.ACTION_UP, 0);
      } catch (Exception ignored) {
      }
    }
    finger = false;
  }

  private static synchronized void handle(JSONObject m) throws Exception {
    lease = SystemClock.uptimeMillis() + 3500;
    String method = m.getString("method");
    if (method.equals("heartbeat")) return;
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
        motion(buttons == 0 ? MotionEvent.ACTION_HOVER_MOVE : MotionEvent.ACTION_MOVE, 0);
        break;
      case "button":
        int b = a.getInt("button");
        if (b < 0 || b > 2) throw new IOException("无效鼠标按钮");
        int mask =
            b == 0
                ? MotionEvent.BUTTON_PRIMARY
                : b == 2 ? MotionEvent.BUTTON_SECONDARY : MotionEvent.BUTTON_TERTIARY;
        boolean down = a.getBoolean("down");
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
        motion(MotionEvent.ACTION_SCROLL, delta / 120f);
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
          new android.net.LocalServerSocket("lanlink-shell-v3-" + Integer.parseInt(args[0]));
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
            if (line.length() > 65536) throw new IOException("消息过大");
            handle(new JSONObject(line));
            result = obj("ok", true);
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
