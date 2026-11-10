package com.lanlink.app;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.pm.PackageManager;
import android.os.Build;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * Exposes the phone as a Bluetooth HID keyboard/mouse so the touchpad can drive a paired
 * computer through the operating system, without LanLink's network input channel.
 */
final class PhoneHid {
  private static final int[] REPORT_MAP = {
    0x05, 0x01, 0x09, 0x02, 0xa1, 0x01, 0x85, 0x01, 0x09, 0x01, 0xa1, 0x00,
    0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x03, 0x81, 0x02,
    0x75, 0x05, 0x95, 0x01, 0x81, 0x03,
    0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81, 0x25, 0x7f, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06,
    0xc0, 0xc0,
    0x05, 0x01, 0x09, 0x06, 0xa1, 0x01, 0x85, 0x02, 0x05, 0x07,
    0x19, 0xe0, 0x29, 0xe7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
    0x75, 0x08, 0x95, 0x01, 0x81, 0x03,
    0x19, 0x00, 0x29, 0x65, 0x15, 0x00, 0x25, 0x65, 0x95, 0x06, 0x81, 0x00, 0xc0,
  };
  private static final String[] PERMISSIONS = {
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.BLUETOOTH_ADVERTISE,
  };
  private final LinkService service;
  private final BluetoothHidDevice.Callback callback;
  private final BluetoothProfile.ServiceListener listener;
  private final Set<Integer> keys = new HashSet<>();
  private BluetoothAdapter adapter;
  private volatile BluetoothHidDevice hid;
  private volatile BluetoothDevice host;
  private volatile boolean registered;
  private boolean requested;
  private volatile Wire pairingPeer;
  private volatile long pairingUntil;
  private int buttons;

  PhoneHid(LinkService service) {
    this.service = service;
    callback = new BluetoothHidDevice.Callback() {
      @Override public void onAppStatusChanged(BluetoothDevice plugged, boolean isRegistered) {
        registered = isRegistered;
        host = null;
        if (isRegistered && plugged != null) try {
          if (hid.getConnectionState(plugged) == BluetoothProfile.STATE_CONNECTED) host = plugged;
        } catch (RuntimeException ignored) {}
        service.notifyChange();
      }

      @Override public void onConnectionStateChanged(BluetoothDevice device, int state) {
        if (state == BluetoothProfile.STATE_CONNECTED) host = device;
        else if (device.equals(host)) host = null;
        service.notifyChange();
      }

      @Override public void onGetReport(BluetoothDevice device, byte type, byte id, int bufferSize) {
        try { hid.replyReport(device, type, id, neutral(id)); } catch (RuntimeException ignored) {}
      }

      @Override public void onSetReport(BluetoothDevice device, byte type, byte id, byte[] data) {
        try { hid.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS); } catch (RuntimeException ignored) {}
      }
    };
    listener = new BluetoothProfile.ServiceListener() {
      @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
        if (profile != BluetoothProfile.HID_DEVICE) return;
        hid = (BluetoothHidDevice) proxy;
        register();
      }

      @Override public void onServiceDisconnected(int profile) {
        hid = null;
        registered = false;
        host = null;
        requested = false;
        service.notifyChange();
      }
    };
  }

  static String[] permissions() { return PERMISSIONS; }

  boolean permitted() {
    if (Build.VERSION.SDK_INT < 31) return true;
    for (String permission : PERMISSIONS)
      if (service.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return false;
    return true;
  }

  boolean connected(String id) {
    BluetoothDevice device = host;
    return device != null && registered && permitted() &&
        device.getAddress().equals(service.hidHost(id));
  }

  void preparePairing(Wire peer) {
    pairingUntil = android.os.SystemClock.elapsedRealtime() + 120_000;
    pairingPeer = peer;
    enable();
  }

  JSONObject pairing(Wire peer, boolean confirm) throws java.io.IOException {
    if (pairingPeer != peer || pairingUntil < android.os.SystemClock.elapsedRealtime())
      throw new java.io.IOException("请先在对方设备的蓝牙 HID 键鼠页面开启配对");
    if (!confirm) {
      String name = permitted() && adapter != null ? adapter.getName() : null;
      if (name == null || name.isEmpty()) throw new java.io.IOException("请先在对方设备开启蓝牙");
      return Wire.obj("name", name);
    }
    BluetoothDevice device = host;
    if (device == null || !registered || !permitted())
      throw new java.io.IOException("请确认系统已连接该蓝牙键鼠，并一次只配对一台设备");
    service.bindHidHost(peer, device.getAddress());
    pairingPeer = null;
    return Wire.obj("connected", connected(peer.id));
  }

  void resume() {
    if (pairingPeer != null && pairingUntil >= android.os.SystemClock.elapsedRealtime()) { enable(); return; }
    java.util.Iterator<String> ids = service.authorizationDevices().keys();
    while (ids.hasNext()) if (!service.hidHost(ids.next()).isEmpty()) { enable(); return; }
  }

  void enable() {
    if (Build.VERSION.SDK_INT < 28 || !permitted()) return;
    if (hid != null) { register(); return; }
    if (requested) return;
    BluetoothManager manager = service.getSystemService(BluetoothManager.class);
    adapter = manager == null ? null : manager.getAdapter();
    if (adapter == null || !adapter.isEnabled()) return;
    requested = adapter.getProfileProxy(service, listener, BluetoothProfile.HID_DEVICE);
  }

  private void register() {
    if (hid == null || registered || Build.VERSION.SDK_INT < 28) return;
    byte[] map = new byte[REPORT_MAP.length];
    for (int i = 0; i < map.length; i++) map[i] = (byte) REPORT_MAP[i];
    BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
        "LanLink", "LanLink Bluetooth HID touchpad", "LanLink", (byte) 0xc0, map);
    try { hid.registerApp(sdp, null, null, service.getMainExecutor(), callback); }
    catch (RuntimeException ignored) {}
  }

  void close() {
    try { if (hid != null && registered) hid.unregisterApp(); } catch (RuntimeException ignored) {}
    if (adapter != null) try { adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid); } catch (RuntimeException ignored) {}
    hid = null;
    host = null;
    registered = false;
    requested = false;
  }

  /** Returns true when the event was delivered through HID; otherwise the caller uses the network. */
  synchronized boolean send(String id, JSONObject event) {
    BluetoothDevice device = host;
    if (!connected(id) || device != host) return false;
    try {
      switch (event.optString("kind")) {
        case "relative": {
          int dx = clamp(event.optDouble("dx", 0));
          int dy = clamp(event.optDouble("dy", 0));
          return dx == 0 && dy == 0 || sendMouse(device, dx, dy, 0);
        }
        case "button": {
          int button = event.optInt("button", 0);
          boolean down = event.optBoolean("down");
          int bit = button == 0 ? 1 : button == 1 ? 4 : 2;
          buttons = down ? buttons | bit : buttons & ~bit;
          return sendMouse(device, 0, 0, 0);
        }
        case "wheel": {
          int ticks = (int) Math.round(event.optDouble("delta", 0) / 120d);
          return sendMouse(device, 0, 0, Math.max(-20, Math.min(20, -ticks)));
        }
        case "key": {
          String code = event.optString("code");
          boolean down = event.optBoolean("down");
          int usage = usage(code);
          if (usage == 0) return false;
          if (down) keys.add(usage); else keys.remove(usage);
          return sendKeys(device);
        }
        default: return false;
      }
    } catch (RuntimeException ignored) { return false; }
  }

  synchronized void release(String id) {
    BluetoothDevice device = host;
    buttons = 0;
    keys.clear();
    if (connected(id) && device == host) try {
      sendMouse(device, 0, 0, 0);
      sendKeys(device);
    } catch (RuntimeException ignored) {}
  }

  private boolean sendMouse(BluetoothDevice device, int dx, int dy, int wheel) {
    byte[] report = {(byte) buttons, (byte) dx, (byte) dy, (byte) wheel};
    return hid.sendReport(device, 1, report);
  }

  private boolean sendKeys(BluetoothDevice device) {
    int mask = 0;
    int[] keyList = new int[6];
    int count = 0;
    for (int usage : keys) {
      if (usage >= 0xE0 && usage <= 0xE7) { mask |= 1 << (usage - 0xE0); continue; }
      if (count < keyList.length) keyList[count++] = usage;
    }
    byte[] report = {(byte) mask, 0, 0, 0, 0, 0, 0, 0};
    for (int i = 0; i < count; i++) report[2 + i] = (byte) keyList[i];
    return hid.sendReport(device, 2, report);
  }

  private static byte[] neutral(byte id) {
    return id == 2 ? new byte[8] : new byte[4];
  }

  private static int clamp(double value) {
    return (int) Math.max(-127, Math.min(127, Math.round(value)));
  }

  private static int usage(String code) {
    if (code.matches("Key[A-Z]")) return 4 + code.charAt(3) - 'A';
    if (code.matches("Digit[0-9]")) {
      int digit = code.charAt(5) - '0';
      return digit == 0 ? 39 : 29 + digit;
    }
    if (code.matches("Numpad[0-9]")) {
      int digit = code.charAt(6) - '0';
      return digit == 0 ? 98 : 88 + digit;
    }
    if (code.matches("F([1-9]|1[0-2])")) return 57 + Integer.parseInt(code.substring(1));
    switch (code) {
      case "ControlLeft": return 0xE0;
      case "ShiftLeft": return 0xE1;
      case "AltLeft": return 0xE2;
      case "MetaLeft": return 0xE3;
      case "ControlRight": return 0xE4;
      case "ShiftRight": return 0xE5;
      case "AltRight": return 0xE6;
      case "MetaRight": return 0xE7;
      case "Enter": return 40;
      case "Escape": return 41;
      case "Backspace": return 42;
      case "Tab": return 43;
      case "Space": return 44;
      case "Minus": return 45;
      case "Equal": return 46;
      case "BracketLeft": return 47;
      case "BracketRight": return 48;
      case "Backslash": return 49;
      case "Semicolon": return 51;
      case "Quote": return 52;
      case "Backquote": return 53;
      case "Comma": return 54;
      case "Period": return 55;
      case "Slash": return 56;
      case "CapsLock": return 57;
      case "Insert": return 73;
      case "Home": return 74;
      case "PageUp": return 75;
      case "Delete": return 76;
      case "End": return 77;
      case "PageDown": return 78;
      case "ArrowRight": return 79;
      case "ArrowLeft": return 80;
      case "ArrowDown": return 81;
      case "ArrowUp": return 82;
      default: return 0;
    }
  }
}
