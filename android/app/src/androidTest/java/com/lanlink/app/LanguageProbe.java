package com.lanlink.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import org.json.JSONObject;

/** Disposable-emulator regression for packaged translations and saved language choices. */
final class LanguageProbe {
  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static boolean hasText(View view, String value) {
    if (view instanceof TextView && value.contentEquals(((TextView) view).getText())) return true;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) if (hasText(group.getChildAt(i), value)) return true;
    }
    return false;
  }

  static String run(Instrumentation test) throws Exception {
    Context context = test.getTargetContext();
    SharedPreferences prefs = context.getSharedPreferences("identity", 0);
    String previous = prefs.getString("language", null);
    Activity activity = null;
    try {
      prefs.edit().remove("language").commit();
      Language.initialize(context);
      Language.refresh(context);
      check("system".equals(Language.mode(context)), "fresh install follows system without saving an override");
      String system = Language.current(context);
      JSONObject catalog;
      try (java.io.InputStream stream = context.getAssets().open("locales.json")) {
        catalog = new JSONObject(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
      }
      check(system.equals("zh-Hans") ? Language.t("设置").equals("设置") :
          Language.t("设置").equals(catalog.getJSONObject("messages").getJSONObject("设置").getString(system)),
          "first render uses supported system language");
      for (String language : LanguageSelection.IDS) {
        Language.setMode(context, language);
        check(language.equals(Language.mode(context)), "saved language " + language);
        if (!language.equals("system") && !language.equals("zh-Hans")) {
          String expected = catalog.getJSONObject("messages").getJSONObject("设置").getString(language);
          check(expected.equals(Language.t("设置")), "packaged settings translation " + language);
        }
      }
      Language.setMode(context, "en");
      activity = test.startActivitySync(new Intent(context, MainActivity.class)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
      test.waitForIdleSync();
      check(hasText(activity.getWindow().getDecorView(), "Devices"), "first activity frame is English");
      Activity first = activity;
      LinkService service = LinkService.instance;
      check(service != null, "background service started");
      test.runOnMainSync(() -> {
        TextView userText = new Ui(first).text("设置", 14, Ui.INK);
        check("设置".contentEquals(userText.getText()), "user text equal to a translation key stays unchanged");
      });
      Instrumentation.ActivityMonitor recreated = test.addMonitor(MainActivity.class.getName(), null, false);
      test.runOnMainSync(() -> {
        try {
          java.lang.reflect.Method picker = MainActivity.class.getDeclaredMethod("showLanguagePicker");
          picker.setAccessible(true);
          android.app.AlertDialog dialog = (android.app.AlertDialog) picker.invoke(first);
          android.widget.ListView list = dialog.getListView();
          int french = java.util.Arrays.asList(LanguageSelection.IDS).indexOf("fr");
          list.performItemClick(list.getChildAt(french), french, list.getAdapter().getItemId(french));
        } catch (Exception failure) { throw new RuntimeException(failure); }
      });
      activity = test.waitForMonitorWithTimeout(recreated, 10000);
      test.removeMonitor(recreated);
      check(activity != null, "language picker recreates the visible activity");
      test.waitForIdleSync();
      check(service == LinkService.instance, "changing language keeps the background service alive");
      check("fr".equals(context.createPackageContext(context.getPackageName(), 0)
          .getSharedPreferences("identity", 0).getString("language", "system")), "saved choice survives a new activity/context");
      check(hasText(activity.getWindow().getDecorView(), Language.t("设置")), "new activity renders the saved language immediately");
      Language.setMode(context, "system");
      check(system.equals(Language.current(context)), "return to system mode");
      return "PASS: first-use system language, all packaged settings languages, picker applies immediately, saved choice, service continuity, unchanged user text";
    } finally {
      if (activity != null) { Activity last = activity; test.runOnMainSync(last::finish); }
      if (previous == null) prefs.edit().remove("language").commit();
      else prefs.edit().putString("language", previous).commit();
      Language.refresh(context);
    }
  }
}
