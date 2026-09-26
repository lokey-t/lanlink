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
  int bitrate = 4000000;
  final Wire peer;
  final TextureView texture;
  final ImageView image;
  final TextView status;
  final Handler main = new Handler(Looper.getMainLooper());
  final AtomicBoolean painting = new AtomicBoolean();
  volatile boolean closed;
  volatile Surface surface;
  volatile VideoWire connection;
  volatile int width, height;
  volatile long renderedFrames;
  MediaCodec decoder;
  int decoderWidth, decoderHeight;
  boolean needKey = true;
  String session;
  VideoViewport viewport;
  boolean legacyViewing;

  VideoPlayer(Wire peer, TextureView texture, ImageView image, TextView status) {
    this.peer = peer;
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
    boolean jpegOnly = false, legacy = false;
    try {
      while (!closed) {
        try {
          if (!legacy && connection == null) {
            JSONObject info = peer.request("videoOpen", Wire.obj("h264", !jpegOnly));
            session = info.getString("id");
            if (closed) break;
            connection = VideoWire.connect(peer.socket.getInetAddress().getHostAddress(), info);
          }
          long keepAt = System.currentTimeMillis(), measuredAt = keepAt;
          long shownAt = renderedFrames;
          int count = 0;
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
              frame = connection.next();
              feedback.receive(frame);
              if (System.currentTimeMillis() - keepAt >= 1000) {
                long sent = System.currentTimeMillis();
                JSONObject result =
                    peer.request(
                        "videoKeep", Wire.obj("id", session, "feedback", feedback.report()));
                feedback.rtt = System.currentTimeMillis() - sent;
                bitrate = result.optInt("bitrate", 4000000);
                keepAt = System.currentTimeMillis();
              }
            }
            if (frame.heartbeat) continue;
            render(frame);
            count++;
            long now = System.currentTimeMillis();
            if (now - measuredAt > 1000) {
              final String label =
                  (frame.h264 ? "H.264 串流" : legacy ? "截图兼容模式" : "兼容串流")
                      + " · "
                      + Math.round((renderedFrames - shownAt) * 1000f / (now - measuredAt))
                      + " fps · "
                      + String.format(java.util.Locale.ROOT, "%.1f Mbps", bitrate / 1000000.0);
              main.post(
                  () -> {
                    if (!closed) status.setText(label);
                  });
              count = 0;
              shownAt = renderedFrames;
              measuredAt = now;
            }
            if (legacy) Thread.sleep(50);
          }
        } catch (Exception error) {
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
          if (reason.startsWith("CONTROL_ENDED:") || reason.startsWith("CONTROL_CONFLICT:")) {
            closed = true;
            break;
          }
          if (reason.contains("不支持")) legacy = true;
          else if (!reason.startsWith("CONSENT_") && !reason.startsWith("PERMISSION_")) {
            if (jpegOnly) legacy = true;
            jpegOnly = true;
          }
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
    VideoWire old = connection;
    if (old != null) old.close();
  }
}
