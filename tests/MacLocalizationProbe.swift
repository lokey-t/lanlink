import Foundation

@main struct MacLocalizationProbe {
    static func require(_ value: Bool, _ message: String) {
        if !value { fputs(message + "\n", stderr); exit(1) }
    }
    static func main() throws {
        let cases: [(String, String)] = [
            ("zh-CN", "zh-Hans"), ("zh-SG", "zh-Hans"), ("zh-TW", "zh-Hant"),
            ("zh_HK", "zh-Hant"), ("zh-MO", "zh-Hant"), ("zh-Hans-HK", "zh-Hans"),
            ("zh-Hant-CN", "zh-Hant"), ("en-GB", "en"), ("pt-BR", "pt"),
            ("de-DE", "de"), ("fr-CA", "fr"), ("es-MX", "es"), ("ja-JP", "ja"),
            ("ko-KR", "ko"), ("it-IT", "it"), ("ru-RU", "ru")
        ]
        for (input, expected) in cases { require(LanLinkLocale.resolve("system", preferredLanguages: [input]) == expected, "Incorrect system language for " + input) }
        require(LanLinkLocale.resolve("system", preferredLanguages: ["nl-NL", "ja-JP"]) == "ja", "Use the next supported preferred language")
        require(LanLinkLocale.resolve("system", preferredLanguages: ["ar-SA"]) == "en", "Unsupported system languages must fall back to English")
        require(LanLinkLocale.resolve("de", preferredLanguages: ["ja-JP"]) == "de", "An explicit preference must override system language")
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("lanlink-locale-" + UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root.appendingPathComponent("src"), withIntermediateDirectories: true)
        let fixture: [String: Any] = ["languages": [], "messages": ["设置": ["en": "Settings", "ja": "設定"], "结果 {0} / {1}": ["en": "Result {0} / {1}"], "仅英文": ["en": "English fallback"]]]
        try JSONSerialization.data(withJSONObject: fixture).write(to: root.appendingPathComponent("src/locales.json"))
        let locale = LanLinkLocale()
        locale.configure(root: root, dataDirectory: root.path, preferredLanguages: ["ja-JP"])
        require(locale.preference == "system" && locale.text("设置") == "設定", "First use must resolve the system language before rendering")
        require(locale.text("仅英文") == "English fallback", "Missing translations must use the English catalog entry")
        require(locale.text("A device named 设置") == "A device named 设置", "Never rewrite fragments of user content")
        require(locale.format("结果 {0} / {1}", ["{1}", "Device 设置"]) == "Result {1} / Device 设置", "Formatting must preserve user text and never recursively replace placeholders")
        try JSONSerialization.data(withJSONObject: ["language": "zh-Hant"]).write(to: root.appendingPathComponent("config.json"))
        locale.configure(root: root, dataDirectory: root.path, preferredLanguages: ["en-US"])
        require(locale.preference == "zh-Hant" && locale.resolved == "zh-Hant", "Persisted preference must win on relaunch")
        require(locale.setPreference("en"), "Changing language should report a change")
        require(!locale.setPreference("en"), "Unchanged language should not rebuild native menus")
        locale.setPreference("zh-Hans")
        require(locale.text("设置") == "设置", "Simplified Chinese uses source keys")
        locale.setPreference("invalid", preferredLanguages: ["de-DE"])
        require(locale.preference == "system" && locale.resolved == "de", "Invalid stored preferences should safely return to system language")
        print("MAC_LOCALIZATION_PROBE_OK")
    }
}
