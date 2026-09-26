package com.lanlink.app;

import android.app.*;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

final class VideoCodecProbe {
  static String run(Instrumentation test, Activity activity, Bundle args) throws Exception {
    VideoPlayer[] player = new VideoPlayer[1];
    TextureView[] view = new TextureView[1];
    test.runOnMainSync(
        () -> {
          FrameLayout box = new FrameLayout(activity);
          view[0] = new TextureView(activity);
          ImageView image = new ImageView(activity);
          TextView status = new TextView(activity);
          box.addView(view[0], new FrameLayout.LayoutParams(640, 480));
          box.addView(image, new FrameLayout.LayoutParams(640, 480));
          Wire peer = new Wire(new Socket(), Wire.obj(), new byte[32], null);
          player[0] = new VideoPlayer(peer, view[0], image, status);
          activity.setContentView(box);
        });
    long end = System.currentTimeMillis() + 5000;
    while (player[0].surface == null && System.currentTimeMillis() < end) Thread.sleep(30);
    if (player[0].surface == null) throw new AssertionError("Texture surface unavailable");
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
      for (int i = 0; i < 100; i++) {
        VideoWire.Frame frame = connection.next();
        if (frame.heartbeat) {
          i--;
          continue;
        }
        if (!frame.h264 || frame.width != 320 || frame.height != 240)
          throw new AssertionError("Unexpected fixture frame");
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
      return result
          + "; Android Surface encoder/decoder roundtrip displayed "
          + VideoEncoderProbe.run(player[0])
          + " frames";
    } finally {
      player[0].close();
      player[0].stopDecoder();
      if (player[0].surface != null) player[0].surface.release();
    }
  }
}
