package com.lanlink.app;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** Native DATAGRAM fragmentation with a bounded decoder reference chain. */
final class QuicVideo implements VideoTransport {
  final QuicManager manager;final long conn,channel;
  final ArrayBlockingQueue<VideoWire.Frame> frames=new ArrayBlockingQueue<>(8);
  final AtomicInteger inflight=new AtomicInteger();volatile long queued;
  volatile boolean closed,needKey=true;long last=-1,queueAt,keyAt;int queueBytes;
  volatile Runnable onNeedKey;
  QuicVideo(QuicManager manager,long conn,long channel){this.manager=manager;this.conn=conn;this.channel=channel;}
  public void ready()throws Exception{if(closed)throw new IOException("QUIC 串流已关闭");}
  public long queued(){return queued;}
  public boolean needsKey(){return needKey;}
  public void keyCallback(Runnable callback){onNeedKey=callback;}
  void key(){long now=System.currentTimeMillis();if(now-keyAt>500){keyAt=now;Runnable callback=onNeedKey;if(callback!=null)callback.run();}}
  synchronized void receive(JSONObject event){try{if(closed)return;byte[] bytes=QuicManager.decode(event);long seq=event.getLong("sequence");if(seq<=last||bytes.length<16)return;if(last>=0&&seq!=last+1)needKey=true;last=seq;ByteBuffer b=ByteBuffer.wrap(bytes);int codec=b.get()&255;boolean key=b.get()!=0;int width=b.getShort()&65535,height=b.getShort()&65535;b.getShort();long time=b.getLong();if(codec<1||codec>3||width<1||height<1||width>4096||height>4096||(long)width*height>8388608)return;
    if(codec==2&&needKey&&!key){key();return;}VideoWire.Frame frame=new VideoWire.Frame(Arrays.copyOfRange(bytes,16,bytes.length),width,height,codec==2,key);frame.time=time;frame.heartbeat=codec==3;
    if(frame.heartbeat&&!frames.isEmpty())return;if(!frame.heartbeat&&(!frame.h264||frame.key)){frames.clear();queueBytes=0;}
    long now=android.os.SystemClock.elapsedRealtime();if(frames.remainingCapacity()==0||queueBytes+frame.data.length>4*1024*1024||!frames.isEmpty()&&now-queueAt>150){frames.clear();queueBytes=0;needKey=true;if(frame.h264&&!frame.key){key();return;}}
    if(codec!=3)needKey=false;if(frames.isEmpty())queueAt=now;frames.offer(frame);queueBytes+=frame.data.length;
  }catch(Exception ignored){needKey=true;key();}}
  public boolean send(VideoWire.Frame frame)throws Exception{if(closed||queued>128*1024||inflight.get()>=4)return false;byte[] bytes=ByteBuffer.allocate(16+frame.data.length).put((byte)(frame.heartbeat?3:frame.h264?2:1)).put((byte)(frame.key?1:0)).putShort((short)frame.width).putShort((short)frame.height).putShort((short)0).putLong(frame.time).put(frame.data).array();inflight.incrementAndGet();queued+=bytes.length;manager.nativeEngine.callAsync("video",Wire.obj("conn",conn,"channel",channel,"data",QuicManager.encode(bytes),"timeout",3000)).whenComplete((result,error)->{inflight.decrementAndGet();if(error!=null){needKey=true;key();}else queued=result.optLong("queued");});return true;}
  public VideoWire.Frame next()throws Exception{VideoWire.Frame frame=frames.poll(5,TimeUnit.SECONDS);if(frame==null||closed)throw new IOException("QUIC 等待画面超时");synchronized(this){queueBytes=Math.max(0,queueBytes-frame.data.length);if(frames.isEmpty())queueAt=0;}return frame;}
  public synchronized void close(){if(closed)return;closed=true;frames.clear();manager.videos.remove(conn+":"+channel,this);}
}
