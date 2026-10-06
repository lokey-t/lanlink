package com.lanlink.app;

import android.content.Context;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import org.webrtc.*;

/** LAN-only WebRTC/DTLS/SCTP transport for the existing native H.264 encoder and decoder. */
final class RtcVideo implements VideoTransport {
  static final int PART=12000,MAX=4*1024*1024;
  static PeerConnectionFactory factory;
  final PeerConnection pc;
  final Map<String,DataChannel> channels=new ConcurrentHashMap<>();
  final ArrayBlockingQueue<VideoWire.Frame> frames=new ArrayBlockingQueue<>(8);
  long queueAt;int queueBytes;
  final Assembler assembler=new Assembler();
  final CountDownLatch gathered=new CountDownLatch(1);
  volatile boolean closed;int sequence;
  volatile Runnable onNeedKey;long keyAt;
  RtcVideo(Context context)throws Exception{
    synchronized(RtcVideo.class){if(factory==null){PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.getApplicationContext()).createInitializationOptions());factory=PeerConnectionFactory.builder().createPeerConnectionFactory();}}
    PeerConnection.RTCConfiguration config=new PeerConnection.RTCConfiguration(Collections.emptyList());config.tcpCandidatePolicy=PeerConnection.TcpCandidatePolicy.DISABLED;config.bundlePolicy=PeerConnection.BundlePolicy.MAXBUNDLE;
    pc=factory.createPeerConnection(config,new PeerConnection.Observer(){
      public void onSignalingChange(PeerConnection.SignalingState state){}
      public void onIceConnectionChange(PeerConnection.IceConnectionState state){if(state==PeerConnection.IceConnectionState.FAILED)close();}
      public void onIceConnectionReceivingChange(boolean receiving){}
      public void onIceGatheringChange(PeerConnection.IceGatheringState state){if(state==PeerConnection.IceGatheringState.COMPLETE)gathered.countDown();}
      public void onIceCandidate(IceCandidate candidate){}
      public void onIceCandidatesRemoved(IceCandidate[] candidates){}
      public void onAddStream(MediaStream stream){}
      public void onRemoveStream(MediaStream stream){}
      public void onDataChannel(DataChannel channel){attach(channel);}
      public void onRenegotiationNeeded(){}
      public void onAddTrack(RtpReceiver receiver,MediaStream[] streams){}
    });if(pc==null)throw new IOException("WebRTC 初始化失败");
  }
  void attach(DataChannel channel){if(!channel.label().equals("ll-video")&&!channel.label().equals("ll-key")){channel.close();return;}channels.put(channel.label(),channel);channel.registerObserver(new DataChannel.Observer(){
    public void onBufferedAmountChange(long previous){}
    public void onStateChange(){}
    public void onMessage(DataChannel.Buffer buffer){try{if(!buffer.binary)throw new IOException("RTC 消息类型无效");byte[] data=new byte[buffer.data.remaining()];buffer.data.get(data);VideoWire.Frame frame=assembler.push(data);if(frame!=null)enqueue(frame);if(assembler.last>=0&&assembler.needKey&&System.currentTimeMillis()-keyAt>500){keyAt=System.currentTimeMillis();Runnable action=onNeedKey;if(action!=null)action.run();}}catch(Exception error){close();}}
  });}
  static final class SDP implements SdpObserver{
    final CompletableFuture<SessionDescription> result=new CompletableFuture<>();
    public void onCreateSuccess(SessionDescription sdp){result.complete(sdp);}
    public void onSetSuccess(){result.complete(null);}
    public void onCreateFailure(String error){result.completeExceptionally(new IOException(error));}
    public void onSetFailure(String error){result.completeExceptionally(new IOException(error));}
    SessionDescription await()throws Exception{return result.get(3500,TimeUnit.MILLISECONDS);}
  }
  void local(SessionDescription sdp)throws Exception{SDP callback=new SDP();pc.setLocalDescription(callback,sdp);callback.await();if(!gathered.await(3500,TimeUnit.MILLISECONDS))throw new IOException("RTC 局域网地址检测超时");}
  void remote(String sdp,SessionDescription.Type type)throws Exception{if(sdp==null||sdp.length()>200000)throw new IOException("RTC 协商无效");SDP callback=new SDP();pc.setRemoteDescription(callback,new SessionDescription(type,sdp));callback.await();}
  String offer()throws Exception{DataChannel.Init realtime=new DataChannel.Init();realtime.ordered=false;realtime.maxRetransmits=0;attach(pc.createDataChannel("ll-video",realtime));attach(pc.createDataChannel("ll-key",new DataChannel.Init()));SDP callback=new SDP();pc.createOffer(callback,new MediaConstraints());local(callback.await());return pc.getLocalDescription().description;}
  String answer(String sdp)throws Exception{remote(sdp,SessionDescription.Type.OFFER);SDP callback=new SDP();pc.createAnswer(callback,new MediaConstraints());local(callback.await());return pc.getLocalDescription().description;}
  void accept(String sdp)throws Exception{remote(sdp,SessionDescription.Type.ANSWER);}
  public boolean needsKey(){return assembler.needKey;}
  public void keyCallback(Runnable callback){onNeedKey=callback;}
  public void ready()throws Exception{long end=System.currentTimeMillis()+3500;while(System.currentTimeMillis()<end){if(closed)throw new IOException("RTC 连接已结束");if(channels.size()==2&&channels.values().stream().allMatch(c->c.state()==DataChannel.State.OPEN))return;Thread.sleep(20);}throw new IOException("RTC 连接超时");}
  public long queued(){long amount=0;for(DataChannel channel:channels.values())amount+=channel.bufferedAmount();return amount;}
  public boolean send(VideoWire.Frame frame)throws Exception{
    DataChannel channel=channels.get(frame.key?"ll-key":"ll-video");if(closed||channel==null||channel.state()!=DataChannel.State.OPEN||queued()>128*1024)return false;
    if(frame.data.length>MAX-16||frame.width<1||frame.height<1||frame.width>4096||frame.height>4096||(long)frame.width*frame.height>8388608)throw new IOException("RTC 画面无效");
    byte[] body=ByteBuffer.allocate(16+frame.data.length).put((byte)(frame.heartbeat?3:frame.h264?2:1)).put((byte)(frame.key?1:0)).putShort((short)frame.width).putShort((short)frame.height).putShort((short)0).putLong(frame.time).put(frame.data).array();int count=(body.length+PART-1)/PART,id=sequence++;
    for(int index=0;index<count;index++){int offset=index*PART,size=Math.min(PART,body.length-offset);ByteBuffer packet=ByteBuffer.allocate(16+size).putInt(id).putShort((short)index).putShort((short)count).putInt(body.length).putInt(offset).put(body,offset,size);packet.flip();if(!channel.send(new DataChannel.Buffer(packet,true)))return false;}return true;
  }
  synchronized void enqueue(VideoWire.Frame frame){
    if(frame.heartbeat&&!frames.isEmpty())return;
    if(!frame.heartbeat&&(!frame.h264||frame.key)){frames.clear();queueBytes=0;}
    long now=android.os.SystemClock.elapsedRealtime();
    if(frames.remainingCapacity()==0||queueBytes+frame.data.length>MAX||!frames.isEmpty()&&now-queueAt>150){frames.clear();queueBytes=0;if(frame.h264&&!frame.key){assembler.needKey=true;return;}}
    if(frames.isEmpty())queueAt=now;frames.offer(frame);queueBytes+=frame.data.length;
  }
  public VideoWire.Frame next()throws Exception{VideoWire.Frame frame=frames.poll(5,TimeUnit.SECONDS);if(frame==null)throw new IOException(closed?"RTC 串流已关闭":"RTC 等待画面超时");synchronized(this){queueBytes=Math.max(0,queueBytes-frame.data.length);if(frames.isEmpty())queueAt=0;}return frame;}
  public void close(){if(closed)return;closed=true;new Thread(()->{for(DataChannel channel:channels.values()){channel.unregisterObserver();channel.close();channel.dispose();}channels.clear();pc.close();pc.dispose();},"lanlink-rtc-close").start();}
  static final class Entry{long at=System.currentTimeMillis();byte[] data;int count;BitSet seen=new BitSet();Entry(int total,int count){data=new byte[total];this.count=count;}}
  static final class Assembler{
    final Map<Integer,Entry> parts=new LinkedHashMap<>();int last=-1;volatile boolean needKey=true;
    synchronized VideoWire.Frame push(byte[] packet)throws Exception{
      if(packet.length<16||packet.length>PART+16)throw new IOException("RTC 分片无效");ByteBuffer p=ByteBuffer.wrap(packet);int id=p.getInt(),index=p.getShort()&65535,count=p.getShort()&65535,total=p.getInt(),offset=p.getInt();
      if(id<0||total<16||total>MAX||count!=(total+PART-1)/PART||index>=count||offset!=index*PART||packet.length-16!=Math.min(PART,total-offset))throw new IOException("RTC 分片范围无效");
      Iterator<Entry> iterator=parts.values().iterator();while(iterator.hasNext())if(System.currentTimeMillis()-iterator.next().at>200){iterator.remove();needKey=true;}
      if(id<=last)return null;Entry entry=parts.get(id);if(entry==null){if(parts.size()>=4){parts.remove(parts.keySet().iterator().next());needKey=true;}entry=new Entry(total,count);parts.put(id,entry);}if(entry.data.length!=total||entry.count!=count)throw new IOException("RTC 分片冲突");if(entry.seen.get(index))return null;System.arraycopy(packet,16,entry.data,offset,packet.length-16);entry.seen.set(index);if(entry.seen.cardinality()!=count)return null;parts.remove(id);
      ByteBuffer b=ByteBuffer.wrap(entry.data);int codec=b.get()&255;boolean key=b.get()!=0;int width=b.getShort()&65535,height=b.getShort()&65535;b.getShort();long time=b.getLong();if(codec<1||codec>3||width<1||height<1||width>4096||height>4096||(long)width*height>8388608)throw new IOException("RTC 画面无效");
      if(last>=0&&id!=last+1)needKey=true;last=id;if(codec==2&&needKey&&!key)return null;if(codec!=3)needKey=false;final int accepted=id;parts.keySet().removeIf(value->value<=accepted);VideoWire.Frame frame=new VideoWire.Frame(Arrays.copyOfRange(entry.data,16,total),width,height,codec==2,key);frame.time=time;frame.heartbeat=codec==3;return frame;
    }
  }
}
