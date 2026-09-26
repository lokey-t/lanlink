package com.lanlink.app;

import android.app.*;
import android.content.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.Socket;
import org.json.JSONObject;

/**
 * Run only on a disposable emulator: verifies real service gates without granting OS permissions.
 */
public final class PermissionFlowTest extends Instrumentation {
  private Bundle options;

  private View find(View root, String label) {
    if (label.contentEquals(
            root.getContentDescription() == null ? "" : root.getContentDescription())
        || root instanceof TextView && label.contentEquals(((TextView) root).getText()))
      return root;
    if (root instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) root;
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = find(group.getChildAt(i), label);
        if (found != null) return found;
      }
    }
    return null;
  }

  private void click(View root, String label) {
    runOnMainSync(
        () -> {
          View target = find(root, label);
          check(target != null && target.isShown(), "visible control: " + label);
          while (!target.isClickable() && target.getParent() instanceof View)
            target = (View) target.getParent();
          if (target instanceof CompoundButton) {
            boolean before = ((CompoundButton) target).isChecked();
            target.performClick();
            check(((CompoundButton) target).isChecked() != before, "toggle: " + label);
          } else check(target.performClick(), "click: " + label);
        });
  }

  private Object field(Object target, String name) throws Exception {
    Field f = target.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return f.get(target);
  }

  private void capture(String name) throws Exception {
    Thread.sleep(650);
    android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot();
    check(bitmap != null, "screenshot: " + name);
    File folder = new File(getTargetContext().getExternalFilesDir(null), "ui-check");
    folder.mkdirs();
    try (FileOutputStream out = new FileOutputStream(new File(folder, name + ".png"))) {
      check(
          bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out), "save screenshot");
    } finally {
      bitmap.recycle();
    }
  }

  private void checkPages(Activity activity) throws Exception {
    capture("devices");
    click(activity.getWindow().getDecorView(), "设置页面");
    capture("settings");
    check(find(activity.getWindow().getDecorView(), "远程输入") != null, "settings content");
    click(activity.getWindow().getDecorView(), "设备页面");
    click(activity.getWindow().getDecorView(), "添加设备");
    Dialog pair = (Dialog) field(activity, "pairDialog");
    check(pair != null && pair.isShowing(), "pair page opened");
    View page = pair.getWindow().getDecorView();
    capture("pairing");
    check(find(page, "二维码尚未显示") != null, "QR initially hidden");
    click(page, "查看二维码");
    check(find(page, "本机配对二维码") != null, "QR revealed on request");
    click(page, "隐藏二维码");
    click(page, "手动输入");
    click(page, LinkService.instance.groupEstablished() ? "解散当前组并加入其他组" : "配对并加入");
    View entry = findEdit(page);
    check(entry != null && ((EditText) entry).getError() != null, "empty code rejected locally");
    capture("join-group");
    runOnMainSync(pair::dismiss);
  }

  private View findEdit(View root) {
    if (root instanceof EditText) return root;
    if (root instanceof ViewGroup) {
      ViewGroup g = (ViewGroup) root;
      for (int i = 0; i < g.getChildCount(); i++) {
        View v = findEdit(g.getChildAt(i));
        if (v != null) return v;
      }
    }
    return null;
  }

  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    options = args;
    start();
  }

  private void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  private void denied(
      Method method, LinkService service, Wire peer, String operation, JSONObject args)
      throws Exception {
    try {
      method.invoke(service, peer, operation, args);
      throw new AssertionError(operation + " unexpectedly allowed");
    } catch (InvocationTargetException expected) {
      check(expected.getCause() instanceof java.io.IOException, "expected permission denial");
    }
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    Activity activity = null;
    Wire peer = null;
    if (!Build.HARDWARE.equals("ranchu") && !Build.HARDWARE.equals("goldfish")) {
      result.putString("stream", "This test only runs on a disposable Android emulator.\n");
      finish(Activity.RESULT_CANCELED, result);
      return;
    }
    try {
      activity =
          startActivitySync(
              new Intent(getTargetContext(), MainActivity.class)
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      if (options != null && options.containsKey("videoPort")) {
        result.putString("stream", VideoCodecProbe.run(this, activity, options) + "\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      long end = System.currentTimeMillis() + 10000;
      while (LinkService.instance == null && System.currentTimeMillis() < end) Thread.sleep(50);
      LinkService service = LinkService.instance;
      check(service != null, "service started");
      if (options != null && options.containsKey("touchpadPort")) {
        result.putString(
            "stream", TouchpadNetworkProbe.run(this, activity, service, options) + "\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      if (options != null && options.containsKey("adbSmokePort")) {
        result.putString("stream", AdbInputProbe.run(this, activity, service, options) + "\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      RemoteInteractionProbe.run(this, activity, service);
      GroupControlProbe.run(this, activity);
      checkPages(activity);
      // This isolated service/UI test injects a peer without a TCP transport. Stop network
      // heartbeat tasks so they cannot close the fixture while screenshots are captured.
      // The entire service is stopped below; no production scheduling is changed.
      ((java.util.concurrent.ScheduledExecutorService) field(service, "clock")).shutdownNow();
      service.setAllowed("input", false);
      service.setAllowed("screen", false);
      service.setAllowed("clipboard", false);
      service.permissionNotice.reset();
      service.consent.reset();
      peer = new Wire(new Socket(), Wire.obj(), new byte[32], service);
      peer.id = "11111111-1111-4111-8111-111111111111";
      peer.info = Wire.obj("name", "测试电脑", "platform", "windows");
      Method method =
          LinkService.class.getDeclaredMethod("handle", Wire.class, String.class, JSONObject.class);
      method.setAccessible(true);
      denied(method, service, peer, "acquire", Wire.obj());
      denied(method, service, peer, "adbBootstrap", Wire.obj());
      denied(method, service, peer, "adbStatus", Wire.obj());
      check(
          service.permissionNotice.peek(System.currentTimeMillis()) == null,
          "unknown peer cannot create permission prompt");
      service.peers.put(peer.id, peer);
      denied(method, service, peer, "acquire", Wire.obj());
      check(service.consent.peek() != null, "first control requires a local decision");
      Activity currentActivity = activity;
      Method refresh = MainActivity.class.getDeclaredMethod("update");
      refresh.setAccessible(true);
      runOnMainSync(
          () -> {
            try {
              refresh.invoke(currentActivity);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
      capture("first-control-consent");
      Dialog consent = (Dialog) field(activity, "consentDialog");
      check(consent != null && consent.isShowing(), "first control popup visible");
      click(consent.getWindow().getDecorView(), "剪贴板与文件");
      Intent projection =
          getTargetContext()
              .getSystemService(android.media.projection.MediaProjectionManager.class)
              .createScreenCaptureIntent();
      ActivityMonitor projectionMonitor =
          addMonitor(
              projection.getComponent().getClassName(),
              new ActivityResult(Activity.RESULT_CANCELED, null),
              true);
      click(consent.getWindow().getDecorView(), "同意并记住");
      check(
          projectionMonitor.getHits() == 1, "approval requests OS screen permission automatically");
      removeMonitor(projectionMonitor);
      check(
          service.consent.peek() == null && service.consent.allowed(peer.id, "input"),
          "consent remembered");
      check(!service.consent.allowed(peer.id, "clipboard"), "individual permission denied");
      DeviceConsent saved =
          new DeviceConsent(
              getTargetContext()
                  .getSharedPreferences("identity", 0)
                  .getString("device_grants", "{}"));
      check(
          saved.allowed(peer.id, "input") && !saved.allowed(peer.id, "clipboard"),
          "grant persisted");
      JSONObject bootstrap = (JSONObject) method.invoke(service, peer, "adbBootstrap", Wire.obj());
      check(
          bootstrap.getString("apkPath").equals(service.getApplicationInfo().sourceDir)
              && bootstrap.getInt("port") > 0
              && bootstrap.getString("secret").startsWith("lanlink1:"),
          "consented desktop can bootstrap without OS input permission");
      JSONObject adbStatus = (JSONObject) method.invoke(service, peer, "adbStatus", Wire.obj());
      check(!adbStatus.getBoolean("ready"), "ADB status does not claim absent helper");
      service.setAllowed("input", false);
      denied(method, service, peer, "adbBootstrap", Wire.obj());
      service.setAllowed("input", true);
      service.permissionNotice.reset();
      denied(method, service, peer, "acquire", Wire.obj("fallbackAccepted", true));
      check(
          !service.capabilities().optBoolean("input"),
          "accessibility fallback removed; ADB required");
      service.permissionNotice.reset();
      denied(method, service, peer, "clipboardGet", Wire.obj());
      check(service.consent.peek() == null, "denied feature does not ask again");
      denied(method, service, peer, "acquire", Wire.obj());
      PermissionNotice.Request first = service.permissionNotice.peek(System.currentTimeMillis());
      check(first != null && first.needs.contains("input"), "input request prompts phone");
      denied(method, service, peer, "acquire", Wire.obj());
      check(
          service.permissionNotice.peek(System.currentTimeMillis()) == first,
          "retries do not flood UI");
      check(
          service.allowed("input") && service.allowed("screen") && !service.allowed("clipboard"),
          "consent enables only selected app features");
      denied(method, service, peer, "screen", Wire.obj());
      check(
          service.permissionNotice.peek(System.currentTimeMillis()).needs.contains("screen"),
          "screen request prompts phone");
      check(!service.screenReady(), "request did not start projection");
      runOnMainSync(
          () -> {
            try {
              refresh.invoke(currentActivity);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
      capture("permission-request");
      Dialog help = (Dialog) field(activity, "permissionDialog");
      check(help != null && help.isShowing(), "permission request automatically opens popup");
      check(
          (help.getWindow().getAttributes().gravity & Gravity.VERTICAL_GRAVITY_MASK)
              == Gravity.CENTER_VERTICAL,
          "permission popup centered");
      check(
          find(activity.getWindow().getDecorView(), "查看并开启") == null,
          "inline permission banner removed");
      capture("permission-guide");
      java.util.Set<?> sheets = (java.util.Set<?>) field(activity, "sheets");
      check(
          !sheets.isEmpty(),
          "permission guide opened; pending="
              + service.permissionNotice.peek(System.currentTimeMillis()));

      runOnMainSync(
          () -> {
            try {
              refresh.invoke(currentActivity);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
      check(
          field(activity, "permissionDialog") == help,
          "refresh reuses permission popup without stacking");
      check(
          find(help.getWindow().getDecorView(), "允许远程输入 · 已授权") != null,
          "granted state shown in place");
      capture("permission-granted-state");
      check(find(help.getWindow().getDecorView(), "打开开发者选项") != null, "ADB settings entry");
      check(find(help.getWindow().getDecorView(), "无障碍备用模式") == null, "no accessibility fallback");
      check(find(help.getWindow().getDecorView(), "授权本次屏幕共享") != null, "projection entry");
      capture("permission-guide");
      ActivityMonitor monitor =
          addMonitor(
              new IntentFilter(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), null, true);
      click(help.getWindow().getDecorView(), "打开开发者选项");
      check(monitor.getHits() == 1, "developer settings intent sent");
      removeMonitor(monitor);
      runOnMainSync(help::dismiss);
      waitForIdleSync();
      check(
          service.permissionNotice.peek(System.currentTimeMillis()) == null,
          "closing popup dismisses request");
      service.setAllowed("input", true);
      denied(method, service, peer, "acquire", Wire.obj());
      check(!service.capabilities().optBoolean("input"), "app toggle cannot replace OS permission");
      Field owner = LinkService.class.getDeclaredField("owner");
      owner.setAccessible(true);
      check(owner.get(service) == null, "denial never grants ownership");
      service.permissionNotice.reset();
      Thread.sleep(3500);
      Method openViewer = MainActivity.class.getDeclaredMethod("openViewer", Wire.class);
      openViewer.setAccessible(true);
      final Wire fixturePeer = peer;
      runOnMainSync(
          () -> {
            try {
              openViewer.invoke(currentActivity, fixturePeer);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
      capture("remote-touchpad");
      Dialog remote = (Dialog) field(activity, "viewer");
      check(
          find(remote.getWindow().getDecorView(), "键盘") != null
              && find(remote.getWindow().getDecorView(), "功能键") != null,
          "compact remote toolbar");
      click(remote.getWindow().getDecorView(), "功能键");
      capture("remote-function-keys");
      runOnMainSync(remote::dismiss);
      owner.set(service, peer.id);
      runOnMainSync(
          () -> {
            try {
              refresh.invoke(currentActivity);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
      check(service.controlledBy() != null, "controlled identity visible");
      click(activity.getWindow().getDecorView(), "结束控制");
      check(owner.get(service) == null, "local stop releases owner");
      denied(method, service, peer, "acquire", Wire.obj());
      denied(method, service, peer, "adbBootstrap", Wire.obj());
      check(
          ((java.util.Set<?>) field(service, "pausedPeers")).contains(peer.id),
          "automatic reconnect blocked after local stop");
      service.closed(peer);
      check(
          service.permissionNotice.peek(System.currentTimeMillis()) == null,
          "disconnect clears prompt");
      result.putString(
          "stream",
          "\n"
              + "PASS: authenticated requests, denial, prompt merging, cooldown, OS permission"
              + " gating, ownership and disconnect cleanup; native pages, QR reveal, invalid code,"
              + " permission card, guide, automatic app permissions, system permission entry,"
              + " target stop, touchpad, system keyboard text, viewport pan/pinch,"
              + " reciprocal-control races, ADB bootstrap consent, ADB-only input gating and"
              + " Node/Android group crypto\n");
      finish(Activity.RESULT_OK, result);
    } catch (Throwable failure) {
      result.putString("stream", "\nFAIL: " + android.util.Log.getStackTraceString(failure) + "\n");
      finish(Activity.RESULT_CANCELED, result);
    } finally {
      if (peer != null) peer.close();
      if (activity != null) {
        Activity a = activity;
        runOnMainSync(a::finish);
      }
      getTargetContext().stopService(new Intent(getTargetContext(), LinkService.class));
    }
  }
}
