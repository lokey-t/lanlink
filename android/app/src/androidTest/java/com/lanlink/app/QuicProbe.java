package com.lanlink.app;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

final class QuicProbe {
  static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
  static String run(LinkService service)throws Exception {
    QuicNative a=new QuicNative(new File(service.getCacheDir(),"quic-probe-a")),b=new QuicNative(new File(service.getCacheDir(),"quic-probe-b"));
    ArrayBlockingQueue<JSONObject> moves=new ArrayBlockingQueue<>(8),requests=new ArrayBlockingQueue<>(8);
    try{
      a.ready.get(3,TimeUnit.SECONDS);JSONObject info=b.ready.get(3,TimeUnit.SECONDS);
      b.listener=e->{if(e.optString("event").equals("datagram"))moves.offer(e);if(e.optString("event").equals("request"))requests.offer(e);};
      String ticket=Wire.hex(VideoWire.random())+Wire.hex(VideoWire.random());ticket=ticket.substring(0,64);
      b.call("ticket",Wire.obj("ticket",ticket,"owner",UUID.randomUUID().toString()));
      long conn=a.call("connect",Wire.obj("address","127.0.0.1:"+info.getInt("port"),"owner",UUID.randomUUID().toString(),"ticket",ticket,"fingerprint",info.getString("fingerprint"),"timeout",5000)).getLong("conn");
      QuicMotion.Sender sender=new QuicMotion.Sender();QuicMotion.Receiver receiver=new QuicMotion.Receiver();sender.move(Wire.obj("kind","relative","dx",5,"dy",2));JSONObject latest=sender.move(Wire.obj("kind","relative","dx",8,"dy",-1));
      a.call("datagram",Wire.obj("conn",conn,"data",QuicManager.encode(Wire.bytes(latest.toString()))));JSONObject move=moves.poll(3,TimeUnit.SECONDS);check(move!=null,"native mouse Datagram arrived");List<JSONObject> inputs=receiver.accept(new JSONObject(new String(QuicManager.decode(move),java.nio.charset.StandardCharsets.UTF_8)));check(inputs.size()==1&&inputs.get(0).getDouble("dx")==13,"lost movement recovered");check(receiver.accept(latest).isEmpty(),"duplicates ignored");
      sender.move(Wire.obj("kind","relative","dx",7,"dy",0));CompletableFuture<JSONObject> reply=a.callAsync("request",Wire.obj("conn",conn,"data",QuicManager.encode(Wire.bytes(Wire.obj("kind","button","__motion",sender.snapshot).toString())),"timeout",5000));JSONObject request=requests.poll(3,TimeUnit.SECONDS);check(request!=null,"reliable click arrived");JSONObject click=new JSONObject(new String(QuicManager.decode(request),java.nio.charset.StandardCharsets.UTF_8));check(receiver.accept(click.getJSONObject("__motion")).get(0).getDouble("dx")==7,"click corrects final lost motion");b.call("reply",Wire.obj("conn",request.getLong("conn"),"stream",request.getLong("stream"),"data",QuicManager.encode(Wire.bytes("clicked"))));check(new String(QuicManager.decode(reply.get(3,TimeUnit.SECONDS)),java.nio.charset.StandardCharsets.UTF_8).equals("clicked"),"reliable reply");
      sender.reset();receiver.reset(sender.epoch);
      MemoryProcess process=new MemoryProcess();java.lang.reflect.Constructor<VirtualHid> constructor=VirtualHid.class.getDeclaredConstructor(Process.class);constructor.setAccessible(true);VirtualHid hid=constructor.newInstance(process);
      try{
        for(JSONObject input:receiver.accept(sender.move(Wire.obj("kind","move","x",.5,"y",.5))))hid.move(input,1000,2000);
        sender.move(Wire.obj("kind","move","x",.5,"y",1,"dx",0,"dy",500)); // Drop the first move at the logical bottom.
        latest=sender.move(Wire.obj("kind","move","x",.5,"y",1,"dx",0,"dy",80));
        a.call("datagram",Wire.obj("conn",conn,"data",QuicManager.encode(Wire.bytes(latest.toString()))));move=moves.poll(3,TimeUnit.SECONDS);check(move!=null,"raw mouse Datagram arrived");
        for(JSONObject input:receiver.accept(new JSONObject(new String(QuicManager.decode(move),java.nio.charset.StandardCharsets.UTF_8))))hid.move(input,1000,2000);
        check(process.distance("REL_Y")==580,"kernel mouse preserves distance after loss at clamped bottom");
        for(int i=0;i<3;i++)sender.move(Wire.obj("kind","move","x",.5,"y",1,"dx",0,"dy",800));
        reply=a.callAsync("request",Wire.obj("conn",conn,"data",QuicManager.encode(Wire.bytes(Wire.obj("kind","button","__motion",sender.snapshot).toString())),"timeout",5000));request=requests.poll(3,TimeUnit.SECONDS);check(request!=null,"raw click barrier arrived");click=new JSONObject(new String(QuicManager.decode(request),java.nio.charset.StandardCharsets.UTF_8));
        for(JSONObject input:receiver.accept(click.getJSONObject("__motion")))hid.move(input,1000,2000);
        check(process.distance("REL_Y")==2980,"burst loss recovers bounded raw chunks before click");check(receiver.accept(sender.snapshot).isEmpty(),"click snapshot does not double move");check(receiver.accept(latest).isEmpty(),"old packet does not reverse recovery");
        b.call("reply",Wire.obj("conn",request.getLong("conn"),"stream",request.getLong("stream"),"data",QuicManager.encode(Wire.bytes("clicked"))));reply.get(3,TimeUnit.SECONDS);
        for(JSONObject input:receiver.accept(sender.move(Wire.obj("kind","move","x",.5,"y",1,"dx",0,"dy",80))))hid.move(input,1000,2000);
        check(process.distance("REL_Y")==3060,"still moves down when logical coordinate remains one");
        for(JSONObject input:receiver.accept(sender.move(Wire.obj("kind","move","x",.5,"y",.99,"dx",0,"dy",-40))))hid.move(input,1000,2000);
        check(process.distance("REL_Y")==3020,"reverse motion works after recovery");
        for(int i=0;i<40;i++)sender.move(Wire.obj("kind","move","x",.5,"y",1,"dx",0,"dy",1000));
        for(JSONObject input:receiver.accept(sender.snapshot))hid.move(input,1000,2000);
        check(process.distance("REL_Y")==3020,"long outage does not replay excessive stale motion");
        for(JSONObject input:receiver.accept(sender.move(Wire.obj("kind","move","x",.5,"y",1,"dx",0,"dy",25))))hid.move(input,1000,2000);
        check(process.distance("REL_Y")==3045,"live motion resumes after excessive recovery gap");
      }finally{hid.close();}
      return "PASS: Android JNI QUIC handshake, raw boundary loss recovery, bounded burst recovery, duplicate/reorder suppression, reliable click barrier and production uinput command output";
    }finally{a.close();b.close();}
  }
  /** Captures production uinput commands without injecting test input into the user's phone. */
  private static final class MemoryProcess extends Process {
    final ByteArrayOutputStream output=new ByteArrayOutputStream();boolean alive=true;
    public OutputStream getOutputStream(){return output;}
    public InputStream getInputStream(){return new ByteArrayInputStream(new byte[0]);}
    public InputStream getErrorStream(){return new ByteArrayInputStream(new byte[0]);}
    public int waitFor(){return 0;}
    public int exitValue(){return 0;}
    public void destroy(){alive=false;}
    public boolean isAlive(){return alive;}
    int distance(String axis)throws Exception {
      int total=0;for(String line:output.toString("UTF-8").split("\n")){JSONObject m=new JSONObject(line);if(!m.optString("command").equals("inject"))continue;JSONArray events=m.getJSONArray("events");for(int i=0;i<events.length();i+=3)if(events.getString(i).equals("EV_REL")&&events.getString(i+1).equals(axis))total+=events.getInt(i+2);}return total;
    }
  }
}
