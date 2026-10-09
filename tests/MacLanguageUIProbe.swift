import AppKit
import SwiftUI

@MainActor final class LanguageProbeDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) { Task { await MacLanguageUIProbe.run() } }
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }
}
@main struct MacLanguageUIProbe {
    @MainActor static func main() {
        let app = NSApplication.shared; app.setActivationPolicy(.prohibited)
        let delegate = LanguageProbeDelegate(); app.delegate = delegate
        withExtendedLifetime(delegate) { app.run() }
    }
    @MainActor static func run() async {
        fputs("LANGUAGE_PROBE_STARTED\n", stderr)
        let model = Model()
        do {
            let root = URL(fileURLWithPath: CommandLine.arguments[1]); model.root = root
            model.initializeLanguage(dataDirectory: root.path)
            try require(L("设置") == "Settings", "Persisted language must apply before the first view is created")
            model.launch(); try await eventually { model.serviceReady && model.state["id"] != nil }
            let host = NSHostingView(rootView: SettingsView(model: model, active: true))
            host.frame = NSRect(x: 0, y: 0, width: 960, height: 720)
            let window = NSWindow(contentRect: host.frame, styleMask: [.titled], backing: .buffered, defer: false)
            window.isReleasedWhenClosed = false; window.contentView = host
            defer { window.close() }
            func fields(_ view: NSView) -> [NSTextField] { (view as? NSTextField).map { [$0] } ?? view.subviews.flatMap(fields) }
            host.layoutSubtreeIfNeeded()
            try await eventually { host.layoutSubtreeIfNeeded(); return fields(host).contains { $0.stringValue == "Saved device" } }
            guard let field = fields(host).first(where: { $0.stringValue == "Saved device" }) else { throw failure("Settings device name field was not rendered") }
            field.stringValue = "My 设置 draft {1}"
            field.delegate?.controlTextDidChange?(Notification(name: NSControl.textDidChangeNotification, object: field))
            model.setLanguage("ja")
            try await eventually { !model.languageSaving && model.languagePreference == "ja" }
            try require(L("设置") == "設定", "Saved language should update visible UI labels")
            // This reload trigger must not discard a dirty name draft.
            model.state["groupId"] = "changed-group"
            try await Task.sleep(nanoseconds: 100_000_000); host.layoutSubtreeIfNeeded()
            try require(fields(host).contains { $0.stringValue == "My 设置 draft {1}" }, "Language switching must preserve the settings draft and user text")
            try require(model.state["name"] as? String == "Saved device", "Language saving must not save the name draft")
            model.setLanguage("fr") // The fixture rejects this save.
            try await eventually { !model.languageSaving }
            try require(model.languagePreference == "ja" && LanLinkLocale.shared.resolved == "ja", "A failed language save must leave the applied language unchanged")
            model.closing = true; model.poll?.cancel(); try model.input.fileHandleForWriting.close()
            try await eventually { model.process?.isRunning != true }
            print("MAC_LANGUAGE_UI_PROBE_OK"); exit(0)
        } catch { model.closing = true; model.process?.terminate(); fputs(error.localizedDescription + "\n", stderr); exit(1) }
    }
    static func failure(_ message: String) -> NSError { NSError(domain: "locale-probe", code: 1, userInfo: [NSLocalizedDescriptionKey: message]) }
    static func require(_ value: Bool, _ message: String) throws { if !value { throw failure(message) } }
    @MainActor static func eventually(_ condition: () -> Bool) async throws {
        for _ in 0..<200 { if condition() { return }; try await Task.sleep(nanoseconds: 25_000_000) }
        throw failure("Timed out waiting for language UI state")
    }
}
