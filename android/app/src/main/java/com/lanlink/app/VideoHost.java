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
  final String id = UUID.randomUUID().toString();
  final byte[] secret = VideoWire.random();
  final ServerSocket server;
  final ArrayBlockingQueue<VideoWire.Frame> frames = new ArrayBlockingQueue<>(1);
  volatile long expires = System.currentTimeMillis() + 10000, sendingAt;
  volatile boolean closed, needKey = true;
  volatile Socket socket;
  volatile VideoWire wire;
  volatile VideoEncoder encoder;

  VideoHost(LinkService service, Wire peer, boolean h264) throws Exception {
    this.service = service;
    this.peer = peer;
    server = new ServerSocket(0);
    server.setSoTimeout(5000);
    new Thread(
            () -> {
              try {
                socket = server.accept();
                wire = VideoWire.accept(socket, secret);
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
                                if (!frames.offer(f)) {
                                  needKey = true;
                                  if (encoder != null) encoder.requestKey();
                                } else needKey = false;
                              }

                              public void failed() {
                                close();
                              }
                            });
                  } catch (Exception ignored) {
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
                  if (frame != null) {
                    sendingAt = System.currentTimeMillis();
                    wire.send(frame);
                    sentAt = System.currentTimeMillis();
                    sendingAt = 0;
                  }
                  if (encoder == null) Thread.sleep(50);
                }
              } catch (Exception ignored) {
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
                  if (now > expires || sendingAt != 0 && now - sendingAt > 2000) close();
                } catch (Exception ignored) {
                  close();
                }
              }
            },
            "lanlink-video-watch")
        .start();
  }

  JSONObject info() {
    return Wire.obj(
        "protocol", 1, "id", id, "key", Wire.hex(secret), "port", server.getLocalPort());
  }

  JSONObject keep(Wire p, JSONObject feedback) throws Exception {
    if (p != peer || closed) throw new IOException("串流已结束");
    service.requireScreen(peer);
    expires = System.currentTimeMillis() + 10000;
    if (adaptive.feedback(
            feedback, sendingAt != 0 && System.currentTimeMillis() - sendingAt > 100 ? 100000 : 0)
        && encoder != null) encoder.setBitrate(adaptive.rate);
    return Wire.obj("bitrate", adaptive.rate);
  }

  public void close() {
    if (closed) return;
    closed = true;
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
    VideoEncoder old = encoder;
    if (old != null) service.stopVideoEncoder(old);
    frames.clear();
  }
}
