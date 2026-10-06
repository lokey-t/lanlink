package com.lanlink.app;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Physical, system-trusted ADB setup only. Never grants group membership or remote control. */
public final class AdbSetupProvider extends ContentProvider {
  public boolean onCreate(){return true;}
  @Override public Bundle call(String method,String arg,Bundle extras){
    int uid=Binder.getCallingUid();
    if(uid!=2000 && uid!=0)throw new SecurityException("仅允许已信任的 ADB 调试连接");
    try {
      LinkService service=LinkService.instance;
      if(service==null || service.shell==null)throw new IllegalStateException("请先在手机打开 LanLink");
      if(service.adbRevoked())throw new IllegalStateException("请先在手机重新开启 ADB 模式");
      JSONObject data;
      if("status".equals(method)) {
        boolean ready=false;
        try {if(service.shell.ready()){service.shell.request(Wire.obj("method","heartbeat"));ready=service.shell.lastInputError==null;}}catch(Exception ignored){}
        data=Wire.obj("ready",ready,"root",service.rootEnabled());
      }
      else if("bootstrap".equals(method)) {
        if(service.rootEnabled())throw new IllegalStateException("手机处于 Root 模式，请在手机处理 Root 授权或切换非 Root 模式");
        if(!service.allowed("input"))throw new IllegalStateException("请先在手机开启键鼠标操作权限");
        data=service.shell.bootstrap();
      } else throw new IllegalArgumentException("不支持的设置操作");
      Bundle result=new Bundle();result.putString("data",Base64.encodeToString(data.toString().getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP));return result;
    } catch(Exception error) {Bundle result=new Bundle();result.putString("error",String.valueOf(error.getMessage()));return result;}
  }
  public Cursor query(Uri u,String[] p,String s,String[] a,String o){throw new UnsupportedOperationException();}
  public String getType(Uri u){return null;}
  public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
  public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
  public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
