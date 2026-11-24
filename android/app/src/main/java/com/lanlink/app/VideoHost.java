package com.lanlink.app;

import java.io.*;
import java.net.*;
import java.util.UUID;
import java.util.concurrent.*;
import org.json.JSONObject;

final class VideoHost implements AutoCloseable {
  final AdaptiveVideo adaptive = new AdaptiveVideo();
  final LinkService service;
  final Wire peer;
  final boolean h264Requested;
  final String id = UUID.randomUUID().toString();
  final byte[] secret = VideoWire.random();
  final ServerSocket server;
  final ArrayBlockingQueue<VideoWire.Frame> frames = new ArrayBlockingQueue<>(8);
  volatile long expires = System.currentTimeMillis() + 10000, sendingAt;
  volatile boolean closed, needKey = true;
  private volatile boolean sentVideo;
  volatile Socket socket;
  volatile VideoWire wire;
  volatile VideoSource encoder;
  private volatile StreamAudio.Host audio;
  private final boolean audioRequested;
  private final int maxFps;
  volatile VideoTransport rtc;String rtcAnswer;long quicChannel;

  VideoHost(LinkService service, Wire peer, boolean h264) throws Exception {
    this(service,peer,h264,null);
  }
  VideoHost(LinkService service, Wire peer, boolean h264,String offer) throws Exception {
    this(service,peer,h264,offer,0);
  }
  VideoHost(LinkService service, Wire peer, boolean h264,String offer,long channel) throws Exception {
    this(service,peer,h264,offer,channel,false);
  }
  VideoHost(LinkService service, Wire peer, boolean h264,String offer,long channel,boolean audioRequested) throws Exception {
    this(service,peer,h264,offer,channel,audioRequested,30);
  }
  VideoHost(LinkService service, Wire peer, boolean h264,String offer,long channel,boolean audioRequested,int maxFps) throws Exception {
    if ((service.rootEnabled() || service.privilegedScreenReady()) && !h264)
      throw new IOException("VIDEO_H264_REQUIRED: ADB / Root 投屏需要 H.264，请升级控制端后重试");
    this.service = service;
    this.peer = peer;
    this.h264Requested = h264;
    server = new ServerSocket(0);
    if(channel>0){try{rtc=service.quic.video(peer,channel);quicChannel=channel;rtc.keyCallback(()->{needKey=true;if(encoder!=null)encoder.requestKey();});}catch(Exception error){server.close();throw error;}}
    else if(offer!=null){try{RtcVideo stream=new RtcVideo(service);rtc=stream;rtcAnswer=stream.answer(offer);}catch(Exception error){if(rtc!=null)rtc.close();server.close();throw error;}}
    this.audioRequested = audioRequested;
    this.maxFps = maxFps == 60 ? 60 : 30;
    service.screenSessionStarted();
    server.setSoTimeout(5000);
    new Thread(
            () -> {
              try {
                if(rtc!=null)rtc.ready();else{socket = server.accept();wire = VideoWire.accept(socket, secret);}
                server.close();
                service.requireScreen(peer);
                if (closed) return;
                if (h264) {
                  try {
                    encoder =
                        service.startVideoEncoder(
                            new VideoEncoder.Output() {
                              public void frame(VideoWire.Frame f) {
                                if (needKey && !f.key) {
                                  if (encoder != null) encoder.requestKey();
                                  return;
                                }
                                VideoWire.Frame oldest=frames.peek();
                                if(oldest!=null&&System.currentTimeMillis()-oldest.time>150||frames.remainingCapacity()==0){frames.clear();needKey=true;}
                                if(needKey&&!f.key){if(encoder!=null)encoder.requestKey();return;}
                                if (!frames.offer(f)) {
                                  needKey = true;
                                  if (encoder != null) encoder.requestKey();
                                } else needKey = false;
                              }

                              public void failed() {
                                close();
                              }
                            });
                  } catch (Exception error) {
                    if (service.rootEnabled() || service.privilegedScreenReady()) throw error;
                    /* JPEG compatibility stream if the codec is unavailable. */
                  }
                }
                if (closed && encoder != null) service.stopVideoEncoder(encoder);
                long sentAt = System.currentTimeMillis();
                while (!closed) {
                  service.requireScreen(peer);
                  VideoWire.Frame frame =
                      encoder == null
                          ? service.latestVideoFrame()
                          : frames.poll(100, TimeUnit.MILLISECONDS);
                  if (frame == null && System.currentTimeMillis() - sentAt > 1000) {
                    frame = new VideoWire.Frame(new byte[0], 1, 1, false, false);
                    frame.heartbeat = true;
                  }
                  if(frame!=null&&frame.h264&&System.currentTimeMillis()-frame.time>150){frames.clear();needKey=true;if(encoder!=null)encoder.requestKey();continue;}
                  if (frame != null) {
                    sendingAt = System.currentTimeMillis();
                    if(rtc!=null){if(!rtc.send(frame)){needKey=true;if(adaptive.congested(Math.max(rtc.queued(),128*1024))&&encoder!=null)encoder.setBitrate(adaptive.rate);if(encoder!=null)encoder.requestKey();} else if(!frame.heartbeat) sentVideo=true;}else { wire.send(frame); if(!frame.heartbeat) sentVideo=true; }
                    sentAt = System.currentTimeMillis();
                    sendingAt = 0;
                  }
                  if (encoder == null) Thread.sleep(frame==null||frame.heartbeat?50:Math.max(50,Math.min(2000,frame.data.length*8000L/adaptive.rate)));
                }
              } catch (Exception error) {
                if (!closed) android.util.Log.w("LanLinkVideoHost", "Video stream stopped", error);
              } finally {
                close();
              }
            },
            "lanlink-video-host")
        .start();
    new Thread(
            () -> {
              while (!closed) {
                try {
                  Thread.sleep(500);
                  service.requireScreen(peer);
                  long now = System.currentTimeMillis();
                  if(sendingAt!=0&&now-sendingAt>150&&adaptive.congested(128*1024)&&encoder!=null)encoder.setBitrate(adaptive.rate);
                  if (now > expires || sendingAt != 0 && now - sendingAt > 5000) close();
                } catch (Exception ignored) {
                  close();
                }
              }
            },
            "lanlink-video-watch")
        .start();
  }

  JSONObject info() throws Exception {
    JSONObject info=Wire.obj(
        "protocol",
        1,
        "id",
        id,
        "key",
        Wire.hex(secret),
        "port",
        server.getLocalPort(),
        "h264Only",
        service.rootEnabled() || service.privilegedScreenReady());if(rtcAnswer!=null)info.put("rtc",rtcAnswer);if(quicChannel>0)info.put("quic",quicChannel);return offerAudio(info);
  }

  private synchronized JSONObject offerAudio(JSONObject info) throws Exception {
    if (!audioRequested || closed) return info;
    if (!service.adbAudioAvailable()) {
      stopAudio();
      return info.put("audioStatus", "adb-required");
    }
    if (!service.ownsRemoteAudio(peer)) {
      if (audio == null) return info;
      stopAudio();
      return info.put("audioStatus", "inactive");
    }
    if (audio == null || !audio.running()) try { audio = new StreamAudio.Host(service, peer); }
    catch (IOException error) { return info.put("audioStatus", "unavailable"); }
    return info.put("audio", audio.info());
  }

  void stopAudio() {
    StreamAudio.Host sound = audio;
    if (sound != null) sound.close();
  }

  JSONObject keep(Wire p, JSONObject feedback) throws Exception {
    if (p != peer || closed) throw new IOException("串流已结束");
    service.requireScreen(peer);
    expires = System.currentTimeMillis() + 10000;
    if (sentVideo) service.keepRemoteDisplay(peer);
    if(feedback!=null && feedback.optBoolean("needKey") && encoder!=null)encoder.requestKey();
    long queued = Math.max(rtc!=null?rtc.queued():0,sendingAt != 0 && System.currentTimeMillis() - sendingAt > 100 ? 100000 : 0);
    if (adaptive.feedback(feedback, queued) && encoder != null) encoder.setBitrate(adaptive.rate);
    if (encoder != null && adaptive.updateFrameRate(feedback, queued, maxFps)) encoder.setFrameRate(adaptive.fps);
    return offerAudio(Wire.obj("bitrate", adaptive.rate));
  }

  public void close() {
    synchronized(this){if (closed) return;closed = true;}
    stopAudio();
    if(rtc!=null)rtc.close();
    try {
      server.close();
    } catch (Exception ignored) {
    }
    if (wire != null) wire.close();
    if (socket != null)
      try {
        socket.close();
      } catch (Exception ignored) {
      }
    VideoSource old = encoder;
    if (old != null) service.stopVideoEncoder(old);
    frames.clear();
    service.screenSessionEnded();
  }
}
