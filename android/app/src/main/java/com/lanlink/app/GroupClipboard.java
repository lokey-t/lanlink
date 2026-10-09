package com.lanlink.app;

import static com.lanlink.app.Wire.obj;
import android.content.SharedPreferences;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

final class GroupClipboard {
  static final long TTL=600000;static final int LIMIT=30;
  private final LinkService service;private final Map<String,JSONObject> records=new HashMap<>();
  private JSONObject latest;private String appliedText,lastText,appliedFingerprint,lastFingerprint;private final AtomicBoolean polling=new AtomicBoolean();
  private final Object writes=new Object();
  volatile String capability="前台自动同步";
  GroupClipboard(LinkService service){this.service=service;}
  private SharedPreferences prefs(){return service.getSharedPreferences("identity",0);}
  boolean upload(){return prefs().getBoolean("clipboard_upload",true);}
  boolean download(){return prefs().getBoolean("clipboard_download",true);}
  boolean shortcuts(){return prefs().getBoolean("clipboard_shortcuts",false) && (!upload() || !download());}
  void setting(String key,boolean value){prefs().edit().putBoolean("clipboard_"+key,value).apply();}
  private boolean allowed(String id){return service.allowed("clipboard") && !service.group.excluded(id) && service.canShareClipboard(id);}
  synchronized void prune(){long cutoff=System.currentTimeMillis()-TTL;records.values().removeIf(r->r.optLong("at")<=cutoff || !allowed(r.optString("source")));ArrayList<JSONObject> list=sorted();for(int i=LIMIT;i<list.size();i++)records.remove(list.get(i).optString("id"));}
  private ArrayList<JSONObject> sorted(){ArrayList<JSONObject> list=new ArrayList<>(records.values());list.sort((a,b)->{int time=Long.compare(b.optLong("at"),a.optLong("at"));return time!=0?time:b.optString("id").compareTo(a.optString("id"));});return list;}
  synchronized JSONArray history(){prune();return new JSONArray(sorted());}
  private JSONObject valid(JSONObject r)throws Exception{
    if(r==null || r.optString("id").isEmpty() || r.optString("id").length()>64 || r.optString("source").length()>64 || !(r.opt("text") instanceof String) || r.optString("text").length()>1000000 || r.optLong("at")<=System.currentTimeMillis()-TTL || r.optLong("at")>System.currentTimeMillis()+30000 || !allowed(r.optString("source")))return null;
    String source=r.optString("source");if(!source.equals(service.deviceId) && !service.authorizationDevices().has(source) && !service.peers.containsKey(source))return null;
    String name=r.optString("name","设备");if(name.length()>80)name=name.substring(0,80);
    return obj("id",r.getString("id"),"source",source,"name",name,"text",r.getString("text"),"at",r.getLong("at"));
  }
  private synchronized boolean add(JSONObject r)throws Exception{JSONObject record=valid(r);if(record==null || r.optJSONArray("files")!=null && r.optJSONArray("files").length()>0 || record.optString("text").isEmpty() || records.containsKey(record.getString("id")))return false;records.put(record.getString("id"),record);prune();return records.containsKey(record.getString("id"));}
  private static boolean newer(JSONObject a,JSONObject b){return b==null || a.optLong("at")>b.optLong("at") || a.optLong("at")==b.optLong("at") && a.optString("id").compareTo(b.optString("id"))>0;}
  synchronized void applied(String text){appliedText=text;lastText=null;lastFingerprint=null;appliedFingerprint=null;}
  synchronized void appliedFiles(String fingerprint){appliedFingerprint=fingerprint;lastText=null;lastFingerprint=null;appliedText=null;}
  synchronized boolean echo(String text){return Objects.equals(text,appliedText)||Objects.equals(text,lastText);}
  synchronized JSONObject latest(){return latest;}
  synchronized JSONObject reusable(String fingerprint,JSONArray files)throws Exception{
    if(files.length()==0||latest==null||!service.deviceId.equals(latest.optString("source"))||latest.optLong("at")<=System.currentTimeMillis()-TTL||!Objects.equals(fingerprint,lastFingerprint)||!service.clipboardOfferAvailable(latest.optString("id")))return null;
    JSONArray old=latest.optJSONArray("files");if(old==null||old.length()!=files.length())return null;
    for(int i=0;i<files.length();i++)if(!old.getJSONObject(i).optString("name").equals(files.getJSONObject(i).optString("name"))||old.getJSONObject(i).optLong("size")!=files.getJSONObject(i).optLong("size"))return null;
    return new JSONObject(latest.toString()).put("_fingerprint",fingerprint);
  }
  void publish(JSONObject offer,boolean manual)throws Exception{
    if(offer.optString("text").isEmpty() && offer.optJSONArray("files").length()==0){if(manual)throw new IOException("本机剪贴板暂无可上传内容");return;}
    synchronized(this){if(manual && latest!=null && service.deviceId.equals(latest.optString("source")) && latest.optLong("at")>System.currentTimeMillis()-TTL && offer.optString("_fingerprint",offer.optString("text")).equals(lastFingerprint) && (offer.optJSONArray("files").length()==0 || offer.optString("id").equals(latest.optString("id")))){for(Wire peer:service.peers.values())if(allowed(peer.id))peer.send(obj("type","clipboard","offer",latest));service.clip=latest;return;}}
    synchronized(this){String fingerprint=offer.optString("_fingerprint",offer.optString("text"));if(!manual && (!upload() || fingerprint.equals(lastFingerprint) || fingerprint.equals(appliedFingerprint) || offer.optJSONArray("files").length()==0 && echo(offer.optString("text")))){if(latest!=null)service.clip=latest;return;}
      lastFingerprint=fingerprint;appliedFingerprint=null;offer.remove("_fingerprint");
      offer.put("at",Math.max(System.currentTimeMillis(),latest==null?0:latest.optLong("at")+1)).put("name",service.name).put("source",service.deviceId);latest=offer;lastText=offer.optString("text");appliedText=null;add(offer);}
    for(Wire peer:service.peers.values())if(allowed(peer.id))peer.send(obj("type","clipboard","offer",offer));
  }
  void receive(Wire peer,JSONObject offer)throws Exception{
    if(!allowed(peer.id))return;service.validateOffer(offer);JSONObject data=new JSONObject(offer.toString());data.put("source",peer.id).put("name",peer.info.optString("name",offer.optString("name","设备")));if(!data.has("at"))data.put("at",System.currentTimeMillis());
    if(data.optLong("at")>System.currentTimeMillis()+30000 || data.optLong("at")<System.currentTimeMillis()-TTL)return;
    synchronized(this){add(data);}adopt(data);
  }
  private void adopt(JSONObject data)throws Exception{ synchronized(this){if(!newer(data,latest))return;latest=data;}service.clip=data;if(download())apply(data,true);}
  void apply(JSONObject offer)throws Exception{apply(offer,false);}
  private void apply(JSONObject offer,boolean automatic)throws Exception{synchronized(writes){if(!allowed(offer.optString("source")))throw new IOException("来源设备未允许共享剪贴板");service.receive(offer,()->!automatic || download() && latest()!=null && latest().optString("id").equals(offer.optString("id")));}}
  void inventory(Wire peer)throws Exception{if(!allowed(peer.id))return;JSONArray ids=new JSONArray();synchronized(this){prune();for(JSONObject record:sorted())ids.put(record.getString("id"));}peer.send(obj("type","clipboard-inventory","ids",ids));}
  void mergeInventory(Wire peer,JSONArray ids)throws Exception{
    if(!allowed(peer.id) || ids==null || ids.length()>LIMIT)return;Set<String> have=new HashSet<>();for(int i=0;i<ids.length();i++){String id=ids.optString(i);if(id.length()>64)return;have.add(id);}
    JSONArray records=history();int sent=0;for(int i=0;i<records.length();i++){JSONObject r=records.getJSONObject(i);if(!have.contains(r.getString("id")) && allowed(r.getString("source"))){peer.send(obj("type","clipboard-record","record",r));if(++sent==4)break;}}
  }
  void record(Wire peer,JSONObject value)throws Exception{if(!allowed(peer.id) || value.optJSONArray("files")!=null && value.optJSONArray("files").length()>0)return;JSONObject r; synchronized(this){r=valid(value);if(r==null || !add(r))return;}adopt(new JSONObject(r.toString()).put("files",new JSONArray()));}
  void downloadNow()throws Exception{
    service.allow("clipboard");JSONArray ids=new JSONArray();JSONArray list=history();for(int i=0;i<list.length();i++)ids.put(list.getJSONObject(i).getString("id"));
    for(Wire peer:service.peers.values())if(allowed(peer.id))try{JSONObject result=peer.request("clipboard-sync",obj("ids",ids));JSONObject data=result.optJSONObject("latest");if(data!=null){if(peer.id.equals(data.optString("source")))receive(peer,data);else record(peer,data);}}catch(Exception error){service.status="同步剪贴板："+error.getMessage();}
    JSONObject data=latest();if(data==null)throw new IOException("组内暂无可下载的剪贴板内容");apply(data);
  }
  void copy(String id)throws Exception{JSONObject r;synchronized(this){prune();r=records.get(id);}if(r==null)throw new IOException("该记录已过期");applied(r.getString("text"));service.writeClipboardText(r.getString("text"));}
  void poll(){
    prune();if(!upload() || !service.allowed("clipboard") || !polling.compareAndSet(false,true))return;
    service.jobs.execute(()->{try{
      if(service.shell.ready()){JSONObject response=service.shell.request(obj("method","clipboard-get"));capability="ADB / Root 后台自动同步";if(response.optBoolean("exists") && !echo(response.optString("text")) && !response.optString("text").isEmpty())publish(obj("id",UUID.randomUUID().toString(),"text",response.optString("text"),"files",new JSONArray()),false);}
      else if(service.uiVisible){capability="前台自动同步 · 后台需要 ADB / Root";service.publishClipboard(false);}
    }catch(Exception error){capability="前台自动同步 · 助手暂不支持后台剪贴板";}finally{polling.set(false);}});
  }
  synchronized void clear(){records.clear();latest=null;lastText=null;appliedText=null;lastFingerprint=null;appliedFingerprint=null;}
}
