package com.lanlink.app;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import org.json.JSONObject;

final class StreamAudio {
  static final int RATE = 48_000;
  static final int PACKET = VideoWire.PCM_PACKET_BYTES;

  static final class Player implements AutoCloseable {
    private final String host;
    private final JSONObject info;
    private volatile boolean closed;
    private volatile VideoWire wire;
    private final Thread worker;

    Player(String host, JSONObject info) {
      this.host = host;
      this.info = info;
      worker = new Thread(this::run, "lanlink-audio-player");
      worker.start();
    }

    private void run() {
      int attempts = 0;
      while (!closed) {
        AudioTrack output = null;
        try (VideoWire connection = connect(attempts++)) {
          wire = connection;
          if (closed) return;
          int minimum = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
          output = new AudioTrack.Builder()
              .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                  .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE).build())
              .setAudioFormat(format(AudioFormat.CHANNEL_OUT_STEREO))
              .setTransferMode(AudioTrack.MODE_STREAM)
              .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
              .setBufferSizeInBytes(Math.max(PACKET * 2, minimum))
              .build();
          output.play();
          while (!closed) {
            VideoWire.Frame frame = connection.next(true);
            if (frame.audio && output.write(frame.data, 0, PACKET, AudioTrack.WRITE_NON_BLOCKING) < 0)
              throw new IOException("声音播放设备不可用");
          }
        } catch (Exception ignored) {
          // Audio failures do not close the picture or input session.
        } finally {
          wire = null;
          if (output != null) output.release();
        }
        try { Thread.sleep(1500); } catch (InterruptedException ignored) { break; }
      }
    }

    private VideoWire connect(int attempt) throws Exception {
      int usb = info.optInt("usbPort");
      if (usb > 0 && usb <= 65535 && attempt % 2 == 0)
        return VideoWire.connect("127.0.0.1", new JSONObject(info.toString()).put("port", usb));
      return VideoWire.connect(host, info);
    }

    public void close() {
      closed = true;
      VideoWire connection = wire;
      if (connection != null) connection.close();
      worker.interrupt();
    }
  }

  static final class Host implements AutoCloseable {
    private final ServerSocket server = new ServerSocket(0);
    private final byte[] secret = VideoWire.random();
    private final LinkService service;
    private final Wire peer;
    private volatile boolean closed;
    private volatile Socket socket;
    private volatile Socket sourceSocket;
    private volatile ServerSocket sourceServer;

    Host(LinkService service, Wire peer) throws IOException {
      this.service = service;
      this.peer = peer;
      new Thread(this::run, "lanlink-audio-host").start();
    }

    JSONObject info() {
      return Wire.obj("protocol", 1, "port", server.getLocalPort(), "key", Wire.hex(secret));
    }

    boolean running() { return !closed; }

    private void run() {
      try {
        while (!closed) {
          try (Socket accepted = server.accept()) {
            socket = accepted;
            if (closed) return;
            try (VideoWire connection = VideoWire.accept(accepted, secret)) {
              if (!closed) capture(connection);
            }
          } catch (Exception error) {
            if (!closed) android.util.Log.d("LanLinkAudio", "Audio connection ended: " + error);
          } finally { socket = null; }
        }
      } finally { close(); }
    }

    private void capture(VideoWire connection) throws Exception {
      RootCapture capture = service.requireRemoteAudio(peer);
      byte[] localSecret = VideoWire.random();
      try (ServerSocket receiver = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
        sourceServer = receiver;
        if (closed) return;
        receiver.setSoTimeout(3500);
        capture.startAudio(receiver.getLocalPort(), Wire.hex(localSecret));
        try (Socket accepted = receiver.accept()) {
          sourceSocket = accepted;
          if (closed) return;
          try (VideoWire input = VideoWire.accept(accepted, localSecret)) {
            while (!closed) {
              VideoWire.Frame frame = input.next(true);
              if (service.requireRemoteAudio(peer) != capture) return;
              connection.send(frame);
            }
          }
        }
      } finally { sourceSocket = null; sourceServer = null; }
    }

    public void close() {
      closed = true;
      try { server.close(); } catch (IOException ignored) {}
      Socket active = socket;
      if (active != null) try { active.close(); } catch (IOException ignored) {}
      Socket source = sourceSocket;
      if (source != null) try { source.close(); } catch (IOException ignored) {}
      ServerSocket pending = sourceServer;
      if (pending != null) try { pending.close(); } catch (IOException ignored) {}
    }
  }

  static AudioFormat format(int channels) {
    return new AudioFormat.Builder().setSampleRate(RATE).setChannelMask(channels)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
  }
}
