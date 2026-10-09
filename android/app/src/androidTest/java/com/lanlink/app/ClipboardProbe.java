package com.lanlink.app;
import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

final class ClipboardProbe {
  private static View find(View root,String label){if(label.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()) || root instanceof TextView && label.contentEquals(((TextView)root).getText()))return root;if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++){View found=find(group.getChildAt(i),label);if(found!=null)return found;}}return null;}
  private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
  private static void page(Instrumentation test,Activity activity,String name)throws Exception{Field field=MainActivity.class.getDeclaredField("page");field.setAccessible(true);Method render=MainActivity.class.getDeclaredMethod("renderPage");render.setAccessible(true);test.runOnMainSync(()->{try{field.set(activity,name);if(name.equals("clipboard")){Field selection=MainActivity.class.getDeclaredField("centerSelection");selection.setAccessible(true);selection.set(activity,"clipboard");}render.invoke(activity);}catch(Exception e){throw new RuntimeException(e);}});test.waitForIdleSync();}
  static String run(Instrumentation test,Activity activity,LinkService service,Bundle args)throws Exception{
    SharedPreferences prefs=activity.getSharedPreferences("identity",0);Map<String,?> before=new HashMap<>(prefs.getAll());String[] keys={"clipboard_upload","clipboard_download","clipboard_shortcuts","allow_clipboard","allow_screen","allow_input"};
    DeviceConsent consent=service.consent;service.consent=new DeviceConsent("{}");service.enableAdbAuthorization();prefs.edit().remove(keys[0]).remove(keys[1]).remove(keys[2]).remove(keys[3]).apply();service.groupClipboard.clear();
    CountDownLatch done=new CountDownLatch(1);Wire peer=null;java.util.concurrent.atomic.AtomicReference<RtcVideo> rtc=new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.atomic.AtomicReference<JSONObject> touch=new java.util.concurrent.atomic.AtomicReference<>(Wire.obj());java.util.concurrent.atomic.AtomicReference<View> originalView=new java.util.concurrent.atomic.AtomicReference<>();
    try{
      check(service.groupClipboard.upload() && service.groupClipboard.download(),"auto clipboard defaults");
      Method add=GroupClipboard.class.getDeclaredMethod("add",JSONObject.class);add.setAccessible(true);long now=System.currentTimeMillis();
      for(int i=0;i<35;i++)add.invoke(service.groupClipboard,Wire.obj("id",String.format(Locale.ROOT,"%02d",i),"source",service.deviceId,"name","fixture","text","fixture"+i,"at",now+i));
      JSONArray bounded=service.groupClipboard.history();check(bounded.length()==30 && bounded.getJSONObject(0).getString("id").equals("34") && bounded.getJSONObject(29).getString("id").equals("05"),"newest thirty history bound");
      check(!(Boolean)add.invoke(service.groupClipboard,Wire.obj("id","expired","source",service.deviceId,"text","expired","at",now-600001)),"expired record rejected");service.groupClipboard.clear();
      peer=new Wire(new Socket("10.0.2.2",Integer.parseInt(args.getString("clipboardSmokePort"))),Wire.obj("id",service.deviceId,"name","Clipboard Android","capabilities",service.capabilities()),Wire.parseToken(args.getString("clipboardSmokeToken")),new Wire.Listener(){
        public void ready(Wire p){service.ready(p);}
        public void closed(Wire p){service.closed(p);}
        public void message(Wire p,JSONObject m){
          if("request".equals(m.optString("type")) && "testClipboard".equals(m.optString("method"))){service.jobs.execute(()->{try{JSONObject a=m.optJSONObject("args"),result=Wire.obj("ok",true);String step=a.optString("step");
            if(step.equals("copy")){String text=a.optString("text");test.runOnMainSync(()->((ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Clipboard test",text)));service.groupClipboard.poll();}
            if(step.equals("helper-copy"))service.shell.request(Wire.obj("method","clipboard-set","text",a.optString("text")));
            if(step.equals("mode")){service.groupClipboard.setting("upload",a.optBoolean("upload",true));service.groupClipboard.setting("download",a.optBoolean("download",true));}
            if(step.equals("manual-upload"))service.publishClipboard();
            if(step.equals("repeat-file-upload")){for(int i=0;i<50;i++)service.publishClipboard();result.put("offer",service.groupClipboard.latest());Field offers=LinkService.class.getDeclaredField("offers");offers.setAccessible(true);check(((Map<?,?>)offers.get(service)).size()<=2,"repeated file upload reuses live snapshot");}
            if(step.equals("manual-download"))service.groupClipboard.downloadNow();
            if(step.equals("rtc-answer")){RtcVideo video=new RtcVideo(activity);rtc.set(video);result.put("sdp",video.answer(a.getString("sdp")));}
            if(step.equals("rtc-offer")){RtcVideo video=new RtcVideo(activity);rtc.set(video);result.put("sdp",video.offer());}
            if(step.equals("rtc-accept"))rtc.get().accept(a.getString("sdp"));
            if(step.equals("rtc-ready"))rtc.get().ready();
            if(step.equals("rtc-send")){VideoWire.Frame frame=new VideoWire.Frame(java.util.Base64.getDecoder().decode(a.getString("data")),a.getInt("width"),a.getInt("height"),true,true);frame.time=a.getLong("time");check(rtc.get().send(frame),"RTC frame accepted");}
            if(step.equals("rtc-next")){VideoWire.Frame frame=rtc.get().next();result.put("data",java.util.Base64.getEncoder().encodeToString(frame.data)).put("width",frame.width).put("height",frame.height).put("time",frame.time);}
            if(step.equals("rtc-close")){RtcVideo old=rtc.getAndSet(null);if(old!=null)old.close();}
            if(step.equals("screen-grant")){prefs.edit().putBoolean("allow_screen",true).apply();service.consent=new DeviceConsent(Wire.obj(p.id,Wire.obj("input",true,"screen",true,"clipboard",true)).toString());}
            if(step.equals("input-surface")){prefs.edit().putBoolean("allow_input",true).apply();test.runOnMainSync(()->{ViewGroup content=activity.findViewById(android.R.id.content);originalView.set(content.getChildAt(0));View surface=new View(activity);surface.setBackgroundColor(0xffeef4fa);surface.setOnTouchListener((view,event)->{touch.set(Wire.obj("action",event.getActionMasked(),"x",event.getRawX(),"y",event.getRawY(),"width",service.displayMetrics().widthPixels,"height",service.displayMetrics().heightPixels));return true;});activity.setContentView(surface);});test.waitForIdleSync();}
            if(step.equals("input-result")){JSONObject latest=touch.get();Iterator<String> fields=latest.keys();while(fields.hasNext()){String field=fields.next();result.put(field,latest.get(field));}}
            if(step.equals("input-restore"))test.runOnMainSync(()->activity.setContentView(originalView.get()));
            if(step.equals("file-source")){java.io.File dir=new java.io.File(activity.getFilesDir(),"received");dir.mkdirs();java.io.File file=new java.io.File(dir,"transport-probe.bin");byte[] bytes=new byte[800000];Arrays.fill(bytes,(byte)9);try(java.io.FileOutputStream out=new java.io.FileOutputStream(file)){out.write(bytes);}android.net.Uri uri=new android.net.Uri.Builder().scheme("content").authority("com.lanlink.app.received").appendPath(file.getName()).build();test.runOnMainSync(()->((ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newUri(activity.getContentResolver(),"File test",uri)));result.put("offer",service.snapshotClipboard()).put("sha256",Wire.hex(Wire.sha(bytes)));}
            if(step.equals("file-receive")){service.receive(a.getJSONObject("offer"));test.runOnMainSync(()->{ClipData data=((ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip();try(java.io.InputStream in=activity.getContentResolver().openInputStream(data.getItemAt(0).getUri())){byte[] bytes=in.readAllBytes();result.put("sha256",Wire.hex(Wire.sha(bytes))).put("length",bytes.length);}catch(Exception error){throw new RuntimeException(error);}});}
            if(step.equals("background")){test.runOnMainSync(()->activity.moveTaskToBack(true));service.uiVisible=false;}
            if(step.equals("foreground")){test.runOnMainSync(()->activity.startActivity(new Intent(activity,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));}
            if(step.equals("copy-record"))service.groupClipboard.copy(a.optString("id"));
            if(step.equals("ui")){
              page(test,activity,"clipboard");check(find(activity.getWindow().getDecorView(),"控制中心页面")!=null,"clipboard dock");check(find(activity.getWindow().getDecorView(),"复制")!=null,"copy history action");
              test.getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,new java.io.FileOutputStream(new java.io.File(activity.getFilesDir(),"clipboard-history.png")));
              service.groupClipboard.setting("upload",false);service.groupClipboard.setting("download",false);service.groupClipboard.setting("shortcuts",true);page(test,activity,"settings");
              check(find(activity.getWindow().getDecorView(),"上传剪贴板")!=null && find(activity.getWindow().getDecorView(),"下载剪贴板")!=null,"manual options when automatic disabled");
              test.runOnMainSync(()->{View target=find(activity.getWindow().getDecorView(),"允许自动上传剪贴板信息到组");check(target!=null,"clipboard settings category");View parent=target;while(parent!=null && !(parent instanceof ScrollView))parent=parent.getParent() instanceof View?(View)parent.getParent():null;if(parent instanceof ScrollView){android.graphics.Rect area=new android.graphics.Rect(0,0,target.getWidth(),target.getHeight());((ScrollView)parent).offsetDescendantRectToMyCoords(target,area);((ScrollView)parent).scrollTo(0,Math.max(0,area.top-40));}});test.waitForIdleSync();Thread.sleep(2200);
              test.getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,new java.io.FileOutputStream(new java.io.File(activity.getFilesDir(),"clipboard-settings.png")));
              page(test,activity,"devices");check(find(activity.getWindow().getDecorView(),"上传剪贴板")!=null && find(activity.getWindow().getDecorView(),"下载剪贴板")!=null,"home vector shortcuts");
              View upload=find(activity.getWindow().getDecorView(),"上传剪贴板"),download=find(activity.getWindow().getDecorView(),"下载剪贴板");check(upload.getParent()==download.getParent() && ((ViewGroup)upload.getParent()).getChildCount()==3,"home shortcuts must share one divided rounded rectangle");
              int[] uploadPosition=new int[2],downloadPosition=new int[2],addPosition=new int[2];upload.getLocationOnScreen(uploadPosition);download.getLocationOnScreen(downloadPosition);find(activity.getWindow().getDecorView(),"添加设备").getLocationOnScreen(addPosition);check(uploadPosition[0]+upload.getWidth()<=downloadPosition[0] && downloadPosition[0]+download.getWidth()<addPosition[0],"upload then download with spacing before add device");
              test.getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,new java.io.FileOutputStream(new java.io.File(activity.getFilesDir(),"clipboard-home.png")));
              service.groupClipboard.setting("upload",true);service.groupClipboard.setting("download",true);
              page(test,activity,"settings");check(find(activity.getWindow().getDecorView(),"在首页显示快捷同步剪贴板按钮")==null,"shortcut option hidden when both directions automatic");page(test,activity,"devices");check(find(activity.getWindow().getDecorView(),"上传剪贴板")==null && find(activity.getWindow().getDecorView(),"下载剪贴板")==null,"automatic home must hide shortcuts even with saved preference");
              service.groupClipboard.setting("download",false);page(test,activity,"settings");check(find(activity.getWindow().getDecorView(),"在首页显示快捷同步剪贴板按钮")!=null,"one manual direction enables shortcut preference");page(test,activity,"devices");check(find(activity.getWindow().getDecorView(),"快捷同步剪贴板")!=null,"saved preference restored for manual download");service.groupClipboard.setting("download",true);
            }
            if(step.equals("status")){result.put("history",service.groupClipboard.history()).put("latest",service.groupClipboard.latest()).put("helper",service.shell.ready()).put("capability",service.groupClipboard.capability);if(service.shell.ready())result.put("system",service.shell.request(Wire.obj("method","clipboard-get")));else {ClipData data=((ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip();result.put("system",Wire.obj("text",data==null?"":data.getItemAt(0).getText().toString()));}}
            p.send(Wire.obj("type","response","id",m.optString("id"),"result",result));if(step.equals("finish"))done.countDown();
          }catch(Throwable error){try{p.send(Wire.obj("type","response","id",m.optString("id"),"error",error.toString()));}catch(Exception ignored){}}});return;}
          service.message(p,m);
        }
      });peer.start();check(done.await(100,TimeUnit.SECONDS),"clipboard fixture finished");return "PASS: Android/desktop automatic and manual clipboard sync, no echo, helper background read/write, text history copy, settings and dock/home shortcuts";
    }finally{RtcVideo old=rtc.getAndSet(null);if(old!=null)old.close();if(peer!=null)peer.close();service.consent=consent;SharedPreferences.Editor editor=prefs.edit();for(String key:keys){if(before.containsKey(key))editor.putBoolean(key,(Boolean)before.get(key));else editor.remove(key);}editor.apply();service.groupClipboard.clear();}
  }
}
