package com.lanlink.app;

import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

/** Standalone JVM test; no Android SDK or third-party test dependency required. */
public final class LanguageSelectionTest {
  private static void expect(String expected, String mode, String... tags) {
    java.util.List<Locale> locales = new java.util.ArrayList<>();
    for (String tag : tags) locales.add(Locale.forLanguageTag(tag));
    String actual = LanguageSelection.resolve(mode, locales);
    if (!expected.equals(actual)) throw new AssertionError(expected + " != " + actual + " for " + Arrays.toString(tags));
  }

  public static void main(String[] args) {
    expect("en", null);
    expect("en", "system", "ar-SA", "hi-IN");
    expect("fr", "system", "ar-SA", "fr-CA", "en-US");
    expect("pt", "system", "pt-BR");
    expect("es", "system", "es-MX");
    expect("zh-Hans", "system", "zh-CN");
    expect("zh-Hans", "system", "zh-SG");
    expect("zh-Hant", "system", "zh-TW");
    expect("zh-Hant", "system", "zh-HK");
    expect("zh-Hant", "system", "zh-MO");
    expect("zh-Hans", "system", "zh-Hans-HK");
    expect("zh-Hant", "system", "zh-Hant-CN");
    expect("ja", "invalid-stored-value", "ja-JP");
    for (String id : LanguageSelection.IDS) {
      if (!id.equals("system")) expect(id, id, "fr-FR", "zh-CN");
    }
    expect("ko", "system", "ko-KR");
    expect("de", "system", "de-AT");
    expect("it", "system", "it-CH");
    expect("ru", "system", "ru-RU");
    // Returning to system follows a later preference change; explicit choices stay fixed.
    expect("fr", "system", "fr-FR");
    expect("ja", "system", "ja-JP");
    expect("de", "de", "ja-JP");
    if (LanguageSelection.IDS.length != LanguageSelection.NAMES.length) throw new AssertionError("native language names");
    System.out.println("PASS: Android language selection, system preference order, Chinese scripts/regions, saved choice, English fallback");
  }
}
