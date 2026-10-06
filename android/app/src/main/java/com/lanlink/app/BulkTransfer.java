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
    service.requireClipboardPermission(peer);if(servers.size()+sockets.size()>=4)throw new IOException("文件传输会话已满");
    long offset=args.getLong("offset"),end=args.getLong("end");if(offset<0||end<offset||end>2L*1024*1024*1024)throw new IOException("文件范围无效");service.fileBytes(args,peer.id);
    ServerSocket server=new ServerSocket(0);server.setSoTimeout(6000);servers.add(server);byte[] secret=VideoWire.random();
    service.jobs.execute(()->{Socket socket=null;try{socket=server.accept();synchronized(this){servers.remove(server);sockets.add(socket);}server.close();VideoWire wire=VideoWire.accept(socket,secret);long position=offset,seq=0,nextAt=System.nanoTime();
      while(position<end){service.requireClipboardPermission(peer);if(!peer.connected)throw new IOException("文件连接已结束");JSONObject request=new JSONObject(args.toString()).put("offset",position);byte[] data=service.fileBytes(request,peer.id);if(data.length>end-position)data=Arrays.copyOf(data,(int)(end-position));if(data.length==0)throw new IOException("文件已变化");byte[] encrypted=cipher(wire.key,seq++,Cipher.ENCRYPT_MODE).doFinal(VideoWire.join(Wire.sha(data),data));wire.output.writeInt(encrypted.length);wire.output.write(encrypted);wire.output.flush();position+=data.length;
      long rate=service.bulkRate()/Math.max(1,sockets.size());nextAt+=data.length*1000000000L/rate;if(position<end)Thread.sleep(Math.max(1,(nextAt-System.nanoTime())/1000000));}
      if(args.optBoolean("completeAck")){byte[] ack=new byte[32];wire.input.readFully(ack);if(!java.security.MessageDigest.isEqual(ack,Wire.hmac(wire.key,Wire.bytes("bulk-complete:"+end))))throw new IOException("文件接收确认无效");}
    }catch(Exception ignored){}finally{servers.remove(server);try{server.close();}catch(Exception ignored){}if(socket!=null){sockets.remove(socket);try{socket.close();}catch(Exception ignored){}}}});
    return Wire.obj("protocol",1,"port",server.getLocalPort(),"key",Wire.hex(secret),"completeAck",args.optBoolean("completeAck"));
  }
  public void close(){for(ServerSocket server:servers)try{server.close();}catch(Exception ignored){}for(Socket socket:sockets)try{socket.close();}catch(Exception ignored){}servers.clear();sockets.clear();}
}
