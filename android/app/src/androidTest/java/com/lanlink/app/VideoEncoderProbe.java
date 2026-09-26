package com.lanlink.app;

import android.opengl.*;
import java.util.concurrent.*;

/** Draw generated colors into the actual codec input surface; no screen capture permission. */
final class VideoEncoderProbe {
  static long run(VideoPlayer player) throws Exception {
    ArrayBlockingQueue<VideoWire.Frame> frames = new ArrayBlockingQueue<>(120);
    VideoEncoder encoder =
        new VideoEncoder(
            320,
            240,
            new VideoEncoder.Output() {
              public void frame(VideoWire.Frame frame) {
                frames.offer(frame);
              }

              public void failed() {}
            });
    EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
    EGLContext context = EGL14.EGL_NO_CONTEXT;
    EGLSurface surface = EGL14.EGL_NO_SURFACE;
    try {
      int[] version = new int[2];
      if (!EGL14.eglInitialize(display, version, 0, version, 1))
        throw new AssertionError("EGL initialize");
      EGLConfig[] configs = new EGLConfig[1];
      int[] count = new int[1];
      int[] attrs = {
        EGL14.EGL_RED_SIZE,
        8,
        EGL14.EGL_GREEN_SIZE,
        8,
        EGL14.EGL_BLUE_SIZE,
        8,
        EGL14.EGL_RENDERABLE_TYPE,
        EGL14.EGL_OPENGL_ES2_BIT,
        0x3142,
        1,
        EGL14.EGL_NONE
      };
      if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) || count[0] == 0)
        throw new AssertionError("EGL config");
      context =
          EGL14.eglCreateContext(
              display,
              configs[0],
              EGL14.EGL_NO_CONTEXT,
              new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE},
              0);
      surface =
          EGL14.eglCreateWindowSurface(
              display, configs[0], encoder.surface, new int[] {EGL14.EGL_NONE}, 0);
      if (!EGL14.eglMakeCurrent(display, surface, surface, context))
        throw new AssertionError("EGL surface");
      player.stopDecoder();
      long before = player.renderedFrames;
      int encoded = 0;
      for (int i = 0; i < 90; i++) {
        if(i==30) encoder.setBitrate(800000); if(i==60) encoder.setBitrate(2600000);
        GLES20.glClearColor(i % 2 == 0 ? 1 : 0, i % 2 == 0 ? 0 : 1, 0.25f, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        EGLExt.eglPresentationTimeANDROID(display, surface, System.nanoTime());
        EGL14.eglSwapBuffers(display, surface);
        VideoWire.Frame frame = frames.poll(35, TimeUnit.MILLISECONDS);
        if (frame != null) {
          encoded++;
          player.render(frame);
        }
      }
      Thread.sleep(200);
      if (encoded < 50 || player.renderedFrames - before < 35)
        throw new AssertionError(
            "Android codec roundtrip: encoded="
                + encoded
                + ", displayed="
                + (player.renderedFrames - before));
      return player.renderedFrames - before;
    } finally {
      EGL14.eglMakeCurrent(
          display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
      if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
      if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
      EGL14.eglTerminate(display);
      encoder.close();
    }
  }
}
