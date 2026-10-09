using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Runtime.InteropServices;

namespace LanLink.Desktop {
  public static class Locale {
    static object messages;
    public static string Preference { get; private set; }
    public static string Language { get; private set; }
    public static event Action Changed;
    public static readonly string[] NativeNames = { "简体中文", "繁體中文", "English", "日本語", "한국어", "Deutsch", "Français", "Español", "Português", "Italiano", "Русский" };
    [DllImport("kernel32.dll")] static extern ushort GetUserDefaultUILanguage();
    public static string SystemLanguage() {
      try { return CultureInfo.GetCultureInfo(GetUserDefaultUILanguage()).Name; }
      catch { return CultureInfo.CurrentUICulture.Name; }
    }
    public static void Initialize(string root, string directory) {
      try { messages = Json.Get(new System.Web.Script.Serialization.JavaScriptSerializer().DeserializeObject(File.ReadAllText(Path.Combine(root, "src/locales.json"))), "messages"); } catch { messages = null; }
      string preference = "system";
      try { preference = Json.Text(new System.Web.Script.Serialization.JavaScriptSerializer().DeserializeObject(File.ReadAllText(Path.Combine(directory, "config.json"))), "language"); } catch { }
      Set(preference);
    }
    public static void Set(string value) {
      string preference = LanguageRules.Preference(value), language = LanguageRules.Resolve(preference, SystemLanguage());
      if (preference == Preference && language == Language) return;
      Preference = preference; Language = language;
      if (Changed != null) Changed();
    }
    public static string T(string source) {
      if (string.IsNullOrEmpty(source) || Language == "zh-Hans") return source;
      object entry = Json.Get(messages, source);
      string translated = Json.Text(entry, Language ?? "en");
      if (string.IsNullOrEmpty(translated)) translated = Json.Text(entry, "en");
      return string.IsNullOrEmpty(translated) ? source : translated;
    }
    public static string Format(string source, params object[] values) {
      return string.Format(CultureInfo.CurrentCulture, T(source), values);
    }
  }
}
