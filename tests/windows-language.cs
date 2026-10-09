using System;
using LanLink.Desktop;

public static class WindowsLanguageTest {
  static int cases;
  static void Equal(string expected, string actual, string context) {
    cases++;
    if(expected!=actual)throw new Exception(context+": expected "+expected+", got "+actual);
  }
  public static void Main() {
    foreach(string language in LanguageRules.Supported) {
      Equal(language, LanguageRules.Resolve(language,"zh-CN"), "Explicit selection survives a different system language");
      Equal(language, LanguageRules.Preference(language.ToUpperInvariant()), "Preference is canonicalized");
    }
    Equal("zh-Hans",LanguageRules.Resolve("system","zh-CN"),"Mainland Chinese");
    Equal("zh-Hans",LanguageRules.Resolve("system","zh-SG"),"Singapore Chinese");
    Equal("zh-Hant",LanguageRules.Resolve("system","zh-TW"),"Taiwan Chinese");
    Equal("zh-Hant",LanguageRules.Resolve("system","zh-HK"),"Hong Kong Chinese");
    Equal("zh-Hant",LanguageRules.Resolve("system","zh-MO"),"Macao Chinese");
    Equal("zh-Hant",LanguageRules.Resolve("system","zh-Hant-CN"),"Explicit traditional script overrides region");
    Equal("zh-Hans",LanguageRules.Resolve("system","zh-Hans-HK"),"Explicit simplified script overrides region");
    Equal("zh-Hant",LanguageRules.Resolve("system","ZH_hant_TW"),"Underscores and case");
    Equal("en",LanguageRules.Resolve("system","en-GB"),"British English");
    Equal("pt",LanguageRules.Resolve("system","pt-BR"),"Brazilian Portuguese");
    Equal("es",LanguageRules.Resolve("system","es-MX"),"Mexican Spanish");
    Equal("fr",LanguageRules.Resolve("system","fr-CA"),"Canadian French");
    Equal("ja",LanguageRules.Resolve(null,"ja-JP"),"First launch uses system language");
    Equal("ko",LanguageRules.Resolve("unknown","ko-KR"),"Invalid saved preference safely follows system");
    Equal("en",LanguageRules.Resolve("system","ar-SA"),"Unsupported system language falls back to English");
    Equal("en",LanguageRules.Resolve("system",null),"Missing system language falls back to English");
    Equal("en",LanguageRules.Resolve("system",""),"Empty system language falls back to English");
    Equal("system",LanguageRules.Preference(null),"Missing preference follows system");
    Equal("system",LanguageRules.Preference("invalid"),"Unknown preference follows system");
    Console.WriteLine("Windows language resolution passed ("+cases+" cases).");
  }
}
