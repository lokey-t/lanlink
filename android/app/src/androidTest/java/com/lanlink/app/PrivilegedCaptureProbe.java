package com.lanlink.app;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

final class PrivilegedCaptureProbe {
  static void run(LinkService service) throws Exception {
    java.lang.reflect.Field projection = LinkService.class.getDeclaredField("projection");
    projection.setAccessible(true);
    if (service.rootEnabled() || projection.get(service) != null || !service.privilegedScreenReady())
      throw new AssertionError("ADB-only capture fixture required");
    for (int round = 0; round < 2; round++) {
      CountDownLatch frames = new CountDownLatch(3);
      AtomicBoolean key = new AtomicBoolean(), failed = new AtomicBoolean();
      VideoSource source = service.startVideoEncoder(new VideoEncoder.Output() {
        public void frame(VideoWire.Frame frame) {
          if (!frame.h264 || frame.width < 100 || frame.height < 100 || frame.data.length == 0)
            failed.set(true);
          if (frame.key) key.set(true);
          frames.countDown();
        }
        public void failed() { failed.set(true); while (frames.getCount() > 0) frames.countDown(); }
      });
      try {
        source.setBitrate(800_000);
        source.requestKey();
        if (!frames.await(12, TimeUnit.SECONDS) || !key.get() || failed.get())
          throw new AssertionError("ADB H.264 capture failed: " + service.status);
      } finally {
        source.close();
        service.stopProjection();
      }
      Thread.sleep(2100);
    }
    if (projection.get(service) != null) throw new AssertionError("Unexpected MediaProjection");
  }
}
