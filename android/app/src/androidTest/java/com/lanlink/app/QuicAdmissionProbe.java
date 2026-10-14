package com.lanlink.app;

import java.io.File;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Three JNI QUIC endpoints exercise shared-engine pressure without any OS input injection. */
final class QuicAdmissionProbe {
  static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
  static void busy(CompletableFuture<JSONObject> result,String message)throws Exception{
    try{result.get(1,TimeUnit.SECONDS);throw new AssertionError(message);}
    catch(ExecutionException error){check(QuicNative.isBusy(error),message+": expected local busy");}
  }
  static long connect(QuicNative source,QuicNative target)throws Exception{
    JSONObject info=target.ready.get(3,TimeUnit.SECONDS);
    String ticket=Wire.hex(VideoWire.random())+Wire.hex(VideoWire.random());ticket=ticket.substring(0,64);
    target.call("ticket",Wire.obj("ticket",ticket,"owner",UUID.randomUUID().toString()));
    return source.call("connect",Wire.obj("address","127.0.0.1:"+info.getInt("port"),"owner",UUID.randomUUID().toString(),"ticket",ticket,"fingerprint",info.getString("fingerprint"),"timeout",5000)).getLong("conn");
  }
  static void run(LinkService service)throws Exception{
    QuicNative a=new QuicNative(new File(service.getCacheDir(),"quic-admission-a")),b=new QuicNative(new File(service.getCacheDir(),"quic-admission-b")),c=new QuicNative(new File(service.getCacheDir(),"quic-admission-c"));
    QuicManager manager=new QuicManager(service);Wire peer=null;
    LinkedBlockingQueue<JSONObject> fromC=new LinkedBlockingQueue<>(),incoming=new LinkedBlockingQueue<>(),moves=new LinkedBlockingQueue<>();
    try{
      a.ready.get(3,TimeUnit.SECONDS);b.ready.get(3,TimeUnit.SECONDS);c.ready.get(3,TimeUnit.SECONDS);
      a.listener=e->{if(e.optString("event").equals("request"))incoming.offer(e);};
      b.listener=e->{if(e.optString("event").equals("datagram"))moves.offer(e);};
      c.listener=e->{if(e.optString("event").equals("request"))fromC.offer(e);};
      long first=connect(a,b),second=connect(a,c);String body=QuicManager.encode(Wire.bytes("held"));
      for(int i=0;i<24;i++)a.callAsync("request",Wire.obj("conn",first,"data",body,"timeout",15000));
      busy(a.callAsync("request",Wire.obj("conn",first,"data",body)),"ordinary per-peer requests are bounded at 24");
      for(int i=0;i<24;i++)a.callAsync("request",Wire.obj("conn",second,"data",body,"timeout",15000));
      busy(a.callAsync("request",Wire.obj("conn",second,"data",body)),"ordinary global requests leave control capacity");
      for(int i=0;i<8;i++)a.callAsync("request",Wire.obj("conn",first,"priority",0,"data",body,"timeout",15000));
      busy(a.callAsync("request",Wire.obj("conn",second,"priority",0,"data",body)),"56 outstanding requests preserve local command capacity");
      a.call("datagram",Wire.obj("conn",first,"data",body));check(moves.poll(3,TimeUnit.SECONDS)!=null,"another peer still receives mouse datagrams under request pressure");
      JSONObject held=fromC.poll(3,TimeUnit.SECONDS);check(held!=null,"third endpoint receives reliable work");
      CompletableFuture<JSONObject> reverse=c.callAsync("request",Wire.obj("conn",held.getLong("conn"),"priority",0,"data",body));
      JSONObject request=incoming.poll(3,TimeUnit.SECONDS);check(request!=null,"incoming request arrives while local requests are full");
      a.call("reply",Wire.obj("conn",request.getLong("conn"),"stream",request.getLong("stream"),"data",body));
      reverse.get(3,TimeUnit.SECONDS);

      peer=new Wire(new Socket(),Wire.obj(),Wire.parseToken(service.token),AuxIsolationProbe.IDLE);peer.id=UUID.randomUUID().toString();peer.connected=true;service.peers.put(peer.id,peer);
      manager.enabled=true;manager.nativeEngine=a;manager.bind(peer,first);
      // Eight in-flight local acknowledgements are the only remaining capacity.
      for(int i=0;i<8;i++)a.pending.put("probe-local-"+i,new QuicNative.Pending("reply",Wire.obj()));
      manager.request(peer,"input",Wire.obj("kind","relative","dx",5,"dy",0),1500);
      check(peer.quicConn==first&&peer.quicTx.seq==1,"local busy keeps connection and cumulative mouse snapshot");
      for(int i=0;i<8;i++)a.pending.remove("probe-local-"+i);
      manager.request(peer,"input",Wire.obj("kind","relative","dx",7,"dy",0),1500);
      JSONObject delivered=moves.poll(3,TimeUnit.SECONDS);check(delivered!=null,"mouse resumes after local capacity recovers");
      JSONObject motion=new JSONObject(new String(QuicManager.decode(delivered),java.nio.charset.StandardCharsets.UTF_8));
      check(motion.getDouble("dx")==12&&peer.quicConn==first,"busy sample is recovered without duplicate delivery or peer loss");
    }finally{
      if(peer!=null){service.peers.remove(peer.id,peer);manager.drop(peer);peer.close();}
      manager.close();a.close();b.close();c.close();
    }
  }
}
