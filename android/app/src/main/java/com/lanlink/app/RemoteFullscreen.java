package com.lanlink.app;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.transition.ChangeBounds;
import android.transition.Fade;
import android.transition.Transition;
import android.transition.TransitionListenerAdapter;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.view.*;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.IdentityHashMap;
import java.util.Map;

/** Changes layout in place so the decoder Surface and the control lease survive fullscreen. */
final class RemoteFullscreen implements AutoCloseable {
  private static final int BACKGROUND = Color.BLACK, PANEL = 0xff202736, TEXT = 0xffeef0fa;
  private final Activity activity;
  private final Dialog dialog;
  private final Ui ui;
  private final LinearLayout outer, box;
  private final FrameLayout stage;
  private final VideoViewport viewport;
  private final RemoteKeyboardView keyboard;
  private final TouchpadView normalPad, leftPad, rightPad;
  private final Runnable restoreInsets;
  final RemotePointer pointer;
  private final SharedPreferences prefs;
  private final Map<View, Integer> previousVisibility = new IdentityHashMap<>();
  private final LinearLayout menu, menuHome, settings;
  private final ScrollView menuScroll;
  private final TextView hint, connection;
  private final boolean supportedPads;
  private final int[] boxPadding = new int[4];
  private int orientation, oldFlags, oldSystemUi, oldStatusColor, oldNavColor;
  private boolean active, menuVisible, closed, oldFollowCursor, oldClipToOutline;
  private boolean leftEnabled, rightEnabled, pinchEnabled;
  private boolean transitioning;
  private int transitionGeneration;
  private Drawable oldViewportBackground, oldOuterBackground, oldStageBackground;
  private WindowInsetsController.OnControllableInsetsChangedListener barsListener;
  private int oldBarsBehavior, oldAppearance;
  private android.window.OnBackInvokedCallback backCallback;

  RemoteFullscreen(Activity activity, Dialog dialog, LinearLayout outer, LinearLayout box,
      FrameLayout stage, VideoViewport viewport, RemoteKeyboardView keyboard,
      TouchpadView normalPad, boolean supportedPads, TouchpadView.Sender sender,
      java.util.function.Consumer<Exception> onError,
      Runnable restoreInsets) {
    this.activity = activity;
    this.dialog = dialog;
    this.outer = outer;
    this.box = box;
    this.stage = stage;
    this.viewport = viewport;
    this.keyboard = keyboard;
    this.normalPad = normalPad;
    this.supportedPads = supportedPads;
    pointer = new RemotePointer(sender, onError);
    this.restoreInsets = restoreInsets;
    ui = new Ui(activity);
    prefs = activity.getSharedPreferences("remote_fullscreen", 0);
    leftEnabled = prefs.getBoolean("left_touchpad", true);
    rightEnabled = prefs.getBoolean("right_touchpad", true);
    pinchEnabled = prefs.getBoolean("pinch_zoom", true);
    leftPad = new TouchpadView(activity, sender, onError);
    rightPad = new TouchpadView(activity, sender, onError);
    leftPad.setCompactDark(true);
    rightPad.setCompactDark(true);
    leftPad.setSurfaceOpacity(.55f);
    rightPad.setSurfaceOpacity(.55f);
    leftPad.setContentDescription(Language.t("左触控板：移动鼠标，轻点单击，双指滚动，长按拖动"));
    rightPad.setContentDescription(Language.t("右触控板：移动鼠标，轻点单击，双指滚动，长按拖动"));
    stage.addView(leftPad);
    stage.addView(rightPad);
    hint = ui.text(Language.t("轻点点击 · 长按拖动 · 双指缩放 · 三指键盘"), 12, 0xffbcc5dc);
    hint.setGravity(Gravity.CENTER);
    hint.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    stage.addView(hint);
    connection = ui.text("", 11, 0xffd4dcf0);
    connection.setGravity(Gravity.CENTER);
    connection.setMaxLines(2);
    connection.setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4));
    stage.addView(connection);
    menu = ui.column();
    menu.setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(16));
    menu.setBackground(ui.shape(PANEL, 24));
    menuHome = ui.column();
    menuHome.addView(ui.text(Language.t("远程控制"), 20, TEXT));
    ui.gap(menuHome, 8);
    menuHome.addView(ui.text(Language.t("调整操作方式，或返回普通视图"), 12, 0xffaab6ce));
    ui.gap(menuHome, 22);
    addMenuButton(menuHome, Language.t("继续全屏"), this::resume);
    addMenuButton(menuHome, Language.t("打开键盘"), () -> { resume(); showKeyboard(); });
    addMenuButton(menuHome, Language.t("设置"), () -> showSettings(true));
    addMenuButton(menuHome, Language.t("退出全屏"), this::exit);
    settings = ui.column();
    settings.addView(ui.text(Language.t("全屏设置"), 20, TEXT));
    ui.gap(settings, 8);
    addSetting(Language.t("左侧触控板"), "left_touchpad", leftEnabled, supportedPads);
    addSetting(Language.t("右侧触控板"), "right_touchpad", rightEnabled, supportedPads);
    if (!supportedPads) {
      settings.addView(ui.text(Language.t("此设备不支持相对鼠标输入，可直接轻点画面控制。"), 12, 0xffaab6ce));
      ui.gap(settings, 8);
    }
    addSetting(Language.t("双指缩放"), "pinch_zoom", pinchEnabled, true);
    addMenuButton(settings, Language.t("复位画面"), viewport::reset);
    addMenuButton(settings, Language.t("返回菜单"), () -> showSettings(false));
    addMenuButton(settings, Language.t("退出全屏"), this::exit);
    menu.addView(menuHome);
    menu.addView(settings);
    settings.setVisibility(View.GONE);
    menuScroll = new ScrollView(activity);
    menuScroll.setFillViewport(false);
    menuScroll.addView(menu);
    stage.addView(menuScroll);
    hideOverlays();
    stage.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
      if (active && (r-l != or-ol || b-t != ob-ot)) {
        endPresentationTransition();
        layoutStage();
      }
    });
  }

  private Button darkButton(String text, Runnable action) {
    Button button = ui.button(text, false, action);
    button.setTextColor(TEXT);
    button.setTextSize(14);
    button.setBackground(ui.touch(0xff323b50, 14));
    return button;
  }

  private void addMenuButton(LinearLayout parent, String title, Runnable action) {
    parent.addView(darkButton(title, action), new LinearLayout.LayoutParams(-1, ui.dp(48)));
    ui.gap(parent, 8);
  }

  private void addSetting(String title, String key, boolean checked, boolean enabled) {
    Switch toggle = new Switch(activity);
    toggle.setText(title);
    toggle.setTextColor(TEXT);
    toggle.setTextSize(14);
    toggle.setSwitchPadding(ui.dp(10));
    toggle.setMinHeight(ui.dp(48));
    toggle.setChecked(checked);
    toggle.setEnabled(enabled);
    toggle.setAlpha(enabled ? 1 : .5f);
    toggle.setThumbTintList(ColorStateList.valueOf(0xffb5a7fa));
    toggle.setOnCheckedChangeListener((v, value) -> {
      prefs.edit().putBoolean(key, value).apply();
      if (key.equals("left_touchpad")) { leftEnabled = value; leftPad.cancelGesture(); }
      else if (key.equals("right_touchpad")) { rightEnabled = value; rightPad.cancelGesture(); }
      else { pinchEnabled = value; if (!value) viewport.reset(); }
      configureGestures();
      layoutStage();
    });
    settings.addView(toggle, new LinearLayout.LayoutParams(-1, -2));
  }

  boolean isActive() { return active; }
  boolean isMenuVisible() { return menuVisible; }

  void enter() {
    if (closed || active) return;
    active = true;
    menuVisible = false;
    dialog.setOnKeyListener((d, keyCode, event) -> {
      if (keyCode != KeyEvent.KEYCODE_BACK || !active) return false;
      if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) handleBack();
      return true;
    });
    orientation = activity.getRequestedOrientation();
    Window window = dialog.getWindow();
    oldFlags = window.getAttributes().flags;
    oldSystemUi = window.getDecorView().getSystemUiVisibility();
    oldStatusColor = window.getStatusBarColor();
    oldNavColor = window.getNavigationBarColor();
    boxPadding[0]=box.getPaddingLeft(); boxPadding[1]=box.getPaddingTop();
    boxPadding[2]=box.getPaddingRight(); boxPadding[3]=box.getPaddingBottom();
    previousVisibility.clear();
    for (int i=0; i<box.getChildCount(); i++) {
      View child = box.getChildAt(i);
      if (child != stage && child != keyboard) {
        previousVisibility.put(child, child.getVisibility());
        child.setVisibility(View.GONE);
      }
    }
    normalPad.cancelGesture();
    hideKeyboard();
    oldFollowCursor = viewport.followCursor;
    oldClipToOutline = viewport.getClipToOutline();
    oldViewportBackground = viewport.getBackground();
    oldOuterBackground = outer.getBackground();
    oldStageBackground = stage.getBackground();
    viewport.followCursor = false;
    viewport.setClipToOutline(false);
    // A fresh drawable preserves saved ColorDrawable instances for ordinary-view restoration.
    viewport.setBackground(new ColorDrawable(BACKGROUND));
    viewport.reset();
    box.setPadding(0,0,0,0);
    outer.setBackground(new ColorDrawable(BACKGROUND));
    stage.setBackground(new ColorDrawable(BACKGROUND));
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    window.setStatusBarColor(Color.BLACK);
    window.setNavigationBarColor(Color.BLACK);
    if (Build.VERSION.SDK_INT >= 30) {
      outer.setOnApplyWindowInsetsListener((v, insets) -> {
        Insets safe = insets.getInsets(WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
        v.setPadding(safe.left, safe.top, safe.right, safe.bottom);
        return insets;
      });
      WindowInsetsController controller = window.getInsetsController();
      if (controller != null) {
        oldBarsBehavior = controller.getSystemBarsBehavior();
        oldAppearance = controller.getSystemBarsAppearance();
        controller.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        barsListener = (c, types) -> { if (active) c.hide(WindowInsets.Type.systemBars()); };
        controller.addOnControllableInsetsChangedListener(barsListener);
        controller.hide(WindowInsets.Type.systemBars());
      }
    } else {
      outer.setPadding(0,0,0,0);
      window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
          | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
          | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
          | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
    activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    if (Build.VERSION.SDK_INT >= 33) {
      backCallback = () -> handleBack();
      dialog.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
          android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
    }
    configureGestures();
    layoutStage();
    outer.requestApplyInsets();
    stage.post(this::layoutStage);
  }

  private void configureGestures() {
    boolean controls = active && !menuVisible && !transitioning;
    viewport.setRemoteGestures(active, controls && pinchEnabled,
        controls ? pointer::tap : null,
        () -> { if (active && !menuVisible && !transitioning) showKeyboard(); },
        controls ? pointer : null);
    leftPad.setEnabled(controls);
    rightPad.setEnabled(controls);
  }

  private void showKeyboard() {
    leftPad.cancelGesture(); rightPad.cancelGesture();
    keyboard.requestFocus();
    keyboard.post(() -> {
      if (active && !closed) activity.getSystemService(InputMethodManager.class)
          .showSoftInput(keyboard, InputMethodManager.SHOW_IMPLICIT);
    });
  }

  private void hideKeyboard() {
    activity.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(keyboard.getWindowToken(), 0);
    keyboard.clearFocus();
  }

  /** System Back reveals the intermediate menu without putting a button over the stream. */
  boolean handleBack() {
    if (!active) return false;
    if (menuVisible) {
      if (settings.getVisibility() == View.VISIBLE) showSettings(false);
      else resume();
    } else showMenu();
    return true;
  }

  void showMenu() {
    if (!active || menuVisible) return;
    menuVisible = true;
    leftPad.cancelGesture(); rightPad.cancelGesture();
    hideKeyboard();
    showSettings(false);
    animatePresentation();
    menuHome.requestFocus();
  }

  private void showSettings(boolean show) {
    menuHome.setVisibility(show ? View.GONE : View.VISIBLE);
    settings.setVisibility(show ? View.VISIBLE : View.GONE);
    menuScroll.scrollTo(0,0);
  }

  void resume() {
    if (!active || !menuVisible) return;
    menuVisible = false;
    animatePresentation();
    viewport.requestFocus();
  }

  private void animatePresentation() {
    endPresentationTransition();
    if (!stage.isLaidOut() || !stage.isAttachedToWindow() || !ValueAnimator.areAnimatorsEnabled()) {
      layoutStage();
      return;
    }
    // Only animate existing view bounds and overlays. The decoder's TextureView stays attached.
    TransitionSet transition = new TransitionSet().setOrdering(TransitionSet.ORDERING_TOGETHER);
    transition.addTransition(new ChangeBounds().addTarget(viewport)
        .addTarget(viewport.texture).addTarget(viewport.image));
    Fade overlays = new Fade(Fade.IN | Fade.OUT);
    for (View view : new View[]{menuScroll,leftPad,rightPad,hint,connection}) overlays.addTarget(view);
    transition.addTransition(overlays);
    transition.setDuration(260);
    transition.setInterpolator(new PathInterpolator(.2f, 0f, 0f, 1f));
    final int generation = transitionGeneration;
    transitioning = true;
    configureGestures();
    transition.addListener(new TransitionListenerAdapter() {
      private void finished() {
        if (generation != transitionGeneration) return;
        transitioning = false;
        configureGestures();
      }
      @Override public void onTransitionEnd(Transition ignored) { finished(); }
      @Override public void onTransitionCancel(Transition ignored) { finished(); }
    });
    TransitionManager.beginDelayedTransition(stage, transition);
    layoutStage();
  }

  private void endPresentationTransition() {
    transitionGeneration++;
    TransitionManager.endTransitions(stage);
    transitioning = false;
    configureGestures();
  }

  void connectionStatus(CharSequence value) { connection.setText(value); }

  private void layoutStage() {
    if (!active || closed) return;
    int width = stage.getWidth(), height = stage.getHeight();
    if (width <= 0 || height <= 0) return;
    int margin = ui.dp(16);
    if (menuVisible) {
      int menuWidth = Math.min(ui.dp(292), Math.max(ui.dp(160), Math.round(width * .32f)));
      int previewWidth = Math.max(1, width - menuWidth - margin * 3);
      FrameLayout.LayoutParams preview = new FrameLayout.LayoutParams(previewWidth, Math.max(1, Math.round(height * .76f)), Gravity.LEFT | Gravity.CENTER_VERTICAL);
      preview.leftMargin = margin;
      viewport.setLayoutParams(preview);
      FrameLayout.LayoutParams panel = new FrameLayout.LayoutParams(menuWidth, Math.max(1,height-margin*2), Gravity.RIGHT | Gravity.CENTER_VERTICAL);
      panel.rightMargin = margin;
      menuScroll.setLayoutParams(panel);
    } else viewport.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
    int padWidth = Math.min(ui.dp(158), Math.round(width * .19f));
    int padHeight = Math.min(ui.dp(204), Math.round(height * .52f));
    FrameLayout.LayoutParams left = new FrameLayout.LayoutParams(padWidth,padHeight,Gravity.LEFT|Gravity.BOTTOM);
    left.leftMargin = margin;
    left.bottomMargin = margin;
    leftPad.setLayoutParams(left);
    FrameLayout.LayoutParams right = new FrameLayout.LayoutParams(padWidth,padHeight,Gravity.RIGHT|Gravity.BOTTOM);
    right.rightMargin = margin;
    right.bottomMargin = margin;
    rightPad.setLayoutParams(right);
    leftPad.setVisibility(!menuVisible && supportedPads && leftEnabled ? View.VISIBLE : View.GONE);
    rightPad.setVisibility(!menuVisible && supportedPads && rightEnabled ? View.VISIBLE : View.GONE);
    menuScroll.setVisibility(menuVisible ? View.VISIBLE : View.GONE);
    hint.setText(pinchEnabled ? Language.t("轻点点击 · 长按拖动 · 双指缩放 · 三指键盘") : Language.t("轻点点击 · 长按拖动 · 三指键盘"));
    FrameLayout.LayoutParams caption = new FrameLayout.LayoutParams(-1,ui.dp(32),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
    caption.leftMargin=padWidth+margin*2; caption.rightMargin=padWidth+margin*2; caption.bottomMargin=ui.dp(4);
    hint.setLayoutParams(caption);
    hint.setVisibility(menuVisible ? View.GONE : View.VISIBLE);
    FrameLayout.LayoutParams status = new FrameLayout.LayoutParams(Math.max(1,width-ui.dp(224)),ui.dp(44),Gravity.TOP|Gravity.CENTER_HORIZONTAL);
    status.topMargin=ui.dp(10);
    connection.setLayoutParams(status);
    connection.setVisibility(menuVisible ? View.GONE : View.VISIBLE);
  }

  void exit() {
    if (!active) return;
    active = false;
    menuVisible = false;
    endPresentationTransition();
    dialog.setOnKeyListener(null);
    leftPad.cancelGesture(); rightPad.cancelGesture();
    hideKeyboard();
    viewport.setRemoteGestures(false, true, null, null);
    viewport.followCursor = oldFollowCursor;
    viewport.setClipToOutline(oldClipToOutline);
    viewport.setBackground(oldViewportBackground);
    viewport.setLayoutParams(new FrameLayout.LayoutParams(-1,-1));
    hideOverlays();
    for (Map.Entry<View,Integer> entry : previousVisibility.entrySet()) entry.getKey().setVisibility(entry.getValue());
    previousVisibility.clear();
    box.setPadding(boxPadding[0],boxPadding[1],boxPadding[2],boxPadding[3]);
    outer.setBackground(oldOuterBackground);
    stage.setBackground(oldStageBackground);
    Window window = dialog.getWindow();
    if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
      dialog.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
      backCallback = null;
    }
    if (Build.VERSION.SDK_INT >= 30 && window.getInsetsController() != null) {
      WindowInsetsController controller = window.getInsetsController();
      if (barsListener != null) controller.removeOnControllableInsetsChangedListener(barsListener);
      barsListener = null;
      controller.setSystemBarsBehavior(oldBarsBehavior);
      controller.setSystemBarsAppearance(oldAppearance, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
      controller.show(WindowInsets.Type.systemBars());
    }
    window.setFlags(oldFlags, WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    window.getDecorView().setSystemUiVisibility(oldSystemUi);
    restoreInsets.run();
    window.setStatusBarColor(oldStatusColor);
    window.setNavigationBarColor(oldNavColor);
    activity.setRequestedOrientation(orientation);
    outer.requestApplyInsets();
  }

  private void hideOverlays() {
    for (View view : new View[]{leftPad,rightPad,hint,connection,menuScroll}) view.setVisibility(View.GONE);
  }

  public void close() {
    exit();
    closed = true;
    endPresentationTransition();
    pointer.close();
    leftPad.close(); rightPad.close();
  }
}
