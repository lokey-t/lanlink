package com.lanlink.app;

import java.io.*;
import java.util.concurrent.atomic.*;

/** Root Surface -> MediaCodec pipeline. stdin controls lifetime, bitrate and keyframes. */
public final class RootStream {
  static final int MAGIC = 0x4c525332;

  public static void main(String[] args) {
    int code = 0;
    try {
      int uid = android.os.Process.myUid();
      if (uid != 0 && uid != 2000) throw new SecurityException("ADB or Root required");
      if (args.length == 1) {
        String secret = System.getenv("LANLINK_VIDEO_SECRET");
        if (secret == null) throw new SecurityException("Missing video credentials");
        try (java.net.Socket socket = new java.net.Socket()) {
          socket.connect(new java.net.InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), 3000);
          socket.setSoTimeout(4000);
          DataInputStream in = new DataInputStream(socket.getInputStream());
          DataOutputStream out = new DataOutputStream(socket.getOutputStream());
          String s = in.readUTF(), c = ShellAuth.nonce();
          if (s.length() != 32) throw new SecurityException("Invalid video challenge");
          out.writeUTF(c);
          out.writeUTF(ShellAuth.proof(secret, "video-helper", s, c));
          out.flush();
          ShellAuth.check(ShellAuth.proof(secret, "video-app", s, c), in.readUTF());
          socket.setSoTimeout(0);
          stream(socket.getInputStream(), socket.getOutputStream());
        }
      } else if (args.length == 0) stream(System.in, System.out);
      else throw new IllegalArgumentException("Invalid capture arguments");
    } catch (Throwable error) {
      error.printStackTrace(System.err);
      code = 1;
    } finally {
      System.exit(code);
    }
  }

  private static void stream(InputStream control, OutputStream video) throws Exception {
    android.os.Looper.prepareMainLooper();
    AtomicBoolean closed = new AtomicBoolean();
    AtomicBoolean keyPending = new AtomicBoolean();
    AtomicBoolean rebuild = new AtomicBoolean();
    AtomicLong failedSince = new AtomicLong();
    AtomicLong lastFrame = new AtomicLong(android.os.SystemClock.elapsedRealtime());
    AtomicInteger bitrate = new AtomicInteger(4_000_000);
    AtomicReference<VideoEncoder> current = new AtomicReference<>();
    DataOutputStream output = new DataOutputStream(new BufferedOutputStream(video));
    output.writeInt(MAGIC);
    output.flush();
    Thread commands =
        new Thread(
            () -> {
              try (BufferedReader input =
                  new BufferedReader(new InputStreamReader(control, "UTF-8"))) {
                String line;
                while ((line = input.readLine()) != null) {
                  if (line.equals("stop")) break;
                  VideoEncoder encoder = current.get();
                  if (line.equals("key")) {
                    keyPending.set(true);
                    if (encoder != null) encoder.requestKey();
                  }
                  if (line.matches("rate [0-9]{6,7}")) {
                    int rate = Integer.parseInt(line.substring(5));
                    if (rate >= 500_000 && rate <= 8_000_000) {
                      bitrate.set(rate);
                      if (encoder != null) encoder.setBitrate(rate);
                    }
                  }
                }
              } catch (Exception ignored) {
              } finally {
                closed.set(true);
              }
            },
            "lanlink-root-controls");
    commands.setDaemon(true);
    commands.start();
    RootDisplay mirror = null;
    try {
      while (!closed.get()) {
        try {
          RootDisplay info = new RootDisplay();
          boolean restart = rebuild.getAndSet(false);
          if (mirror == null
              || restart
              || info.width != mirror.width
              || info.height != mirror.height
              || info.rotation != mirror.rotation
              || info.layer != mirror.layer
              // Some codecs stop repeating a static Surface. Reattach must still get a fresh IDR.
              || keyPending.get()
                  && android.os.SystemClock.elapsedRealtime() - lastFrame.get() > 500) {
            VideoEncoder old = current.getAndSet(null);
            if (old != null) old.close();
            if (mirror != null) {
              mirror.close();
              mirror = null;
            }
            if (old != null) {
              old.worker.join(1500);
              if (old.worker.isAlive()) throw new IOException("编码器未能停止");
            }
            float scale = Math.min(1f, 1600f / Math.max(info.width, info.height));
            int width = Math.max(8, Math.round(info.width * scale) / 8 * 8);
            int height = Math.max(8, Math.round(info.height * scale) / 8 * 8);
            lastFrame.set(android.os.SystemClock.elapsedRealtime());
            VideoEncoder encoder =
                new VideoEncoder(
                    width,
                    height,
                    new VideoEncoder.Output() {
                      public void frame(VideoWire.Frame frame) {
                        lastFrame.set(android.os.SystemClock.elapsedRealtime());
                        failedSince.set(0);
                        if (frame.key) keyPending.set(false);
                        try {
                          synchronized (output) {
                            output.writeInt(frame.data.length);
                            output.writeInt(frame.width);
                            output.writeInt(frame.height);
                            output.writeInt(frame.key ? 1 : 0);
                            output.write(frame.data);
                            output.flush();
                          }
                        } catch (IOException failure) {
                          closed.set(true);
                        }
                      }

                      public void failed() {
                        // An app/rotation transition may invalidate the codec Surface.
                        // Rebuild the video source while keeping the authenticated session.
                        failedSince.compareAndSet(0, android.os.SystemClock.elapsedRealtime());
                        rebuild.set(true);
                      }
                    });
            current.set(encoder);
            encoder.setBitrate(bitrate.get());
            mirror = info;
            mirror.mirror(encoder.surface, width, height);
          }
        } catch (Exception error) {
          long now = android.os.SystemClock.elapsedRealtime();
          failedSince.compareAndSet(0, now);
          if (now - failedSince.get() > 10000) throw error;
          android.util.Log.w("LanLinkRootVideo", "Rebuilding screen source", error);
          VideoEncoder old = current.getAndSet(null);
          if (old != null) old.close();
          if (mirror != null) {
            mirror.close();
            mirror = null;
          }
          if (old != null) {
            old.worker.join(1500);
            if (old.worker.isAlive()) throw new IOException("编码器未能停止", error);
          }
          rebuild.set(true);
          Thread.sleep(500);
        }
        if (failedSince.get() != 0
            && android.os.SystemClock.elapsedRealtime() - failedSince.get() > 10000)
          throw new IOException("Root 编码器持续不可用");
        Thread.sleep(200);
      }
    } finally {
      if (mirror != null) mirror.close();
      VideoEncoder old = current.getAndSet(null);
      if (old != null) {
        old.close();
        old.worker.join(1500);
      }
    }
  }
}
