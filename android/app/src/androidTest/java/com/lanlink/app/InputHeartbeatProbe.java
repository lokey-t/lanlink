package com.lanlink.app;

import java.net.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;

/** A delayed encrypted lease reply must not stall input or create parallel renewals. */
final class InputHeartbeatProbe {
  static void run() throws Exception { channel(false); channel(true); }

  static void channel(boolean auxiliary) throws Exception {
    ExecutorService callers=Executors.newFixedThreadPool(3);
    Wire phone=null,remote=null,controller=null;
    CountDownLatch ready=new CountDownLatch(2),leaseReceived=new CountDownLatch(1);
    AtomicInteger leases=new AtomicInteger();
    AtomicReference<JSONObject> pendingLease=new AtomicReference<>();
    AtomicReference<Exception> failure=new AtomicReference<>();
    byte[] key=Wire.parseToken(Wire.token());
    try(ServerSocket listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
      Socket client=new Socket(InetAddress.getLoopbackAddress(),listener.getLocalPort());
      Socket accepted=listener.accept();
      Wire.Listener idle=new Wire.Listener(){
        public void ready(Wire p){ready.countDown();}
        public void closed(Wire p){}
        public void message(Wire p,JSONObject message){}
      };
      phone=new Wire(client,Wire.obj("id",UUID.randomUUID().toString()),key,idle);
      remote=new Wire(accepted,Wire.obj("id",UUID.randomUUID().toString()),key,new Wire.Listener(){
        public void ready(Wire p){ready.countDown();}
        public void closed(Wire p){}
        public void message(Wire p,JSONObject message){
          if(!message.optString("type").equals("request"))return;
          if(message.optString("method").equals("keepControl")&&leases.incrementAndGet()==1){pendingLease.set(message);leaseReceived.countDown();return;}
          try{p.send(Wire.obj("type","response","id",message.getString("id"),"result",Wire.obj()));}
          catch(Exception error){failure.set(error);}
        }
      });
      phone.start();remote.start();
      AdaptiveTransportProbe.check(ready.await(3,TimeUnit.SECONDS),"encrypted heartbeat fixture connects");
      controller=phone;
      if(auxiliary){controller=new Wire(new Socket(),Wire.obj(),key,idle);controller.fastPath=phone;}
      final Wire sender=controller;
      Future<JSONObject> first=callers.submit(()->sender.request("keepControl",Wire.obj()));
      AdaptiveTransportProbe.check(leaseReceived.await(2,TimeUnit.SECONDS),"lease reaches selected transport");
      AtomicReference<Thread> secondThread=new AtomicReference<>();
      Future<JSONObject> second=callers.submit(()->{secondThread.set(Thread.currentThread());return sender.request("keepControl",Wire.obj());});
      Future<JSONObject> move=callers.submit(()->sender.request("input",Wire.obj("kind","relative","dx",1,"dy",0)));
      move.get(1,TimeUnit.SECONDS);
      AdaptiveTransportProbe.check(!first.isDone()&&!second.isDone()&&leases.get()==1,"input proceeds before heartbeat response and renewals stay single-flight");
      // Ensure the second caller actually joined the first FutureTask before
      // completing it; an unscheduled caller is a new renewal, not a duplicate.
      long joinedBy=System.currentTimeMillis()+1000;
      while((secondThread.get()==null||secondThread.get().getState()!=Thread.State.WAITING)&&System.currentTimeMillis()<joinedBy)Thread.sleep(2);
      AdaptiveTransportProbe.check(secondThread.get()!=null&&secondThread.get().getState()==Thread.State.WAITING,"second caller joins the in-flight renewal");
      remote.send(Wire.obj("type","response","id",pendingLease.get().getString("id"),"result",Wire.obj()));
      first.get(2,TimeUnit.SECONDS);second.get(2,TimeUnit.SECONDS);
      sender.request("keepControl",Wire.obj());
      AdaptiveTransportProbe.check(leases.get()==2&&failure.get()==null,"completed heartbeat allows next lease");
    } finally {
      if(controller!=null)controller.close();
      if(phone!=null)phone.close();
      if(remote!=null)remote.close();
      callers.shutdownNow();
    }
  }
}
