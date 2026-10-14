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
  static native byte[] waitPacket(long handle);
  static native int motion(long handle,long conn,byte[] data);
  interface Listener {void event(JSONObject event);default void motion(long conn,byte[] bytes){}}
  static final class Pending extends CompletableFuture<JSONObject>{
    final String op;final long conn;final boolean urgent;
    Pending(String op,JSONObject args){this.op=op;conn=args.optLong("conn");urgent=op.equals("request")?args.optInt("priority",127)==0:!op.equals("video");}
  }
  final Map<String,Pending> pending=new ConcurrentHashMap<>();
  static boolean isBusy(Throwable error){for(Throwable e=error;e!=null;e=e.getCause())if(String.valueOf(e.getMessage()).startsWith("QUIC_BUSY:"))return true;return false;}
  private static java.io.IOException busy(){return new java.io.IOException("QUIC_BUSY: QUIC 通道繁忙，请稍后重试");}
  final ScheduledThreadPoolExecutor worker=new ScheduledThreadPoolExecutor(1);
  {worker.setRemoveOnCancelPolicy(true);}
  final CompletableFuture<JSONObject> ready=new CompletableFuture<>();
  long handle,sequence;boolean waiting,binaryMotion;volatile boolean closed;volatile Listener listener;
  QuicNative(File dir)throws Exception{if(!available)throw new java.io.IOException("QUIC 原生库未安装");handle=start("0.0.0.0:0",dir.getAbsolutePath());if(handle==0)throw new java.io.IOException("QUIC 初始化失败");drain();binaryMotion=ready.get(3,TimeUnit.SECONDS).optInt("motionV2")==2;if(binaryMotion){Thread thread=new Thread(this::waitLoop,"lanlink-quic-events");thread.setDaemon(true);thread.start();}else worker.scheduleWithFixedDelay(this::drain,0,4,TimeUnit.MILLISECONDS);}
  synchronized CompletableFuture<JSONObject> callAsync(String op,JSONObject args){
    Pending result=new Pending(op,args);
    if(closed||handle==0){result.completeExceptionally(new java.io.IOException("QUIC 已关闭"));return result;}
    int ordinary=0,requests=0,globalRequests=0;
    for(Pending entry:pending.values()){if(!entry.urgent)ordinary++;if(entry.op.equals("request")){globalRequests++;if(entry.conn==result.conn)requests++;}}
    // Shared native capacity must leave room for another peer's input and replies.
    if(pending.size()>=64||!result.urgent&&ordinary>=48||op.equals("request")&&(globalRequests>=56||requests>=(result.urgent?32:24))){result.completeExceptionally(busy());return result;}
    String id=Long.toString(++sequence);
    try{
      JSONObject message=new JSONObject(args.toString()).put("id",id).put("op",op);pending.put(id,result);
      if(command(handle,message.toString())!=0)throw busy();
    }catch(Exception e){pending.remove(id,result);result.completeExceptionally(e);return result;}
    ScheduledFuture<?> deadline=worker.schedule(()->{if(pending.remove(id,result))result.completeExceptionally(new TimeoutException("QUIC 响应超时"));},args.optLong("timeout",15000)+250,TimeUnit.MILLISECONDS);
    result.whenComplete((value,error)->deadline.cancel(false));return result;
  }
  JSONObject call(String op,JSONObject args)throws Exception{return callAsync(op,args).get(args.optLong("timeout",15000)+500,TimeUnit.MILLISECONDS);}
  synchronized void sendMotion(long conn,byte[] bytes)throws Exception{if(closed||handle==0)throw new java.io.IOException("QUIC 已关闭");if(motion(handle,conn,bytes)!=0)throw busy();}
  void waitLoop(){
    while(true){long current;synchronized(this){if(closed){finishStop();return;}waiting=true;current=handle;}
      byte[] bytes=null;try{bytes=waitPacket(current);}catch(Throwable error){close();}
      synchronized(this){waiting=false;if(closed){finishStop();return;}}
      if(bytes==null)continue;
      try{java.nio.ByteBuffer b=java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN);if(b.getInt()!=bytes.length)throw new java.io.IOException("QUIC event length");
        while(b.hasRemaining()&&!closed){int kind=b.get()&255,size=b.getInt();if(size<0||size>b.remaining())throw new java.io.IOException("QUIC event size");byte[] body=new byte[size];b.get(body);
          if(kind==1){java.nio.ByteBuffer m=java.nio.ByteBuffer.wrap(body).order(java.nio.ByteOrder.BIG_ENDIAN);long conn=m.getLong();byte[] motion=new byte[m.remaining()];m.get(motion);Listener callback=listener;if(callback!=null)callback.motion(conn,motion);}
          else deliver(new JSONObject(new String(body,java.nio.charset.StandardCharsets.UTF_8)));
        }
      }catch(Exception error){close();}
    }
  }
  private void finishStop(){if(handle!=0){stop(handle);handle=0;}}
  void deliver(JSONObject event)throws Exception{if(event.has("id")){CompletableFuture<JSONObject> result=pending.remove(event.getString("id"));if(result!=null){if(event.has("error"))result.completeExceptionally(new java.io.IOException(event.getString("error")));else result.complete(event.getJSONObject("result"));}}else if(event.optString("event").equals("ready"))ready.complete(event);else if(event.optString("event").equals("overflow"))close();else{Listener callback=listener;if(callback!=null)callback.event(event);}}
  void drain(){String data;synchronized(this){if(closed)return;data=poll(handle);}if(data==null)return;try{JSONArray events=new JSONArray(data);for(int i=0;i<events.length();i++)deliver(events.getJSONObject(i));}catch(Exception error){close();}}
  public synchronized void close(){if(closed)return;closed=true;worker.shutdownNow();for(CompletableFuture<JSONObject> result:pending.values())result.completeExceptionally(new java.io.IOException("QUIC 已关闭"));pending.clear();if(!waiting)finishStop();Listener callback=listener;if(callback!=null)callback.event(Wire.obj("event","engineClosed"));}
}
