package com.lanlink.app;

import android.app.*;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Disposable-emulator checks of the native settings and effective per-device grants. */
final class SettingsProbe {
  private final Instrumentation test;
  private final Activity activity;
  private final LinkService service;

  private SettingsProbe(Instrumentation test, Activity activity, LinkService service) {
    this.test = test;
    this.activity = activity;
    this.service = service;
  }

  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

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
    test.runOnMainSync(
        () -> {
          View target = find(root, label);
          check(target != null, "settings control: " + label);
          while (!target.isClickable() && target.getParent() instanceof View)
            target = (View) target.getParent();
          if (target instanceof CompoundButton) {
            boolean before = ((CompoundButton) target).isChecked();
            target.performClick();
            check(((CompoundButton) target).isChecked() != before, "toggle " + label);
          } else check(target.performClick(), "click " + label);
        });
    test.waitForIdleSync();
  }

  private Object field(Object object, String name) throws Exception {
    Field f = object.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return f.get(object);
  }

  private void page(String name) throws Exception {
    Field field = MainActivity.class.getDeclaredField("page");
    field.setAccessible(true);
    Method render = MainActivity.class.getDeclaredMethod("renderPage");
    render.setAccessible(true);
    test.runOnMainSync(
        () -> {
          try {
            field.set(activity, name);
            render.invoke(activity);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    test.waitForIdleSync();
  }

  private Dialog sheet() throws Exception {
    for (Object value : (Set<?>) field(activity, "sheets"))
      if (((Dialog) value).isShowing()) return (Dialog) value;
    throw new AssertionError("sheet not open");
  }

  private void capture(String name) throws Exception {
    Thread.sleep(400);
    Bitmap image = test.getUiAutomation().takeScreenshot();
    check(image != null, "settings screenshot");
    File folder = new File(activity.getExternalFilesDir(null), "ui-check");
    folder.mkdirs();
    try (FileOutputStream out = new FileOutputStream(new File(folder, name + ".png"))) {
      image.compress(Bitmap.CompressFormat.PNG, 100, out);
    } finally {
      image.recycle();
    }
  }

  static void run(Instrumentation test, Activity activity, LinkService service) throws Exception {
    new SettingsProbe(test, activity, service).run();
  }

  private void run() throws Exception {
    SharedPreferences prefs = service.getSharedPreferences("identity", 0),
        helper = service.getSharedPreferences("adb-helper", 0);
    String known = prefs.getString("known_devices", "{}"),
        saved = prefs.getString("device_grants", "{}"),
        name = service.name;
    boolean revoked = service.adbRevoked(),
        adb = helper.getBoolean("adb_authorized", false),
        auth = helper.getBoolean("authorized", false),
        input = service.allowed("input");
    final String a = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        b = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
        c = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
    org.json.JSONObject priorMember = service.group.data.getJSONObject("members").optJSONObject(c);
    try {
      prefs
          .edit()
          .putString(
              "known_devices",
              Wire.obj(
                      a,
                      Wire.obj("name", "工作电脑", "platform", "windows"),
                      b,
                      Wire.obj("name", "MacBook", "platform", "darwin"))
                  .toString())
          .putBoolean("adb_revoked", false)
          .commit();
      helper.edit().putBoolean("adb_authorized", false).commit();
      service.consent = new DeviceConsent("{}");
      // A pending Root manager decision survives Activity refresh and cannot queue another switch.
      java.util.concurrent.atomic.AtomicBoolean changing =
          (java.util.concurrent.atomic.AtomicBoolean) field(service, "rootChanging");
      boolean savedRoot = service.rootEnabled();
      try {
        prefs.edit().putBoolean("root_mode", true).commit();
        check(
            service.capabilities().optBoolean("videoH264Only"),
            "Root reports its H.264 requirement even during helper recovery");
        try {
          new VideoHost(service, null, false);
          throw new AssertionError("Root accepted a JPEG-only session");
        } catch (IOException expected) {
          check(
              expected.getMessage().startsWith("VIDEO_H264_REQUIRED:"),
              "Root rejection tells the viewer to retry H.264");
        }
        page("settings");
        click(activity.getWindow().getDecorView(), "切换 Root 模式");
        check(
            service.rootEnabled() && ((Set<?>) field(activity, "sheets")).isEmpty(),
            "selected Root segment is a no-op, never a revoke dialog");
        changing.set(true);
        page("settings");
        check(
            !find(activity.getWindow().getDecorView(), "切换非 Root 模式").isEnabled(),
            "both segments disabled during service-level switch");
        long start = SystemClock.elapsedRealtime();
        try {
          service.setRootMode(false);
          throw new AssertionError("duplicate mode change queued");
        } catch (IOException expected) {
          check(
              SystemClock.elapsedRealtime() - start < 500, "duplicate switch rejected immediately");
        }
        check(service.rootEnabled(), "duplicate switch never disables Root");
      } finally {
        changing.set(false);
        prefs.edit().putBoolean("root_mode", savedRoot).commit();
      }
      page("settings");
      View decor = activity.getWindow().getDecorView();
      for (String label :
          new String[] {"设备名称", "权限使用模式", "ADB 授权", "组设备授权管理", "使用说明"})
        check(find(decor, label) != null, "ordered settings entry " + label);
      capture("settings-0313");
      boolean oldGlow=prefs.getBoolean("edge_glow",false),oldOverlay=android.provider.Settings.canDrawOverlays(activity);
      try{
        prefs.edit().putBoolean("edge_glow",false).commit();
        overlayPermission(false);page("settings");
        check(!((Switch)find(decor,"切换键鼠边缘光效")).isChecked(),"phone glow defaults off");
        test.runOnMainSync(()->find(decor,"切换键鼠边缘光效").performClick());test.waitForIdleSync();
        AlertDialog glowGuide=(AlertDialog)field(activity,"edgeGlowPermissionDialog");
        check(glowGuide!=null && glowGuide.isShowing() && !prefs.getBoolean("edge_glow",false),"missing overlay permission explains use without enabling glow");
        test.runOnMainSync(()->glowGuide.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
        overlayPermission(true);page("settings");click(decor,"切换键鼠边缘光效");
        check(prefs.getBoolean("edge_glow",false) && !glowGuide.isShowing(),"granted overlay enables directly without prompting");
        final EdgeGlow[] glow={null};test.runOnMainSync(()->glow[0]=EdgeGlow.feedback(activity,null,"countdown","left",1));
        check(glow[0]!=null,"authorized edge overlay appears");
        WindowManager.LayoutParams params=(WindowManager.LayoutParams)glow[0].getLayoutParams();
        check((params.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)!=0 && (params.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)!=0,"glow never intercepts mouse or keyboard");
        test.runOnMainSync(glow[0]::close);
        prefs.edit().putBoolean("edge_glow",false).commit();test.runOnMainSync(()->check(EdgeGlow.feedback(activity,null,"countdown","left",1)==null,"disabled glow creates no overlay"));
      }finally{overlayPermission(oldOverlay);prefs.edit().putBoolean("edge_glow",oldGlow).commit();page("settings");}
      click(decor, "设备名称");
      Dialog rename = sheet();
      EditText edit = (EditText) find(rename.getWindow().getDecorView(), "设备名称输入");
      test.runOnMainSync(() -> edit.setText("我的测试手机"));
      click(rename.getWindow().getDecorView(), "保存名称");
      long until = SystemClock.elapsedRealtime() + 3000;
      while (rename.isShowing() && SystemClock.elapsedRealtime() < until) Thread.sleep(30);
      check(
          service.name.equals("我的测试手机") && prefs.getString("name", "").equals(service.name),
          "name persists");
      check(find(decor, "在其他应用上层显示") == null, "removed permission absent");

      click(decor, "ADB 授权");
      Dialog guide = sheet();
      check(find(guide.getWindow().getDecorView(), "打开开发者选项") != null, "ADB how-to settings entry");
      test.runOnMainSync(guide::dismiss);
      helper.edit().putBoolean("adb_authorized", true).commit();
      page("settings");
      click(decor, "ADB 授权");
      Dialog revoke = sheet();
      check(
          find(revoke.getWindow().getDecorView(), "解除授权") != null, "authorized row offers revoke");
      capture("adb-revoke-0313");
      click(revoke.getWindow().getDecorView(), "解除授权");
      until = SystemClock.elapsedRealtime() + 3000;
      while (revoke.isShowing() && SystemClock.elapsedRealtime() < until) Thread.sleep(30);
      check(
          service.adbRevoked() && !service.adbAuthorized() && !service.shell.recovering(),
          "revocation blocks authorization and transient recovery");
      click(decor, "组设备授权管理");
      synchronized(service.group) {
        service.group.data.getJSONObject("members").put(c,Wire.obj("id",c,"name","尚未连接的电脑","platform","windows"));
      }
      page("permissions");
      check(!service.peers.containsKey(c) && !service.knownDevices().has(c),"fixture is only a group member, never connected");
      check(find(decor,"尚未连接的电脑 键鼠标操作")!=null,"offline group member can be configured before first connection");
      check(!service.consent.hasGrant(c),"viewing the page does not silently grant access");
      click(decor,"尚未连接的电脑 键鼠标操作");
      until=SystemClock.elapsedRealtime()+3000;
      while(!service.consent.allowed(c,"input") && SystemClock.elapsedRealtime()<until)Thread.sleep(30);
      service.setDevicePermission(c,"screen",true);
      service.consent.require(c,"尚未连接的电脑","input");
      service.consent.require(c,"尚未连接的电脑","screen");
      check(service.consent.peek()==null,"pre-authorized device does not need first-control confirmation");
      DeviceConsent preauthorized=new DeviceConsent(prefs.getString("device_grants","{}"));
      check(preauthorized.allowed(c,"input") && preauthorized.allowed(c,"screen") && !preauthorized.allowed(c,"clipboard"),"offline permission selection persists with unselected features denied");
      check(!service.authorizationDevices().has(service.deviceId),"authorization list excludes this phone");
      try{service.setDevicePermission("not-a-group-device","input",true);throw new AssertionError("nonmember accepted");}catch(IOException expected){}
      page("permissions");
      capture("group-preauthorization");
      check(find(decor, "工作电脑 键鼠标操作") != null, "per-device switch");
      click(decor, "工作电脑 键鼠标操作");
      until = SystemClock.elapsedRealtime() + 3000;
      while (!service.consent.allowed(a, "input") && SystemClock.elapsedRealtime() < until)
        Thread.sleep(30);
      service.setDevicePermission(b, "screen", true);
      check(
          service.consent.allowed(a, "input")
              && !service.consent.allowed(a, "screen")
              && service.consent.allowed(b, "screen")
              && !service.consent.allowed(b, "input"),
          "isolated device permissions");
      DeviceConsent reloaded = new DeviceConsent(prefs.getString("device_grants", "{}"));
      check(
          reloaded.allowed(a, "input") && reloaded.allowed(b, "screen"),
          "grants persist on restart");
      page("permissions");
      capture("group-permissions-0313");
      Field owner = LinkService.class.getDeclaredField("owner");
      owner.setAccessible(true);
      owner.set(service, a);
      service.setDevicePermission(a, "input", false);
      check(owner.get(service) == null, "input revocation releases active ownership");
      @SuppressWarnings("unchecked")
      Map<String, Long> viewers = (Map<String, Long>) field(service, "screenViewers");
      viewers.put(b, System.currentTimeMillis() + 4000);
      service.setDevicePermission(b, "screen", false);
      check(!viewers.containsKey(b), "screen revocation ends lease");
      try {
        service.consent.require(a, "工作电脑", "input");
        throw new AssertionError("revoked control accepted");
      } catch (IOException expected) {
        check(
            expected.getMessage().startsWith("CONSENT_DENIED"),
            "explicit denial without repeated prompt");
      }
      check(service.consent.peek() == null, "revocation never prompts again");
    } finally {
      synchronized(service.group){if(priorMember==null)service.group.data.getJSONObject("members").remove(c);else service.group.data.getJSONObject("members").put(c,priorMember);}
      for (Object value : new ArrayList<>((Set<?>) field(activity, "sheets")))
        test.runOnMainSync(((Dialog) value)::dismiss);
      prefs
          .edit()
          .putString("known_devices", known)
          .putString("device_grants", saved)
          .putBoolean("adb_revoked", revoked)
          .putBoolean("allow_input", input)
          .commit();
      helper.edit().putBoolean("adb_authorized", adb).putBoolean("authorized", auth).commit();
      service.consent = new DeviceConsent(saved);
      service.rename(name);
      page("devices");
    }
  }

  private void overlayPermission(boolean granted)throws Exception{
    try(android.os.ParcelFileDescriptor pipe=test.getUiAutomation().executeShellCommand("appops set "+activity.getPackageName()+" SYSTEM_ALERT_WINDOW "+(granted?"allow":"deny"));FileInputStream in=new FileInputStream(pipe.getFileDescriptor())){byte[] buffer=new byte[256];while(in.read(buffer)!=-1){}}
    Thread.sleep(150);
  }
}
