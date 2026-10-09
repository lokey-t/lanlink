package com.lanlink.app;

import android.app.LocaleManager;
import android.content.Context;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

/** Translates only app-owned messages explicitly passed to t(), never user text. */
final class Language {
  private static volatile Context application;
  private static volatile JSONObject messages = new JSONObject();
  private static volatile String activeLanguage = "en";

  static synchronized void initialize(Context context) {
    if (application != null) return;
    Context app = context.getApplicationContext();
    try (InputStream input = app.getAssets().open("locales.json")) {
      JSONObject catalog = new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
      messages = catalog.getJSONObject("messages");
    } catch (Exception failure) {
      android.util.Log.w("LanLink", "Unable to load language catalog", failure);
    }
    application = app;
    refresh(app);
  }

  static String mode(Context context) {
    return LanguageSelection.mode(context.getSharedPreferences("identity", 0).getString("language", "system"));
  }

  static void setMode(Context context, String value) {
    context.getSharedPreferences("identity", 0).edit().putString("language", LanguageSelection.mode(value)).apply();
    refresh(context);
  }

  static String current(Context context) {
    LocaleList locales = Resources.getSystem().getConfiguration().getLocales();
    if (Build.VERSION.SDK_INT >= 33) {
      LocaleManager manager = context.getSystemService(LocaleManager.class);
      if (manager != null) locales = manager.getSystemLocales();
    }
    List<Locale> preferred = new ArrayList<>();
    for (int i = 0; i < locales.size(); i++) preferred.add(locales.get(i));
    return LanguageSelection.resolve(mode(context), preferred);
  }

  static boolean refresh(Context context) {
    String next = current(context);
    boolean changed = !next.equals(activeLanguage);
    activeLanguage = next;
    return changed;
  }

  static String[] labels() {
    String[] labels = LanguageSelection.NAMES.clone();
    labels[0] = t("跟随系统");
    return labels;
  }

  static String label(Context context) {
    String selected = mode(context);
    for (int i = 0; i < LanguageSelection.IDS.length; i++)
      if (selected.equals(LanguageSelection.IDS[i])) return labels()[i];
    return labels()[0];
  }

  static String t(String source) {
    Context context = application;
    if (context == null || source == null || source.isEmpty()) return source;
    String language = activeLanguage;
    if (language.equals("zh-Hans")) return source;
    JSONObject translations = messages.optJSONObject(source);
    if (translations == null) return source;
    String translated = translations.optString(language, "");
    if (translated.isEmpty()) translated = translations.optString("en", "");
    return translated.isEmpty() ? source : translated;
  }
}
