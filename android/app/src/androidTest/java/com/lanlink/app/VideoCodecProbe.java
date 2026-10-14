package com.lanlink.app;

import android.app.*;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

final class VideoCodecProbe {
  private static void keepalivePressure() {
    AtomicInteger closes = new AtomicInteger();
    VideoTransport transport = new VideoTransport() {
      public void ready() {}
      public long queued() { return 0; }
      public boolean send(VideoWire.Frame frame) { return true; }
      public VideoWire.Frame next() { return null; }
      public boolean needsKey() { return false; }
      public void keyCallback(Runnable callback) {}
      public void close() { closes.incrementAndGet(); }
    };
    Exception busy = new java.io.IOException("QUIC_BUSY: local queue admission pressure");
    VideoPlayer.keepaliveFailed(busy, null, transport);
    VideoPlayer.keepaliveFailed(new java.util.concurrent.ExecutionException(busy), null, transport);
    if (closes.get() != 0) throw new AssertionError("Transient keepalive pressure must preserve video");
    VideoPlayer.keepaliveFailed(new java.util.concurrent.TimeoutException("QUIC response timeout"), null, transport);
    if (closes.get() != 1) throw new AssertionError("A failed keepalive must still disconnect video");
  }

  static String run(Instrumentation test, Activity activity, Bundle args) throws Exception {
    keepalivePressure();
    VideoPlayer[] player = new VideoPlayer[1];
    TextureView[] view = new TextureView[1];
    ImageView[] image = new ImageView[1];
    TextView[] status = new TextView[1];
    test.runOnMainSync(
        () -> {
          FrameLayout box = new FrameLayout(activity);
          view[0] = new TextureView(activity);
          image[0] = new ImageView(activity);
          status[0] = new TextView(activity);
          box.addView(view[0], new FrameLayout.LayoutParams(640, 480));
          box.addView(image[0], new FrameLayout.LayoutParams(640, 480));
          activity.setContentView(box);
        });
    long end = System.currentTimeMillis() + 5000;
    while (!view[0].isAvailable() && System.currentTimeMillis() < end) Thread.sleep(30);
    test.runOnMainSync(() -> {
      Wire peer = new Wire(new Socket(), Wire.obj(), new byte[32], null);
      player[0] = new VideoPlayer(peer, view[0], image[0], status[0]);
    });
    if (player[0].surface == null) throw new AssertionError("Texture surface unavailable");
    if (player[0].needsKeyFrame(null)) throw new AssertionError("Encoder startup must not request IDR");
    JSONObject info =
        Wire.obj(
            "protocol",
            1,
            "port",
            Integer.parseInt(args.getString("videoPort")),
            "key",
            args.getString("videoKey"));
    AtomicInteger rendered = new AtomicInteger();
    long start = System.currentTimeMillis();
    try (VideoWire connection = VideoWire.connect("10.0.2.2", info)) {
      Bitmap jpeg = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888);
      jpeg.eraseColor(android.graphics.Color.MAGENTA);
      ByteArrayOutputStream jpegBytes = new ByteArrayOutputStream();
      jpeg.compress(Bitmap.CompressFormat.JPEG, 80, jpegBytes);
      jpeg.recycle();
      player[0].render(new VideoWire.Frame(jpegBytes.toByteArray(), 320, 240, false, true));
      if (player[0].needKey || player[0].needsKeyFrame(null))
        throw new AssertionError("JPEG compatibility stream must not request IDR");
      VideoWire.Frame firstKey = null;
      for (int i = 0; i < 100; i++) {
        VideoWire.Frame frame = connection.next();
        if (frame.heartbeat) {
          i--;
          continue;
        }
        if (!frame.h264 || frame.width != 320 || frame.height != 240)
          throw new AssertionError("Unexpected fixture frame");
        if (firstKey == null && frame.key) firstKey = frame;
        player[0].render(frame);
        if (i == 0)
          player[0].decoder.setOnFrameRenderedListener(
              (codec, pts, time) -> rendered.incrementAndGet(),
              new Handler(Looper.getMainLooper()));
      }
      Thread.sleep(200);
      Bitmap[] bitmap = new Bitmap[1];
      test.runOnMainSync(() -> bitmap[0] = view[0].getBitmap(320, 240));
      if (player[0].renderedFrames < 45 || bitmap[0] == null)
        throw new AssertionError(
            "Not enough displayed frames: "
                + player[0].renderedFrames
                + ", decoder callbacks="
                + rendered.get());
      int first = bitmap[0].getPixel(20, 20), other = bitmap[0].getPixel(200, 120);
      bitmap[0].recycle();
      if (first == other) throw new AssertionError("Synthetic picture was not rendered");
      String result =
          "PASS: Node to Android AES-GCM stream, H.264 MediaCodec decoder, "
              + player[0].renderedFrames
              + " displayed frames in "
              + (System.currentTimeMillis() - start)
              + " ms; synthetic picture verified";
      connection.close();
      String roundtrip = result
          + "; Android Surface encoder/decoder roundtrip displayed "
          + VideoEncoderProbe.run(player[0])
          + " frames";
      if (firstKey == null) throw new AssertionError("Missing fixture IDR");
      player[0].stopDecoder();
      test.waitForIdleSync();
      long before = player[0].renderedFrames;
      player[0].render(firstKey);
      VideoWire.Frame heartbeat = new VideoWire.Frame(new byte[0], 1, 1, false, false);
      heartbeat.heartbeat = true;
      end = System.currentTimeMillis() + 2000;
      while (player[0].renderedFrames == before && System.currentTimeMillis() < end) {
        Thread.sleep(50);
        player[0].render(heartbeat);
      }
      if (player[0].renderedFrames == before)
        throw new AssertionError("A static H264 frame must display with only heartbeats afterward");
      if (player[0].width != 320 || player[0].height != 240 || !player[0].receivingH264)
        throw new AssertionError("Heartbeat must preserve video dimensions and codec");
      player[0].render(new VideoWire.Frame(jpegBytes.toByteArray(), 320, 240, false, true));
      if (player[0].decoder != null || player[0].needsKeyFrame(null))
        throw new AssertionError("H264 to JPEG fallback must release codec and stop IDR requests");
      return roundtrip + "; pre-existing Surface, JPEG fallback, static H264 heartbeat and transient keepalive pressure verified";
    } finally {
      player[0].close();
      player[0].stopDecoder();
      if (player[0].surface != null) player[0].surface.release();
    }
  }
}
