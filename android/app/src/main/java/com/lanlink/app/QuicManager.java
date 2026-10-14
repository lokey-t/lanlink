package com.lanlink.app;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.security.SecureRandom;
import org.json.*;

/** Authenticated primary membership binds a short-lived QUIC connection ticket. */
final class QuicManager implements AutoCloseable {
  final LinkService service;QuicNative nativeEngine;JSONObject info;volatile boolean enabled;
  final Map<Long,Wire> connections=new ConcurrentHashMap<>();
  final Map<Long,QuicMotion.Pacing> motionRates=new ConcurrentHashMap<>();
  final Map<String,Wire> offers=new ConcurrentHashMap<>();
  final Map<String,QuicVideo> videos=new ConcurrentHashMap<>();
  final Set<Wire> opening=ConcurrentHashMap.newKeySet();
  final ScheduledExecutorService clock=Executors.newSingleThreadScheduledExecutor();
  static final Set<String> NOTIFY=new HashSet<>(Arrays.asList("clipboard","clipboard-record","clipboard-inventory","cursor","group-members","group-dissolved","group-removed","control-ended","master-selection","layout-update","edge-glow"));
  QuicManager(LinkService service){this.service=service;}
  void start(){if(!QuicNative.available)return;try{nativeEngine=new QuicNative(new File(service.getFilesDir(),"quic"));info=nativeEngine.ready.get(3,TimeUnit.SECONDS);nativeEngine.listener=new QuicNative.Listener(){public void event(JSONObject e){QuicManager.this.event(e);}public void motion(long conn,byte[] bytes){QuicManager.this.motion(conn,bytes);}};enabled=true;clock.scheduleWithFixedDelay(()->{for(Wire p:service.peers.values())service.jobs.execute(()->{if(p.quicConn!=0){try{request(p,"linkPing",Wire.obj(),2500);}catch(Exception error){if(!QuicNative.isBusy(error))drop(p);}}else connect(p);});},1,4,TimeUnit.SECONDS);}catch(Exception error){if(nativeEngine!=null)nativeEngine.close();}}
  boolean valid(Wire p){return enabled&&p.connected&&service.peers.get(p.id)==p&&p.matchesGroupToken(service.token)&&service.group!=null&&!service.group.excluded(p.id);}
  static String encode(byte[] b){return Base64.getEncoder().encodeToString(b);}
  static byte[] decode(JSONObject e)throws Exception{return Base64.getDecoder().decode(e.getString("data"));}
  JSONObject offer(Wire p)throws Exception{if(!valid(p))throw new IOException("QUIC 不可用");byte[] random=new byte[32];new SecureRandom().nextBytes(random);String ticket=Wire.hex(random),owner=UUID.randomUUID().toString();offers.put(owner,p);clock.schedule(()->offers.remove(owner),6500,TimeUnit.MILLISECONDS);nativeEngine.call("ticket",Wire.obj("owner",owner,"ticket",ticket));return new JSONObject(info.toString()).put("ticket",ticket).put("protocol",1);}
  void connect(Wire p){if(!valid(p)||p.fastPath!=null||p.quicConn!=0||p.info.optJSONObject("capabilities")==null||p.info.optJSONObject("capabilities").optInt("quic")!=1||service.deviceId.compareTo(p.id)>0||p.quicRetry>System.currentTimeMillis()||!opening.add(p))return;
    String owner=UUID.randomUUID().toString();offers.put(owner,p);
    try{JSONObject offer=p.requestDirect("quicOpen",Wire.obj(),3000);int port=offer.getInt("port");String fingerprint=offer.getString("fingerprint"),ticket=offer.getString("ticket");if(offer.optInt("protocol")!=1||port<1||port>65535||!fingerprint.matches("[a-f0-9]{64}")||!ticket.matches("[a-f0-9]{64}"))throw new IOException("QUIC 协商无效");String host=p.socket.getInetAddress().getHostAddress();if(host.contains(":"))host="["+host+"]";JSONObject result=nativeEngine.call("connect",Wire.obj("address",host+":"+port,"owner",owner,"fingerprint",fingerprint,"ticket",ticket,"timeout",6000));if(valid(p))bind(p,result.getLong("conn"));else nativeEngine.callAsync("close",result);}
    catch(Exception error){p.quicRetry=System.currentTimeMillis()+15000;}finally{offers.remove(owner);opening.remove(p);}}
  synchronized void bind(Wire p,long conn){if(!valid(p)||p.quicConn!=0&&p.quicConn!=conn){nativeEngine.callAsync("close",Wire.obj("conn",conn));return;}if(p.quicConn==conn)return;p.quic=this;p.quicConn=conn;p.quicTx=new QuicMotion.Sender();p.quicRx=new QuicMotion.Receiver();p.inputTx=p.inputRx=0;p.inputPending=null;p.heldMotion=null;p.quicInputs=new QuicInputQueue(service.inputJobs);connections.put(conn,p);motionRates.put(conn,new QuicMotion.Pacing());}
  boolean modern(Wire p){return p.quicConn!=0&&nativeEngine.binaryMotion&&p.info.optJSONObject("capabilities")!=null&&p.info.optJSONObject("capabilities").optInt("motionV2")==2;}
  JSONObject request(Wire p,String method,JSONObject original,long timeout)throws Exception{
    if(!valid(p)||p.quicConn==0)throw new IOException("QUIC 会话已结束");JSONObject a=new JSONObject(original.toString());long conn=p.quicConn;
    boolean moving=method.equals("input")&&(a.optString("kind").equals("move")||a.optString("kind").equals("relative"));
    if(!moving&&!method.equals("keepControl")&&AuxLink.CONTROL.contains(method)&&p.inputPending!=null){p.inputPending.get(timeout+500,TimeUnit.MILLISECONDS);p.inputPending=null;if(p.quicConn!=conn)throw new IOException("QUIC 会话已结束");}
    if(method.equals("acquire")||method.equals("inputReset")||method.equals("release")){p.quicTx.reset();p.inputTx=0;a.put("__motionEpoch",p.quicTx.epoch);}
    if(method.equals("input")&&(a.optString("kind").equals("move")||a.optString("kind").equals("relative"))){QuicMotion.Pacing pacing=motionRates.get(conn);try{if(pacing!=null)pacing.pace();JSONObject snapshot=p.quicTx.move(a);if(modern(p)){snapshot.put("afterInput",p.inputTx);nativeEngine.sendMotion(conn,QuicMotion.encode(snapshot));return Wire.obj();}JSONObject sent=nativeEngine.call("datagram",Wire.obj("conn",conn,"data",encode(Wire.bytes(snapshot.toString())),"timeout",timeout));if(pacing!=null&&sent.has("queued"))pacing.feedback(null,sent.optLong("queued"));return Wire.obj();}catch(Exception error){if(QuicNative.isBusy(error)||String.valueOf(error.getMessage()).matches(".*\\bDone\\b.*")){if(pacing!=null)pacing.feedback(null,131072L);return Wire.obj();}drop(p);throw error;}}
    if(method.equals("input")&&modern(p))a.put("__inputSeq",++p.inputTx).put("__inputEpoch",p.quicTx.epoch);
    if(method.equals("input")&&p.quicTx.snapshot!=null)a.put("__motion",p.quicTx.barrier());
    long started=android.os.SystemClock.elapsedRealtime();JSONObject command=Wire.obj("conn",conn,"priority",AuxLink.CONTROL.contains(method)||method.equals("linkPing")?0:127,"data",encode(Wire.bytes(Wire.obj("method",method,"args",a).toString())),"timeout",timeout);
    if(method.equals("input")&&modern(p)){
      CompletableFuture<JSONObject> pending=nativeEngine.callAsync("request",command).thenApply(result->{try{JSONObject reply=new JSONObject(new String(decode(result),java.nio.charset.StandardCharsets.UTF_8));if(reply.has("error"))throw new IOException(reply.getString("error"));QuicMotion.Pacing pacing=motionRates.get(conn);if(pacing!=null)pacing.feedback((double)(android.os.SystemClock.elapsedRealtime()-started),null);return reply.optJSONObject("result");}catch(Exception error){throw new CompletionException(error);}});
      p.inputPending=pending;pending.whenComplete((value,error)->{if(error!=null&&p.quicConn==conn)drop(p);});return Wire.obj();
    }
    JSONObject result=nativeEngine.call("request",command);QuicMotion.Pacing pacing=motionRates.get(conn);if(pacing!=null&&QuicMotion.rttSample(method,a))pacing.feedback((double)(android.os.SystemClock.elapsedRealtime()-started),null);JSONObject reply=new JSONObject(new String(decode(result),java.nio.charset.StandardCharsets.UTF_8));if(reply.has("error"))throw new IOException(reply.getString("error"));return reply.optJSONObject("result")==null?Wire.obj():reply.getJSONObject("result");
  }
  void notify(Wire p,JSONObject value){service.jobs.execute(()->{try{request(p,"__notify",value,5000);}catch(Exception error){if(!QuicNative.isBusy(error))drop(p);try{p.sendDirect(value);}catch(Exception ignored){}}});}
  void event(JSONObject e){String type=e.optString("event");long conn=e.optLong("conn");Wire p=connections.get(conn);
    if(type.equals("engineClosed")){enabled=false;reset();return;}
    if(type.equals("connected")){Wire offered=offers.get(e.optString("owner"));if(offered!=null)bind(offered,conn);return;}
    if(type.equals("closed")){if(p!=null)drop(p);return;}
    if(type.equals("video")){QuicVideo video=videos.get(conn+":"+e.optLong("channel"));if(video!=null)video.receive(e);return;}
    if(type.equals("stats")){QuicMotion.Pacing pacing=motionRates.get(conn);if(pacing!=null&&e.has("queued"))pacing.feedback(null,e.optLong("queued"));for(QuicVideo video:videos.values())if(video.conn==conn)video.queued=e.optLong("queued");return;}
    if(p==null||!valid(p)||p.quicConn!=conn)return;
    if(type.equals("datagram")){try{JSONObject motion=new JSONObject(new String(decode(e),java.nio.charset.StandardCharsets.UTF_8));p.quicInputs.add(motion,()->{try{if(valid(p)&&p.quicConn==conn)applyMotion(p,motion);}catch(Exception ignored){}});}catch(Exception ignored){}return;}
    if(type.equals("request")){try{JSONObject message=new JSONObject(new String(decode(e),java.nio.charset.StandardCharsets.UTF_8));String method=message.getString("method");if(AuxLink.CONTROL.contains(method)||method.equals("prepare"))p.quicInputs.add(null,()->incoming(p,e,message));else service.jobs.execute(()->incoming(p,e,message));}catch(Exception error){drop(p);}}
  }
  void motion(long conn,byte[] bytes){Wire p=connections.get(conn);if(p==null||!valid(p)||p.quicConn!=conn)return;try{JSONObject m=QuicMotion.decode(bytes);p.quicInputs.add(m,()->{try{if(valid(p)&&p.quicConn==conn)applyMotion(p,m);}catch(Exception ignored){}});}catch(Exception ignored){}}
  void applyMotion(Wire p,JSONObject motion)throws Exception{
    if(motion.has("afterInput")){long after=motion.getLong("afterInput");if(!motion.optString("epoch").equals(p.quicRx.epoch)||after<0||after>p.inputRx+1)return;if(after>p.inputRx){if(p.heldMotion==null||motion.optLong("seq")>p.heldMotion.optLong("seq"))p.heldMotion=motion;return;}}
    service.motionInputs(p,p.quicRx.accept(motion));}
  void incoming(Wire p,JSONObject e,JSONObject message){byte[] response;String method=message.optString("method");try{
    if(!valid(p)||p.quicConn!=e.getLong("conn"))throw new IOException("QUIC 会话已结束");JSONObject a=message.getJSONObject("args");
    if(method.equals("acquire")||method.equals("inputReset")||method.equals("release")){String epoch=a.optString("__motionEpoch",null);if(epoch==null||epoch.length()!=36)throw new IOException("鼠标会话无效");p.quicRx.reset(epoch);p.inputRx=0;p.heldMotion=null;a.remove("__motionEpoch");}
    if(method.equals("input")&&a.has("__inputSeq"))service.requireOwner(p);
    long sequence=a.optLong("__inputSeq",-1);if(a.has("__inputSeq")&&(sequence!=p.inputRx+1||!a.optString("__inputEpoch").equals(p.quicRx.epoch)))throw new IOException("输入顺序无效");a.remove("__inputSeq");a.remove("__inputEpoch");
    if(a.has("__motion")){applyMotion(p,a.getJSONObject("__motion"));a.remove("__motion");}
    if(method.equals("quicFile")){service.requireClipboardPermission(p);byte[] bytes=service.bulk.quicBytes(p,a);ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(0);out.write(Wire.sha(bytes));out.write(bytes);response=out.toByteArray();}
    else {JSONObject result;if(method.equals("__notify")){if(!NOTIFY.contains(a.optString("type")))throw new IOException("QUIC 消息无效");p.receiveNotify(a);result=Wire.obj();}else result=service.handle(p,method,a);if(sequence>=0){p.inputRx=sequence;JSONObject held=p.heldMotion;p.heldMotion=null;if(held!=null)applyMotion(p,held);}response=Wire.bytes(Wire.obj("result",result).toString());}
  }catch(Exception error){response=method.equals("quicFile")?Wire.bytes("\u0001"+error.getMessage()):Wire.bytes(Wire.obj("error",error.getMessage()).toString());}
    nativeEngine.callAsync("reply",Wire.obj("conn",e.optLong("conn"),"stream",e.optLong("stream"),"data",encode(response)));
  }
  byte[] file(Wire p,JSONObject a)throws Exception{JSONObject response=nativeEngine.call("request",Wire.obj("conn",p.quicConn,"data",encode(Wire.bytes(Wire.obj("method","quicFile","args",a).toString())),"timeout",15000));byte[] data=decode(response);if(data.length<33||data[0]!=0)throw new IOException("QUIC 文件传输失败");byte[] bytes=Arrays.copyOfRange(data,33,data.length);if(!java.security.MessageDigest.isEqual(Wire.sha(bytes),Arrays.copyOfRange(data,1,33)))throw new IOException("QUIC 文件校验失败");return bytes;}
  QuicVideo video(Wire p,long channel)throws Exception{if(!valid(p)||p.quicConn==0)throw new IOException("QUIC 串流未就绪");QuicVideo video=new QuicVideo(this,p.quicConn,channel);QuicVideo old=videos.put(p.quicConn+":"+channel,video);if(old!=null)old.close();return video;}
  synchronized void drop(Wire p){long conn=p.quicConn;if(conn==0)return;p.quicConn=0;p.quicRetry=System.currentTimeMillis()+15000;connections.remove(conn);motionRates.remove(conn);for(QuicVideo video:new ArrayList<>(videos.values()))if(video.conn==conn)video.close();nativeEngine.callAsync("close",Wire.obj("conn",conn));service.inputJobs.execute(()->{try{service.handle(p,"release",Wire.obj());}catch(Exception ignored){}});service.jobs.execute(()->{try{if(p.connected)p.requestDirect("release",Wire.obj(),1500);}catch(Exception ignored){}});}
  void reset(){offers.clear();for(Wire p:new ArrayList<>(connections.values()))drop(p);if(nativeEngine!=null)nativeEngine.callAsync("resetTickets",Wire.obj());}
  public void close(){enabled=false;clock.shutdownNow();reset();if(nativeEngine!=null)nativeEngine.close();}
}
