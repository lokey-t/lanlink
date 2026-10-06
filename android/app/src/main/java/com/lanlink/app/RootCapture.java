package com.lanlink.app;

import java.io.*;

/** Continuously drains encoded frames; no unbounded queue and no input-channel lock. */
final class RootCapture implements AutoCloseable {
  private volatile boolean closed;
  private java.net.ServerSocket server;
  private java.net.Socket socket;
  private BufferedWriter commands;
  private volatile VideoEncoder.Output listener;
  private long syncAt;
  private int bitrate = 4_000_000;
  private volatile boolean receivedFrame;
  private final long startedAt = android.os.SystemClock.elapsedRealtime();

  RootCapture(LinkService service) {
    Thread watchdog =
        new Thread(
            () -> {
              while (!closed && !receivedFrame) {
                try {
                  Thread.sleep(2000);
                } catch (InterruptedException ignored) {
                  return;
                }
                if (!closed
                    && !receivedFrame
                    && android.os.SystemClock.elapsedRealtime() - startedAt > 10000) {
                  fail(service);
                  return;
                }
              }
            },
            "lanlink-root-video-watch");
    watchdog.setDaemon(true);
    watchdog.start();
    new Thread(
            () -> {
              try {
                String secret = Wire.token();
                java.net.ServerSocket receiver;
                synchronized (this) {
                  if (closed) return;
                  server = receiver = new java.net.ServerSocket(0, 2,
                      java.net.InetAddress.getByName("127.0.0.1"));
                }
                service.shell.request(Wire.obj("method", "startVideo", "port", receiver.getLocalPort(),
                    "secret", secret, "apkPath", service.getApplicationInfo().sourceDir));
                java.net.Socket child;
                while (true) {
                  child = receiver.accept();
                  synchronized (this) {
                    if (closed) { child.close(); return; }
                    socket = child;
                  }
                  try {
                    child.setSoTimeout(4000);
                    DataInputStream in = new DataInputStream(child.getInputStream());
                    DataOutputStream out = new DataOutputStream(child.getOutputStream());
                    String challenge = ShellAuth.nonce();
                    out.writeUTF(challenge);
                    out.flush();
                    String nonce = in.readUTF();
                    if (nonce.length() != 32) throw new SecurityException("无效串流握手");
                    ShellAuth.check(ShellAuth.proof(secret, "video-helper", challenge, nonce), in.readUTF());
                    out.writeUTF(ShellAuth.proof(secret, "video-app", challenge, nonce));
                    out.flush();
                    child.setSoTimeout(0);
                    break;
                  } catch (Exception invalid) {
                    child.close();
                    if (closed) return;
                  }
                }
                synchronized (this) {
                  if (closed) { child.close(); return; }
                  socket = child;
                  server.close();
                  server = null;
                  commands = new BufferedWriter(new OutputStreamWriter(child.getOutputStream(), "UTF-8"));
                  send("rate " + bitrate);
                  send("key");
                }
                try (DataInputStream input =
                    new DataInputStream(new BufferedInputStream(child.getInputStream()))) {
                  if (input.readInt() != RootStream.MAGIC) throw new IOException("屏幕串流协议不匹配");
                  while (!closed) {
                    int length = input.readInt(),
                        width = input.readInt(),
                        height = input.readInt(),
                        flags = input.readInt();
                    if (length < 1
                        || length > 16 * 1024 * 1024
                        || width < 1
                        || width > 8192
                        || height < 1
                        || height > 8192
                        || flags < 0
                        || flags > 1) throw new IOException("无效的编码帧");
                    byte[] data = new byte[length];
                    input.readFully(data);
                    receivedFrame = true;
                    VideoEncoder.Output target = listener;
                    if (target != null)
                      target.frame(new VideoWire.Frame(data, width, height, true, flags == 1));
                  }
                }
              } catch (Exception error) {
                if (!closed) android.util.Log.w("LanLinkRootVideo", "Root stream stopped", error);
                fail(service);
              } finally {
                close();
              }
            },
            "lanlink-root-video")
        .start();
  }

  private void fail(LinkService service) {
    VideoEncoder.Output target;
    synchronized (this) {
      if (closed) return;
      target = listener;
      close();
    }
    service.status = "ADB / Root H.264 串流中断，请检查助手连接或重试";
    if (target != null) target.failed();
  }

  synchronized VideoSource attach(VideoEncoder.Output target) throws IOException {
    if (closed) throw new IOException("屏幕串流已停止，请重试");
    listener = target;
    send("key");
    return new VideoSource() {
      public void setBitrate(int rate) {
        synchronized (RootCapture.this) {
          if (listener == target) {
            bitrate = Math.max(500_000, Math.min(8_000_000, rate));
            send("rate " + bitrate);
          }
        }
      }

      public void requestKey() {
        synchronized (RootCapture.this) {
          long now = android.os.SystemClock.elapsedRealtime();
          if (listener == target && now - syncAt >= 500) {
            syncAt = now;
            send("key");
          }
        }
      }

      public void close() {
        synchronized (RootCapture.this) {
          if (listener == target) listener = null;
        }
      }
    };
  }

  private synchronized void send(String command) {
    if (commands == null || closed) return;
    try {
      commands.write(command);
      commands.newLine();
      commands.flush();
    } catch (IOException ignored) {
    }
  }

  boolean running() {
    return !closed;
  }

  public synchronized void close() {
    if (closed) return;
    send("stop");
    closed = true;
    listener = null;
    try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    try { if (server != null) server.close(); } catch (IOException ignored) {}
    socket = null;
    server = null;
  }
}
