package com.lanlink.app;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.os.Build;
import java.io.IOException;

/** System playback redirection, invoked only inside the authorized ADB shell helper. */
final class AdbAudioCapture implements AutoCloseable {
  private volatile boolean closed;
  private volatile VideoWire wire;
  private volatile AudioRecord recorder;
  private final Thread worker;

  static boolean supported() {
    return Build.VERSION.SDK_INT >= 31 && android.os.Process.myUid() == 2000;
  }

  AdbAudioCapture(int port, String secret, AdbAudioCapture previous) {
    if (previous != null) previous.close();
    worker = new Thread(() -> {
      try {
        if (previous != null) previous.worker.join(1500);
        if (!closed && (previous == null || !previous.worker.isAlive())) run(port, secret);
      } catch (InterruptedException ignored) {}
    }, "lanlink-adb-audio");
    worker.setDaemon(true);
    worker.start();
  }

  private void run(int port, String secret) {
    if (!supported() || closed) return;
    try (VideoWire connection = VideoWire.connect("127.0.0.1", Wire.obj("protocol", 1, "port", port, "key", secret))) {
      wire = connection;
      if (closed) return;
      // No commands travel back on this socket. EOF must stop Android's
      // recording route even if AudioRecord is waiting for samples.
      connection.socket.setSoTimeout(0);
      Thread disconnectWatch = new Thread(() -> {
        try { connection.input.read(); }
        catch (IOException ignored) {}
        finally { close(); }
      }, "lanlink-adb-audio-disconnect");
      disconnectWatch.setDaemon(true);
      disconnectWatch.start();
      AudioRecord record = createRecorder();
      try {
        synchronized (this) {
          if (closed) return;
          recorder = record;
          record.startRecording();
        }
        if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
          throw new IOException("ADB sound source did not start");
        byte[] data = new byte[VideoWire.PCM_PACKET_BYTES];
        int offset = 0;
        while (!closed) {
          int count = record.read(data, offset, data.length - offset, AudioRecord.READ_BLOCKING);
          if (count <= 0) break;
          offset += count;
          if (offset != data.length) continue;
          VideoWire.Frame packet = new VideoWire.Frame(data, 1, 1, false, false);
          packet.audio = true;
          connection.send(packet);
          offset = 0;
        }
      } finally {
        synchronized (this) { recorder = null; record.release(); }
      }
    } catch (Exception error) {
      if (!closed) android.util.Log.w("LanLinkAudio", "ADB playback capture ended: " + error);
    } finally { wire = null; }
  }

  // The recorder runs as the authorized ADB shell, whose playback-capture
  // permission and REMOTE_SUBMIX source are outside the public app SDK contract.
  @android.annotation.SuppressLint({"MissingPermission", "WrongConstant"})
  private static AudioRecord createRecorder() {
    if (Build.VERSION.SDK_INT < 31 || android.os.Process.myUid() != 2000)
      throw new IllegalStateException("ADB playback capture requires Android 12+ and the shell UID");
    final String shell = "com.android.shell";
    final AttributionSource attribution = new AttributionSource.Builder(android.os.Process.myUid())
        .setPackageName(shell).build();
    Context context = new ContextWrapper(null) {
      @Override public String getPackageName() { return shell; }
      @Override public String getOpPackageName() { return shell; }
      @Override public AttributionSource getAttributionSource() { return attribution; }
      @Override public Context getApplicationContext() { return this; }
      @Override public int getDeviceId() { return 0; }
    };
    int minimum = AudioRecord.getMinBufferSize(StreamAudio.RATE, AudioFormat.CHANNEL_IN_STEREO,
        AudioFormat.ENCODING_PCM_16BIT);
    return new AudioRecord.Builder().setContext(context)
        .setAudioSource(8) // AudioSource.REMOTE_SUBMIX is a system API.
        .setAudioFormat(StreamAudio.format(AudioFormat.CHANNEL_IN_STEREO))
        .setBufferSizeInBytes(Math.max(4 * VideoWire.PCM_PACKET_BYTES, 2 * minimum)).build();
  }

  @Override public synchronized void close() {
    closed = true;
    VideoWire active = wire;
    if (active != null) active.close();
    if (recorder != null) try { recorder.stop(); } catch (IllegalStateException ignored) {}
    worker.interrupt();
  }
}
