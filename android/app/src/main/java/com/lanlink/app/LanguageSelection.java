package com.lanlink.app;

import java.util.List;
import java.util.Locale;

/** Locale matching without Android dependencies, shared by the UI and resolver tests. */
final class LanguageSelection {
  static final String[] IDS = {"system", "zh-Hans", "zh-Hant", "en", "ja", "ko", "de", "fr", "es", "pt", "it", "ru"};
  static final String[] NAMES = {"", "简体中文", "繁體中文", "English", "日本語", "한국어", "Deutsch", "Français", "Español", "Português", "Italiano", "Русский"};

  static String mode(String value) {
    for (String id : IDS) if (id.equals(value)) return id;
    return "system";
  }

  static String resolve(String selection, List<Locale> preferred) {
    String explicit = mode(selection);
    if (!explicit.equals("system")) return explicit;
    for (Locale locale : preferred) {
      String matched = match(locale);
      if (matched != null) return matched;
    }
    return "en";
  }

  private static String match(Locale locale) {
    if (locale == null) return null;
    String language = locale.getLanguage();
    if (language.equals("zh")) {
      String script = locale.getScript(), region = locale.getCountry();
      if (script.equalsIgnoreCase("Hant")) return "zh-Hant";
      if (script.equalsIgnoreCase("Hans")) return "zh-Hans";
      return region.equals("TW") || region.equals("HK") || region.equals("MO") ? "zh-Hant" : "zh-Hans";
    }
    for (String id : IDS) if (id.equals(language) && !id.equals("system")) return id;
    return null;
  }
}
