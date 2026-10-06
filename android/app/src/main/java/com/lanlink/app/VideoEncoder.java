package com.lanlink.app;

import android.media.*;
import android.os.Bundle;
import android.view.Surface;
import java.nio.ByteBuffer;

final class VideoEncoder implements VideoSource {
  interface Output {
    void frame(VideoWire.Frame frame);

    void failed();
  }

  final MediaCodec codec;
  final Surface surface;
  final Thread worker;
  volatile boolean closed;
  volatile long syncAt;

  VideoEncoder(int width, int height, Output output) throws Exception {
    MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
    format.setInteger(
        MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
    format.setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000);
    format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
    format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
    format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
    format.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, 30);
    format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L);
    MediaCodec created = MediaCodec.createEncoderByType("video/avc");
    Surface input = null;
    try {
      created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
      input = created.createInputSurface();
      created.start();
    } catch (Exception error) {
      if (input != null) input.release();
      created.release();
      throw error;
    }
    codec = created;
    surface = input;
    worker =
        new Thread(
            () -> {
              MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
              byte[] config = new byte[0];
              try {
                while (!closed) {
                  int index = codec.dequeueOutputBuffer(info, 10000);
                  if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat actual = codec.getOutputFormat();
                    config =
                        VideoWire.join(
                            copy(actual.getByteBuffer("csd-0")),
                            copy(actual.getByteBuffer("csd-1")));
                  } else if (index >= 0) {
                    ByteBuffer buffer = codec.getOutputBuffer(index);
                    byte[] data = new byte[info.size];
                    if (buffer != null) {
                      buffer.position(info.offset);
                      buffer.limit(info.offset + info.size);
                      buffer.get(data);
                    }
                    codec.releaseOutputBuffer(index, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                      config = data;
                      continue;
                    }
                    boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                    if (data.length > 0)
                      output.frame(
                          new VideoWire.Frame(
                              key ? VideoWire.join(config, data) : data, width, height, true, key));
                  }
                }
              } catch (Exception error) {
                if (!closed) {
                  android.util.Log.w("LanLinkVideo", "Encoder interrupted", error);
                  output.failed();
                }
              } finally {
                try {
                  codec.stop();
                } catch (Exception ignored) {
                }
                codec.release();
              }
            },
            "lanlink-video-encoder");
    worker.start();
  }

  static byte[] copy(ByteBuffer source) {
    if (source == null) return new byte[0];
    ByteBuffer b = source.duplicate();
    byte[] data = new byte[b.remaining()];
    b.get(data);
    return data;
  }

  public void setBitrate(int rate) {
    if (closed) return;
    try {
      Bundle b = new Bundle();
      b.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, rate);
      codec.setParameters(b);
    } catch (Exception ignored) {
    }
  }

  public void requestKey() {
    if (closed || System.currentTimeMillis() - syncAt < 500) return;
    syncAt = System.currentTimeMillis();
    try {
      Bundle b = new Bundle();
      b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
      codec.setParameters(b);
    } catch (Exception ignored) {
    }
  }

  public void close() {
    closed = true;
    surface.release();
  }
}
