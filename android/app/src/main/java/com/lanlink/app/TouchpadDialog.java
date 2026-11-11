package com.lanlink.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.transition.ChangeBounds;
import android.transition.Fade;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.json.JSONObject;

/** An input-only control session. No screen subscription or video decoder is opened. */
final class TouchpadDialog extends Dialog {
  private static final int INK = 0xffefeff1;
  private static final int RAIL_INK = 0xff85858b;
  private enum MenuPage { MAIN, SETTINGS, DEVICES }

  private final Activity activity;
  private final Consumer<Wire> switchDevice;
  private final SharedPreferences settings;
  private final LinkService service;
  private final Wire peer;
  private final Ui ui;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService commands = Executors.newSingleThreadExecutor();
  private final TouchpadView pad;
  private final Runnable hideIntroHint;
  private final RemoteKeyboardView keyboard;
  private final TextView status;
  private final Button connect;
  private final ProgressBar spinner;
  private final FrameLayout outer, stage;
  private final LinearLayout surface, leftRail, rightRail, connection, menu;
  private final ScrollView menuScroll;
  private final View backKey, leftKey, keyboardKey, rightKey;
  private MenuPage menuPage = MenuPage.MAIN;
  private boolean menuVisible, ready;
  private int oldOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
  private android.window.OnBackInvokedCallback backCallback;
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

  TouchpadDialog(Activity activity, LinkService service, Wire peer, Consumer<Wire> switchDevice) {
    super(activity, android.R.style.Theme_Material_NoActionBar);
    this.activity = activity;
    this.service = service;
    this.peer = peer;
    this.switchDevice = switchDevice;
    ui = new Ui(activity);
    settings = activity.getSharedPreferences("standalone_touchpad", 0);
    outer = new FrameLayout(activity);
    outer.setBackgroundColor(0xff080809);
    stage = new FrameLayout(activity);
    outer.addView(stage, new FrameLayout.LayoutParams(-1, -1));

    surface = ui.row();
    surface.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
    stage.addView(surface, new FrameLayout.LayoutParams(-1, -1));
    leftRail = ui.column();
    rightRail = ui.column();
    backKey = railKey(leftRail, "back", "返回", 27, this::showMenu);
    leftKey = railKey(leftRail, "mouse-left", "左键", 73, () -> clickMouse(0));
    keyboardKey = railKey(rightRail, "keyboard", "键盘", 27, this::showKeyboard);
    rightKey = railKey(rightRail, "mouse-right", "右键", 73, () -> clickMouse(2));
    surface.addView(leftRail, new LinearLayout.LayoutParams(ui.dp(80), -1));

    pad = new TouchpadView(activity, event -> service.remoteInput(peer.id, event),
        error -> main.post(() -> failed(error)));
    pad.setStandaloneDark();
    hideIntroHint = () -> pad.setIntroHintVisible(false);
    pad.setHapticFeedbackEnabled(settings.getBoolean("haptics", true));
    FrameLayout area = new FrameLayout(activity);
    area.addView(pad, new FrameLayout.LayoutParams(-1, -1));
    surface.addView(area, new LinearLayout.LayoutParams(0, -1, 1));
    surface.addView(rightRail, new LinearLayout.LayoutParams(ui.dp(80), -1));

    LinearLayout connection = ui.column();
    connection.setGravity(Gravity.CENTER);
    connection.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
    connection.setBackground(ui.shape(0xff202023, 14));
    status = ui.text(Language.t("正在连接…"), 13, 0xffe2e2e5);
    status.setGravity(Gravity.CENTER);
    status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    spinner = new ProgressBar(activity);
    spinner.setIndeterminateTintList(ColorStateList.valueOf(0xffd6d6da));
    connection.addView(spinner, new LinearLayout.LayoutParams(ui.dp(26), ui.dp(26)));
    connection.addView(status, new LinearLayout.LayoutParams(-1, -2));
    connect = ui.button(Language.t("重试"), false, this::retry);
    connect.setTextColor(INK);
    connect.setBackground(ui.touch(0xff37373c, 10));
    connection.addView(connect, new LinearLayout.LayoutParams(-2, ui.dp(44)));
    FrameLayout.LayoutParams connectionParams = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
    connectionParams.setMargins(ui.dp(8), 0, ui.dp(8), 0);
    area.addView(connection, connectionParams);
    this.connection = connection;

    menuScroll = new ScrollView(activity);
    menuScroll.setFillViewport(true);
    menuScroll.setBackground(ui.shape(0xff171719, 20));
    menuScroll.setClipToOutline(true);
    menuScroll.setVisibility(View.GONE);
    menu = ui.column();
    menu.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10));
    menuScroll.addView(menu);
    stage.addView(menuScroll, new FrameLayout.LayoutParams(0, 0));

    keyboard = new RemoteKeyboardView(activity, new RemoteKeyboardView.Sender() {
      public void text(String text) {
        if (menuVisible) return;
        send(() -> {
          if (peer.id.equals(service.active)) peer.request("text", Wire.obj("text", text, "touchpad", true));
        });
      }
      public void key(String code, boolean down) {
        if (menuVisible) return;
        send(() -> service.remoteInput(peer.id, Wire.obj("kind", "key", "code", code, "down", down)));
      }
    });
    keyboard.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    stage.addView(keyboard, new FrameLayout.LayoutParams(1, 1));
    stage.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
      if (r - l != or - ol || b - t != ob - ot) layoutStage();
    });
    setContentView(outer);
    setReady(false);
  }

  private View railKey(LinearLayout rail, String icon, String label, int weight, Runnable action) {
    LinearLayout key = ui.column();
    key.setGravity(Gravity.CENTER);
    key.addView(ui.icon(icon, RAIL_INK), new LinearLayout.LayoutParams(ui.dp(23), ui.dp(23)));
    ui.gap(key, 4);
    String title = Language.t(label);
    TextView text = ui.text(title, 12, RAIL_INK);
    text.setGravity(Gravity.CENTER);
    key.addView(text);
    bindButton(key, title, action);
    rail.addView(key, new LinearLayout.LayoutParams(-1, 0, weight));
    return key;
  }

  private void clickMouse(int button) {
    if (!pad.isEnabled() || !peer.id.equals(service.active)) return;
    pad.clickButton(button);
  }

  private void retry() {
    if (!currentPeerConnected()) { dismiss(); return; }
    if (requesting) return;
    freshRequest = true;
    forceNetwork = false;
    requestSince = Math.max(System.currentTimeMillis(), service.endedAt + 1);
    retryAutomatically = true;
    nextAttempt = 0;
    requestControl();
  }

  private void showMenu() {
    hideKeyboard();
    menuVisible = true;
    showMenuPage(MenuPage.MAIN);
    updateInput();
    present();
  }

  private void resume() {
    menuVisible = false;
    updateInput();
    present();
  }

  private void present() {
    TransitionManager.endTransitions(stage);
    if (stage.isLaidOut()) {
      TransitionSet transition = new TransitionSet()
          .addTransition(new ChangeBounds().addTarget(surface))
          .addTransition(new Fade().addTarget(menuScroll).addTarget(leftRail).addTarget(rightRail));
      transition.setDuration(260);
      transition.setInterpolator(new PathInterpolator(.2f, 0f, 0f, 1f));
      TransitionManager.beginDelayedTransition(stage, transition);
    }
    layoutStage();
  }

  private void layoutStage() {
    int width = stage.getWidth(), height = stage.getHeight();
    if (width <= 0 || height <= 0) return;
    int railWidth = Math.min(ui.dp(132), Math.max(ui.dp(64), Math.round(width * .155f)));
    leftRail.getLayoutParams().width = railWidth;
    rightRail.getLayoutParams().width = railWidth;
    leftRail.setVisibility(menuVisible ? View.GONE : View.VISIBLE);
    rightRail.setVisibility(menuVisible ? View.GONE : View.VISIBLE);
    menuScroll.setVisibility(menuVisible ? View.VISIBLE : View.GONE);
    if (menuVisible) {
      int gap = ui.dp(12);
      int menuWidth = Math.min(width / 2, Math.min(ui.dp(264), Math.max(ui.dp(144), Math.round(width * .32f))));
      int previewWidth = Math.max(1, width - menuWidth - 3 * gap);
      FrameLayout.LayoutParams preview = new FrameLayout.LayoutParams(
          previewWidth, Math.round(height * .76f), Gravity.LEFT | Gravity.CENTER_VERTICAL);
      preview.leftMargin = gap;
      surface.setLayoutParams(preview);
      FrameLayout.LayoutParams panel = new FrameLayout.LayoutParams(
          menuWidth, Math.max(1, height - 2 * gap), Gravity.RIGHT | Gravity.CENTER_VERTICAL);
      panel.rightMargin = gap;
      menuScroll.setLayoutParams(panel);
    } else {
      surface.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
    }
    surface.setBackground(menuVisible ? ui.shape(0xff111112, 16) : null);
    surface.setClipToOutline(menuVisible);
  }

  private void showMenuPage(MenuPage page) {
    menuPage = page;
    menu.removeAllViews();
    String title = page == MenuPage.SETTINGS ? "设置" : page == MenuPage.DEVICES ? "切换设备" : "触控板";
    TextView heading = ui.title(Language.t(title), 16);
    heading.setTextColor(INK);
    menu.addView(heading);
    ui.gap(menu, 8);
    switch (page) {
      case MAIN:
        menuButton("touchpad", "继续全屏", this::resume);
        menuButton("settings", "设置", () -> showMenuPage(MenuPage.SETTINGS));
        menuButton("refresh", "切换设备", () -> showMenuPage(MenuPage.DEVICES));
        menuButton("close", "退出触控板", this::dismiss);
        break;
      case SETTINGS:
        Switch haptics = new Switch(getContext());
        haptics.setText(Language.t("触感反馈"));
        haptics.setTextColor(INK);
        haptics.setMinHeight(ui.dp(48));
        haptics.setChecked(pad.isHapticFeedbackEnabled());
        haptics.setOnCheckedChangeListener((button, checked) -> {
          pad.setHapticFeedbackEnabled(checked);
          settings.edit().putBoolean("haptics", checked).apply();
        });
        menu.addView(haptics);
        menuButton("back", "返回菜单", () -> showMenuPage(MenuPage.MAIN));
        break;
      case DEVICES:
        showDeviceChoices();
        menuButton("back", "返回菜单", () -> showMenuPage(MenuPage.MAIN));
        break;
    }
    menuScroll.scrollTo(0, 0);
  }

  private void showDeviceChoices() {
    List<Wire> choices = new ArrayList<>();
    JSONObject devices = service.authorizationDevices();
    for (Iterator<String> ids = devices.keys(); ids.hasNext();) {
      Wire target = service.peers.get(ids.next());
      if (target == null || !target.connected || peer.id.equals(target.id)) continue;
      JSONObject capabilities = target.info.optJSONObject("capabilities");
      if (capabilities != null && capabilities.optBoolean("relativeInput")) choices.add(target);
    }
    choices.sort(Comparator.comparing(target -> target.info.optString("name"), String.CASE_INSENSITIVE_ORDER));
    if (choices.isEmpty()) {
      menu.addView(ui.text(Language.t("暂无其他在线设备"), 13, 0xffb5b5bd));
      ui.gap(menu, 8);
    }
    for (Wire target : choices) {
      addMenuButton("monitor", target.info.optString("name", Language.t("设备")), () -> switchDevice.accept(target));
    }
  }

  private void menuButton(String icon, String text, Runnable action) {
    addMenuButton(icon, Language.t(text), action);
  }

  private void addMenuButton(String icon, String label, Runnable action) {
    LinearLayout row = ui.row();
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(ui.dp(10), 0, ui.dp(8), 0);
    row.setBackground(new RippleDrawable(
        ColorStateList.valueOf(0x33ffffff), ui.shape(0xff29292c, 10), null));
    row.addView(ui.icon(icon, INK), new LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)));
    TextView text = ui.text(label, 13, INK);
    text.setPadding(ui.dp(8), 0, 0, 0);
    text.setSingleLine(true);
    text.setEllipsize(android.text.TextUtils.TruncateAt.END);
    row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
    bindButton(row, label, action);
    menu.addView(row, new LinearLayout.LayoutParams(-1, ui.dp(48)));
    ui.gap(menu, 6);
  }

  private void bindButton(LinearLayout button, String label, Runnable action) {
    button.setContentDescription(label);
    button.setFocusable(true);
    button.setAccessibilityDelegate(new View.AccessibilityDelegate() {
      @Override public void onInitializeAccessibilityNodeInfo(
          View host, android.view.accessibility.AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(host, info);
        info.setClassName(Button.class.getName());
      }
    });
    for (int i = 0; i < button.getChildCount(); i++)
      button.getChildAt(i).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    button.setOnClickListener(v -> action.run());
  }

  private void styleRails(WindowInsets insets, Insets safe) {
    int topLeft = RoundedCorner.POSITION_TOP_LEFT, topRight = RoundedCorner.POSITION_TOP_RIGHT;
    int bottomRight = RoundedCorner.POSITION_BOTTOM_RIGHT, bottomLeft = RoundedCorner.POSITION_BOTTOM_LEFT;
    styleRailKey(backKey, topLeft, railRadius(insets, topLeft, safe.left, safe.top), true, true);
    styleRailKey(keyboardKey, topRight, railRadius(insets, topRight, safe.right, safe.top), false, true);
    styleRailKey(rightKey, bottomRight, railRadius(insets, bottomRight, safe.right, safe.bottom), false, false);
    styleRailKey(leftKey, bottomLeft, railRadius(insets, bottomLeft, safe.left, safe.bottom), true, false);
  }

  private float railRadius(WindowInsets insets, int position, int sideInset, int verticalInset) {
    if (Build.VERSION.SDK_INT < 31 || insets == null) return 0;
    RoundedCorner corner = insets.getRoundedCorner(position);
    return corner == null ? 0 : Math.max(0, corner.getRadius() - Math.max(sideInset, verticalInset));
  }

  private void styleRailKey(View key, int corner, float radius, boolean left, boolean top) {
    float[] radii = new float[8];
    radii[2 * corner] = radii[2 * corner + 1] = radius;
    GradientDrawable fill = new GradientDrawable();
    fill.setColor(0xff1d1d20);
    fill.setCornerRadii(radii);
    GradientDrawable mask = new GradientDrawable();
    mask.setColor(Color.WHITE);
    mask.setCornerRadii(radii);
    RippleDrawable ripple = new RippleDrawable(ColorStateList.valueOf(0x46ffffff), fill, mask);
    key.setBackground(ripple);
    key.setForeground(new RailDivider(left, top));
  }

  private final class RailDivider extends android.graphics.drawable.Drawable {
    private final Paint paint = new Paint();
    private final boolean left, top;

    RailDivider(boolean left, boolean top) {
      this.left = left;
      this.top = top;
      paint.setColor(0xff39393e);
    }

    @Override public void draw(Canvas canvas) {
      Rect bounds = getBounds();
      int x = left ? bounds.right - 1 : bounds.left;
      canvas.drawRect(x, bounds.top, x + 1, bounds.bottom, paint);
      if (top) canvas.drawRect(bounds.left, bounds.bottom - 1, bounds.right, bounds.bottom, paint);
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
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
    this.ready = ready;
    updateInput();
    connection.setVisibility(ready ? View.GONE : View.VISIBLE);
    connect.setVisibility(ready || requesting ? View.GONE : View.VISIBLE);
    connect.setEnabled(!currentPeerConnected() || !requesting);
    connect.setText(!currentPeerConnected() ? Language.t("返回设备列表") : Language.t("重试"));
    spinner.setVisibility(!ready && requesting ? View.VISIBLE : View.GONE);
  }

  private void updateInput() {
    boolean enabled = ready && !menuVisible;
    if (!enabled && pad.isEnabled()) pad.cancelGesture();
    pad.setEnabled(enabled);
    pad.setAlpha(ready || menuVisible ? 1f : .58f);
    for (View key : new View[] {leftKey, rightKey, keyboardKey}) {
      key.setEnabled(enabled);
      key.setAlpha(enabled ? 1f : .5f);
    }
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
          if (closed || !currentPeerConnected() || !peer.id.equals(service.active)) return;
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
    if (!pad.isEnabled() || !peer.id.equals(service.active)) return;
    pad.cancelGesture();
    keyboard.requestFocus();
    InputMethodManager ime = getContext().getSystemService(InputMethodManager.class);
    if (ime != null) ime.showSoftInput(keyboard, InputMethodManager.SHOW_IMPLICIT);
  }

  private void hideKeyboard() {
    InputMethodManager ime = getContext().getSystemService(InputMethodManager.class);
    if (ime != null) ime.hideSoftInputFromWindow(keyboard.getWindowToken(), 0);
    keyboard.clearFocus();
  }

  @Override public void onBackPressed() {
    if (!menuVisible) showMenu();
    else if (menuPage != MenuPage.MAIN) showMenuPage(MenuPage.MAIN);
    else resume();
  }

  @Override public void show() {
    oldOrientation = activity.getRequestedOrientation();
    activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    super.show();
    Window window = getWindow();
    if (window != null) {
      window.setLayout(-1, -1);
      window.setStatusBarColor(Color.TRANSPARENT);
      window.setNavigationBarColor(Color.BLACK);
      window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
      window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
      if (Build.VERSION.SDK_INT >= 30) {
        window.setDecorFitsSystemWindows(false);
        outer.setOnApplyWindowInsetsListener((view, insets) -> {
          Insets safe = insets.getInsets(WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
          view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
          styleRails(insets, safe);
          return insets;
        });
        outer.requestApplyInsets();
      } else outer.setFitsSystemWindows(true);
      if (Build.VERSION.SDK_INT >= 33) {
        backCallback = this::onBackPressed;
        window.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
      }
      immersive();
    }
    styleRails(null, Insets.NONE);
    pad.setIntroHintVisible(true);
    main.removeCallbacks(hideIntroHint);
    main.postDelayed(hideIntroHint, 10_000);
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
    main.removeCallbacks(hideIntroHint);
    if (Build.VERSION.SDK_INT >= 33 && backCallback != null && getWindow() != null)
      getWindow().getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
    hideKeyboard();
    pad.close();
    activity.setRequestedOrientation(oldOrientation);
    if (peer.id.equals(service.active) || requesting && service.active == null) service.releaseControl();
    commands.shutdown();
  }
}
