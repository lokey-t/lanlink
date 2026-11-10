package com.lanlink.app;

import android.app.*;
import android.content.res.Configuration;
import android.os.SystemClock;
import android.view.*;
import android.widget.TextView;
import java.lang.reflect.*;
import java.net.Socket;

/** Exercises the actual viewer entry point without connecting to or controlling a remote device. */
final class ViewerStartupProbe {
  static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
  static View find(View root, String label) {
    if (label.contentEquals(root.getContentDescription() == null ? "" : root.getContentDescription())
        || root instanceof TextView && label.contentEquals(((TextView)root).getText())) return root;
    if (root instanceof ViewGroup) for (int i=0;i<((ViewGroup)root).getChildCount();i++) {
      View found=find(((ViewGroup)root).getChildAt(i),label); if(found!=null)return found;
    }
    return null;
  }
  static String run(Instrumentation test, Activity activity) throws Exception {
    return runPlatform(test, activity, "windows") + "; " + runPlatform(test, activity, "android");
  }

  private static String runPlatform(Instrumentation test, Activity activity, String platform) throws Exception {
    boolean landscape = activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    Wire peer = new Wire(new Socket(), Wire.obj(), new byte[32], new Wire.Listener() {
      public void ready(Wire wire) {} public void message(Wire wire, org.json.JSONObject message) {}
      public void closed(Wire wire) {}
    });
    peer.id = "viewer-startup-ui-only";
    peer.info = Wire.obj("name", "Viewer UI fixture", "platform", platform);
    final Dialog[] viewer={null};
    try {
      test.runOnMainSync(()->{try{
        Method open=MainActivity.class.getDeclaredMethod("openViewer",Wire.class);open.setAccessible(true);open.invoke(activity,peer);
        Field field=MainActivity.class.getDeclaredField("viewer");field.setAccessible(true);viewer[0]=(Dialog)field.get(activity);
        check(viewer[0]!=null&&viewer[0].isShowing(),"actual viewer opened");
        View close=find(viewer[0].getWindow().getDecorView(),Language.t("关闭并释放控制"));
        check(close!=null,"normal view close action exists");
        check(landscape?!close.isShown():close.isShown(),"startup presentation follows orientation");
      }catch(Exception error){throw new RuntimeException(error);}});
      SystemClock.sleep(350);
      if(landscape){
        test.runOnMainSync(()->{
          Dialog dialog=viewer[0];
          dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK));
          dialog.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK));
        });
        SystemClock.sleep(450);
        test.runOnMainSync(()->{
          View exit=find(viewer[0].getWindow().getDecorView(),Language.t("退出全屏"));
          check(exit!=null&&exit.isShown(),"Back opens fullscreen menu");
          check(exit.performClick(),"user can leave fullscreen");
        });
        SystemClock.sleep(450);
        test.runOnMainSync(()->{
          check(!viewer[0].isShowing(),"exiting fullscreen closes remote session");
          try {
            Field current = MainActivity.class.getDeclaredField("viewer"); current.setAccessible(true);
            Field viewing = MainActivity.class.getDeclaredField("viewing"); viewing.setAccessible(true);
            check(current.get(activity) == null && viewing.get(activity) == null, "viewer lifecycle cleared");
            Field outgoing = LinkService.class.getDeclaredField("outgoingViewer"); outgoing.setAccessible(true);
            check(outgoing.get(LinkService.instance) == null, "outgoing viewing lease released");
          } catch (Exception failure) { throw new RuntimeException(failure); }
        });
      }
      return "PASS: " + platform + " viewer opens " + (landscape?"fullscreen in landscape; exit closes viewer and releases session":"ordinary view in portrait");
    }finally{
      test.runOnMainSync(()->{if(viewer[0]!=null)viewer[0].dismiss();});peer.close();
    }
  }
}
