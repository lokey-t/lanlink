package com.lanlink.app;

import java.io.File;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Real JNI endpoints; remote input is recorded, never injected into the OS. */
final class QuicPipelineProbe {
  static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
  static void run(LinkService service)throws Exception {
    QuicNative a=new QuicNative(new File(service.getCacheDir(),"pipeline-a")),b=new QuicNative(new File(service.getCacheDir(),"pipeline-b"));
    QuicManager manager=new QuicManager(service);Wire p=null;ExecutorService executor=Executors.newSingleThreadExecutor();
    ArrayBlockingQueue<JSONObject> presses=new ArrayBlockingQueue<>(2),moves=new ArrayBlockingQueue<>(2),releases=new ArrayBlockingQueue<>(2);
    try {
      b.listener=new QuicNative.Listener(){public void motion(long conn,byte[] bytes){try{moves.offer(QuicMotion.decode(bytes));}catch(Exception e){throw new RuntimeException(e);}}
        public void event(JSONObject e){if(!e.optString("event").equals("request"))return;try{
          JSONObject request=new JSONObject(new String(QuicManager.decode(e),java.nio.charset.StandardCharsets.UTF_8));String method=request.getString("method");JSONObject args=request.getJSONObject("args");
          if(method.equals("input")&&args.optBoolean("down")){presses.offer(e);return;}
          if(method.equals("input"))releases.offer(request);
          b.callAsync("reply",Wire.obj("conn",e.getLong("conn"),"stream",e.getLong("stream"),"data",QuicManager.encode(Wire.bytes("{\"result\":{}}"))));
        }catch(Exception error){throw new RuntimeException(error);}}};
      long conn=QuicAdmissionProbe.connect(a,b);
      p=new Wire(new Socket(),Wire.obj(),Wire.parseToken(service.token),AuxIsolationProbe.IDLE);p.id=UUID.randomUUID().toString();p.connected=true;p.info=Wire.obj("capabilities",Wire.obj("quic",1,"motionV2",2));service.peers.put(p.id,p);
      manager.enabled=true;manager.nativeEngine=a;manager.bind(p,conn);
      p.request("acquire",Wire.obj());p.request("input",Wire.obj("kind","button","button",0,"down",true));
      JSONObject held=presses.poll(3,TimeUnit.SECONDS);check(held!=null&&!p.inputPending.isDone(),"press awaits its remote reply");
      p.request("input",Wire.obj("kind","relative","dx",.25,"dy",-1.5));JSONObject moved=moves.poll(3,TimeUnit.SECONDS);
      check(moved!=null&&moved.getLong("afterInput")==1&&!p.inputPending.isDone(),"binary movement bypasses the press reply with a dependency marker");
      p.request("keepControl",Wire.obj());check(!p.inputPending.isDone(),"lease renewal bypasses the held press");
      final Wire peer=p;Future<?> up=executor.submit(()->{try{peer.request("input",Wire.obj("kind","button","button",0,"down",false));}catch(Exception error){throw new RuntimeException(error);}});
      check(releases.poll(50,TimeUnit.MILLISECONDS)==null,"release waits behind its press");
      b.call("reply",Wire.obj("conn",held.getLong("conn"),"stream",held.getLong("stream"),"data",QuicManager.encode(Wire.bytes("{\"result\":{}}"))));
      up.get(3,TimeUnit.SECONDS);JSONObject released=releases.poll(3,TimeUnit.SECONDS);
      check(released!=null&&released.getJSONObject("args").getLong("__inputSeq")==2,"release retains reliable input order");
    }finally{executor.shutdownNow();if(p!=null){service.peers.remove(p.id,p);manager.drop(p);p.close();}manager.close();a.close();b.close();}
  }
}
