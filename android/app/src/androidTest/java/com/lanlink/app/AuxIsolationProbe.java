package com.lanlink.app;

import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Real authenticated auxiliary sockets retain their primary peer and selected-link identity. */
final class AuxIsolationProbe {
  static final Wire.Listener IDLE=new Wire.Listener(){
    public void ready(Wire peer){}
    public void closed(Wire peer){}
    public void message(Wire peer,JSONObject message){}
  };
  static final class Fixture extends LinkService {
    final List<String> lost=new CopyOnWriteArrayList<>(),requests=new CopyOnWriteArrayList<>();
    @Override void auxInputLost(Wire peer){lost.add(peer.id);}
    @Override public void message(Wire peer,JSONObject message,Wire reply){
      requests.add(peer.id+":"+message.optString("method"));
      try{reply.send(Wire.obj("type","response","id",message.getString("id"),"result",Wire.obj()));}
      catch(Exception error){throw new AssertionError(error);}
    }
  }
  static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
  static Wire primary(Fixture service){
    Wire peer=new Wire(new Socket(),Wire.obj(),Wire.parseToken(service.token),IDLE);
    peer.id=UUID.randomUUID().toString();peer.connected=true;service.peers.put(peer.id,peer);return peer;
  }
  static Wire connect(AuxLink link,Wire primary)throws Exception{
    CountDownLatch ready=new CountDownLatch(1);
    Wire client=new Wire(new Socket("127.0.0.1",link.server.getLocalPort()),Wire.obj("id",primary.id),Wire.parseToken(link.secret),new Wire.Listener(){
      public void ready(Wire peer){ready.countDown();}
      public void closed(Wire peer){}
      public void message(Wire peer,JSONObject message){}
    });
    client.start();check(ready.await(3,TimeUnit.SECONDS),"auxiliary peer authenticates");
    client.requestDirect("linkUse",Wire.obj(),1500);return client;
  }
  static String run(LinkService live)throws Exception{
    Fixture service=new Fixture();service.group=live.group;service.token=live.token;
    Wire windows=primary(service),mac=primary(service);
    String phoneId=UUID.randomUUID().toString();
    List<AuxLink> links=new ArrayList<>();List<Wire> clients=new ArrayList<>();
    try{
      AuxLink unused=new AuxLink(service,mac,Wire.obj("id",phoneId));links.add(unused);unused.close();
      check(service.lost.isEmpty(),"never-connected offer cannot release Mac LAN/QUIC input (null is not an active link)");
      AuxLink first=new AuxLink(service,windows,Wire.obj("id",phoneId));links.add(first);clients.add(connect(first,windows));
      check(windows.fastPath==first.wire&&mac.fastPath==null,"Windows USB link cannot become Mac fast path");
      clients.get(0).requestDirect("input",Wire.obj("kind","relative","dx",1,"dy",0),1500);
      check(service.requests.equals(Collections.singletonList(windows.id+":input")),"auxiliary requests keep their authenticated primary identity");
      AuxLink replacement=new AuxLink(service,windows,Wire.obj("id",phoneId));links.add(replacement);clients.add(connect(replacement,windows));
      first.close();
      check(windows.fastPath==replacement.wire&&service.lost.isEmpty(),"old auxiliary close cannot clear or release its replacement");
      check(!windows.clearFastPath(first.wire)&&windows.fastPath==replacement.wire,"late old-request failure cannot clear the new link");
      AuxLink macProbe=new AuxLink(service,mac,Wire.obj("id",phoneId));links.add(macProbe);macProbe.close();
      check(service.lost.isEmpty()&&windows.fastPath==replacement.wire,"Mac auxiliary probe cannot disturb either live input route");
      replacement.close();
      check(service.lost.equals(Collections.singletonList(windows.id))&&mac.fastPath==null,"only selected Windows link signals loss; Mac is never released");
      return "PASS: authenticated Windows auxiliary identity, untouched Mac LAN path, unused-offer close, replaced-link close and late failure isolation";
    }finally{
      for(AuxLink link:links)link.close();for(Wire client:clients)client.close();windows.close();mac.close();
      service.jobs.shutdownNow();service.inputJobs.shutdownNow();service.quic.close();
    }
  }
}
