package com.lanlink.app;

import android.app.*;
import android.service.notification.StatusBarNotification;
import java.net.Socket;
import java.util.UUID;
import org.json.*;

/** Disposable-emulator verification of real notification actions and replacement. */
final class StagePreviewProbe {
  static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
  static StatusBarNotification notice(LinkService service){for(StatusBarNotification n:service.getSystemService(NotificationManager.class).getActiveNotifications())if(n.getId()==17)return n;return null;}
  static void until(java.util.function.BooleanSupplier condition)throws Exception{long end=System.currentTimeMillis()+5000;while(!condition.getAsBoolean()){if(System.currentTimeMillis()>end)throw new AssertionError("notification update timed out");Thread.sleep(30);}}
  static JSONObject row(String name,String source,JSONObject preview,String state){String id=UUID.randomUUID().toString();return Wire.obj("id",id,"source",source,"sourceName","文件预览测试电脑","state",state,"preview",preview,"ephemeral",UUID.randomUUID().toString(),"offer",Wire.obj("id",UUID.randomUUID().toString(),"name",name,"type","file","files",new JSONArray(),"directories",new JSONArray(),"size",0));}
  static String run(Instrumentation test,LinkService service)throws Exception{
    StagingFiles stage=service.staging;check(stage.rows.isEmpty(),"test requires an empty disposable emulator inbox");
    Wire peer=new Wire(new Socket(),Wire.obj(),new byte[32],null);peer.id=UUID.randomUUID().toString();
    JSONObject old=Wire.obj("group",service.group.data.optString("id"),"origin",peer.id,"id",UUID.randomUUID().toString(),"revision",System.currentTimeMillis());
    JSONObject first=row("旧预传输.txt",peer.id,old,"pending"),active=row("正在传输.txt",peer.id,old,"receiving"),accepted=row("已接受排队.txt",peer.id,old,"pending");
    try{
      stage.applyPreview(peer,old);synchronized(stage){stage.rows.put(first.optString("id"),first);stage.rows.put(active.optString("id"),active);stage.rows.put(accepted.optString("id"),accepted);stage.running.add(accepted.optString("id"));stage.save();}
      until(()->notice(service)!=null);Notification previous=notice(service).getNotification();check(previous.actions.length==2,"accept and reject available in system notification");
      JSONObject next=new JSONObject(old.toString()).put("id",UUID.randomUUID().toString()).put("revision",old.optLong("revision")+1);stage.applyPreview(peer,next);
      until(()->notice(service)==null);check(stage.rows.containsKey(active.optString("id"))&&stage.rows.containsKey(accepted.optString("id")),"replacement preserves active and accepted tasks");
      JSONObject current=row("新预传输.txt",peer.id,next,"pending");synchronized(stage){stage.rows.put(current.optString("id"),current);stage.save();}
      until(()->notice(service)!=null);previous.actions[1].actionIntent.send();Thread.sleep(250);check(stage.rows.containsKey(current.optString("id")),"stale reject cannot remove replacement");
      check(!stage.applyPreview(peer,old).optBoolean("current"),"old announcement cannot resurrect preview");
      notice(service).getNotification().actions[1].actionIntent.send();until(()->notice(service)==null);check(!stage.rows.containsKey(current.optString("id")),"notification reject removes pending file");
      return "PASS: Android system receive notification, accept/reject actions, group preview replacement, stale action isolation and active/accepted preservation";
    }finally{synchronized(stage){stage.running.remove(accepted.optString("id"));stage.rows.clear();stage.save();}peer.socket.close();}
  }
}
