package com.lanlink.app;

import android.os.SystemClock;
import android.view.InputDevice;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** A session-scoped kernel input device, owned by the authenticated shell/root helper. */
final class VirtualHid implements Closeable {
  private static final String MOUSE = "LanLink Virtual Mouse";
  private static final String KEYBOARD = "LanLink Virtual Keyboard";
  private static final String[] KEYS = {
    "KEY_A", "KEY_B", "KEY_C", "KEY_D", "KEY_E", "KEY_F", "KEY_G", "KEY_H", "KEY_I",
    "KEY_J", "KEY_K", "KEY_L", "KEY_M", "KEY_N", "KEY_O", "KEY_P", "KEY_Q", "KEY_R",
    "KEY_S", "KEY_T", "KEY_U", "KEY_V", "KEY_W", "KEY_X", "KEY_Y", "KEY_Z",
    "KEY_0", "KEY_1", "KEY_2", "KEY_3", "KEY_4", "KEY_5", "KEY_6", "KEY_7",
    "KEY_8", "KEY_9", "KEY_KP0", "KEY_KP1", "KEY_KP2", "KEY_KP3", "KEY_KP4",
    "KEY_KP5", "KEY_KP6", "KEY_KP7", "KEY_KP8", "KEY_KP9", "KEY_F1", "KEY_F2",
    "KEY_F3", "KEY_F4", "KEY_F5", "KEY_F6", "KEY_F7", "KEY_F8", "KEY_F9",
    "KEY_F10", "KEY_F11", "KEY_F12", "KEY_LEFTCTRL", "KEY_RIGHTCTRL", "KEY_LEFTSHIFT",
    "KEY_RIGHTSHIFT", "KEY_LEFTALT", "KEY_RIGHTALT", "KEY_LEFTMETA", "KEY_RIGHTMETA",
    "KEY_ENTER", "KEY_ESC", "KEY_BACKSPACE", "KEY_DELETE", "KEY_TAB", "KEY_SPACE",
    "KEY_LEFT", "KEY_RIGHT", "KEY_UP", "KEY_DOWN", "KEY_HOME", "KEY_END",
    "KEY_PAGEUP", "KEY_PAGEDOWN", "KEY_INSERT", "KEY_CAPSLOCK", "KEY_SEMICOLON",
    "KEY_EQUAL", "KEY_COMMA", "KEY_MINUS", "KEY_DOT", "KEY_SLASH", "KEY_GRAVE",
    "KEY_LEFTBRACE", "KEY_RIGHTBRACE", "KEY_BACKSLASH", "KEY_APOSTROPHE"
  };
  private static final Set<String> SUPPORTED = new HashSet<>(Arrays.asList(KEYS));
  private final Process process;
  private final BufferedWriter out;
  private boolean positioned;
  private float previousX, previousY;
  private double remainderX, remainderY;

  boolean alive() { return process.isAlive(); }

  private VirtualHid(Process process) {
    this.process = process;
    out = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
    Thread drain = new Thread(() -> {
      try (InputStream stream = process.getInputStream()) {
        byte[] buffer = new byte[1024];
        while (stream.read(buffer) != -1) {}
      } catch (IOException ignored) {}
    }, "LanLink-uinput-output");
    drain.setDaemon(true);
    drain.start();
  }

  static VirtualHid open() {
    Process process = null;
    VirtualHid device = null;
    try {
      Set<Integer> existing = new HashSet<>();
      for (int id : InputDevice.getDeviceIds()) existing.add(id);
      process = new ProcessBuilder("/system/bin/uinput", "-").redirectErrorStream(true).start();
      device = new VirtualHid(process);
      device.send(register(1, MOUSE, 0x4c4c, 1,
          new String[] {"EV_KEY", "EV_REL"},
          new String[] {"BTN_LEFT", "BTN_RIGHT", "BTN_MIDDLE"},
          new String[] {"REL_X", "REL_Y", "REL_WHEEL"}));
      device.send(register(2, KEYBOARD, 0x4c4c, 2,
          new String[] {"EV_KEY"}, KEYS, null));
      long until = SystemClock.uptimeMillis() + 1600;
      do {
        if (!process.isAlive()) throw new IOException("uinput exited");
        if (present(MOUSE, existing) && present(KEYBOARD, existing)) {
          // InputDevice enumeration can precede the first dispatchable input frame.
          SystemClock.sleep(80);
          return device;
        }
        SystemClock.sleep(35);
      } while (SystemClock.uptimeMillis() < until);
      throw new IOException("virtual input devices not registered");
    } catch (Exception error) {
      if (device != null) device.close();
      else if (process != null) process.destroy();
      return null;
    }
  }

  private static boolean present(String name, Set<Integer> existing) {
    for (int id : InputDevice.getDeviceIds()) {
      InputDevice device = InputDevice.getDevice(id);
      if (!existing.contains(id) && device != null && name.equals(device.getName())
          && device.isExternal()) return true;
    }
    return false;
  }

  // Numeric Linux uinput ABI values also work on Android 14, whose reader cannot parse names.
  static int eventCode(String name) {
    switch (name) {
      case "BTN_LEFT": return 272;
      case "BTN_MIDDLE": return 274;
      case "BTN_RIGHT": return 273;
      case "EV_KEY": return 1;
      case "EV_REL": return 2;
      case "EV_SYN": return 0;
      case "KEY_0": return 11;
      case "KEY_1": return 2;
      case "KEY_2": return 3;
      case "KEY_3": return 4;
      case "KEY_4": return 5;
      case "KEY_5": return 6;
      case "KEY_6": return 7;
      case "KEY_7": return 8;
      case "KEY_8": return 9;
      case "KEY_9": return 10;
      case "KEY_A": return 30;
      case "KEY_APOSTROPHE": return 40;
      case "KEY_B": return 48;
      case "KEY_BACKSLASH": return 43;
      case "KEY_BACKSPACE": return 14;
      case "KEY_C": return 46;
      case "KEY_CAPSLOCK": return 58;
      case "KEY_COMMA": return 51;
      case "KEY_D": return 32;
      case "KEY_DELETE": return 111;
      case "KEY_DOT": return 52;
      case "KEY_DOWN": return 108;
      case "KEY_E": return 18;
      case "KEY_END": return 107;
      case "KEY_ENTER": return 28;
      case "KEY_EQUAL": return 13;
      case "KEY_ESC": return 1;
      case "KEY_F": return 33;
      case "KEY_F1": return 59;
      case "KEY_F10": return 68;
      case "KEY_F11": return 87;
      case "KEY_F12": return 88;
      case "KEY_F2": return 60;
      case "KEY_F3": return 61;
      case "KEY_F4": return 62;
      case "KEY_F5": return 63;
      case "KEY_F6": return 64;
      case "KEY_F7": return 65;
      case "KEY_F8": return 66;
      case "KEY_F9": return 67;
      case "KEY_G": return 34;
      case "KEY_GRAVE": return 41;
      case "KEY_H": return 35;
      case "KEY_HOME": return 102;
      case "KEY_I": return 23;
      case "KEY_INSERT": return 110;
      case "KEY_J": return 36;
      case "KEY_K": return 37;
      case "KEY_KP0": return 82;
      case "KEY_KP1": return 79;
      case "KEY_KP2": return 80;
      case "KEY_KP3": return 81;
      case "KEY_KP4": return 75;
      case "KEY_KP5": return 76;
      case "KEY_KP6": return 77;
      case "KEY_KP7": return 71;
      case "KEY_KP8": return 72;
      case "KEY_KP9": return 73;
      case "KEY_L": return 38;
      case "KEY_LEFT": return 105;
      case "KEY_LEFTALT": return 56;
      case "KEY_LEFTBRACE": return 26;
      case "KEY_LEFTCTRL": return 29;
      case "KEY_LEFTMETA": return 125;
      case "KEY_LEFTSHIFT": return 42;
      case "KEY_M": return 50;
      case "KEY_MINUS": return 12;
      case "KEY_N": return 49;
      case "KEY_O": return 24;
      case "KEY_P": return 25;
      case "KEY_PAGEDOWN": return 109;
      case "KEY_PAGEUP": return 104;
      case "KEY_Q": return 16;
      case "KEY_R": return 19;
      case "KEY_RIGHT": return 106;
      case "KEY_RIGHTALT": return 100;
      case "KEY_RIGHTBRACE": return 27;
      case "KEY_RIGHTCTRL": return 97;
      case "KEY_RIGHTMETA": return 126;
      case "KEY_RIGHTSHIFT": return 54;
      case "KEY_S": return 31;
      case "KEY_SEMICOLON": return 39;
      case "KEY_SLASH": return 53;
      case "KEY_SPACE": return 57;
      case "KEY_T": return 20;
      case "KEY_TAB": return 15;
      case "KEY_U": return 22;
      case "KEY_UP": return 103;
      case "KEY_V": return 47;
      case "KEY_W": return 17;
      case "KEY_X": return 45;
      case "KEY_Y": return 21;
      case "KEY_Z": return 44;
      case "REL_WHEEL": return 8;
      case "REL_X": return 0;
      case "REL_Y": return 1;
      case "SYN_REPORT": return 0;
      default: throw new IllegalArgumentException("Unsupported input code: " + name);
    }
  }

  private static JSONArray eventCodes(String[] names) {
    JSONArray values = new JSONArray();
    for (String name : names) values.put(eventCode(name));
    return values;
  }

  private static JSONObject register(int id, String name, int vid, int pid,
                                     String[] eventTypes, String[] buttons, String[] relative)
      throws JSONException {
    JSONArray configuration = new JSONArray();
    configuration.put(new JSONObject().put("type", 100).put("data", eventCodes(eventTypes)));
    configuration.put(new JSONObject().put("type", 101).put("data", eventCodes(buttons)));
    if (relative != null)
      configuration.put(new JSONObject().put("type", 102).put("data", eventCodes(relative)));
    return new JSONObject().put("id", id).put("command", "register").put("name", name)
        .put("vid", vid).put("pid", pid).put("bus", "bluetooth")
        .put("configuration", configuration);
  }

  private void send(JSONObject message) throws IOException {
    if (!process.isAlive()) throw new IOException("virtual input device stopped");
    out.write(message.toString());
    out.newLine();
    out.flush();
  }

  private void inject(int id, Object... events) throws Exception {
    JSONArray data = new JSONArray();
    for (Object event : events) data.put(event instanceof String ? eventCode((String) event) : event);
    if (android.os.Build.VERSION.SDK_INT >= 35)
      send(new JSONObject().put("id", id).put("command", "updateTimeBase"));
    send(new JSONObject().put("id", id).put("command", "inject").put("events", data));
  }

  void move(JSONObject args, int width, int height) throws Exception {
    float x = (float) args.getDouble("x"), y = (float) args.getDouble("y");
    if (!positioned) {
      // The pointer starts in the centre. Snap to the entry edge only once.
      int initialX = x < .1f ? -10000 : x > .9f ? 10000 : 0;
      int initialY = y < .1f ? -10000 : y > .9f ? 10000 : 0;
      positioned = true;
      previousX = x;
      previousY = y;
      if (initialX != 0 || initialY != 0) relative(initialX, initialY);
      return;
    }
    // Released desktop builds send only normalized positions. Never treat missing
    // relative fields as zero: that leaves a registered system mouse immobile.
    double dx = args.has("dx") && args.has("dy")
        ? args.getDouble("dx") : (x - previousX) * Math.max(1, width - 1);
    double dy = args.has("dx") && args.has("dy")
        ? args.getDouble("dy") : (y - previousY) * Math.max(1, height - 1);
    if (!Double.isFinite(dx) || !Double.isFinite(dy)) throw new IOException("无效鼠标位移");
    previousX = x;
    previousY = y;
    remainderX += Math.max(-32767, Math.min(32767, dx));
    remainderY += Math.max(-32767, Math.min(32767, dy));
    int wholeX = (int) remainderX, wholeY = (int) remainderY;
    remainderX -= wholeX;
    remainderY -= wholeY;
    relative(wholeX, wholeY);
  }

  private void relative(int dx, int dy) throws Exception {
    if (dx == 0 && dy == 0) return;
    inject(1, "EV_REL", "REL_X", dx, "EV_REL", "REL_Y", dy,
        "EV_SYN", "SYN_REPORT", 0);
  }

  void button(int index, boolean down) throws Exception {
    String code = index == 0 ? "BTN_LEFT" : index == 2 ? "BTN_RIGHT" : "BTN_MIDDLE";
    inject(1, "EV_KEY", code, down ? 1 : 0, "EV_SYN", "SYN_REPORT", 0);
  }

  void wheel(int delta) throws Exception {
    int ticks = Math.max(-20, Math.min(20, -Math.round(delta / 120f)));
    if (ticks != 0)
      inject(1, "EV_REL", "REL_WHEEL", ticks, "EV_SYN", "SYN_REPORT", 0);
  }

  boolean key(int androidCode, boolean down) throws Exception {
    String linuxCode = linuxKey(androidCode);
    if (linuxCode == null) return false;
    inject(2, "EV_KEY", linuxCode, down ? 1 : 0, "EV_SYN", "SYN_REPORT", 0);
    return true;
  }

  private static String linuxKey(int code) {
    String name = android.view.KeyEvent.keyCodeToString(code);
    if (!name.startsWith("KEYCODE_")) return null;
    name = name.substring(8);
    if (name.startsWith("NUMPAD_")) name = "KP" + name.substring(7);
    else switch (name) {
      case "CTRL_LEFT": name = "LEFTCTRL"; break;
      case "CTRL_RIGHT": name = "RIGHTCTRL"; break;
      case "SHIFT_LEFT": name = "LEFTSHIFT"; break;
      case "SHIFT_RIGHT": name = "RIGHTSHIFT"; break;
      case "ALT_LEFT": name = "LEFTALT"; break;
      case "ALT_RIGHT": name = "RIGHTALT"; break;
      case "META_LEFT": name = "LEFTMETA"; break;
      case "META_RIGHT": name = "RIGHTMETA"; break;
      case "ESCAPE": name = "ESC"; break;
      case "FORWARD_DEL": name = "DELETE"; break;
      case "DEL": name = "BACKSPACE"; break;
      case "MOVE_HOME": name = "HOME"; break;
      case "MOVE_END": name = "END"; break;
      case "PAGE_UP": name = "PAGEUP"; break;
      case "PAGE_DOWN": name = "PAGEDOWN"; break;
      case "DPAD_LEFT": name = "LEFT"; break;
      case "DPAD_RIGHT": name = "RIGHT"; break;
      case "DPAD_UP": name = "UP"; break;
      case "DPAD_DOWN": name = "DOWN"; break;
      case "CAPS_LOCK": name = "CAPSLOCK"; break;
      case "EQUALS": name = "EQUAL"; break;
      case "PERIOD": name = "DOT"; break;
      case "GRAVE": name = "GRAVE"; break;
      case "LEFT_BRACKET": name = "LEFTBRACE"; break;
      case "RIGHT_BRACKET": name = "RIGHTBRACE"; break;
      case "APOSTROPHE": name = "APOSTROPHE"; break;
    }
    String linux = "KEY_" + name;
    return SUPPORTED.contains(linux) ? linux : null;
  }

  @Override public void close() {
    try { out.close(); } catch (IOException ignored) {}
    process.destroy();
  }
}
