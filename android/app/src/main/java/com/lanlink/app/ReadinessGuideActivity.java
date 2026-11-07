package com.lanlink.app;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

public final class ReadinessGuideActivity extends Activity {
  private WebView illustration;

  @Override protected void onCreate(Bundle state) {
    Ui.applyTheme(this);
    super.onCreate(state);
    Ui ui = new Ui(this);
    LinearLayout card = ui.card();
    ScrollView scroll = new ScrollView(this);
    scroll.setBackgroundColor(Ui.BG);
    scroll.setPadding(ui.dp(20), 0, ui.dp(20), 0);
    scroll.setOnApplyWindowInsetsListener((view, insets) -> {
      if (android.os.Build.VERSION.SDK_INT >= 30) {
        android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
        view.setPadding(ui.dp(20) + bars.left, bars.top + ui.dp(16),
            ui.dp(20) + bars.right, bars.bottom + ui.dp(16));
      }
      return insets;
    });
    scroll.setFillViewport(true);
    FrameLayout panel = new FrameLayout(this);
    panel.addView(card, new FrameLayout.LayoutParams(-1, -2, android.view.Gravity.CENTER));
    scroll.addView(panel);
    setContentView(scroll);

    LinkService service = LinkService.instance;
    card.addView(ui.title(Language.t("未就绪 · 查看授权教程"), 22));
    ui.gap(card, 16);
    illustration = new WebView(this);
    illustration.setFocusable(false);
    illustration.setBackgroundColor(android.graphics.Color.TRANSPARENT);
    illustration.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    illustration.getSettings().setAllowContentAccess(false);
    illustration.getSettings().setAllowFileAccess(false);
    illustration.getSettings().setBlockNetworkLoads(true);
    illustration.loadDataWithBaseURL("file:///android_asset/", """
        <!doctype html><meta name="viewport" content="width=device-width, initial-scale=1">
        <style>body{margin:0;display:grid;place-items:center;height:100vh}
        img{width:100%;max-height:100%}</style><img src="adb-connect.svg" alt="">
        """, "text/html", "UTF-8", null);
    card.addView(illustration, new LinearLayout.LayoutParams(-1, ui.dp(120)));
    ui.gap(card, 16);

    card.addView(ui.text(Language.t("1. 手机与电脑加入同一组。\n")
        + Language.t("2. 用数据线连接电脑，在手机开发者选项中开启 USB 调试并信任电脑。\n")
        + Language.t("3. 在电脑「安卓授权」选择本机并点击授权。小米等手机还需开启「USB 调试（安全设置）」。"), 15, Ui.INK));
    ui.gap(card, 12);
    card.addView(ui.text(Language.t("有 Root 权限的手机也可以在 LanLink 设置中启用 Root 模式。"), 14, Ui.MUTED));
    if (service != null && service.adbRevoked() && !service.rootEnabled()) {
      ui.gap(card, 12);
      card.addView(ui.button(Language.t("允许电脑授权"), true, () -> {
        service.enableAdbAuthorization();
        finish();
      }));
    }
    ui.gap(card, 20);
    card.addView(ui.button(Language.t("返回设备"), false, this::finish));
  }

  @Override protected void onDestroy() {
    if (illustration != null) {
      if (illustration.getParent() instanceof android.view.ViewGroup parent) parent.removeView(illustration);
      illustration.destroy();
    }
    super.onDestroy();
  }
}
