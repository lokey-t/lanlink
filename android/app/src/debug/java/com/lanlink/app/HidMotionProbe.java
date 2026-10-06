package com.lanlink.app;

import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import java.io.*;
import java.lang.reflect.Method;
import java.util.regex.*;
import org.json.JSONObject;

/** Debug-build shell/root diagnostic: small movements, no keys, taps or app settings. */
public final class HidMotionProbe {
  private static float[] cursor(int id) throws Exception {
    Process dump = new ProcessBuilder("/system/bin/dumpsys", "input").start();
    StringBuilder text = new StringBuilder();
    try (BufferedReader in = new BufferedReader(new InputStreamReader(dump.getInputStream()))) {
      String line;
      while ((line = in.readLine()) != null) text.append(line).append('\n');
    }
    Matcher match = Pattern.compile("MotionEvent\\(deviceId=" + id
        + ",[^\\n]*source=MOUSE[^\\n]*xCursorPosition=([0-9.]+), yCursorPosition=([0-9.]+)")
        .matcher(text);
    float[] result = null;
    while (match.find()) result = new float[] {Float.parseFloat(match.group(1)), Float.parseFloat(match.group(2))};
    if (result == null) throw new IOException("No system mouse event for device " + id);
    return result;
  }

  public static void main(String[] args) throws Exception {
    Looper.prepare();
    Method init = ShellAgent.class.getDeclaredMethod("initializeInput");
    init.setAccessible(true);
    init.invoke(null);
    Method handle = ShellAgent.class.getDeclaredMethod("handle", JSONObject.class);
    handle.setAccessible(true);
    try {
      System.out.println("probe: registering input devices");
      handle.invoke(null, Wire.obj("method", "activatePointer"));
      int mouse = -1;
      for (int id : InputDevice.getDeviceIds()) {
        InputDevice device = InputDevice.getDevice(id);
        if (device != null && "LanLink Virtual Mouse".equals(device.getName())) mouse = id;
      }
      if (mouse < 0) throw new IOException("Virtual mouse did not register");
      System.out.println("probe: testing system cursor movement");
      move(handle, .5, .5, null);
      move(handle, .5, .5, 1);
      SystemClock.sleep(150);
      if (args.length > 0 && args[0].equals("--external-observer")) {
        System.out.println(Wire.obj("phase", "before", "deviceId", mouse));
        SystemClock.sleep(1500);
        move(handle, .46, .5, null);
        SystemClock.sleep(150);
        System.out.println(Wire.obj("phase", "legacy", "deviceId", mouse));
        SystemClock.sleep(1500);
        move(handle, .46, .5, 12);
        SystemClock.sleep(150);
        System.out.println(Wire.obj("phase", "relative", "deviceId", mouse));
        SystemClock.sleep(1500);
        return;
      }
      System.out.println("probe: reading initial cursor");
      float[] before = cursor(mouse);
      System.out.println("probe: sending legacy movement");
      // Test the legacy desktop packet, deliberately omitting dx and dy.
      double direction = before[0] > 500 ? -.04 : .04;
      move(handle, .5 + direction, .5, null);
      SystemClock.sleep(150);
      float[] legacy = cursor(mouse);
      if (Math.abs(legacy[0] - before[0]) < 2) throw new IOException("Legacy position packet did not move system mouse");
      move(handle, .5 + direction, .5, direction > 0 ? -12 : 12);
      SystemClock.sleep(150);
      float[] relative = cursor(mouse);
      if (Math.abs(relative[0] - legacy[0]) < 2) throw new IOException("Relative packet did not move system mouse");
      System.out.println(Wire.obj("uid", android.os.Process.myUid(), "legacyMove", true,
          "relativeMove", true, "deviceId", mouse));
    } finally {
      handle.invoke(null, Wire.obj("method", "release"));
    }
  }

  private static void move(Method handle, double x, double y, Integer dx) throws Exception {
    JSONObject a = Wire.obj("kind", "move", "x", x, "y", y);
    if (dx != null) a.put("dx", dx).put("dy", 0);
    handle.invoke(null, Wire.obj("method", "input", "pointer", true,
        "width", 1080, "height", 2400, "args", a));
  }
}
