package com.lanlink.app;

import android.app.*;
import android.content.*;
import android.view.*;
import android.view.inputmethod.*;
import java.io.*;
import java.util.*;
import org.json.*;

final class GroupControlProbe {
  static void check(boolean ok, String label) {
    if (!ok) throw new AssertionError(label);
  }

  static void run(Instrumentation test, Activity activity) throws Exception {
    JSONObject vector;
    try (InputStream input = test.getContext().getAssets().open("group-vector.json")) {
      vector =
          new JSONObject(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
    }
    JSONObject config = vector.getJSONObject("recipient");
    SharedPreferences prefs =
        test.getTargetContext().getSharedPreferences("group-protocol-fixture", 0);
    prefs
        .edit()
        .clear()
        .putString("device_public", config.getJSONObject("deviceKey").getString("public"))
        .putString("device_private", config.getJSONObject("deviceKey").getString("private"))
        .putString("group", config.getJSONObject("group").toString())
        .commit();
    LinkService fake =
        new LinkService() {
          public void groupChanged() {}

          public void forgetDevice(String id) {}

          public void stopInbound() {}

          public void join(String token, JSONObject meta) throws Exception {
            this.token = token;
            group.adopt(meta, token);
          }
        };
    fake.deviceId = config.getString("id");
    fake.name = config.getString("name");
    fake.token = config.getString("token");
    GroupKeys group = new GroupKeys(fake, prefs);
    fake.group = group;
    try {
      JSONObject bad = new JSONObject(vector.getJSONObject("box").toString());
      bad.put("signature", "AAAA");
      try {
        group.accept(bad);
        throw new AssertionError("tampered signature accepted");
      } catch (java.security.SignatureException | IOException expected) {
      }
      group.accept(vector.getJSONObject("box"));
      check(fake.token.equals(vector.getString("expected")), "Node envelope decrypts on Android");
      check(group.excluded(vector.getString("removed")), "Android revocation applied");
      try {
        group.accept(vector.getJSONObject("box"));
        throw new AssertionError("replay accepted");
      } catch (IOException expected) {
      }
      GroupKeys restarted = new GroupKeys(fake, prefs);
      check(restarted.excluded(vector.getString("removed")), "membership survives restart");
      JSONObject reply =
          group.box(
              group
                  .data
                  .getJSONObject("members")
                  .getJSONObject(vector.getJSONObject("box").getString("issuer")));
      try (FileOutputStream out =
          new FileOutputStream(
              new File(
                  test.getTargetContext().getExternalFilesDir(null),
                  "group-interop-result.json"))) {
        out.write(Wire.bytes(reply.toString()));
      }
      JSONObject notice = vector.getJSONObject("dissolution");
      JSONObject badNotice = new JSONObject(notice.toString()).put("signature", "AAAA");
      try {
        group.verifyDissolution(badNotice);
        throw new AssertionError("tampered dissolution accepted");
      } catch (java.security.SignatureException | IOException expected) {
      }
      group.verifyDissolution(notice);
      try (FileOutputStream out =
          new FileOutputStream(
              new File(
                  test.getTargetContext().getExternalFilesDir(null),
                  "group-dissolution-result.json"))) {
        out.write(Wire.bytes(group.dissolutionRecord().toString()));
      }
      JSONObject oldQuery = group.recoveryQuery();
      group.acceptDissolution(notice);
      check(
          !group.data.getString("id").equals(oldQuery.getString("group")),
          "Android exits whole group");
      GroupKeys terminalRestart = new GroupKeys(fake, prefs);
      check(
          terminalRestart
              .recoveryResponse(oldQuery)
              .getString("signature")
              .equals(notice.getString("signature")),
          "offline dissolution survives restart");
      try {
        terminalRestart.verifyDissolution(notice);
        throw new AssertionError("terminal replay accepted");
      } catch (IOException expected) {
      }
    } finally {
      group.close();
      prefs.edit().clear().commit();
    }
    List<JSONObject> events = Collections.synchronizedList(new ArrayList<>());
    String link = "lanlink://pair?v=2&code=12345678&id=0123456789abcdef0123456789abcdef";
    com.google.zxing.common.BitMatrix matrix =
        new com.google.zxing.qrcode.QRCodeWriter()
            .encode(link, com.google.zxing.BarcodeFormat.QR_CODE, 320, 320);
    byte[] luma = new byte[320 * 320];
    for (int y = 0; y < 320; y++)
      for (int x = 0; x < 320; x++) luma[y * 320 + x] = (byte) (matrix.get(x, y) ? 0 : 255);
    check(ScanActivity.decodeLuma(luma, 320, 320).equals(link), "camera frame QR recognition");
    TouchpadView[] pad = new TouchpadView[1];
    test.runOnMainSync(
        () -> {
          pad[0] = new TouchpadView(activity, events::add);
          android.widget.FrameLayout layout = new android.widget.FrameLayout(activity);
          layout.addView(pad[0], new android.widget.FrameLayout.LayoutParams(400, 400));
          activity.addContentView(layout, new android.widget.FrameLayout.LayoutParams(400, 400));
        });
    long time = android.os.SystemClock.uptimeMillis();
    test.runOnMainSync(
        () -> {
          for (MotionEvent e :
              new MotionEvent[] {
                MotionEvent.obtain(time, time, 0, 30, 30, 0),
                MotionEvent.obtain(time, time + 30, 2, 150, 80, 0),
                MotionEvent.obtain(time, time + 60, 1, 150, 80, 0)
              }) {
            pad[0].onTouchEvent(e);
            e.recycle();
          }
        });
    Thread.sleep(100);
    check(
        events.stream()
            .anyMatch(e -> e.optString("kind").equals("relative") && e.optDouble("dx") > 0),
        "relative movement");
    check(
        events.stream().noneMatch(e -> e.optString("kind").equals("button")),
        "movement is not click");
    events.clear();
    test.runOnMainSync(
        () -> {
          for (MotionEvent e :
              new MotionEvent[] {
                MotionEvent.obtain(time, time, 0, 30, 30, 0),
                MotionEvent.obtain(time, time + 80, 1, 30, 30, 0)
              }) {
            pad[0].onTouchEvent(e);
            e.recycle();
          }
        });
    Thread.sleep(100);
    check(
        events.size() == 2 && events.get(0).optBoolean("down") && !events.get(1).optBoolean("down"),
        "tap is balanced mouse click");
    List<String> text = new ArrayList<>();
    test.runOnMainSync(
        () -> {
          RemoteKeyboardView keyboard =
              new RemoteKeyboardView(
                  activity,
                  new RemoteKeyboardView.Sender() {
                    public void text(String value) {
                      text.add(value);
                    }

                    public void key(String code, boolean down) {
                      text.add(code + down);
                    }
                  });
          InputConnection input = keyboard.onCreateInputConnection(new EditorInfo());
          input.setComposingText("你", 1);
          input.setComposingText("你好", 1);
          input.commitText("你好", 1);
          input.finishComposingText();
          input.deleteSurroundingText(1, 0);
          check(
              text.equals(Arrays.asList("你好", "Backspacetrue", "Backspacefalse")),
              "IME composition committed once and deletion balanced");
          pad[0].close();
          ((android.view.ViewGroup) pad[0].getParent()).removeView(pad[0]);
        });
  }
}
