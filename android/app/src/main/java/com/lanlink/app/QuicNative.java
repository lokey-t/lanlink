package com.lanlink.app;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** JNI interface to the same native quiche engine used by the desktop. */
final class QuicNative implements AutoCloseable {
  static final boolean available;
  static {boolean ok=false;try{System.loadLibrary("lanlink_quic");ok=true;}catch(UnsatisfiedLinkError ignored){}available=ok;}
  static native long start(String host,String dir);
  static native int command(long handle,String json);
  static native String poll(long handle);
  static native void stop(long handle);
  interface Listener {void event(JSONObject event);}
  final Map<String,CompletableFuture<JSONObject>> pending=new ConcurrentHashMap<>();
  final ScheduledThreadPoolExecutor worker=new ScheduledThreadPoolExecutor(1);
  {worker.setRemoveOnCancelPolicy(true);}
  final CompletableFuture<JSONObject> ready=new CompletableFuture<>();
  long handle,sequence;volatile boolean closed;volatile Listener listener;
  QuicNative(File dir)throws Exception{if(!available)throw new java.io.IOException("QUIC 原生库未安装");handle=start("0.0.0.0:0",dir.getAbsolutePath());if(handle==0)throw new java.io.IOException("QUIC 初始化失败");worker.scheduleWithFixedDelay(this::drain,0,4,TimeUnit.MILLISECONDS);}
  synchronized CompletableFuture<JSONObject> callAsync(String op,JSONObject args){CompletableFuture<JSONObject> result=new CompletableFuture<>();if(closed||pending.size()>=64){result.completeExceptionally(new java.io.IOException("QUIC 缓冲区已满"));return result;}String id=Long.toString(++sequence);try{JSONObject message=new JSONObject(args.toString()).put("id",id).put("op",op);pending.put(id,result);if(command(handle,message.toString())!=0){pending.remove(id);throw new java.io.IOException("QUIC 缓冲区已满");}}catch(Exception e){result.completeExceptionally(e);}ScheduledFuture<?> deadline=worker.schedule(()->{if(pending.remove(id,result))result.completeExceptionally(new TimeoutException("QUIC 响应超时"));},args.optLong("timeout",15000)+250,TimeUnit.MILLISECONDS);result.whenComplete((value,error)->deadline.cancel(false));return result;}
  JSONObject call(String op,JSONObject args)throws Exception{return callAsync(op,args).get(args.optLong("timeout",15000)+500,TimeUnit.MILLISECONDS);}
  void drain(){String data;synchronized(this){if(closed)return;data=poll(handle);}if(data==null)return;try{JSONArray events=new JSONArray(data);for(int i=0;i<events.length();i++){JSONObject event=events.getJSONObject(i);if(event.has("id")){CompletableFuture<JSONObject> result=pending.remove(event.getString("id"));if(result!=null){if(event.has("error"))result.completeExceptionally(new java.io.IOException(event.getString("error")));else result.complete(event.getJSONObject("result"));}}else if(event.optString("event").equals("ready"))ready.complete(event);else if(event.optString("event").equals("overflow"))close();else{Listener callback=listener;if(callback!=null)callback.event(event);}}}catch(Exception error){close();}}
  public synchronized void close(){if(closed)return;closed=true;worker.shutdownNow();for(CompletableFuture<JSONObject> result:pending.values())result.completeExceptionally(new java.io.IOException("QUIC 已关闭"));pending.clear();if(handle!=0){stop(handle);handle=0;}Listener callback=listener;if(callback!=null)callback.event(Wire.obj("event","engineClosed"));}
}
