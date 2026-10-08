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
    check(find(activity.getWindow().getDecorView(), "ADB 授权") != null, "settings content");
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
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
      if (options != null && options.containsKey("videoPort")) {
        result.putString("stream", VideoCodecProbe.run(this, activity, options) + "\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      long end = System.currentTimeMillis() + 10000;
      while (LinkService.instance == null && System.currentTimeMillis() < end) Thread.sleep(50);
      LinkService service = LinkService.instance;
      check(service != null, "service started");
      if(options!=null&&options.containsKey("fullscreenGestureProbe")){result.putString("stream",FullscreenGestureProbe.run(this)+"\n");finish(Activity.RESULT_OK,result);return;}
      if(options!=null&&options.containsKey("fullscreenUiProbe")){result.putString("stream",FullscreenGestureProbe.runUi(this,activity)+"\n");finish(Activity.RESULT_OK,result);return;}
      if(options!=null&&options.containsKey("inputOnlyTouchpadProbe")){result.putString("stream",InputOnlyTouchpadProbe.run(this,service)+"\n");finish(Activity.RESULT_OK,result);return;}
      if(options!=null&&options.containsKey("touchpadCenterProbe")){result.putString("stream",TouchpadCenterProbe.run(this,activity,service)+"\n");finish(Activity.RESULT_OK,result);return;}
      if(options!=null&&options.containsKey("remoteInteractionProbe")){RemoteInteractionProbe.run(this,activity,service);result.putString("stream","PASS: existing remote viewport, cursor-follow and control lifecycle regressions\n");finish(Activity.RESULT_OK,result);return;}
      if(options!=null&&options.containsKey("groupConsentProbe")){result.putString("stream",GroupConsentProbe.run(service)+"\n");finish(Activity.RESULT_OK,result);return;}
      if(options!=null&&options.containsKey("adaptiveTransportProbe")){result.putString("stream",AdaptiveTransportProbe.run()+"\n");finish(Activity.RESULT_OK,result);return;}
      if(options!=null&&options.containsKey("multiComputerPorts")){result.putString("stream",MultiComputerProbe.run(this,activity,service,options)+"\n");finish(Activity.RESULT_OK,result);return;}
      if(options!=null&&options.containsKey("pairJoinCode")){result.putString("stream",PairJoinProbe.run(this,service,options)+"\n");finish(Activity.RESULT_OK,result);return;}
      if(options!=null&&options.containsKey("udpProbePort")){result.putString("stream",UdpBroadcastProbe.run(this,options)+"\n");finish(Activity.RESULT_OK,result);return;}
      if(options!=null&&options.containsKey("adbPermissionProbe")){result.putString("stream",AdbPermissionProbe.run(this,activity,service)+"\n");finish(Activity.RESULT_OK,result);return;}
      if(options!=null&&options.containsKey("edgeGlowProbe")){result.putString("stream",EdgeGlowProbe.run(this,service)+"\n");finish(Activity.RESULT_OK,result);return;}
      if(options!=null&&options.containsKey("quicProbe")){result.putString("stream",QuicProbe.run(service)+"\n");finish(Activity.RESULT_OK,result);return;}
      if(options!=null&&options.containsKey("rtcQueue")){
        RtcVideo rtc=new RtcVideo(service);
        try{
          rtc.assembler.needKey=false;VideoWire.Frame key=new VideoWire.Frame(new byte[20],320,240,true,true);
          rtc.enqueue(key);for(int i=0;i<3;i++)rtc.enqueue(new VideoWire.Frame(new byte[20],320,240,true,false));
          check(rtc.frames.size()==4&&!rtc.assembler.needKey,"short stall retains H264 reference chain");
          VideoWire.Frame ping=new VideoWire.Frame(new byte[0],1,1,false,false);ping.heartbeat=true;rtc.enqueue(ping);check(rtc.frames.size()==4,"heartbeat does not replace queued video");
          for(int i=0;i<4;i++)rtc.next();rtc.enqueue(key);Thread.sleep(170);rtc.enqueue(new VideoWire.Frame(new byte[20],320,240,true,false));check(rtc.frames.isEmpty()&&rtc.assembler.needKey,"long backlog drops chain and requests IDR");
          rtc.enqueue(key);check(rtc.next().key,"IDR resumes playback");result.putString("stream","PASS: short stalls, heartbeat, bounded backlog and IDR recovery\n");
        }finally{rtc.close();}finish(Activity.RESULT_OK,result);return;
      }
      if(options!=null&&options.containsKey("stagingSmokePort")){result.putString("stream",StagingProbe.run(this,activity,service,options)+"\n");finish(Activity.RESULT_OK,result);return;}
      if(options!=null && options.containsKey("clipboardSmokePort")){result.putString("stream",ClipboardProbe.run(this,activity,service,options)+"\n");finish(Activity.RESULT_OK,result);return;}
      if (options != null && options.containsKey("pairFlowPort")) {
        result.putString("stream", PairingFlowProbe.run(this, activity, service, options) + "\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      if (options != null && options.containsKey("rootDenied")) {
        boolean denied = false;
        try {
          service.setRootMode(true);
        } catch (Exception expected) {
          denied = true;
        }
        check(
            denied && !service.rootActive() && !service.rootEnabled(),
            "rejected su must not claim root authorization");
        click(activity.getWindow().getDecorView(), "设置页面");
        check(
            find(activity.getWindow().getDecorView(), "ADB 授权") != null,
            "normal permissions remain available after root denial");
        result.putString(
            "stream",
            "PASS: denied application su leaves Root disabled and normal permission settings"
                + " visible.\n");
        finish(Activity.RESULT_OK, result);
        return;
      }
      RecoveryProbe.run(this, service);
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
      // Missing-permission scenarios must stay deterministic after the real ADB smoke
      service.enableAdbAuthorization();
      // test: its persistent helper deliberately survives process restarts. Keep the
      // bootstrap server, but use a disposable binding unknown to that helper.
      service.shell.close();
      android.content.SharedPreferences helperPrefs = service.getSharedPreferences("adb-helper", 0);
      String savedSecret = helperPrefs.getString("secret", null);
      int savedPort = helperPrefs.getInt("port", 0);
      helperPrefs.edit().putString("secret", Wire.token()).putInt("port", 0).commit();
      try {
        service.shell = new ShellChannel(service);
      } finally {
        android.content.SharedPreferences.Editor restore = helperPrefs.edit();
        if (savedSecret == null) restore.remove("secret");
        else restore.putString("secret", savedSecret);
        restore.putInt("port", savedPort).commit();
      }
      RemoteInteractionProbe.run(this, activity, service);
      HelperRecoveryProbe.run(service);
      // Subsequent cases represent a never-authorized device rather than a short disconnect.
      Field success = ShellChannel.class.getDeclaredField("lastSuccess");
      success.setAccessible(true);
      success.setLong(service.shell, 0);
      GroupControlProbe.run(this, activity);
      checkPages(activity);
      // This isolated service/UI test injects a peer without a TCP transport. Stop network
      // heartbeat tasks so they cannot close the fixture while screenshots are captured.
      // The entire service is stopped below; no production scheduling is changed.
      ((java.util.concurrent.ScheduledExecutorService) field(service, "clock")).shutdownNow();
      SettingsProbe.run(this, activity, service);
      service.setAllowed("input", false);
      service.setAllowed("screen", false);
      service.setAllowed("clipboard", false);
      service.permissionNotice.reset();
      service.consent.reset();
      peer = new Wire(new Socket(), Wire.obj(), new byte[32], service);
      peer.id = "11111111-1111-4111-8111-111111111111";
      // A previous run deliberately stops this same fixture peer. This case models
      // a new requester, so remove only its persisted stop marker before first consent.
      ((java.util.Set<?>) field(service,"pausedPeers")).remove(peer.id);
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
      check(service.consent.peek() == null, "first group control never asks for application consent");
      JSONObject initial = (JSONObject) method.invoke(service, peer, "permission-status", Wire.obj());
      for (String key : DeviceConsent.KEYS)
        check(initial.getJSONObject("permissions").getJSONObject(key).getBoolean("peerAllowed"), "group default allows " + key);
      check(!initial.getJSONObject("permissions").getJSONObject("input").getBoolean("systemGranted"), "group default never replaces system permission");
      check(((JSONObject) method.invoke(service, peer, "permission-request", Wire.obj())).getBoolean("ready"), "already allowed requests do not prompt");
      service.revokeConsent(peer.id);
      check(!service.consent.allowed(peer.id, "input"), "explicit revocation overrides the default");
      method.invoke(service, peer, "permission-request", Wire.obj("permissions", new org.json.JSONArray().put("input")));
      check(service.consent.peek() != null, "only an explicit request to restore revoked access needs a decision");
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
      check(!find(consent.getWindow().getDecorView(), "剪贴板文件共享").isShown(), "advanced permissions initially collapsed");
      check(find(consent.getWindow().getDecorView(), "远程关机") == null, "phone excludes shutdown");
      click(consent.getWindow().getDecorView(), "高级选项");
      check(find(consent.getWindow().getDecorView(), "剪贴板文件共享").isShown(), "advanced permissions expand");
      capture("consent-advanced");
      click(consent.getWindow().getDecorView(), "剪贴板文件共享");
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
          projectionMonitor.getHits() == 0,
          "input-only approval does not request screen permission");
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
      waitForIdleSync();
      View banner=(View)field(activity,"noticeBox"),dock=(View)field(activity,"navigation");
      int[] bannerAt=new int[2],dockAt=new int[2];banner.getLocationOnScreen(bannerAt);dock.getLocationOnScreen(dockAt);
      check(banner.getHeight()>0 && bannerAt[1]<dockAt[1] && bannerAt[1]+banner.getHeight()>dockAt[1],"controlled banner protrudes above and behind Dock");
      View stop=find(activity.getWindow().getDecorView(), "中止任务");
      int[] stopAt=new int[2];stop.getLocationOnScreen(stopAt);
      check(stopAt[1]+stop.getHeight()<=dockAt[1],"stop action stays above Dock and remains accessible");
      capture("controlled-dock");
      click(activity.getWindow().getDecorView(), "中止任务");
      check(owner.get(service) == null, "local stop releases owner");
      denied(method, service, peer, "acquire", Wire.obj());
      method.invoke(service, peer, "adbBootstrap", Wire.obj());
      check(owner.get(service)==null,"preparing helper never resumes stopped control");
      boolean providerDenied=false;
      try{getTargetContext().getContentResolver().call(android.net.Uri.parse("content://com.lanlink.app.adbsetup"),"bootstrap",null,null);}catch(SecurityException expected){providerDenied=true;}
      check(providerDenied,"ordinary application UID cannot access physical ADB setup secret");
      check(
          ((java.util.Set<?>) field(service, "pausedPeers")).contains(peer.id),
          "automatic reconnect blocked after local stop");
      JSONObject stoppedReport=(JSONObject)method.invoke(service,peer,"permission-status",Wire.obj());
      check(stoppedReport.getJSONObject("permissions").getJSONObject("input").getBoolean("peerAllowed"),"stopping keeps the input grant visible");
      check(!stoppedReport.getJSONObject("permissions").getJSONObject("clipboard").getBoolean("peerAllowed"),"stopping preserves partial denials");
      // The fixture has no system input helper, so prepare still fails at OS readiness,
      // but a new explicit session must not ask for the remembered device grant again.
      denied(method,service,peer,"prepare",Wire.obj("newSession",true));
      check(!((java.util.Set<?>)field(service,"pausedPeers")).contains(peer.id),"new session clears only the stop marker");
      check(service.consent.peek()==null && service.consent.allowed(peer.id,"input") && !service.consent.allowed(peer.id,"clipboard"),"new session retains grants without another approval");
      DeviceConsent afterStop=new DeviceConsent(getTargetContext().getSharedPreferences("identity",0).getString("device_grants","{}"));
      check(afterStop.allowed(peer.id,"input") && !afterStop.allowed(peer.id,"clipboard"),"remembered grants survive stop and restart");
      service.permissionNotice.reset();
      service.revokeAdbAuthorization();
      method.invoke(service,peer,"adb-permission-request",Wire.obj());
      runOnMainSync(()->{try{refresh.invoke(currentActivity);}catch(Exception e){throw new RuntimeException(e);}});
      Dialog adbDialog=(Dialog)field(activity,"permissionDialog");
      check(adbDialog!=null && adbDialog.isShowing(),"revoked ADB request opens phone confirmation");
      check(service.adbRevoked(),"request must not restore permission automatically");
      capture("adb-allow-request");
      click(adbDialog.getWindow().getDecorView(),"允许 ADB 授权");
      check(!service.adbRevoked() && owner.get(service)==null,"phone confirmation permits setup without granting control");
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
              + " Node/Android group crypto; settings rename, authorization revoke, per-device"
              + " grants, persistence and immediate ownership release\n");
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
