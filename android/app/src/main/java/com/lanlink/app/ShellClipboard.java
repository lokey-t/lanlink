package com.lanlink.app;

import android.content.ClipData;
import android.os.IBinder;
import java.lang.reflect.Method;
import java.io.IOException;
import org.json.JSONObject;

// Only loaded by the authenticated ADB/Root helper, never by the app UID.
final class ShellClipboard {
  private static Object service;
  private static Object call(String operation, ClipData data) throws Exception {
    if(service==null){IBinder binder=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"clipboard");service=Class.forName("android.content.IClipboard$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);}
    Exception last=null;
    for(Method method:service.getClass().getMethods())if(method.getName().equals(operation)){
      try{Class<?>[] types=method.getParameterTypes();Object[] args=new Object[types.length];boolean firstString=true;int integers=0;
        for(int i=0;i<types.length;i++){
          if(types[i]==ClipData.class)args[i]=data;
          else if(types[i]==String.class){args[i]=firstString?"com.android.shell":null;firstString=false;}
          else if(types[i]==int.class){args[i]=integers++==0?android.os.Process.myUid()/100000:0;}
          else if(types[i]==boolean.class)args[i]=false;
          else throw new IOException("Unsupported clipboard signature");
        }
        method.setAccessible(true);return method.invoke(service,args);
      }catch(Exception error){last=error;}
    }
    throw new IOException("系统暂不支持助手剪贴板访问",last);
  }
  static JSONObject get() throws Exception {ClipData clip=(ClipData)call("getPrimaryClip",null);String text="";if(clip!=null && clip.getItemCount()>0 && clip.getItemAt(0).getText()!=null)text=clip.getItemAt(0).getText().toString();return Wire.obj("exists",clip!=null,"text",text);}
  static void set(String text) throws Exception {if(text.length()>1000000)throw new IOException("剪贴板过大");call("setPrimaryClip",ClipData.newPlainText("LanLink",text));}
}
