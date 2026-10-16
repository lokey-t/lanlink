package com.lanlink.app;

import android.app.Instrumentation;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Build;
import android.provider.Settings;
import android.view.WindowManager;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

/** Disposable emulator only: checks the actual non-touchable overlay animator. */
final class EdgeGlowProbe {
  static Object field(EdgeGlow view,String name)throws Exception{Field f=EdgeGlow.class.getDeclaredField(name);f.setAccessible(true);return f.get(view);}
  static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
  static String run(Instrumentation test,LinkService service)throws Exception{
    check(Settings.canDrawOverlays(service),"emulator overlay permission fixture required");
    SharedPreferences prefs=service.getSharedPreferences("identity",0);boolean had=prefs.contains("edge_glow"),enabled=prefs.getBoolean("edge_glow",false);
    AtomicReference<EdgeGlow> current=new AtomicReference<>();
    try{
      prefs.edit().putBoolean("edge_glow",false).apply();test.runOnMainSync(()->current.set(EdgeGlow.feedback(service,null,"countdown","left",1)));check(current.get()==null,"disabled glow must not attach");
      prefs.edit().putBoolean("edge_glow",true).apply();
      for(String edge:new String[]{"top","bottom","left","right"}){
        test.runOnMainSync(()->current.set(EdgeGlow.feedback(service,current.get(),"countdown",edge,1)));
        EdgeGlow aligned=current.get();check(aligned!=null,"edge overlay attaches: "+edge);
        long deadline=android.os.SystemClock.uptimeMillis()+2000;
        while(aligned.getWidth()==0&&android.os.SystemClock.uptimeMillis()<deadline){Thread.sleep(20);test.waitForIdleSync();}
        test.waitForIdleSync();
        test.runOnMainSync(()->{
          WindowManager.LayoutParams params=(WindowManager.LayoutParams)aligned.getLayoutParams();
          check((params.flags&WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)!=0&&(params.flags&WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)!=0,"feedback must never capture keyboard or touch");
          if(Build.VERSION.SDK_INT>=30){
            check(params.getFitInsetsTypes()==0,"system bars must not inset the physical edge overlay");
            Rect screen=service.getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();int[] at=new int[2];aligned.getLocationOnScreen(at);
            check(edge.equals("right")?at[0]+aligned.getWidth()==screen.right:at[0]==screen.left,"overlay aligns with horizontal screen edge: "+edge);
            check(edge.equals("bottom")?at[1]+aligned.getHeight()==screen.bottom:at[1]==screen.top,"overlay aligns with vertical screen edge, not the status-bar bottom: "+edge);
            check(edge.equals("top")||edge.equals("bottom")?aligned.getWidth()==screen.width():aligned.getHeight()==screen.height(),"edge strip spans the full display: "+edge);
          }
        });
        if(edge.equals("top")){
          android.graphics.Bitmap screenshot=test.getUiAutomation().takeScreenshot();check(screenshot!=null,"top edge screenshot");java.io.File folder=new java.io.File(service.getExternalFilesDir(null),"ui-check");folder.mkdirs();
          try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(folder,"edge-glow-top.png"))){screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{screenshot.recycle();}
        }
        test.runOnMainSync(()->{current.get().close();current.set(null);});
      }
      test.runOnMainSync(()->current.set(EdgeGlow.feedback(service,null,"countdown","left",0)));EdgeGlow panel=current.get();check(panel!=null && (Boolean)field(panel,"attached"),"countdown attaches the active edge");check((Float)field(panel,"strength")==0,"countdown starts at zero");
      test.runOnMainSync(()->current.set(EdgeGlow.feedback(service,current.get(),"countdown","left",1)));check(current.get()==panel && (Float)field(panel,"strength")==.5f,"same edge reaches half strength without replacing the view");
      test.runOnMainSync(()->current.set(EdgeGlow.feedback(service,current.get(),"depart","left",0)));check(current.get()==panel && (Float)field(panel,"strength")>=.5f,"departure keeps the countdown illuminated");
      Thread.sleep(100);test.waitForIdleSync();check((Float)field(panel,"strength")>.5f,"departure flashes above half strength");
      Thread.sleep(430);test.waitForIdleSync();check(!(Boolean)field(panel,"attached"),"departure fades to zero and removes overlay");
      test.runOnMainSync(()->current.set(EdgeGlow.feedback(service,current.get(),"countdown","right",.4)));EdgeGlow cancelled=current.get();test.runOnMainSync(()->current.set(EdgeGlow.feedback(service,current.get(),"cancel","right",0)));Thread.sleep(400);check(current.get()==null && !(Boolean)field(cancelled,"attached"),"leaving cancels without a delayed flash");
      return "PASS: Android four physical screen edges including top status-bar area, non-touchable overlays, countdown 0 -> 50, continuous departure 50 -> 100 -> 0, cancellation and disabled preference";
    }finally{test.runOnMainSync(()->{if(current.get()!=null)current.get().close();});SharedPreferences.Editor editor=prefs.edit();if(had)editor.putBoolean("edge_glow",enabled);else editor.remove("edge_glow");editor.apply();}
  }
}
