import Foundation
import AppKit

@main struct MacUXProbe {
    @MainActor static func main() {
        let application = NSApplication.shared
        application.setActivationPolicy(CommandLine.arguments.contains("--warnings-only") ? .prohibited : .regular)
        Task { @MainActor in await run() }
        application.run()
    }
    @MainActor static func run() async {
        let model = Model()
        do {
            try savedOperationWarnings()
            if CommandLine.arguments.contains("--warnings-only") { print("MAC_WARNING_PROBE_OK"); exit(0) }
            if CommandLine.arguments.contains("--editing-only") {
                for _ in 0..<10 { try await editCommands() }
                print("MAC_EDITING_PROBE_OK"); exit(0)
            }
            try await editCommands()
            try require(PairingCode.normalized(" 0012\u{00a0}3456\n") == "00123456", "grouped pairing code must preserve its leading zeroes")
            try require(PairingCode.valid(PairingCode.normalized("0012 3456\r\n")), "whitespace-normalized pairing code should be valid")
            try require(!PairingCode.valid(PairingCode.normalized("0012ab3456")), "letters must not be silently converted into a valid pairing code")
            try require(!PairingCode.valid("001234567"), "excess digits must not be silently truncated")
            try require(Model.readableMessage("PERMISSION_REQUIRED: 请完成授权") == "请完成授权", "internal error codes must not be displayed")
            model.root = URL(fileURLWithPath: CommandLine.arguments[1]); model.launch()
            try await eventually { model.serviceReady }
            await model.refresh()
            let firstPID = model.process!.processIdentifier
            let waiting = Task { do { _ = try await model.call("hold"); return false } catch { return true } }
            try await eventually { !model.pending.isEmpty }
            await model.restartService()
            try require(await waiting.value, "restart must reject requests to the previous process")
            try await eventually { model.serviceReady }
            try require(model.process!.processIdentifier != firstPID, "restart should launch a new service after the old host exits")
            try require(model.serviceError.isEmpty && !model.serviceRestarting, "recovery must clear the global service failure")
            let pong = try await model.call("ping")
            try require(pong["pid"] as? Int == Int(model.process!.processIdentifier), "new calls must use the new process pipes")
            try await eventually { model.pending.isEmpty }
            model.devices = [Device(id: "phone", name: "Phone", platform: "android", online: true)]
            model.process?.terminate()
            try await eventually { !model.serviceReady && !model.serviceError.isEmpty }
            try require(model.devices.first { $0.id == "phone" }?.online == false, "service failure must mark peers offline")
            await model.restartService(); try await eventually { model.serviceReady }
            model.pendingFeatureID = "phone"; model.pendingFeature = {}
            let access = AccessController(model: model, request: ["id": "phone", "name": "Phone", "requestId": "request-1", "keys": ["input"]])
            model.accessController = access; access.task?.cancel(); access.task = nil
            await access.check()
            try require(access.message.contains("离线") && !access.ended, "the authorization window must show the live offline reason")
            _ = try await model.call("access-phase", ["ended": true]); await access.check()
            try require(access.ended && access.message.contains("超时"), "an expired offline request must end with a visible reason")
            try require(model.pendingFeatureID == nil, "expired requests must not block another device")
            access.end()
            _ = try await model.call("access-phase", ["ready": true])
            var resumed = false; model.pendingFeatureID = "phone"; model.pendingFeature = { resumed = true }
            let granted = AccessController(model: model, request: ["id": "phone", "name": "Phone", "requestId": "request-2", "keys": ["input"]])
            model.accessController = granted; granted.task?.cancel(); granted.task = nil
            let completing = Task { await granted.check() }
            try await eventually { granted.completing }
            await model.refresh()
            try require(!granted.closed && !granted.ended, "polling must not cancel a request while completion is being acknowledged")
            await completing.value
            try require(resumed, "successful authorization must resume the original operation after a delayed completion reply")
            model.closing = true; model.poll?.cancel(); try model.input.fileHandleForWriting.close()
            try await eventually { model.process?.isRunning != true }
            print("MAC_UX_PROBE_OK")
            exit(0)
        } catch {
            fputs(error.localizedDescription + "\n", stderr)
            model.closing = true; model.process?.terminate(); exit(1)
        }
    }
    @MainActor static func savedOperationWarnings() throws {
        let model = Model()
        let message = "授权已保存，部分运行状态未能恢复，请重启服务"
        let displayed = model.presentOperationResult(["message": message, "warning": "NATIVE_ERROR: 输入释放失败"], success: "普通成功文案")
        try require(displayed.contains(message) && displayed.contains("输入释放失败"), "a saved mutation must display the backend completion message and cleanup warning")
        try require(!displayed.contains("NATIVE_ERROR:"), "cleanup warnings must not expose internal error codes")
        try require(model.serviceError.isEmpty && model.needsServiceRecovery, "a saved mutation warning must offer recovery without claiming the mutation failed")
        model.presentOperationResult(["message": "其他操作已完成"], success: "旧文案")
        try require(model.notice == "其他操作已完成", "backend success messages must take precedence over fallback UI copy")
        try require(model.globalMessage == displayed, "unrelated successful actions must not hide an unresolved cleanup warning")
        model.failed("服务已退出")
        try require(model.globalMessage == "服务已退出", "a service failure must take precedence over the saved-operation warning")
        model.consume(Data("{\"ready\":true}\n".utf8)); model.poll?.cancel()
        try require(model.serviceWarning.isEmpty && !model.needsServiceRecovery, "a successful service restart must clear the cleanup warning")
        model.showRemoteNotice("H.264 串流 · 30 fps")
        model.notice = "其他操作已完成"
        model.clearRemoteNotice()
        try require(model.remoteNotice.isEmpty && model.notice == "其他操作已完成", "ending remote status must preserve a newer unrelated notice")
        model.showRemoteNotice("正在控制")
        model.serviceWarning = "服务仍需要恢复"
        model.releaseAll()
        try require(model.remoteNotice.isEmpty && model.notice.isEmpty, "returning to local control must dismiss remote status")
        try require(model.globalMessage == "服务仍需要恢复", "remote cleanup must preserve unresolved service warnings")
        model.serviceError = "服务已退出"
        model.clearRemoteNotice()
        try require(model.globalMessage == "服务已退出", "remote cleanup must preserve service failures")
    }
    @MainActor static func editCommands() async throws {
        let pasteboard = NSPasteboard.general
        let saved = (pasteboard.pasteboardItems ?? []).map { item in item.types.compactMap { type in item.data(forType: type).map { (type, $0) } } }
        defer {
            pasteboard.clearContents()
            let items = saved.map { values in let item = NSPasteboardItem(); for (type, data) in values { item.setData(data, forType: type) }; return item }
            if !items.isEmpty { pasteboard.writeObjects(items) }
        }
        let delegate = AppDelegate(); delegate.installMainMenu()
        let window = MainAppWindow(contentRect: NSRect(x: 0, y: 0, width: 460, height: 180), styleMask: [.borderless, .closable], backing: .buffered, defer: false)
        window.title = "LanLink 编辑响应链测试"; window.center(); window.isReleasedWhenClosed = false
        let original = "LanLink 测试🙂 " + UUID().uuidString
        let field = NSTextField(string: original)
        field.frame = NSRect(x: 20, y: 70, width: 420, height: 30)
        window.contentView?.addSubview(field)
        defer { window.close() }
        func snapshot() -> String {
            let live = field.currentEditor() as? NSTextView
            return "active=\(NSApp.isActive), frontmost=\(NSWorkspace.shared.frontmostApplication?.processIdentifier == ProcessInfo.processInfo.processIdentifier), key=\(window.isKeyWindow), responderIsEditor=\(live != nil && window.firstResponder === live), editorLength=\(live?.string.utf16.count ?? -1), selection=\(live?.selectedRange() ?? NSRange(location: NSNotFound, length: 0))"
        }
        func check(_ condition: Bool, _ message: String) throws {
            if !condition { throw NSError(domain: "probe", code: 3, userInfo: [NSLocalizedDescriptionKey: message + " [" + snapshot() + "]"]) }
        }
        func focused() -> Bool {
            NSApp.isActive && NSWorkspace.shared.frontmostApplication?.processIdentifier == ProcessInfo.processInfo.processIdentifier && NSApp.keyWindow === window && window.isKeyWindow && field.currentEditor() != nil && window.firstResponder === field.currentEditor()
        }
        func liveEditor() throws -> NSTextView {
            guard let editor = field.currentEditor() as? NSTextView else { throw NSError(domain: "probe", code: 3, userInfo: [NSLocalizedDescriptionKey: "The field must own the current AppKit editor [" + snapshot() + "]"]) }
            return editor
        }
        func acquireFocus() async throws {
            NSApp.activate(ignoringOtherApps: true); window.makeKeyAndOrderFront(nil)
            try check(window.makeFirstResponder(field), "the test field must accept first responder")
            for _ in 0..<500 {
                if focused() { let editor = try liveEditor(); editor.allowsUndo = true; return }
                try await Task.sleep(nanoseconds: 10_000_000)
            }
            try check(false, "the fixture must become the real foreground application and focus its field before sending commands")
        }
        // Use the physical virtual key codes too. Supplying keyCode 0 for C/X/V/Z
        // describes contradictory events and can be remapped by an input source.
        let commands: [String: (Selector, UInt16)] = [
            "a": (#selector(NSText.selectAll(_:)), 0), "c": (#selector(NSText.copy(_:)), 8),
            "x": (#selector(NSText.cut(_:)), 7), "v": (#selector(NSText.paste(_:)), 9), "z": (Selector(("undo:")), 6)
        ]
        func key(_ character: String, expected: String, result: (NSTextView) -> Bool) async throws {
            try check(focused(), "Command+" + character + " requires the real foreground field editor")
            let before = try liveEditor(), command = commands[character]!
            guard let edit = NSApp.mainMenu?.items.first(where: { $0.title == "编辑" })?.submenu,
                  let item = edit.items.first(where: { $0.action == command.0 && $0.keyEquivalent == character }) else {
                throw NSError(domain: "probe", code: 3, userInfo: [NSLocalizedDescriptionKey: "The production edit menu must contain Command+" + character])
            }
            edit.update()
            try check(item.isEnabled, "Command+" + character + " must be enabled for the active editor")
            try check(NSApp.target(forAction: command.0, to: item.target, from: item) != nil, "Command+" + character + " must have a live responder-chain target")
            let event = NSEvent.keyEvent(with: .keyDown, location: .zero, modifierFlags: .command, timestamp: ProcessInfo.processInfo.systemUptime, windowNumber: window.windowNumber, context: nil, characters: character, charactersIgnoringModifiers: character, isARepeat: false, keyCode: command.1)!
            try check(NSApp.mainMenu?.performKeyEquivalent(with: event) == true, "Command+" + character + " must route through the native edit menu")
            // Menu dispatch can complete on a later AppKit event-loop turn. Wait
            // for the exact behavioral outcome, never repeat the command or edit
            // the text/selection to make an assertion pass.
            for _ in 0..<500 {
                try check(focused(), "Command+" + character + " must retain foreground editor focus")
                let current = try liveEditor()
                try check(current === before, "Command+" + character + " must operate on the focused editor, not a stale cached editor")
                if result(current) { return }
                try await Task.sleep(nanoseconds: 10_000_000)
            }
            try check(false, expected)
        }
        try await acquireFocus()
        try liveEditor().setSelectedRange(NSRange(location: original.utf16.count, length: 0))
        try await key("a", expected: "Command+A must select all field content") { $0.selectedRange() == NSRange(location: 0, length: original.utf16.count) }
        try await key("c", expected: "Command+C must copy the unique selection without changing it") { pasteboard.string(forType: .string) == original && $0.selectedRange() == NSRange(location: 0, length: original.utf16.count) }
        try await key("x", expected: "Command+X must remove the selection and put it on the clipboard") { $0.string.isEmpty && pasteboard.string(forType: .string) == original }
        try await key("v", expected: "Command+V must restore copied text") { $0.string == original }
        try await key("a", expected: "Command+A must select the pasted content") { $0.selectedRange() == NSRange(location: 0, length: original.utf16.count) }
        let editor = try liveEditor(); editor.insertText("Replacement", replacementRange: editor.selectedRange()); editor.breakUndoCoalescing()
        try check(editor.string == "Replacement", "the undo fixture must first perform the replacement edit")
        try await key("z", expected: "Command+Z must undo field edits") { $0.string == original }
        try check(window.makeFirstResponder(nil), "the field must finish editing before window reactivation")
        window.orderOut(nil)
        try await acquireFocus()
        let reopened = try liveEditor()
        try check(reopened.string == original, "reactivating the window must retain the edited field value")
        reopened.setSelectedRange(NSRange(location: original.utf16.count, length: 0))
        try await key("a", expected: "editing commands must survive window reactivation") { $0.selectedRange() == NSRange(location: 0, length: original.utf16.count) }
    }
    @MainActor static func eventually(_ condition: () -> Bool) async throws {
        for _ in 0..<500 { if condition() { return }; try await Task.sleep(nanoseconds: 10_000_000) }
        throw NSError(domain: "probe", code: 2, userInfo: [NSLocalizedDescriptionKey: "Timed out waiting for native service state"])
    }
    static func require(_ condition: Bool, _ message: String) throws {
        if !condition { throw NSError(domain: "probe", code: 4, userInfo: [NSLocalizedDescriptionKey: message]) }
    }
}
