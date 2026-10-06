package com.lanlink.app;

import android.graphics.*;
import android.media.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Native MediaCodec Surface decoder, with JPEG and legacy-peer compatibility. */
final class VideoPlayer implements AutoCloseable {
  final AdaptiveVideo.Feedback feedback = new AdaptiveVideo.Feedback();
  volatile int bitrate = 4000000;
  Wire peer;
  final String peerId;
  boolean requireControl;
  final TextureView texture;
  final ImageView image;
  final TextView status;
  final Handler main = new Handler(Looper.getMainLooper());
  final AtomicBoolean painting = new AtomicBoolean();
  volatile boolean closed;
  volatile Surface surface;
  volatile VideoWire connection;
  volatile VideoTransport rtc;long rtcCooldown;
  volatile int width, height;
  volatile long renderedFrames;
  MediaCodec decoder;
  int decoderWidth, decoderHeight;
  volatile boolean needKey = true;
  volatile String session;
  volatile String codecLabel="";
  long fpsAt,fpsFrames;
  final Runnable metrics=new Runnable(){public void run(){if(closed)return;long now=SystemClock.elapsedRealtime();if(fpsAt!=0&&!codecLabel.isEmpty())status.setText(codecLabel+" · "+Math.round((renderedFrames-fpsFrames)*1000f/Math.max(1,now-fpsAt))+" fps · "+String.format(java.util.Locale.ROOT,"%.1f Mbps",bitrate/1000000.0));fpsAt=now;fpsFrames=renderedFrames;main.postDelayed(this,1000);}};
  VideoViewport viewport;
  boolean legacyViewing;

  VideoPlayer(Wire peer, TextureView texture, ImageView image, TextView status) {
    this.peer = peer;
    peerId = peer.id;
    this.texture = texture;
    this.image = image;
    this.status = status;
    texture.setSurfaceTextureListener(
        new TextureView.SurfaceTextureListener() {
          public void onSurfaceTextureAvailable(SurfaceTexture t, int w, int h) {
            surface = new Surface(t);
          }

          public void onSurfaceTextureSizeChanged(SurfaceTexture t, int w, int h) {
            fit();
          }

          public boolean onSurfaceTextureDestroyed(SurfaceTexture t) {
            close();
            return true;
          }

          public void onSurfaceTextureUpdated(SurfaceTexture t) {
            renderedFrames++;
          }
        });
  }

  void start() {
    main.post(metrics);
    new Thread(this::run, "lanlink-video-player").start();
  }

  void fit() {
    if (viewport != null) {
      viewport.fit(width, height);
      return;
    }
    int w = texture.getWidth(), h = texture.getHeight();
    if (width < 1 || height < 1 || w < 1 || h < 1) return;
    float scale = Math.min((float) w / width, (float) h / height);
    Matrix matrix = new Matrix();
    matrix.setScale(width * scale / w, height * scale / h, w / 2f, h / 2f);
    texture.setTransform(matrix);
  }

  void render(VideoWire.Frame frame) throws Exception {
    width = frame.width;
    height = frame.height;
    if (!frame.h264) {
      if (!painting.compareAndSet(false, true)) return;
      Bitmap bitmap = BitmapFactory.decodeByteArray(frame.data, 0, frame.data.length);
      main.post(
          () -> {
            try {
              if (!closed && bitmap != null) {
                image.setVisibility(View.VISIBLE);
                image.setImageBitmap(bitmap);
                fit();
                renderedFrames++;
              } else if (bitmap != null) bitmap.recycle();
            } finally {
              painting.set(false);
            }
          });
      return;
    }
    Surface target = surface;
    if (target == null) return;
    if (decoder != null && (decoderWidth != width || decoderHeight != height)) stopDecoder();
    if (decoder == null) {
      if (!frame.key) return;
      MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
      format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, VideoWire.MAX);
      if (Build.VERSION.SDK_INT >= 30) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
      decoder = MediaCodec.createDecoderByType("video/avc");
      decoder.configure(format, target, null, 0);
      decoder.start();
      decoderWidth = width;
      decoderHeight = height;
      needKey = true;
      main.post(
          () -> {
            if (!closed) {
              image.setVisibility(View.INVISIBLE);
              fit();
            }
          });
    }
    if (needKey && !frame.key) return;
    drainOutput();
    int index = decoder.dequeueInputBuffer(10000);
    if (index < 0) {
      stopDecoder();
      return;
    }
    ByteBuffer input = decoder.getInputBuffer(index);
    if (input == null || input.capacity() < frame.data.length) throw new IOException("视频帧超出解码器容量");
    input.clear();
    input.put(frame.data);
    decoder.queueInputBuffer(index, 0, frame.data.length, System.nanoTime() / 1000, 0);
    needKey = false;
    drainOutput();
  }

  void drainOutput() {
    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    int latest = -1;
    for (; ; ) {
      int output = decoder.dequeueOutputBuffer(info, 0);
      if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue;
      if (output < 0) break;
      if (latest >= 0) decoder.releaseOutputBuffer(latest, false);
      latest = output;
    }
    if (latest >= 0) decoder.releaseOutputBuffer(latest, true);
  }

  void stopDecoder() {
    if (decoder == null) return;
    try {
      decoder.stop();
    } catch (Exception ignored) {
    }
    decoder.release();
    decoder = null;
    needKey = true;
  }

  void run() {
    boolean jpegOnly = false, legacy = false, h264Only = false;
    try {
      while (!closed) {
        try {
          LinkService service = LinkService.instance;
          if (requireControl) {
            if (service == null || !peerId.equals(service.active)) {
              Thread.sleep(500);
              continue;
            }
            peer = service.peer(peerId);
            service.beginViewing(peerId);
          }
          if (!legacy && connection == null && rtc==null) {
            JSONObject info=null;
            if(peer.fastPath==null&&peer.quicConn!=0&&System.currentTimeMillis()>rtcCooldown){
              try{long channel=new java.security.SecureRandom().nextInt()&0xffffffffL;if(channel==0)channel=1;rtc=LinkService.instance.quic.video(peer,channel);info=peer.request("videoOpen",Wire.obj("h264",!jpegOnly,"quic",channel));session=info.getString("id");if(info.optLong("quic")!=channel)throw new IOException("QUIC 串流协商失败");rtc.ready();}
              catch(Exception unavailable){if(rtc!=null)rtc.close();rtc=null;rtcCooldown=System.currentTimeMillis()+30000;if(session!=null){try{peer.request("videoStop",Wire.obj("id",session));}catch(Exception ignored){}session=null;}}
            }
            if(rtc==null && peer.fastPath==null && peer.info.optJSONObject("capabilities")!=null && peer.info.optJSONObject("capabilities").optInt("rtcVideo")==1 && System.currentTimeMillis()>rtcCooldown){
              try{RtcVideo stream=new RtcVideo(LinkService.instance);rtc=stream;info=peer.request("videoOpen",Wire.obj("h264",!jpegOnly,"rtc",stream.offer()));session=info.getString("id");stream.accept(info.getString("rtc"));rtc.ready();if(closed)throw new IOException("CONTROL_ENDED: 画面请求已取消");}
              catch(Exception unavailable){if(rtc!=null)rtc.close();rtc=null;rtcCooldown=System.currentTimeMillis()+30000;if(session!=null){try{peer.request("videoStop",Wire.obj("id",session));}catch(Exception ignored){}session=null;}if(closed)throw unavailable;}
            }
            if(rtc==null)info = peer.request("videoOpen", Wire.obj("h264", !jpegOnly));
            h264Only = info.optBoolean("h264Only");
            session = info.getString("id");
            if(rtc!=null)rtc.keyCallback(()->{LinkService currentService=LinkService.instance;if(currentService!=null)currentService.jobs.execute(()->{try{peer.request("videoKey",Wire.obj("id",session));}catch(Exception ignored){}});});
            if (closed) break;
            int usbPort = info.optInt("usbPort");
            if(rtc!=null){}else if (usbPort > 0 && usbPort <= 65535) {
              JSONObject route = new JSONObject(info.toString()).put("port", usbPort);
              try { connection = VideoWire.connect("127.0.0.1", route); }
              catch (Exception unavailable) {
                connection = VideoWire.connect(peer.socket.getInetAddress().getHostAddress(), info);
              }
            } else connection = VideoWire.connect(peer.socket.getInetAddress().getHostAddress(), info);
          }
          final java.util.concurrent.ScheduledExecutorService keeper=java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
          final String keepSession=session;final Wire keepPeer=peer;final VideoWire keepConnection=connection;final VideoTransport keepRtc=rtc;
          keeper.scheduleWithFixedDelay(()->{
            if(closed||keepSession==null||!keepSession.equals(session))return;
            try{long sent=SystemClock.elapsedRealtime();JSONObject result=keepPeer.request("videoKeep",Wire.obj("id",keepSession,"feedback",feedback.report().put("needKey",needKey||keepRtc!=null&&keepRtc.needsKey())));if(keepSession.equals(session)){feedback.rtt=SystemClock.elapsedRealtime()-sent;bitrate=result.optInt("bitrate",4000000);}}
            catch(Exception error){if(keepConnection!=null)keepConnection.close();if(keepRtc!=null)keepRtc.close();}
          },1,1,java.util.concurrent.TimeUnit.SECONDS);
          try {
          while (!closed) {
            VideoWire.Frame frame;
            if (legacy) {
              legacyViewing = true;
              JSONObject f = peer.request("screen", Wire.obj());
              frame =
                  new VideoWire.Frame(
                      Base64.getDecoder().decode(f.getString("data")),
                      f.getInt("width"),
                      f.getInt("height"),
                      false,
                      true);
            } else {
              frame =rtc!=null?rtc.next():connection.next();
              feedback.receive(frame);
            }
            if (frame.heartbeat) continue;
            codecLabel=frame.h264?(rtc instanceof QuicVideo?"H.264 · QUIC":rtc!=null?"H.264 · WebRTC":"H.264 · TCP"):legacy?"截图兼容模式":"兼容串流";
            render(frame);
            if (legacy) Thread.sleep(50);
          }
          } finally {keeper.shutdownNow();}
        } catch (Exception error) {
          if(rtc!=null)rtcCooldown=System.currentTimeMillis()+30000;
          String reason = error.getMessage() == null ? "串流连接暂不可用" : error.getMessage();
          main.post(
              () -> {
                if (!closed)
                  status.setText(
                      reason
                          .replace("CONSENT_PENDING: ", "")
                          .replace("CONSENT_DENIED: ", "")
                          .replace("PERMISSION_REQUIRED: ", "")
                          .replace("CONTROL_CONFLICT: ", ""));
              });
          if (reason.startsWith("CONTROL_ENDED:")
              || reason.startsWith("CONTROL_CONFLICT:")
              || reason.startsWith("CONSENT_DENIED:")) {
            closed = true;
            break;
          }
          if (reason.startsWith("VIDEO_H264_REQUIRED:") || reason.contains("Root 投屏需要 H.264")) {
            h264Only = true;
            jpegOnly = false;
            legacy = false;
          } else if (!h264Only && reason.contains("不支持")) legacy = true;
          else if (!h264Only
              && (error instanceof MediaCodec.CodecException || reason.contains("解码")))
            jpegOnly = true;
        } finally {
          disconnect();
        }
        if (!closed) Thread.sleep(1500);
      }
    } catch (InterruptedException ignored) {
    } finally {
      disconnect();
      if (surface != null) surface.release();
    }
  }

  void disconnect() {
    codecLabel="";
    VideoTransport rtcOld=rtc;rtc=null;if(rtcOld!=null)rtcOld.close();
    VideoWire old = connection;
    connection = null;
    if (old != null) old.close();
    stopDecoder();
    if (legacyViewing) {
      legacyViewing = false;
      try {
        peer.request("screenStop", Wire.obj());
      } catch (Exception ignored) {
      }
    }
    if (session != null) {
      try {
        peer.request("videoStop", Wire.obj("id", session));
      } catch (Exception ignored) {
      }
      session = null;
    }
  }

  public void close() {
    closed = true;
    main.removeCallbacks(metrics);
    if(rtc!=null)rtc.close();
    VideoWire old = connection;
    if (old != null) old.close();
  }
}
