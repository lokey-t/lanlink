package com.lanlink.app;

import static com.lanlink.app.Wire.obj;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Separate, persistent file inbox. Receiving never changes the system clipboard. */
final class StagingFiles {
  static final long MAX=2147483648L;
  final LinkService service;final File root,exportRoot;
  final LinkedHashMap<String,JSONObject> rows=new LinkedHashMap<>(),exports=new LinkedHashMap<>();
  final Set<String> running=ConcurrentHashMap.newKeySet(),cancelled=ConcurrentHashMap.newKeySet(),shareRequests=ConcurrentHashMap.newKeySet();
  final Map<String,Thread> workers=new ConcurrentHashMap<>();final Map<String,BulkTransfer.Reader> readers=new ConcurrentHashMap<>();final ExecutorService transfers=Executors.newSingleThreadExecutor();
  StagingFiles(LinkService service){this.service=service;root=new File(service.getFilesDir(),"staging");exportRoot=new File(service.getFilesDir(),"stage-outgoing");root.mkdirs();exportRoot.mkdirs();load();}
  static String relative(String p)throws Exception{if(p==null||p.length()>1024||p.split("/",-1).length>32)throw new IOException("文件路径无效");for(String part:p.split("/",-1)){if(part.equals(".")||part.equals(".."))throw new IOException("文件路径无效");LinkService.safeName(part);}return p;}
  static JSONObject validate(JSONObject o)throws Exception{
    String id=o.getString("id"),name=LinkService.safeName(o.getString("name")),type=o.getString("type");if(!id.matches("[a-fA-F0-9-]{36}")||!type.equals("file")&&!type.equals("folder"))throw new IOException("暂存文件信息无效");
    JSONArray files=o.getJSONArray("files"),dirs=o.getJSONArray("directories");if(files.length()+dirs.length()>1000)throw new IOException("暂存文件数量过多");
    Set<String> seen=new HashSet<>(),ids=new HashSet<>(),directories=new HashSet<>();List<String> paths=new ArrayList<>();long total=0;
    for(int i=0;i<dirs.length();i++){String p=relative(dirs.getString(i));if(!seen.add(p.toLowerCase(Locale.ROOT)))throw new IOException("文件名称重复");directories.add(p);paths.add(p);}
    JSONArray normalized=new JSONArray();
    for(int i=0;i<files.length();i++){JSONObject f=files.getJSONObject(i);String p=relative(f.getString("relative")),fid=f.getString("id"),fname=LinkService.safeName(f.getString("name"));long size=f.getLong("size");if(!fid.matches("[a-fA-F0-9-]{36}")||!ids.add(fid)||size<0||size>MAX||!seen.add(p.toLowerCase(Locale.ROOT))||!p.substring(p.lastIndexOf('/')+1).equals(fname))throw new IOException("文件信息无效");total+=size;paths.add(p);normalized.put(obj("id",fid,"name",fname,"relative",p,"size",size));}
    if(total>MAX||type.equals("file")&&(dirs.length()!=0||files.length()!=1||!files.getJSONObject(0).getString("relative").equals(name))||type.equals("folder")&&!directories.contains(name))throw new IOException("文件分享范围无效或大于 2 GB");
    for(String p:paths){if(!p.equals(name)&&!p.startsWith(name+"/"))throw new IOException("文件超出分享目录");int slash=p.lastIndexOf('/');if(slash!=-1&&!directories.contains(p.substring(0,slash)))throw new IOException("文件父目录无效");}
    return obj("id",id,"name",name,"type",type,"size",total,"files",normalized,"directories",dirs);
  }
  synchronized void load(){
    try{JSONArray data=new JSONArray(new String(Files.readAllBytes(new File(root,"index.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));for(int i=0;i<Math.min(200,data.length());i++)try{JSONObject row=data.getJSONObject(i);if(!row.getString("id").matches("[a-fA-F0-9-]{36}")||row.has("ephemeral")&&row.optString("state").equals("pending"))continue;row.put("offer",validate(row.getJSONObject("offer")));if(row.optString("state").equals("receiving")||row.optString("state").equals("queued"))row.put("state","failed").put("error","上次传输中断，点击重新传输");if(!row.optString("state").equals("ready")||location(row).exists())rows.put(row.getString("id"),row);}catch(Exception ignored){}}catch(Exception ignored){}
    try{JSONArray data=new JSONArray(new String(Files.readAllBytes(new File(exportRoot,"index.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));for(int i=0;i<Math.min(200,data.length());i++){JSONObject row=data.getJSONObject(i);validate(row.getJSONObject("offer"));if(row.getLong("until")>System.currentTimeMillis()){String state=row.optString("state","queued");if(state.equals("queued")||state.equals("sending")||state.equals("preparing"))row.put("state","failed").put("error","上次发送中断，请重试");exports.put(row.getJSONObject("offer").getString("id"),row);}}}catch(Exception ignored){}
  }
  synchronized void save()throws Exception{store(new File(root,"index.json"),new JSONArray(rows.values()));store(new File(exportRoot,"index.json"),new JSONArray(exports.values()));}
  static void store(File dest,JSONArray data)throws Exception{File part=new File(dest+".tmp");try(FileOutputStream out=new FileOutputStream(part)){out.write(Wire.bytes(data.toString()));out.getFD().sync();}java.nio.file.Files.move(part.toPath(),dest.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
  synchronized JSONArray list(){JSONArray list=new JSONArray();List<JSONObject> sorted=new ArrayList<>(rows.values());sorted.sort((a,b)->Long.compare(b.optLong("created"),a.optLong("created")));for(JSONObject r:sorted){JSONObject o=r.optJSONObject("offer");Wire p=service.peers.get(r.optString("source"));list.put(obj("id",r.optString("id"),"name",o.optString("name"),"type",o.optString("type"),"size",o.optLong("size"),"count",o.optJSONArray("files").length(),"files",fileList(o.optJSONArray("files")),"state",r.optString("state").equals("cancelled")&&running.contains(r.optString("id"))?"stopping":r.optString("state").equals("pending")&&running.contains(r.optString("id"))?"queued":r.optString("state"),"error",r.optString("error"),"received",r.optLong("received"),"created",r.optLong("created"),"sourceName",r.optString("sourceName"),"online",p!=null&&p.connected));}return list;}
  static JSONArray fileList(JSONArray original){JSONArray copy=new JSONArray();for(int i=0;i<original.length();i++){JSONObject f=original.optJSONObject(i);copy.put(obj("id",f.optString("id"),"name",f.optString("name"),"relative",f.optString("relative"),"size",f.optLong("size")));}return copy;}
  synchronized JSONArray outgoingList(){
    JSONArray result=new JSONArray();List<JSONObject> sorted=new ArrayList<>(exports.values());sorted.sort((a,b)->Long.compare(b.optLong("created"),a.optLong("created")));
    for(JSONObject r:sorted){JSONObject offer=r.optJSONObject("offer");result.put(obj("id",offer.optString("id"),"name",offer.optString("name"),"size",offer.optLong("size"),"state",r.optString("state","queued"),"received",r.optLong("received"),"targetName",r.optString("targetName","设备"),"error",r.optString("error"),"created",r.optLong("created")));}return result;
  }
  synchronized void disconnected(String peer){try{for(JSONObject r:exports.values())if(r.optString("target").equals(peer)&&Arrays.asList("queued","sending","preparing").contains(r.optString("state")))r.put("state","failed").put("error","设备已离线，重新连接后可重试");save();}catch(Exception ignored){}}
  synchronized File location(JSONObject row)throws Exception{return new File(new File(root,row.getString("id")),row.getJSONObject("offer").getString("name"));}
  synchronized JSONObject ready(String id)throws Exception{JSONObject r=rows.get(id);if(r==null||!r.optString("state").equals("ready"))throw new IOException("文件尚未传输完成");return r;}
  File file(String id,String path)throws Exception{JSONObject row=ready(id);String relative=relative(path);JSONObject offer=row.getJSONObject("offer");boolean found=false;for(int i=0;i<offer.getJSONArray("files").length();i++)if(offer.getJSONArray("files").getJSONObject(i).getString("relative").equals(relative))found=true;if(!found)throw new IOException("文件不存在");File base=new File(root,id).getCanonicalFile(),file=new File(base,relative).getCanonicalFile();if(!file.getPath().startsWith(base.getPath()+File.separator)||!file.isFile())throw new IOException("文件不存在");return file;}
  Uri uri(String id,String relative){Uri.Builder b=new Uri.Builder().scheme("content").authority("com.lanlink.app.received").appendPath("staging").appendPath(id);for(String part:relative.split("/"))b.appendPath(part);return b.build();}
  synchronized JSONObject accept(Wire peer,JSONObject args)throws Exception{
    service.requireClipboardPermission(peer);JSONObject offer=validate(args.getJSONObject("offer"));JSONObject row=null;
    for(JSONObject existing:rows.values())if(existing.getString("source").equals(peer.id)&&existing.getJSONObject("offer").getString("id").equals(offer.getString("id"))){row=existing;break;}
    if(row==null){if(rows.size()>=200)throw new IOException("暂存区已满，请先保存文件");String id=UUID.randomUUID().toString();row=obj("id",id,"offer",offer,"source",peer.id,"sourceName",peer.info.optString("name","设备"),"created",System.currentTimeMillis(),"state","pending","received",0);rows.put(id,row);save();}else if(!row.getJSONObject("offer").toString().equals(offer.toString()))throw new IOException("分享项目已改变");
    if(args.has("ephemeral")){String session=args.getString("ephemeral");if(!session.matches("[a-fA-F0-9-]{36}"))throw new IOException("拖动会话无效");row.put("ephemeral",session);save();}
    String id=row.getString("id");if(args.optBoolean("start")&&row.optString("state").equals("ready"))notifyPeer(row,"stage-complete","");else if(args.optBoolean("start")&&(!row.optString("state").equals("cancelled")||args.optBoolean("resume")))start(id,args.optBoolean("resume"));else poll();return obj("id",id);
  }
  void start(String id)throws Exception{start(id,true);}
  synchronized void start(String id,boolean resume)throws Exception{
    JSONObject row=rows.get(id);if(row==null)throw new IOException("暂存文件不存在");if(row.optString("state").equals("ready"))return;if(running.contains(id)&&cancelled.contains(id))throw new IOException("正在取消传输，请稍后重试");if(!running.add(id))return;cancelled.remove(id);
    transfers.execute(()->{synchronized(this){workers.put(id,Thread.currentThread());}try{checkActive(id);download(id,resume);}catch(Exception e){
      synchronized(this){JSONObject r=rows.get(id);if(r!=null){try{if(String.valueOf(e.getMessage()).contains("已取消"))cancelled.add(id);if(cancelled.contains(id))r.put("state","cancelled").put("error","");else if(r.has("ephemeral")&&!r.optBoolean("started"))rows.remove(id);else r.put("state","failed").put("error",String.valueOf(e.getMessage()));save();if(!cancelled.contains(id))notifyPeer(r,"stage-failed",String.valueOf(e.getMessage()));}catch(Exception ignored){}}}
    }finally{synchronized(this){workers.remove(id);readers.remove(id);running.remove(id);}Thread.interrupted();}});
  }
  void checkActive(String id)throws IOException{if(cancelled.contains(id)||Thread.currentThread().isInterrupted())throw new IOException("传输已取消");}
  void notifyPeer(JSONObject row,String method,String error){String peer=row.optString("source"),id=row.optJSONObject("offer").optString("id");service.jobs.execute(()->{try{service.peer(peer).request(method,obj("id",id,"error",error));}catch(Exception ignored){}});}
  synchronized void cancel(String id)throws Exception{
    JSONObject row=rows.get(id);if(row==null)return;if(row.optString("state").equals("ready"))throw new IOException("文件已接收完成，请使用删除");
    cancelled.add(id);row.put("state","cancelled").put("error","");Thread worker=workers.get(id);if(worker!=null)worker.interrupt();BulkTransfer.Reader reader=readers.get(id);if(reader!=null)reader.close();save();notifyPeer(row,"stage-cancel","");if(!running.contains(id))delete(new File(root,id+".part"));
  }
  void download(String id,boolean resume)throws Exception{
    JSONObject r; synchronized(this){checkActive(id);r=rows.get(id);if(r==null||r.optString("state").equals("ready"))return;service.requireClipboardPermission(service.peer(r.getString("source")));r.put("state","receiving").put("received",0).put("error","");save();}
    File partial=new File(root,id+".part"),destination=new File(root,id);delete(partial);partial.mkdirs();long total=0;
    try{checkActive(id);JSONObject offer=r.getJSONObject("offer");service.peer(r.getString("source")).request("stage-begin",obj("id",offer.getString("id"),"resume",resume));synchronized(this){r.put("started",true);save();}JSONArray directories=offer.getJSONArray("directories"),files=offer.getJSONArray("files");for(int i=0;i<directories.length();i++){File dir=new File(partial,directories.getString(i));if(!dir.mkdirs()&&!dir.isDirectory())throw new IOException("无法创建接收目录");}
      for(int i=0;i<files.length();i++){JSONObject f=files.getJSONObject(i);File target=new File(partial,f.getString("relative"));target.getParentFile().mkdirs();long offset=0,size=f.getLong("size");BulkTransfer.Reader bulk=null;int retries=0;
        try(FileOutputStream out=new FileOutputStream(target)){
          while(offset<size){checkActive(id);Wire p=service.peer(r.getString("source"));service.requireClipboardPermission(p);JSONObject args=obj("offerId",offer.getString("id"),"fileId",f.getString("id"),"offset",offset);byte[] bytes;
            if(p.quicConn!=0&&p.fastPath==null){try{bytes=service.quic.file(p,args);if(bytes.length==0||bytes.length>192*1024||offset+bytes.length>size)throw new IOException("文件校验失败");checkActive(id);out.write(bytes);offset+=bytes.length;progress(r,total+offset);if(service.bulkRate()<32L*1024*1024)Thread.sleep(Math.min(1000,bytes.length*1000L/Math.max(8192,service.bulkRate())));continue;}catch(Exception e){checkActive(id);String reason=String.valueOf(e.getMessage());if(reason.contains("校验")||reason.contains("授权")||reason.contains("过期"))throw e;service.quic.drop(p);}}
            if(p.info.optJSONObject("capabilities")!=null&&p.info.getJSONObject("capabilities").optInt("bulk")==1&&bulk==null){bulk=BulkTransfer.connect(p,p.request("bulkOpen",new JSONObject(args.toString()).put("end",size).put("completeAck",true)));readers.put(id,bulk);checkActive(id);}
            JSONObject chunk=bulk==null?p.request("fileChunk",args):null;
            try{bytes=bulk!=null?bulk.next():Base64.getDecoder().decode(chunk.getString("data"));}catch(EOFException|java.net.SocketException|java.net.SocketTimeoutException e){if(bulk==null||retries++>=2)throw e;bulk.close();bulk=null;Thread.sleep(500);continue;}
            if(bytes.length==0||bytes.length>192*1024||offset+bytes.length>size||chunk!=null&&!Wire.hex(Wire.sha(bytes)).equals(chunk.getString("sha256")))throw new IOException("文件校验失败");checkActive(id);out.write(bytes);offset+=bytes.length;progress(r,total+offset);if(service.bulkRate()<32L*1024*1024)Thread.sleep(Math.min(1000,bytes.length*1000L/Math.max(8192,service.bulkRate())));
          }
        }finally{readers.remove(id);if(bulk!=null){try{if(offset==size&&!cancelled.contains(id))bulk.complete(size);}finally{bulk.close();}}}total+=size;
      }
      service.requireClipboardPermission(service.peer(r.getString("source")));synchronized(this){checkActive(id);if(!partial.renameTo(destination))throw new IOException("无法完成接收文件");r.put("state","ready").put("received",total);save();}service.status="文件已接收到控制中心暂存区";try{service.peer(r.getString("source")).request("stage-complete",obj("id",offer.getString("id")));}catch(Exception ignored){}
    }catch(Exception e){delete(partial);throw e;}
  }
  synchronized void progress(JSONObject row,long bytes)throws Exception{row.put("received",bytes);}
  synchronized void expire()throws Exception{Iterator<Map.Entry<String,JSONObject>> it=exports.entrySet().iterator();while(it.hasNext()){Map.Entry<String,JSONObject> e=it.next();if(e.getValue().optLong("until")<System.currentTimeMillis()){delete(new File(exportRoot,e.getKey()));it.remove();}}save();}
  synchronized void clearExports()throws Exception{for(String id:exports.keySet())delete(new File(exportRoot,id));exports.clear();save();}
  void share(Wire peer,List<Uri> uris)throws Exception{share(peer,uris,UUID.randomUUID().toString());}
  void share(Wire peer,List<Uri> uris,String requestId)throws Exception{
    if(!shareRequests.add(requestId))return;
    List<String> prepared=new ArrayList<>();
    try{
      expire();service.requireClipboardPermission(peer);if(peer.info.optJSONObject("capabilities")==null||peer.info.getJSONObject("capabilities").optInt("staging")!=1)throw new IOException("请更新接收设备以使用暂存文件");if(uris.isEmpty()||uris.size()>100)throw new IOException("每次最多分享 100 个文件");
      for(Uri uri:uris){String name="shared-file";try(Cursor c=service.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst()&&!c.isNull(0))name=c.getString(0);}name=LinkService.safeName(name);String id=UUID.randomUUID().toString(),fid=UUID.randomUUID().toString();File dir=new File(exportRoot,id);dir.mkdirs();File target=new File(dir,name);long size=0;
        try(InputStream in=service.getContentResolver().openInputStream(uri);FileOutputStream out=new FileOutputStream(target)){if(in==null)throw new IOException("无法读取分享文件");byte[] buffer=new byte[192*1024];int n;while((n=in.read(buffer))!=-1){size+=n;if(size>MAX)throw new IOException("文件不能大于 2 GB");out.write(buffer,0,n);}}catch(Exception e){delete(dir);throw e;}
        JSONObject offer=validate(obj("id",id,"name",name,"type","file","files",new JSONArray().put(obj("id",fid,"name",name,"relative",name,"size",size)),"directories",new JSONArray()));
        synchronized(this){pruneExports();if(exports.size()>=200){delete(dir);throw new IOException("发送记录已满，请取消旧任务后重试");}exports.put(id,obj("offer",offer,"target",peer.id,"targetName",peer.info.optString("name","设备"),"group",service.group.data.optString("id"),"until",System.currentTimeMillis()+7L*86400000,"created",System.currentTimeMillis(),"state","preparing","received",0));prepared.add(id);save();}
      }
    }catch(Exception failure){synchronized(this){for(String id:prepared){exports.remove(id);delete(new File(exportRoot,id));}save();}shareRequests.remove(requestId);throw failure;}
    // Prepare the complete batch first: URI/read failures never leave an invisible partial offer.
    Exception failure=null;for(String id:prepared){try{offerOutgoing(id,false);}catch(Exception e){failure=e;}}
    if(failure!=null)throw failure;
  }
  synchronized void pruneExports()throws Exception{Iterator<Map.Entry<String,JSONObject>> it=exports.entrySet().iterator();while(exports.size()>=200&&it.hasNext()){Map.Entry<String,JSONObject> row=it.next();if(Arrays.asList("completed","failed","cancelled").contains(row.getValue().optString("state"))){delete(new File(exportRoot,row.getKey()));it.remove();}}}
  void retryOutgoing(String id)throws Exception{offerOutgoing(id,true);}
  void offerOutgoing(String id,boolean resume)throws Exception{
    JSONObject row;Wire peer;synchronized(this){row=exports.get(id);if(row==null){if(!resume)return;throw new IOException("发送记录不存在");}if(row.optString("state").equals("completed")||row.optString("state").equals("cancelled")&&!resume)return;peer=service.peer(row.getString("target"));row.put("state","queued").put("error","").put("received",0);save();}
    try{service.requireClipboardPermission(peer);peer.request("stage-offer",obj("offer",row.getJSONObject("offer"),"start",true,"resume",resume));}
    catch(Exception failure){synchronized(this){if(!row.optString("state").equals("completed")&&!row.optString("state").equals("cancelled")){row.put("state","failed").put("error",String.valueOf(failure.getMessage()));save();}}throw failure;}
  }
  void cancelOutgoing(String id)throws Exception{JSONObject row;synchronized(this){row=exports.get(id);if(row==null)return;if(row.optString("state").equals("completed"))return;row.put("state","cancelled").put("error","");save();}service.jobs.execute(()->{try{service.peer(row.optString("target")).request("stage-cancel",obj("id",id));}catch(Exception ignored){}});}
  synchronized void removeOutgoing(String id)throws Exception{JSONObject r=exports.get(id);if(r==null)return;if(Arrays.asList("preparing","queued","sending").contains(r.optString("state")))throw new IOException("请先取消正在发送的任务");if(!r.optString("state").equals("completed"))cancelOutgoing(id);exports.remove(id);save();delete(new File(exportRoot,id));}
  synchronized JSONObject remoteStopped(Wire peer,String id,boolean failed,String error)throws Exception{
    JSONObject outgoing=exports.get(id);if(outgoing!=null&&outgoing.optString("target").equals(peer.id)&&!outgoing.optString("state").equals("completed")&&!(failed&&outgoing.optString("state").equals("cancelled"))){outgoing.put("state",failed?"failed":"cancelled").put("error",failed?error:"");save();}
    if(!failed)for(JSONObject incoming:rows.values())if(incoming.optString("source").equals(peer.id)&&incoming.getJSONObject("offer").optString("id").equals(id)&&incoming.optString("state").equals("ready"))notifyPeer(incoming,"stage-complete","");
    if(!failed)for(JSONObject incoming:rows.values())if(incoming.optString("source").equals(peer.id)&&incoming.getJSONObject("offer").optString("id").equals(id)&&!incoming.optString("state").equals("ready")){String local=incoming.getString("id");cancelled.add(local);incoming.put("state","cancelled").put("error","");Thread worker=workers.get(local);if(worker!=null)worker.interrupt();BulkTransfer.Reader reader=readers.get(local);if(reader!=null)reader.close();if(!running.contains(local))delete(new File(root,local+".part"));save();}
    return obj();
  }
  synchronized boolean owns(String id){return exports.containsKey(id);}
  byte[] bytes(JSONObject args,String requester)throws Exception{
    JSONObject export; synchronized(this){export=exports.get(args.getString("offerId"));}
    if(export==null||Arrays.asList("cancelled","completed").contains(export.optString("state"))||!export.getString("target").equals(requester)||export.getLong("until")<System.currentTimeMillis()||!export.getString("group").equals(service.group.data.optString("id")))throw new IOException("文件分享已过期或未授权此设备");JSONObject offer=export.getJSONObject("offer"),f=offer.getJSONArray("files").getJSONObject(0);long offset=args.getLong("offset");if(!f.getString("id").equals(args.getString("fileId"))||offset<0||offset>f.getLong("size"))throw new IOException("文件范围无效");File file=new File(new File(exportRoot,offer.getString("id")),offer.getString("name"));if(file.length()!=f.getLong("size"))throw new IOException("源文件已变化");try(RandomAccessFile in=new RandomAccessFile(file,"r")){in.seek(offset);byte[] data=new byte[(int)Math.min(LinkService.fileChunkLimit(args),f.getLong("size")-offset)];in.readFully(data);synchronized(this){if(!export.optString("state").equals("completed")&&!export.optString("state").equals("cancelled"))export.put("state","sending").put("received",Math.max(export.optLong("received"),offset+data.length));}return data;}
  }
  synchronized void remove(String id)throws Exception{if(!rows.containsKey(id))return;if(running.contains(id))throw new IOException("传输正在停止，请稍后删除");rows.remove(id);cancelled.remove(id);save();delete(new File(root,id));delete(new File(root,id+".part"));}
  synchronized JSONObject retract(Wire peer,String session)throws Exception{Iterator<JSONObject> it=rows.values().iterator();while(it.hasNext()){JSONObject r=it.next();if(r.optString("source").equals(peer.id)&&r.optString("ephemeral").equals(session)&&r.optString("state").equals("pending"))it.remove();}save();return obj();}
  synchronized JSONObject available(Wire peer,String id){JSONObject r=exports.get(id);return obj("available",r!=null&&r.optString("target").equals(peer.id)&&!Arrays.asList("cancelled","completed").contains(r.optString("state"))&&r.optLong("until")>System.currentTimeMillis()&&r.optString("group").equals(service.group.data.optString("id"))&&new File(new File(exportRoot,id),r.optJSONObject("offer").optString("name")).isFile());}
  synchronized JSONObject complete(Wire peer,String id)throws Exception{JSONObject r=exports.get(id);if(r!=null&&r.optString("target").equals(peer.id)){r.put("state","completed").put("received",r.getJSONObject("offer").optLong("size")).put("error","");save();delete(new File(exportRoot,id));service.status="文件已发送到 "+r.optString("targetName","设备");}return obj();}
  synchronized JSONObject begin(Wire peer,String id,boolean resume)throws Exception{JSONObject row=exports.get(id);if(row!=null&&row.optString("target").equals(peer.id)&&row.optString("state").equals("cancelled")&&!resume)throw new IOException("发送已取消，请手动重新传输");if(row==null||!row.optString("target").equals(peer.id)||row.optString("state").equals("completed")||row.optLong("until")<System.currentTimeMillis()||!row.optString("group").equals(service.group.data.optString("id"))||!new File(new File(exportRoot,id),row.getJSONObject("offer").getString("name")).isFile())throw new IOException("源文件已删除或分享已结束");row.put("started",true).put("state","sending").put("error","");save();return obj();}
  volatile boolean polling;
  void poll(){if(polling)return;polling=true;service.jobs.execute(()->{try{List<JSONObject> pending=new ArrayList<>();synchronized(this){for(JSONObject r:rows.values())if(r.has("ephemeral")&&r.optString("state").equals("pending"))pending.add(r);}for(JSONObject r:pending){boolean available=false;try{available=service.peer(r.getString("source")).request("stage-status",obj("id",r.getJSONObject("offer").getString("id"))).optBoolean("available");}catch(Exception ignored){}if(!available)synchronized(this){if(r.optString("state").equals("pending")){rows.remove(r.optString("id"));save();}}}}catch(Exception ignored){}finally{polling=false;}});}
  void close(){transfers.shutdownNow();}
  static void delete(File f)throws IOException{if(!f.exists())return;if(f.isDirectory()&&!Files.isSymbolicLink(f.toPath())){File[] children=f.listFiles();if(children!=null)for(File child:children)delete(child);}if(!f.delete())throw new IOException("无法移除暂存文件");}
  void saveFile(String id,Uri destination)throws Exception{JSONObject r=ready(id);if(!r.getJSONObject("offer").getString("type").equals("file"))throw new IOException("文件夹需要选择保存目录");JSONObject f=r.getJSONObject("offer").getJSONArray("files").getJSONObject(0);copy(file(id,f.getString("relative")),destination);remove(id);}
  void copy(File source,Uri destination)throws Exception{try(InputStream in=new FileInputStream(source);OutputStream out=service.getContentResolver().openOutputStream(destination,"wt")){if(out==null)throw new IOException("无法写入保存位置");byte[] data=new byte[192*1024];int n;while((n=in.read(data))!=-1)out.write(data,0,n);}}
  void saveFolder(String id,Uri tree)throws Exception{JSONObject r=ready(id),offer=r.getJSONObject("offer");Uri base=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));Map<String,Uri> folders=new HashMap<>();folders.put("",base);List<String> dirs=new ArrayList<>();JSONArray d=offer.getJSONArray("directories");for(int i=0;i<d.length();i++)dirs.add(d.getString(i));dirs.sort(Comparator.comparingInt(String::length));
    for(String relative:dirs){int slash=relative.lastIndexOf('/');String parent=slash<0?"":relative.substring(0,slash),name=slash<0?relative:relative.substring(slash+1);Uri created=DocumentsContract.createDocument(service.getContentResolver(),folders.get(parent),DocumentsContract.Document.MIME_TYPE_DIR,name);if(created==null)throw new IOException("无法创建保存目录");folders.put(relative,created);}
    JSONArray files=offer.getJSONArray("files");for(int i=0;i<files.length();i++){JSONObject f=files.getJSONObject(i);String relative=f.getString("relative");int slash=relative.lastIndexOf('/');Uri created=DocumentsContract.createDocument(service.getContentResolver(),folders.get(slash<0?"":relative.substring(0,slash)),"application/octet-stream",f.getString("name"));if(created==null)throw new IOException("无法保存文件");copy(file(id,relative),created);}remove(id);
  }
}
