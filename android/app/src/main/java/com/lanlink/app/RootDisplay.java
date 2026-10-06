package com.lanlink.app;

import android.graphics.Rect;
import android.hardware.display.VirtualDisplay;
import android.os.IBinder;
import android.view.Surface;
import java.lang.reflect.*;

/** Root-only mirror of display 0; protected content capture is not requested. */
final class RootDisplay implements AutoCloseable {
  final int width, height, rotation, layer;
  private VirtualDisplay display;
  private IBinder token;
  private Class<?> surfaceControl;

  RootDisplay() throws Exception {
    Class<?> global = Class.forName("android.hardware.display.DisplayManagerGlobal");
    Object manager = global.getMethod("getInstance").invoke(null);
    Object info = global.getMethod("getDisplayInfo", int.class).invoke(manager, 0);
    if (info == null) throw new IllegalStateException("主屏幕不可用");
    width = info.getClass().getField("logicalWidth").getInt(info);
    height = info.getClass().getField("logicalHeight").getInt(info);
    rotation = info.getClass().getField("rotation").getInt(info);
    layer = info.getClass().getField("layerStack").getInt(info);
  }

  void mirror(Surface surface, int videoWidth, int videoHeight) throws Exception {
    try {
      Method create =
          android.hardware.display.DisplayManager.class.getMethod(
              "createVirtualDisplay", String.class, int.class, int.class, int.class, Surface.class);
      display =
          (VirtualDisplay)
              create.invoke(null, "LanLink Root stream", videoWidth, videoHeight, 0, surface);
      if (display == null) throw new IllegalStateException("屏幕镜像创建失败");
      return;
    } catch (ReflectiveOperationException unavailable) {
      surfaceControl = Class.forName("android.view.SurfaceControl");
      token =
          (IBinder)
              surfaceControl
                  .getMethod("createDisplay", String.class, boolean.class)
                  .invoke(null, "LanLink Root stream", false);
      try {
        surfaceControl.getMethod("openTransaction").invoke(null);
        try {
          surfaceControl
              .getMethod("setDisplaySurface", IBinder.class, Surface.class)
              .invoke(null, token, surface);
          surfaceControl
              .getMethod("setDisplayProjection", IBinder.class, int.class, Rect.class, Rect.class)
              .invoke(
                  null,
                  token,
                  0,
                  new Rect(0, 0, width, height),
                  new Rect(0, 0, videoWidth, videoHeight));
          surfaceControl
              .getMethod("setDisplayLayerStack", IBinder.class, int.class)
              .invoke(null, token, layer);
        } finally {
          surfaceControl.getMethod("closeTransaction").invoke(null);
        }
      } catch (Exception failure) {
        close();
        throw failure;
      }
    }
  }

  public void close() {
    if (display != null) {
      display.release();
      display = null;
    }
    if (token != null) {
      try {
        surfaceControl.getMethod("destroyDisplay", IBinder.class).invoke(null, token);
      } catch (Exception ignored) {
      }
      token = null;
    }
  }
}
