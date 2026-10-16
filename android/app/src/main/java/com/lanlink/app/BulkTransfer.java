package com.lanlink.app;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.json.*;

/** Independent authenticated binary file transport; no file payload on the input connection. */
final class BulkTransfer implements AutoCloseable {
  static final int CHUNK=192*1024;
  final LinkService service;
  final Set<ServerSocket> servers=ConcurrentHashMap.newKeySet();
  final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
  static final class Slot {long nextAt,until;Slot(long now){nextAt=now;until=now+10000;}}
  final Map<String,Slot> quicStreams=new HashMap<>();
  static long requestedRate(JSONObject args)throws IOException{if(!args.has("maxRate"))return 32L*1024*1024;Object value=args.opt("maxRate");if(!(value instanceof Number))throw new IOException("文件速率无效");double rate=((Number)value).doubleValue();if(!Double.isFinite(rate)||rate!=Math.floor(rate)||rate<128||rate>32L*1024*1024)throw new IOException("文件速率无效");return (long)rate;}
  static int slice(long rate,int requested){return Math.max(1,Math.min(CHUNK,Math.min(requested,Math.max(128,(int)(rate/10)))));}
  synchronized long rate(){long now=android.os.SystemClock.elapsedRealtime();quicStreams.values().removeIf(slot->slot.until<now);return service.bulkRate()/Math.max(1,servers.size()+sockets.size()+quicStreams.size());}
  byte[] quicBytes(Wire peer,JSONObject args)throws Exception{
    service.requireClipboardPermission(peer);long requested=requestedRate(args);int requestedBytes=LinkService.fileChunkLimit(args);String key=peer.id+":"+args.getString("offerId")+":"+args.getString("fileId");long now=android.os.SystemClock.elapsedRealtime(),start;int maxBytes;
    synchronized(this){rate();Slot slot=quicStreams.get(key);if(slot==null){if(quicStreams.size()>=16)throw new IOException("文件传输会话已满");slot=new Slot(now);quicStreams.put(key,slot);}long budget=Math.min(rate(),requested);maxBytes=slice(budget,requestedBytes);start=Math.max(now,slot.nextAt);slot.nextAt=start+maxBytes*1000L/budget;slot.until=slot.nextAt+10000;}
    try{if(start>now)Thread.sleep(start-now);service.requireClipboardPermission(peer);if(!peer.connected||service.peers.get(peer.id)!=peer)throw new IOException("文件连接已结束");byte[] data=service.fileBytes(new JSONObject(args.toString()).put("maxBytes",maxBytes),peer.id);if(args.has("end")&&args.getLong("offset")+data.length>=args.getLong("end"))synchronized(this){quicStreams.remove(key);}return data;}
    catch(Exception error){synchronized(this){quicStreams.remove(key);}throw error;}
  }
  BulkTransfer(LinkService service){this.service=service;}
  static Cipher cipher(byte[] key,long seq,int mode)throws Exception{Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,ByteBuffer.allocate(12).putInt(0).putLong(seq).array()));c.updateAAD(Wire.bytes("lanlink-bulk-v1:"+seq));return c;}
  static final class Reader implements AutoCloseable {
    final VideoWire wire;final boolean completeAck;long seq;
    Reader(VideoWire wire,boolean completeAck){this.wire=wire;this.completeAck=completeAck;}
    void complete(long end)throws Exception{if(completeAck){wire.output.write(Wire.hmac(wire.key,Wire.bytes("bulk-complete:"+end)));wire.output.flush();}}
    byte[] next()throws Exception{int length=wire.input.readInt();if(length<48||length>CHUNK+48)throw new IOException("文件块无效");byte[] bytes=new byte[length];wire.input.readFully(bytes);byte[] plain=cipher(wire.key,seq++,Cipher.DECRYPT_MODE).doFinal(bytes),data=Arrays.copyOfRange(plain,32,plain.length);if(!java.security.MessageDigest.isEqual(Arrays.copyOf(plain,32),Wire.sha(data)))throw new IOException("文件校验失败");return data;}
    public void close(){wire.close();}
  }
  static Reader connect(Wire peer,JSONObject info)throws Exception{return new Reader(VideoWire.connect(peer.socket.getInetAddress().getHostAddress(),info),info.optBoolean("completeAck"));}
  synchronized JSONObject open(Wire peer,JSONObject args)throws Exception{
    service.requireClipboardPermission(peer);final long requested=requestedRate(args);final int requestedBytes=LinkService.fileChunkLimit(args);if(servers.size()+sockets.size()>=16)throw new IOException("文件传输会话已满");
    long offset=args.getLong("offset"),end=args.getLong("end");if(offset<0||end<offset||end>2L*1024*1024*1024)throw new IOException("文件范围无效");service.fileBytes(new JSONObject(args.toString()).put("maxBytes",1),peer.id);
    ServerSocket server=new ServerSocket(0);server.setSoTimeout(6000);servers.add(server);byte[] secret=VideoWire.random();
    service.jobs.execute(()->{Socket socket=null;try{socket=server.accept();synchronized(this){servers.remove(server);sockets.add(socket);}server.close();VideoWire wire=VideoWire.accept(socket,secret);long position=offset,seq=0,nextAt=System.nanoTime();
      while(position<end){service.requireClipboardPermission(peer);if(!peer.connected)throw new IOException("文件连接已结束");long rate=Math.min(rate(),requested);int maxBytes=slice(rate,requestedBytes);JSONObject request=new JSONObject(args.toString()).put("offset",position).put("maxBytes",maxBytes);byte[] data=service.fileBytes(request,peer.id);if(data.length>end-position)data=Arrays.copyOf(data,(int)(end-position));if(data.length==0)throw new IOException("文件已变化");byte[] encrypted=cipher(wire.key,seq++,Cipher.ENCRYPT_MODE).doFinal(VideoWire.join(Wire.sha(data),data));wire.output.writeInt(encrypted.length);wire.output.write(encrypted);wire.output.flush();position+=data.length;
      nextAt=Math.max(nextAt,System.nanoTime())+data.length*1000000000L/rate;if(position<end)Thread.sleep(Math.max(1,(nextAt-System.nanoTime())/1000000));}
      if(args.optBoolean("completeAck")){byte[] ack=new byte[32];wire.input.readFully(ack);if(!java.security.MessageDigest.isEqual(ack,Wire.hmac(wire.key,Wire.bytes("bulk-complete:"+end))))throw new IOException("文件接收确认无效");}
    }catch(Exception ignored){}finally{servers.remove(server);try{server.close();}catch(Exception ignored){}if(socket!=null){sockets.remove(socket);try{socket.close();}catch(Exception ignored){}}}});
    return Wire.obj("protocol",1,"port",server.getLocalPort(),"key",Wire.hex(secret),"completeAck",args.optBoolean("completeAck"));
  }
  public void close(){for(ServerSocket server:servers)try{server.close();}catch(Exception ignored){}for(Socket socket:sockets)try{socket.close();}catch(Exception ignored){}servers.clear();sockets.clear();synchronized(this){quicStreams.clear();}}
}
