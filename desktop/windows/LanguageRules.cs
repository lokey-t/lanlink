using System;

namespace LanLink.Desktop {
  // Kept independent of Windows/WPF so locale matching can run on every test host.
  public static class LanguageRules {
    public static readonly string[] Supported = { "zh-Hans", "zh-Hant", "en", "ja", "ko", "de", "fr", "es", "pt", "it", "ru" };
    public static string Preference(string value) {
      foreach (string language in Supported) if (string.Equals(language, value, StringComparison.OrdinalIgnoreCase)) return language;
      return "system";
    }
    public static string Resolve(string preference, string systemLanguage) {
      string selected = Preference(preference);
      if (selected != "system") return selected;
      string tag = (systemLanguage ?? "").Replace('_', '-').ToLowerInvariant();
      string[] parts = tag.Split('-');
      if (parts[0] == "zh") {
        // Script is more specific than region (e.g. zh-Hans-HK).
        foreach (string part in parts) if (part == "hans") return "zh-Hans";
        foreach (string part in parts) if (part == "hant") return "zh-Hant";
        foreach (string part in parts) if (part == "tw" || part == "hk" || part == "mo") return "zh-Hant";
        return "zh-Hans";
      }
      foreach (string language in Supported) if (parts[0] == language) return language;
      return "en";
    }
  }
}
