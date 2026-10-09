package com.lanlink.app;

import android.app.*;
import android.content.SharedPreferences;
import android.view.*;
import android.widget.TextView;
import java.lang.reflect.*;
import java.net.Socket;
import java.net.ServerSocket;
import java.net.InetAddress;
import java.util.*;
import org.json.JSONObject;

/** Emulator-only protocol and visible phone confirmation, restoring all modified preferences. */
final class AdbPermissionProbe {
  static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
  static Object field(Object value,String name)throws Exception{Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);}
  static View find(View view,String label){
    if(view instanceof TextView&&label.contentEquals(((TextView)view).getText()))return view;
    if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;}
    return null;
  }
  static void restore(SharedPreferences prefs,Map<String,?> saved,String... keys){
    SharedPreferences.Editor e=prefs.edit();for(String key:keys){Object old=saved.get(key);if(old==null)e.remove(key);else e.putBoolean(key,(Boolean)old);}e.commit();
  }
  static String run(Instrumentation test,Activity activity,LinkService service)throws Exception{
    SharedPreferences identity=service.getSharedPreferences("identity",0),helper=service.getSharedPreferences("adb-helper",0);
    Map<String,?> original=identity.getAll(),originalHelper=helper.getAll();Object owner=field(service,"owner");
    check(owner==null,"no active control during probe");
    // A disconnected fake Wire is removed by the real service heartbeat, which also
    // clears permissionNotice. Use an authenticated loopback connection so the UI
    // assertion tests confirmation, not a race against the disconnect cleanup.
    Wire peer,desktop;String peerId=UUID.randomUUID().toString();byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);
    try(ServerSocket listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
      Socket client=new Socket(InetAddress.getLoopbackAddress(),listener.getLocalPort()),server=listener.accept();
      peer=new Wire(client,Wire.obj("id",service.deviceId,"name","ADB 测试手机"),key,new Wire.Listener(){public void ready(Wire p){}public void closed(Wire p){service.closed(p);}public void message(Wire p,JSONObject m){}});
      desktop=new Wire(server,Wire.obj("id",peerId,"name","ADB 授权测试电脑","platform","windows"),key,new Wire.Listener(){
        public void ready(Wire p){}public void closed(Wire p){}
        public void message(Wire p,JSONObject m){try{if(m.optString("type").equals("ping"))p.send(Wire.obj("type","pong"));else if(m.optString("type").equals("request"))p.send(Wire.obj("type","response","id",m.optString("id"),"result",Wire.obj()));}catch(Exception ignored){}}
      });peer.start();desktop.start();long deadline=System.currentTimeMillis()+5000;while((!peer.connected||!desktop.connected)&&System.currentTimeMillis()<deadline)Thread.sleep(20);check(peer.connected&&desktop.connected,"authenticated ADB fixture connected");
    }
    Method update=MainActivity.class.getDeclaredMethod("update");update.setAccessible(true);
    try{
      service.peers.put(peer.id,peer);
      for(boolean revoked:new boolean[]{false,true}){
        identity.edit().putBoolean("root_mode",false).putBoolean("adb_revoked",revoked).commit();helper.edit().putBoolean("adb_authorized",false).commit();service.permissionNotice.reset();
        JSONObject report=service.handle(peer,"permission-status",Wire.obj());
        check(!report.getJSONObject("engine").getBoolean("ready")&&!report.getJSONObject("engine").getBoolean("authorized"),"ADB missing is not ready");
        service.handle(peer,"adb-permission-request",Wire.obj());
        if(!revoked){
          check(service.permissionNotice.peek(System.currentTimeMillis())==null,"first-time setup does not add application approval to system USB debugging");
          check(!service.adbAuthorized(),"skipping application approval never pretends system helper is ready");
          continue;
        }
        check(service.permissionNotice.peek(System.currentTimeMillis()).needs.contains("adb"),"explicitly revoked ADB requires local restoration");
        test.runOnMainSync(()->{try{update.invoke(activity);}catch(Exception e){throw new RuntimeException(e);}});test.waitForIdleSync();
        Dialog dialog=(Dialog)field(activity,"permissionDialog");check(dialog!=null&&dialog.isShowing(),"phone confirmation is visible (resumed="+field(activity,"resumed")+", alive="+field(activity,"alive")+", request="+service.permissionNotice.peek(System.currentTimeMillis())+", consent="+service.consent.peek()+")");
        View button=find(dialog.getWindow().getDecorView(),"允许 ADB 授权");check(button!=null&&button.isShown(),"explicit allow action is visible");
        check(service.adbRevoked()==revoked,"request never silently grants permission");
        test.runOnMainSync(()->button.performClick());test.waitForIdleSync();
        check(!service.adbRevoked()&&!service.adbAuthorized(),"approval enables setup but does not pretend helper is authorized");
        check(field(service,"owner")==null&&!service.consent.hasGrant(peer.id),"ADB approval does not grant device control");
      }
      return "PASS: first-time ADB setup without redundant app approval, truthful helper readiness, visible explicit-revocation restoration and independent device grants";
    }finally{
      service.peers.remove(peer.id,peer);peer.close();desktop.close();service.permissionNotice.removePeer(peer.id);service.dismissPermissionNotice();
      restore(identity,original,"root_mode","adb_revoked","allow_input");restore(helper,originalHelper,"adb_authorized");
      test.runOnMainSync(()->{try{update.invoke(activity);}catch(Exception ignored){}});
    }
  }
}
