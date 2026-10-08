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
  final Set<String> running=ConcurrentHashMap.newKeySet();final ExecutorService transfers=Executors.newSingleThreadExecutor();
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
    try{JSONArray data=new JSONArray(new String(Files.readAllBytes(new File(root,"index.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));for(int i=0;i<Math.min(200,data.length());i++)try{JSONObject row=data.getJSONObject(i);if(!row.getString("id").matches("[a-fA-F0-9-]{36}")||row.has("ephemeral")&&row.optString("state").equals("pending"))continue;row.put("offer",validate(row.getJSONObject("offer")));if(row.optString("state").equals("receiving"))row.put("state","failed").put("error","上次传输中断，点击重新传输");if(!row.optString("state").equals("ready")||location(row).exists())rows.put(row.getString("id"),row);}catch(Exception ignored){}}catch(Exception ignored){}
    try{JSONArray data=new JSONArray(new String(Files.readAllBytes(new File(exportRoot,"index.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));for(int i=0;i<Math.min(200,data.length());i++){JSONObject row=data.getJSONObject(i);validate(row.getJSONObject("offer"));if(row.getLong("until")>System.currentTimeMillis())exports.put(row.getJSONObject("offer").getString("id"),row);}}catch(Exception ignored){}
  }
  synchronized void save()throws Exception{store(new File(root,"index.json"),new JSONArray(rows.values()));store(new File(exportRoot,"index.json"),new JSONArray(exports.values()));}
  static void store(File dest,JSONArray data)throws Exception{File part=new File(dest+".tmp");try(FileOutputStream out=new FileOutputStream(part)){out.write(Wire.bytes(data.toString()));out.getFD().sync();}java.nio.file.Files.move(part.toPath(),dest.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
  synchronized JSONArray list(){JSONArray list=new JSONArray();List<JSONObject> sorted=new ArrayList<>(rows.values());sorted.sort((a,b)->Long.compare(b.optLong("created"),a.optLong("created")));for(JSONObject r:sorted){JSONObject o=r.optJSONObject("offer");Wire p=service.peers.get(r.optString("source"));list.put(obj("id",r.optString("id"),"name",o.optString("name"),"type",o.optString("type"),"size",o.optLong("size"),"count",o.optJSONArray("files").length(),"files",o.optJSONArray("files"),"state",r.optString("state").equals("pending")&&running.contains(r.optString("id"))?"queued":r.optString("state"),"error",r.optString("error"),"received",r.optLong("received"),"created",r.optLong("created"),"sourceName",r.optString("sourceName"),"online",p!=null&&p.connected));}return list;}
  synchronized File location(JSONObject row)throws Exception{return new File(new File(root,row.getString("id")),row.getJSONObject("offer").getString("name"));}
  synchronized JSONObject ready(String id)throws Exception{JSONObject r=rows.get(id);if(r==null||!r.optString("state").equals("ready"))throw new IOException("文件尚未传输完成");return r;}
  File file(String id,String path)throws Exception{JSONObject row=ready(id);String relative=relative(path);JSONObject offer=row.getJSONObject("offer");boolean found=false;for(int i=0;i<offer.getJSONArray("files").length();i++)if(offer.getJSONArray("files").getJSONObject(i).getString("relative").equals(relative))found=true;if(!found)throw new IOException("文件不存在");File base=new File(root,id).getCanonicalFile(),file=new File(base,relative).getCanonicalFile();if(!file.getPath().startsWith(base.getPath()+File.separator)||!file.isFile())throw new IOException("文件不存在");return file;}
  Uri uri(String id,String relative){Uri.Builder b=new Uri.Builder().scheme("content").authority("com.lanlink.app.received").appendPath("staging").appendPath(id);for(String part:relative.split("/"))b.appendPath(part);return b.build();}
  synchronized JSONObject accept(Wire peer,JSONObject args)throws Exception{
    service.requireClipboardPermission(peer);JSONObject offer=validate(args.getJSONObject("offer"));JSONObject row=null;
    for(JSONObject existing:rows.values())if(existing.getString("source").equals(peer.id)&&existing.getJSONObject("offer").getString("id").equals(offer.getString("id"))){row=existing;break;}
    if(row==null){if(rows.size()>=200)throw new IOException("暂存区已满，请先保存文件");String id=UUID.randomUUID().toString();row=obj("id",id,"offer",offer,"source",peer.id,"sourceName",peer.info.optString("name","设备"),"created",System.currentTimeMillis(),"state","pending","received",0);rows.put(id,row);save();}else if(!row.getJSONObject("offer").toString().equals(offer.toString()))throw new IOException("分享项目已改变");
    if(args.has("ephemeral")){String session=args.getString("ephemeral");if(!session.matches("[a-fA-F0-9-]{36}"))throw new IOException("拖动会话无效");row.put("ephemeral",session);save();}
    String id=row.getString("id");if(args.optBoolean("start"))start(id);else poll();return obj("id",id);
  }
  void start(String id)throws Exception{if(!rows.containsKey(id))throw new IOException("暂存文件不存在");if(!running.add(id))return;transfers.execute(()->{try{download(id);}catch(Exception e){synchronized(this){JSONObject r=rows.get(id);if(r!=null){try{if(r.has("ephemeral")&&!r.optBoolean("started"))rows.remove(id);else r.put("state","failed").put("error",String.valueOf(e.getMessage()));save();}catch(Exception ignored){}}}}finally{running.remove(id);}});}
  void download(String id)throws Exception{
    JSONObject r; synchronized(this){r=rows.get(id);if(r==null||r.optString("state").equals("ready"))return;service.requireClipboardPermission(service.peer(r.getString("source")));r.put("state","receiving").put("received",0).put("error","");save();}
    File partial=new File(root,id+".part"),destination=new File(root,id);delete(partial);partial.mkdirs();long total=0;
    try{JSONObject offer=r.getJSONObject("offer");service.peer(r.getString("source")).request("stage-begin",obj("id",offer.getString("id")));synchronized(this){r.put("started",true);save();}JSONArray directories=offer.getJSONArray("directories"),files=offer.getJSONArray("files");for(int i=0;i<directories.length();i++){File dir=new File(partial,directories.getString(i));if(!dir.mkdirs()&&!dir.isDirectory())throw new IOException("无法创建接收目录");}
      for(int i=0;i<files.length();i++){JSONObject f=files.getJSONObject(i);File target=new File(partial,f.getString("relative"));target.getParentFile().mkdirs();long offset=0,size=f.getLong("size");BulkTransfer.Reader bulk=null;int retries=0;
        try(FileOutputStream out=new FileOutputStream(target)){
          while(offset<size){Wire p=service.peer(r.getString("source"));service.requireClipboardPermission(p);JSONObject args=obj("offerId",offer.getString("id"),"fileId",f.getString("id"),"offset",offset);byte[] bytes;
            if(p.quicConn!=0&&p.fastPath==null){try{bytes=service.quic.file(p,args);if(bytes.length==0||bytes.length>192*1024||offset+bytes.length>size)throw new IOException("文件校验失败");out.write(bytes);offset+=bytes.length;progress(r,total+offset);if(service.bulkRate()<32L*1024*1024)Thread.sleep(Math.min(1000,bytes.length*1000L/Math.max(8192,service.bulkRate())));continue;}catch(Exception e){String reason=String.valueOf(e.getMessage());if(reason.contains("校验")||reason.contains("授权")||reason.contains("过期"))throw e;service.quic.drop(p);}}
            if(p.info.optJSONObject("capabilities")!=null&&p.info.getJSONObject("capabilities").optInt("bulk")==1&&bulk==null)bulk=BulkTransfer.connect(p,p.request("bulkOpen",new JSONObject(args.toString()).put("end",size).put("completeAck",true)));
            JSONObject chunk=bulk==null?p.request("fileChunk",args):null;
            try{bytes=bulk!=null?bulk.next():Base64.getDecoder().decode(chunk.getString("data"));}catch(EOFException|java.net.SocketException|java.net.SocketTimeoutException e){if(bulk==null||retries++>=2)throw e;bulk.close();bulk=null;Thread.sleep(500);continue;}
            if(bytes.length==0||bytes.length>192*1024||offset+bytes.length>size||chunk!=null&&!Wire.hex(Wire.sha(bytes)).equals(chunk.getString("sha256")))throw new IOException("文件校验失败");out.write(bytes);offset+=bytes.length;progress(r,total+offset);if(service.bulkRate()<32L*1024*1024)Thread.sleep(Math.min(1000,bytes.length*1000L/Math.max(8192,service.bulkRate())));
          }
        }finally{if(bulk!=null){try{if(offset==size)bulk.complete(size);}finally{bulk.close();}}}total+=size;
      }
      service.requireClipboardPermission(service.peer(r.getString("source")));if(!partial.renameTo(destination))throw new IOException("无法完成接收文件");synchronized(this){r.put("state","ready").put("received",total);save();}service.status="文件已接收到控制中心暂存区";try{service.peer(r.getString("source")).request("stage-complete",obj("id",offer.getString("id")));}catch(Exception ignored){}
    }catch(Exception e){delete(partial);throw e;}
  }
  synchronized void progress(JSONObject row,long bytes)throws Exception{row.put("received",bytes);}
  synchronized void expire()throws Exception{Iterator<Map.Entry<String,JSONObject>> it=exports.entrySet().iterator();while(it.hasNext()){Map.Entry<String,JSONObject> e=it.next();if(e.getValue().optLong("until")<System.currentTimeMillis()){delete(new File(exportRoot,e.getKey()));it.remove();}}save();}
  synchronized void clearExports()throws Exception{for(String id:exports.keySet())delete(new File(exportRoot,id));exports.clear();save();}
  void share(Wire peer,List<Uri> uris)throws Exception{
    expire();
    service.requireClipboardPermission(peer);if(peer.info.optJSONObject("capabilities")==null||peer.info.getJSONObject("capabilities").optInt("staging")!=1)throw new IOException("请更新接收设备以使用暂存文件");if(uris.isEmpty()||uris.size()>100)throw new IOException("每次最多分享 100 个文件");
    for(Uri uri:uris){String name="shared-file";try(Cursor c=service.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst()&&!c.isNull(0))name=c.getString(0);}name=LinkService.safeName(name);String id=UUID.randomUUID().toString(),fid=UUID.randomUUID().toString();File dir=new File(exportRoot,id);dir.mkdirs();File target=new File(dir,name);long size=0;
      try(InputStream in=service.getContentResolver().openInputStream(uri);FileOutputStream out=new FileOutputStream(target)){if(in==null)throw new IOException("无法读取分享文件");byte[] buffer=new byte[192*1024];int n;while((n=in.read(buffer))!=-1){size+=n;if(size>MAX)throw new IOException("文件不能大于 2 GB");out.write(buffer,0,n);}}catch(Exception e){delete(dir);throw e;}
      JSONObject offer=validate(obj("id",id,"name",name,"type","file","files",new JSONArray().put(obj("id",fid,"name",name,"relative",name,"size",size)),"directories",new JSONArray()));
      synchronized(this){if(exports.size()>=200){delete(dir);throw new IOException("发送记录已满，请稍后重试");}exports.put(id,obj("offer",offer,"target",peer.id,"group",service.group.data.optString("id"),"until",System.currentTimeMillis()+7L*86400000));save();}
      service.requireClipboardPermission(peer);peer.request("stage-offer",obj("offer",offer,"start",true));
    }
  }
  synchronized boolean owns(String id){return exports.containsKey(id);}
  byte[] bytes(JSONObject args,String requester)throws Exception{
    JSONObject export; synchronized(this){export=exports.get(args.getString("offerId"));}
    if(export==null||!export.getString("target").equals(requester)||export.getLong("until")<System.currentTimeMillis()||!export.getString("group").equals(service.group.data.optString("id")))throw new IOException("文件分享已过期或未授权此设备");JSONObject offer=export.getJSONObject("offer"),f=offer.getJSONArray("files").getJSONObject(0);long offset=args.getLong("offset");if(!f.getString("id").equals(args.getString("fileId"))||offset<0||offset>f.getLong("size"))throw new IOException("文件范围无效");File file=new File(new File(exportRoot,offer.getString("id")),offer.getString("name"));if(file.length()!=f.getLong("size"))throw new IOException("源文件已变化");try(RandomAccessFile in=new RandomAccessFile(file,"r")){in.seek(offset);byte[] data=new byte[(int)Math.min(LinkService.fileChunkLimit(args),f.getLong("size")-offset)];in.readFully(data);return data;}
  }
  synchronized void remove(String id)throws Exception{if(!rows.containsKey(id))return;if(rows.get(id).optString("state").equals("receiving"))throw new IOException("正在传输，请等待完成");rows.remove(id);save();delete(new File(root,id));}
  synchronized JSONObject retract(Wire peer,String session)throws Exception{Iterator<JSONObject> it=rows.values().iterator();while(it.hasNext()){JSONObject r=it.next();if(r.optString("source").equals(peer.id)&&r.optString("ephemeral").equals(session)&&r.optString("state").equals("pending"))it.remove();}save();return obj();}
  synchronized JSONObject available(Wire peer,String id){JSONObject r=exports.get(id);return obj("available",r!=null&&r.optString("target").equals(peer.id)&&r.optLong("until")>System.currentTimeMillis()&&r.optString("group").equals(service.group.data.optString("id"))&&new File(new File(exportRoot,id),r.optJSONObject("offer").optString("name")).isFile());}
  synchronized JSONObject complete(Wire peer,String id)throws Exception{JSONObject r=exports.get(id);if(r!=null&&r.optString("target").equals(peer.id)){exports.remove(id);save();delete(new File(exportRoot,id));}return obj();}
  synchronized JSONObject begin(Wire peer,String id)throws Exception{if(!available(peer,id).optBoolean("available"))throw new IOException("源文件已删除或分享已结束");exports.get(id).put("started",true);save();return obj();}
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
