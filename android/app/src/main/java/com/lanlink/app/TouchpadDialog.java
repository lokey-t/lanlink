package com.lanlink.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Insets;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** An input-only control session. No screen subscription or video decoder is opened. */
final class TouchpadDialog extends Dialog {
  private final LinkService service;
  private final Wire peer;
  private final Ui ui;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService commands = Executors.newSingleThreadExecutor();
  private final TouchpadView pad;
  private final RemoteKeyboardView keyboard;
  private final TextView status;
  private final Button connect;
  private final ProgressBar spinner;
  private final LinearLayout outer;
  private long requestSince = System.currentTimeMillis();
  private volatile boolean closed, requesting;
  private boolean freshRequest = true, wasConnected, wasBluetooth, retryAutomatically = true;
  private long nextAttempt, requestStart;
  private boolean forceNetwork;

  private final Runnable refresh = new Runnable() {
    public void run() {
      if (closed || !isShowing()) return;
      boolean connected = peer.connected && service.peers.get(peer.id) == peer;
      boolean owned = connected && peer.id.equals(service.active);
      if (!connected) {
        retryAutomatically = false;
        setReady(false);
        status.setText(Language.t("设备已离线，请返回列表并等待设备上线"));
      } else if (peer.id.equals(service.endedPeer) && service.endedAt >= requestSince) {
        retryAutomatically = false;
        setReady(false);
        status.setText(Language.t("对方已结束控制，点击重新请求可再次连接"));
      } else if (owned) {
        wasConnected = true;
        setReady(true);
        wasBluetooth = service.usingHid(peer.id);
        status.setText(Language.t(wasBluetooth ? "已连接 · 蓝牙 HID" : "已连接 · 原生触控板"));
      } else {
        setReady(false);
        if (!connected) {
          retryAutomatically = false;
          status.setText(Language.t("设备已离线，请返回列表并等待设备上线"));
        } else if (wasBluetooth) {
          forceNetwork = true;
          wasBluetooth = false;
          wasConnected = false;
          retryAutomatically = true;
          nextAttempt = System.currentTimeMillis() + 700;
          status.setText(Language.t("正在连接…"));
        } else if (wasConnected) {
          retryAutomatically = false;
          wasConnected = false;
          status.setText(Language.t("控制已中断，点击连接重新开始"));
        } else if (retryAutomatically && !requesting && System.currentTimeMillis() >= nextAttempt) {
          requestControl();
        } else if (!requesting) {
          status.setText(Language.t("正在连接…"));
        }
      }
      main.postDelayed(this, 700);
    }
  };

  TouchpadDialog(Activity activity, LinkService service, Wire peer) {
    super(activity, android.R.style.Theme_Material_NoActionBar);
    this.service = service;
    this.peer = peer;
    ui = new Ui(activity);
    outer = ui.column();
    outer.setBackgroundColor(0xff111722);
    LinearLayout content = ui.column();
    content.setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(18));
    outer.addView(content, new LinearLayout.LayoutParams(-1, -1));
    LinearLayout header = ui.row();
    header.addView(tool("back", Language.t("关闭并释放触控板"), this::dismiss),
        new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
    LinearLayout labels = ui.column();
    labels.setPadding(ui.dp(12), 0, ui.dp(8), 0);
    TextView title = ui.text(peer.info.optString("name", Language.t("触控板")), 19, 0xfff0f2fa);
    title.setMaxLines(1);
    title.setEllipsize(android.text.TextUtils.TruncateAt.END);
    labels.addView(title);
    ui.gap(labels, 5);
    status = ui.text(Language.t("正在连接…"), 12, 0xffaeb8cb);
    labels.addView(status);
    header.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
    header.addView(tool("keyboard", Language.t("打开远程键盘"), this::showKeyboard),
        new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
    content.addView(header);
    ui.gap(content, 12);
    pad = new TouchpadView(activity, event -> service.remoteInput(peer.id, event),
        error -> main.post(() -> failed(error)));
    pad.setCompactDark(true);
    pad.setEnabled(false);
    pad.setAlpha(.5f);
    FrameLayout area = new FrameLayout(activity);
    area.addView(pad, new FrameLayout.LayoutParams(-1, -1));
    spinner = new ProgressBar(activity);
    spinner.setIndeterminate(true);
    FrameLayout.LayoutParams spinnerParams =
        new FrameLayout.LayoutParams(ui.dp(44), ui.dp(44), Gravity.CENTER);
    area.addView(spinner, spinnerParams);
    content.addView(area, new LinearLayout.LayoutParams(-1, 0, 1));
    ui.gap(content, 14);
    LinearLayout footer = ui.row();
    LinearLayout hints = ui.column();
    hints.addView(ui.text(Language.t("单指移动 · 轻点单击 · 长按拖动"), 12, 0xffc9d0df));
    ui.gap(hints, 5);
    hints.addView(ui.text(Language.t("双指滚动 · 双指轻点右键"), 11, 0xff8e9ab0));
    footer.addView(hints, new LinearLayout.LayoutParams(0, -2, 1));
    connect = ui.button(Language.t("重试"), false, () -> {
      if (!currentPeerConnected()) { dismiss(); return; }
      if (requesting) return;
      freshRequest = true;
      forceNetwork = false;
      requestSince = Math.max(System.currentTimeMillis(), service.endedAt + 1);
      retryAutomatically = true;
      nextAttempt = 0;
      requestControl();
    });
    connect.setTextColor(0xffd5caff);
    connect.setTextSize(12);
    connect.setBackground(ui.touch(0xff303348, 14));
    footer.addView(connect, new LinearLayout.LayoutParams(ui.dp(96), ui.dp(46)));
    content.addView(footer);
    keyboard = new RemoteKeyboardView(activity, new RemoteKeyboardView.Sender() {
      public void text(String text) {
        send(() -> {
          if (peer.id.equals(service.active)) peer.request("text", Wire.obj("text", text, "touchpad", true));
        });
      }
      public void key(String code, boolean down) {
        send(() -> service.remoteInput(peer.id, Wire.obj("kind", "key", "code", code, "down", down)));
      }
    });
    content.addView(keyboard, new LinearLayout.LayoutParams(1, 1));
    setContentView(outer);
  }

  private View tool(String glyph, String description, Runnable action) {
    FrameLayout button = new FrameLayout(getContext());
    button.setContentDescription(description);
    button.setFocusable(true);
    button.setBackground(ui.touch(0xff262e3f, 16));
    button.addView(ui.icon(glyph, 0xffd8d2f1),
        new FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.CENTER));
    button.setOnClickListener(v -> action.run());
    return button;
  }

  private interface Command { void run() throws Exception; }

  private void send(Command command) {
    if (closed) return;
    commands.execute(() -> {
      if (closed) return;
      try { command.run(); }
      catch (Exception failure) { main.post(() -> failed(failure)); }
    });
  }

  private boolean currentPeerConnected() {
    return peer.connected && service.peers.get(peer.id) == peer;
  }

  private void setReady(boolean ready) {
    if (!ready && pad.isEnabled()) pad.cancelGesture();
    pad.setEnabled(ready);
    pad.setAlpha(ready ? 1f : .5f);
    connect.setVisibility(ready || requesting ? View.GONE : View.VISIBLE);
    connect.setEnabled(!currentPeerConnected() || !requesting);
    connect.setText(!currentPeerConnected() ? Language.t("返回设备列表") : Language.t("重试"));
    if (spinner != null) spinner.setVisibility(!ready && requesting ? View.VISIBLE : View.GONE);
  }

  private void failed(Exception failure) {
    if (closed) return;
    requesting = false;
    setReady(false);
    String message = failure.getMessage() == null ? Language.t("连接失败，请重新请求") : failure.getMessage();
    status.setText(message.replaceFirst("^[A-Z_]+: ?", ""));
  }

  private void requestControl() {
    if (closed || requesting) return;
    if (!currentPeerConnected()) { setReady(false); status.setText(Language.t("设备已离线，请返回列表并等待设备上线")); return; }
    requesting = true;
    requestStart = System.currentTimeMillis();
    setReady(false);
    status.setText(Language.t("正在连接…"));
    final boolean fresh = freshRequest, network = forceNetwork;
    commands.execute(() -> {
      try {
        if (closed) return;
        service.controlTouchpad(peer.id, fresh, network);
        if (closed) {
          if (peer.id.equals(service.active)) service.releaseControl();
          return;
        }
        long elapsed = System.currentTimeMillis() - requestStart;
        main.postDelayed(() -> {
          if (closed) return;
          freshRequest = false;
          wasConnected = true;
          setReady(true);
          wasBluetooth = service.usingHid(peer.id);
          status.setText(Language.t(wasBluetooth ? "已连接 · 蓝牙 HID" : "已连接 · 原生触控板"));
        }, Math.max(0, 320 - elapsed));
      } catch (Exception failure) {
        main.post(() -> {
          if (closed) return;
          String reason = failure.getMessage() == null ? "" : failure.getMessage();
          if (reason.startsWith("CONSENT_") || reason.startsWith("PERMISSION_")) freshRequest = false;
          retryAutomatically = reason.startsWith("CONSENT_PENDING:")
              || reason.startsWith("PERMISSION_REQUIRED:")
              || reason.startsWith("BLUETOOTH_NOT_READY:")
              || reason.contains("上次控制请求尚未结束");
          nextAttempt = System.currentTimeMillis() + 2000;
          failed(failure);
        });
      } finally {
        requesting = false;
      }
    });
  }

  private void showKeyboard() {
    if (!peer.id.equals(service.active)) return;
    pad.cancelGesture();
    keyboard.requestFocus();
    InputMethodManager ime = getContext().getSystemService(InputMethodManager.class);
    if (ime != null) ime.showSoftInput(keyboard, InputMethodManager.SHOW_IMPLICIT);
  }

  public void show() {
    super.show();
    Window window = getWindow();
    if (window != null) {
      window.setLayout(-1, -1);
      window.setStatusBarColor(Color.TRANSPARENT);
      window.setNavigationBarColor(0xff111722);
      window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
      window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
      if (Build.VERSION.SDK_INT >= 30) {
        window.setDecorFitsSystemWindows(false);
        outer.setOnApplyWindowInsetsListener((view, insets) -> {
          Insets safe = insets.getInsets(WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
          view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
          return insets;
        });
        outer.requestApplyInsets();
      } else outer.setFitsSystemWindows(true);
      immersive();
    }
    main.post(refresh);
  }

  private void immersive() {
    Window window = getWindow();
    if (window == null) return;
    if (Build.VERSION.SDK_INT >= 30) {
      WindowInsetsController controller = window.getInsetsController();
      if (controller != null) {
        controller.hide(WindowInsets.Type.systemBars());
        controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
      }
    } else window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
  }

  public void onWindowFocusChanged(boolean focused) {
    super.onWindowFocusChanged(focused);
    if (focused) immersive();
    else pad.cancelGesture();
  }

  protected void onStop() {
    closeSession();
    super.onStop();
  }

  private void closeSession() {
    if (closed) return;
    closed = true;
    main.removeCallbacks(refresh);
    InputMethodManager ime = getContext().getSystemService(InputMethodManager.class);
    if (ime != null) ime.hideSoftInputFromWindow(keyboard.getWindowToken(), 0);
    pad.close();
    if (peer.id.equals(service.active) || requesting && service.active == null) service.releaseControl();
    commands.shutdown();
  }
}
