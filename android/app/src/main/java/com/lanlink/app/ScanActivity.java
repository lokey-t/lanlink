package com.lanlink.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.media.Image;
import android.media.ImageReader;
import android.os.*;
import android.util.Size;
import android.view.*;
import android.widget.*;
import java.nio.ByteBuffer;
import java.util.*;

/** On-device QR scanning: camera frames are never saved or transmitted. */
public final class ScanActivity extends Activity {
  private TextureView preview;
  private TextView status;
  private HandlerThread worker;
  private Handler handler;
  private CameraDevice camera;
  private CameraCaptureSession session;
  private ImageReader reader;
  private Surface surface;
  private volatile boolean stopped = true, finished;
  private long lastFrame;
  private int generation;

  @Override
  public void onCreate(Bundle state) {
    Ui.applyTheme(this);
    super.onCreate(state);
    Ui ui = new Ui(this);
    LinearLayout page = ui.column();
    page.setPadding(ui.dp(20), ui.dp(24), ui.dp(20), ui.dp(20));
    page.setBackgroundColor(Ui.BG);
    page.addView(ui.button("返回添加设备", false, this::finish));
    ui.gap(page, 18);
    page.addView(ui.title("扫描组邀请二维码", 22));
    ui.gap(page, 10);
    status = ui.text("将另一台设备的二维码放入画面", 14, Ui.MUTED);
    page.addView(status);
    ui.gap(page, 16);
    preview = new TextureView(this);
    preview.setContentDescription("二维码扫描取景框");
    FrameLayout frame = new FrameLayout(this);
    frame.setBackground(ui.shape(0xff202637, 24));
    frame.setClipToOutline(true);
    frame.addView(preview, new FrameLayout.LayoutParams(-1, -1));
    page.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1));
    ui.gap(page, 16);
    page.addView(ui.text("二维码只在本机识别。识别后仍需确认加入，不会自动解散组。", 12, Ui.MUTED));
    setContentView(page);
    page.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          v.setPadding(
              ui.dp(20) + insets.getSystemWindowInsetLeft(),
              ui.dp(16) + insets.getSystemWindowInsetTop(),
              ui.dp(20) + insets.getSystemWindowInsetRight(),
              ui.dp(16) + insets.getSystemWindowInsetBottom());
          return insets;
        });
    preview.setSurfaceTextureListener(
        new TextureView.SurfaceTextureListener() {
          public void onSurfaceTextureAvailable(SurfaceTexture t, int w, int h) {
            startCamera();
          }

          public void onSurfaceTextureSizeChanged(SurfaceTexture t, int w, int h) {}

          public boolean onSurfaceTextureDestroyed(SurfaceTexture t) {
            stopCamera();
            return true;
          }

          public void onSurfaceTextureUpdated(SurfaceTexture t) {}
        });
  }

  @Override
  protected void onResume() {
    super.onResume();
    stopped = false;
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[] {Manifest.permission.CAMERA}, 1);
    else if (preview.isAvailable()) startCamera();
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
    super.onRequestPermissionsResult(request, permissions, grants);
    if (request == 1 && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED)
      startCamera();
    else status.setText("未开启相机权限，可返回使用手动输入或从图片选择。");
  }

  private void startCamera() {
    if (stopped
        || finished
        || worker != null
        || !preview.isAvailable()
        || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
      return;
    final int current = ++generation;
    try {
      CameraManager manager = getSystemService(CameraManager.class);
      String selected = null;
      for (String id : manager.getCameraIdList()) {
        if (selected == null) selected = id;
        if (Objects.equals(
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING),
            CameraCharacteristics.LENS_FACING_BACK)) {
          selected = id;
          break;
        }
      }
      if (selected == null) throw new IllegalStateException("没有可用相机");
      Size[] sizes =
          manager
              .getCameraCharacteristics(selected)
              .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
              .getOutputSizes(ImageFormat.YUV_420_888);
      Size size =
          Arrays.stream(sizes)
              .filter(s -> s.getWidth() <= 1280 && s.getHeight() <= 1280)
              .max(Comparator.comparingInt(s -> s.getWidth() * s.getHeight()))
              .orElse(sizes[sizes.length - 1]);
      worker = new HandlerThread("lanlink-qr-camera");
      worker.start();
      handler = new Handler(worker.getLooper());
      reader =
          ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 2);
      reader.setOnImageAvailableListener(this::readFrame, handler);
      SurfaceTexture texture = preview.getSurfaceTexture();
      texture.setDefaultBufferSize(size.getWidth(), size.getHeight());
      surface = new Surface(texture);
      manager.openCamera(
          selected,
          new CameraDevice.StateCallback() {
            public void onOpened(CameraDevice device) {
              if (stopped || current != generation) {
                device.close();
                return;
              }
              camera = device;
              try {
                device.createCaptureSession(
                    Arrays.asList(surface, reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                      public void onConfigured(CameraCaptureSession configured) {
                        if (stopped || current != generation) {
                          configured.close();
                          return;
                        }
                        session = configured;
                        try {
                          CaptureRequest.Builder request =
                              device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                          request.addTarget(surface);
                          request.addTarget(reader.getSurface());
                          request.set(
                              CaptureRequest.CONTROL_AF_MODE,
                              CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                          configured.setRepeatingRequest(request.build(), null, handler);
                        } catch (Exception e) {
                          fail();
                        }
                      }

                      public void onConfigureFailed(CameraCaptureSession configured) {
                        fail();
                      }
                    },
                    handler);
              } catch (Exception e) {
                fail();
              }
            }

            public void onDisconnected(CameraDevice device) {
              device.close();
              fail();
            }

            public void onError(CameraDevice device, int error) {
              device.close();
              fail();
            }
          },
          handler);
    } catch (Exception error) {
      fail();
    }
  }

  static String decodeLuma(byte[] bytes, int width, int height) throws Exception {
    com.google.zxing.PlanarYUVLuminanceSource source =
        new com.google.zxing.PlanarYUVLuminanceSource(
            bytes, width, height, 0, 0, width, height, false);
    Map<com.google.zxing.DecodeHintType, Object> hints =
        new EnumMap<>(com.google.zxing.DecodeHintType.class);
    hints.put(
        com.google.zxing.DecodeHintType.POSSIBLE_FORMATS,
        Collections.singletonList(com.google.zxing.BarcodeFormat.QR_CODE));
    hints.put(com.google.zxing.DecodeHintType.TRY_HARDER, true);
    return new com.google.zxing.MultiFormatReader()
        .decode(
            new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(source)),
            hints)
        .getText();
  }

  private void readFrame(ImageReader source) {
    try (Image frame = source.acquireLatestImage()) {
      if (frame == null || stopped || finished || SystemClock.elapsedRealtime() - lastFrame < 200)
        return;
      lastFrame = SystemClock.elapsedRealtime();
      int w = frame.getWidth(), h = frame.getHeight();
      Image.Plane plane = frame.getPlanes()[0];
      ByteBuffer buffer = plane.getBuffer();
      byte[] y = new byte[w * h];
      for (int row = 0; row < h; row++)
        for (int col = 0; col < w; col++)
          y[row * w + col] = buffer.get(row * plane.getRowStride() + col * plane.getPixelStride());
      String value = decodeLuma(y, w, h);
      if (!value.startsWith("lanlink://pair?")) {
        runOnUiThread(() -> status.setText("请扫描 LanLink 的组邀请二维码"));
        return;
      }
      finished = true;
      runOnUiThread(
          () -> {
            setResult(RESULT_OK, new Intent().putExtra("pairingLink", value));
            finish();
          });
    } catch (Exception ignored) {
    }
  }

  private void fail() {
    runOnUiThread(
        () -> {
          status.setText("相机暂不可用，请返回手动输入或从图片选择。");
          stopCamera();
        });
  }

  private void stopCamera() {
    stopped = true;
    generation++;
    if (session != null) {
      session.close();
      session = null;
    }
    if (camera != null) {
      camera.close();
      camera = null;
    }
    if (reader != null) {
      reader.close();
      reader = null;
    }
    if (surface != null) {
      surface.release();
      surface = null;
    }
    if (worker != null) {
      worker.quitSafely();
      worker = null;
    }
  }

  @Override
  protected void onPause() {
    stopCamera();
    super.onPause();
  }
}
