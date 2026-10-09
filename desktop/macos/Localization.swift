import Foundation

// UI source strings are the catalog keys. Device names, clipboard text and paths
// never enter this lookup; service messages may use exact matches only.
final class LanLinkLocale {
    struct Language: Decodable, Identifiable { let id: String; let name: String }
    private struct Catalog: Decodable { let languages: [Language]; let messages: [String: [String: String]] }
    static let supported = ["zh-Hans", "zh-Hant", "en", "ja", "ko", "de", "fr", "es", "pt", "it", "ru"]
    static let shared = LanLinkLocale()
    private(set) var preference = "system"
    private(set) var resolved = "en"
    private(set) var languages: [Language] = [
        Language(id: "system", name: "跟随系统"), Language(id: "zh-Hans", name: "简体中文"), Language(id: "zh-Hant", name: "繁體中文"),
        Language(id: "en", name: "English"), Language(id: "ja", name: "日本語"), Language(id: "ko", name: "한국어"),
        Language(id: "de", name: "Deutsch"), Language(id: "fr", name: "Français"), Language(id: "es", name: "Español"),
        Language(id: "pt", name: "Português"), Language(id: "it", name: "Italiano"), Language(id: "ru", name: "Русский")
    ]
    private var messages: [String: [String: String]] = [:]

    static func supportedLanguage(_ identifier: String) -> String? {
        let parts = identifier.replacingOccurrences(of: "_", with: "-").lowercased().split(separator: "-").map(String.init)
        guard let base = parts.first else { return nil }
        if base == "zh" {
            if parts.contains("hant") { return "zh-Hant" }
            if parts.contains("hans") { return "zh-Hans" }
            return parts.contains(where: { ["tw", "hk", "mo"].contains($0) }) ? "zh-Hant" : "zh-Hans"
        }
        return supported.contains(base) ? base : nil
    }
    static func resolve(_ preference: String, preferredLanguages: [String]) -> String {
        if preference != "system", let explicit = supportedLanguage(preference) { return explicit }
        return preferredLanguages.compactMap(supportedLanguage).first ?? "en"
    }
    func configure(root: URL, dataDirectory: String, preferredLanguages: [String] = Locale.preferredLanguages) {
        if let data = try? Data(contentsOf: root.appendingPathComponent("src/locales.json")),
           let catalog = try? JSONDecoder().decode(Catalog.self, from: data) {
            messages = catalog.messages
            // Preserve the built-in options if a packaged catalog is incomplete.
            if Set(catalog.languages.map(\.id)) == Set(["system"] + Self.supported) { languages = catalog.languages }
        }
        let config = URL(fileURLWithPath: dataDirectory).appendingPathComponent("config.json")
        let values = (try? Data(contentsOf: config)).flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }
        setPreference(values?["language"] as? String ?? "system", preferredLanguages: preferredLanguages)
    }
    @discardableResult func setPreference(_ value: String, preferredLanguages: [String] = Locale.preferredLanguages) -> Bool {
        let nextPreference = value == "system" || Self.supported.contains(value) ? value : "system"
        let nextLanguage = Self.resolve(nextPreference, preferredLanguages: preferredLanguages)
        let changed = preference != nextPreference || resolved != nextLanguage
        preference = nextPreference; resolved = nextLanguage
        return changed
    }
    func text(_ source: String) -> String {
        if resolved == "zh-Hans" { return source }
        return messages[source]?[resolved] ?? messages[source]?["en"] ?? source
    }
    func format(_ source: String, _ values: [String]) -> String {
        // A single pass prevents user supplied text such as "{1}" from being
        // interpreted as another placeholder during replacement.
        let template = text(source)
        guard let expression = try? NSRegularExpression(pattern: #"\{([0-9]+)\}"#) else { return template }
        let mutable = NSMutableString(string: template)
        for match in expression.matches(in: template, range: NSRange(template.startIndex..., in: template)).reversed() {
            guard let indexRange = Range(match.range(at: 1), in: template), let index = Int(template[indexRange]), values.indices.contains(index) else { continue }
            mutable.replaceCharacters(in: match.range, with: values[index])
        }
        return mutable as String
    }
}
func L(_ source: String) -> String { LanLinkLocale.shared.text(source) }
func LF(_ source: String, _ values: String...) -> String { LanLinkLocale.shared.format(source, values) }
