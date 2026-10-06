package com.lanlink.app;

import android.app.Instrumentation;
import android.content.SharedPreferences;
import android.provider.Settings;
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
      test.runOnMainSync(()->current.set(EdgeGlow.feedback(service,null,"countdown","left",0)));EdgeGlow panel=current.get();check(panel!=null && (Boolean)field(panel,"attached"),"countdown attaches the active edge");check((Float)field(panel,"strength")==0,"countdown starts at zero");
      test.runOnMainSync(()->current.set(EdgeGlow.feedback(service,current.get(),"countdown","left",1)));check(current.get()==panel && (Float)field(panel,"strength")==.5f,"same edge reaches half strength without replacing the view");
      test.runOnMainSync(()->current.set(EdgeGlow.feedback(service,current.get(),"depart","left",0)));check(current.get()==panel && (Float)field(panel,"strength")>=.5f,"departure keeps the countdown illuminated");
      Thread.sleep(100);test.waitForIdleSync();check((Float)field(panel,"strength")>.5f,"departure flashes above half strength");
      Thread.sleep(430);test.waitForIdleSync();check(!(Boolean)field(panel,"attached"),"departure fades to zero and removes overlay");
      test.runOnMainSync(()->current.set(EdgeGlow.feedback(service,current.get(),"countdown","right",.4)));EdgeGlow cancelled=current.get();test.runOnMainSync(()->current.set(EdgeGlow.feedback(service,current.get(),"cancel","right",0)));Thread.sleep(400);check(current.get()==null && !(Boolean)field(cancelled,"attached"),"leaving cancels without a delayed flash");
      return "PASS: Android edge countdown 0 -> 50, continuous departure 50 -> 100 -> 0, cancellation and disabled preference";
    }finally{test.runOnMainSync(()->{if(current.get()!=null)current.get().close();});SharedPreferences.Editor editor=prefs.edit();if(had)editor.putBoolean("edge_glow",enabled);else editor.remove("edge_glow");editor.apply();}
  }
}
