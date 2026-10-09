import AppKit

@MainActor final class DockProbeDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        Task {
            do {
                try await MacDockProbe.run()
                print("MAC_DOCK_PROBE_OK")
                exit(0)
            } catch {
                fputs(error.localizedDescription + "\n", stderr)
                exit(1)
            }
        }
    }
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }
}

@main struct MacDockProbe {
    @MainActor static func main() {
        let app = NSApplication.shared
        app.setActivationPolicy(.regular)
        let lifecycle = DockProbeDelegate()
        app.delegate = lifecycle
        withExtendedLifetime(lifecycle) { app.run() }
    }

    @MainActor static func run() async throws {
        let suite = "com.lanlink.test.dock." + UUID().uuidString
        guard let preferences = UserDefaults(suiteName: suite) else { throw failure("Cannot create isolated preferences") }
        defer { preferences.removePersistentDomain(forName: suite) }
        let delegate = AppDelegate(preferences: preferences)
        let model = delegate.model
        model.appDelegate = delegate
        try require(!model.dockIconHidden, "The Dock icon must be visible by default")
        try require(!model.serviceReady && model.process == nil, "This probe must start without a device service")
        try require(delegate.applyDockIconVisibility(hidden: model.dockIconHidden), "Default Dock policy must apply")
        try require(NSApp.activationPolicy() == .regular, "The default policy must be regular")

        let window = MainAppWindow(contentRect: NSRect(x: 0, y: 0, width: 460, height: 180), styleMask: [.borderless, .closable, .miniaturizable], backing: .buffered, defer: false)
        window.title = "LanLink Dock 测试"
        window.isReleasedWhenClosed = false
        window.delegate = delegate
        window.center()
        delegate.mainWindow = window
        let field = NSTextField(string: "Unsaved settings 设置 draft")
        field.frame = NSRect(x: 20, y: 70, width: 420, height: 30)
        window.contentView?.addSubview(field)
        defer {
            model.closing = true
            model.controlReleaseTask?.cancel()
            window.delegate = nil
            window.close()
            if let item = model.statusItem { NSStatusBar.system.removeStatusItem(item) }
        }
        delegate.installMainMenu()
        delegate.showMain()
        try require(window.makeFirstResponder(field), "The settings field must accept editing")
        try await eventually("The settings window must acquire focus before changing Dock policy") { focused(window) && field.currentEditor() != nil }

        model.setDockIconHidden(true)
        await drainMainQueue()
        try require(model.dockIconHidden && preferences.bool(forKey: Model.dockIconHiddenKey), "Hiding the Dock icon must save immediately while the device service is offline")
        try require(NSApp.activationPolicy() == .accessory, "Hiding must use the accessory policy")
        try await eventually("Changing Dock policy must preserve the visible settings window and its focus") { window.isVisible && focused(window) }
        try require(field.stringValue == "Unsaved settings 设置 draft", "Changing Dock policy must preserve the settings draft")
        try require(Model(preferences: UserDefaults(suiteName: suite)!).dockIconHidden, "A new model must restore the hidden preference")
        guard let statusItem = model.statusItem, let menu = statusItem.menu,
              let openIndex = menu.items.firstIndex(where: { $0.action == #selector(AppDelegate.showMain) }) else {
            throw failure("Hiding the Dock icon must retain a menu bar entry that opens LanLink")
        }
        try require(menu.items[openIndex].target as? AppDelegate === delegate, "The menu bar action must target the live app delegate")
        try require(statusItem.button != nil && menu.items.contains(where: { $0.action == #selector(AppDelegate.quitApp) }), "The menu bar must retain its visible button and Quit action")
        try await selectAll(window: window, field: field)

        // The custom title bar closes this borderless window through the delegate.
        delegate.closeMainWindow()
        try await eventually("Closing the main window must hide it") { !window.isVisible }
        try require(!model.closing && NSApp.isRunning, "Closing the window must leave the application running")
        try require(model.statusItem === statusItem && NSApp.activationPolicy() == .accessory, "Closing must retain the menu bar entry without restoring the Dock icon")
        menu.performActionForItem(at: openIndex)
        try await eventually("The actual menu bar action must reopen the main window") { window.isVisible && focused(window) }
        try require(NSApp.activationPolicy() == .accessory && model.dockIconHidden, "Reopening from the menu bar must keep the Dock icon hidden")
        try require(field.stringValue == "Unsaved settings 设置 draft", "Reopening must preserve the existing settings view")
        try await selectAll(window: window, field: field)

        NSApp.hide(nil)
        try await eventually("The application must enter the hidden state") { NSApp.isHidden }
        menu.performActionForItem(at: openIndex)
        try await eventually("The menu bar action must also unhide the application") { !NSApp.isHidden && window.isVisible && focused(window) }
        try require(NSApp.activationPolicy() == .accessory, "Unhiding must retain the accessory policy")

        model.setDockIconHidden(false)
        await drainMainQueue()
        try require(!model.dockIconHidden && !preferences.bool(forKey: Model.dockIconHiddenKey), "Restoring the Dock icon must save immediately")
        try require(NSApp.activationPolicy() == .regular, "Restoring the Dock icon must return to regular activation")
        try await eventually("Restoring the Dock icon must retain the open settings window") { window.isVisible && focused(window) }
        try require(!Model(preferences: UserDefaults(suiteName: suite)!).dockIconHidden, "A new model must restore the visible preference")
        try require(model.statusItem === statusItem, "Policy changes must reuse the same menu bar item")
        try require(model.process == nil && !model.serviceReady, "Dock preferences and menu recovery must not require starting a service")

        let detached = Model(preferences: preferences)
        detached.setDockIconHidden(true)
        try require(!detached.dockIconHidden && !preferences.bool(forKey: Model.dockIconHiddenKey), "A preference must not be saved when its activation policy cannot be applied")
    }

    @MainActor static func selectAll(window: NSWindow, field: NSTextField) async throws {
        try require(window.makeFirstResponder(field), "The restored settings field must accept focus")
        try await eventually("The settings field must own the live editor") { focused(window) && field.currentEditor() != nil }
        guard let editor = field.currentEditor() as? NSTextView else { throw failure("Missing live field editor") }
        editor.setSelectedRange(NSRange(location: editor.string.utf16.count, length: 0))
        guard let edit = NSApp.mainMenu?.items.compactMap({ $0.submenu }).first(where: { menu in menu.items.contains { $0.action == #selector(NSText.selectAll(_:)) } }),
              let command = edit.items.first(where: { $0.action == #selector(NSText.selectAll(_:)) }) else {
            throw failure("The production editing menu must retain Select All")
        }
        edit.update()
        try require(command.isEnabled && NSApp.target(forAction: command.action!, to: command.target, from: command) != nil, "Select All must resolve through the live responder chain in accessory mode")
        let event = NSEvent.keyEvent(with: .keyDown, location: .zero, modifierFlags: .command, timestamp: ProcessInfo.processInfo.systemUptime, windowNumber: window.windowNumber, context: nil, characters: "a", charactersIgnoringModifiers: "a", isARepeat: false, keyCode: 0)!
        try require(NSApp.mainMenu?.performKeyEquivalent(with: event) == true, "Command+A must route through the native menu while the Dock icon is hidden")
        try await eventually("Command+A must select the existing draft without changing it") {
            focused(window) && field.currentEditor() === editor && editor.selectedRange() == NSRange(location: 0, length: editor.string.utf16.count)
        }
        try require(editor.string == "Unsaved settings 设置 draft", "The editing command must not modify the draft")
    }

    @MainActor static func focused(_ window: NSWindow) -> Bool {
        NSApp.isActive && NSApp.keyWindow === window && window.isKeyWindow && NSWorkspace.shared.frontmostApplication?.processIdentifier == ProcessInfo.processInfo.processIdentifier
    }
    @MainActor static func drainMainQueue() async {
        await withCheckedContinuation { continuation in DispatchQueue.main.async { continuation.resume() } }
    }
    @MainActor static func eventually(_ message: String, _ condition: () -> Bool) async throws {
        for _ in 0..<200 {
            if condition() { return }
            try await Task.sleep(nanoseconds: 25_000_000)
        }
        throw failure(message)
    }
    static func failure(_ message: String) -> NSError { NSError(domain: "dock-probe", code: 1, userInfo: [NSLocalizedDescriptionKey: message]) }
    static func require(_ value: Bool, _ message: String) throws { if !value { throw failure(message) } }
}
