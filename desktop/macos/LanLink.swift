import SwiftUI
import AppKit
import UniformTypeIdentifiers
import ServiceManagement

enum Palette {
    static func adaptive(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(nsColor: NSColor(name: nil, dynamicProvider: { appearance in
            let value = appearance.bestMatch(from: [.aqua, .darkAqua]) == .darkAqua ? dark : light
            return NSColor(srgbRed: CGFloat((value >> 16) & 255) / 255,
                           green: CGFloat((value >> 8) & 255) / 255,
                           blue: CGFloat(value & 255) / 255, alpha: 1)
        }))
    }
    static let ink = adaptive(0x202C43, 0xE4EAF4)
    static let muted = adaptive(0x65748B, 0xA8B5CA)
    static let accent = adaptive(0x476389, 0xA7C2F5)
    static let surface = adaptive(0xF9FBFE, 0x171E2B)
    static let canvas = adaptive(0xF1F5FC, 0x111722)
    static let card = adaptive(0xFFFFFF, 0x222C3C)
    static let line = adaptive(0xDDE5F2, 0x3B4960)
}

struct FlatButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(.system(size: 13, weight: .medium)).padding(.horizontal, 15).padding(.vertical, 10)
            .foregroundStyle(Palette.accent)
            .background(configuration.isPressed ? Color.blue.opacity(0.18) : Palette.card, in: RoundedRectangle(cornerRadius: 10))
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(Palette.line, lineWidth: 1)).opacity(enabled ? 1 : 0.4)
    }
}
// The same 24-unit clipboard-and-arrow drawing as the SVG assets.
struct ClipboardSyncGlyph: Shape {
    var upload: Bool
    func path(in rect: CGRect) -> Path {
        var p=Path()
        p.move(to:CGPoint(x:8,y:5));p.addLine(to:CGPoint(x:5,y:5));p.addQuadCurve(to:CGPoint(x:3,y:7),control:CGPoint(x:3,y:5));p.addLine(to:CGPoint(x:3,y:20));p.addQuadCurve(to:CGPoint(x:5,y:22),control:CGPoint(x:3,y:22));p.addLine(to:CGPoint(x:14,y:22));p.addQuadCurve(to:CGPoint(x:16,y:20),control:CGPoint(x:16,y:22));p.addLine(to:CGPoint(x:16,y:18))
        p.move(to:CGPoint(x:7,y:2));p.addLine(to:CGPoint(x:12,y:2));p.addQuadCurve(to:CGPoint(x:13,y:3),control:CGPoint(x:13,y:2));p.addLine(to:CGPoint(x:13,y:6));p.addLine(to:CGPoint(x:6,y:6));p.addLine(to:CGPoint(x:6,y:3));p.addQuadCurve(to:CGPoint(x:7,y:2),control:CGPoint(x:6,y:2))
        p.move(to:CGPoint(x:6,y:11));p.addLine(to:CGPoint(x:11,y:11));p.move(to:CGPoint(x:6,y:15));p.addLine(to:CGPoint(x:10,y:15))
        p.move(to:CGPoint(x:19,y:4));p.addLine(to:CGPoint(x:19,y:15));let tip:CGFloat=upload ? 4:15,wing:CGFloat=upload ? 8:11
        p.move(to:CGPoint(x:15,y:wing));p.addLine(to:CGPoint(x:19,y:tip));p.addLine(to:CGPoint(x:23,y:wing))
        let s=min(rect.width,rect.height)/24
        return p.applying(CGAffineTransform(scaleX:s,y:s).concatenating(CGAffineTransform(translationX:rect.midX-12*s,y:rect.midY-12*s)))
    }
}
struct ClipboardQuickButton: View {
    var upload:Bool; var action:()->Void
    var body:some View { Button(action:action){ClipboardSyncGlyph(upload:upload).stroke(style:StrokeStyle(lineWidth:1.5,lineCap:.round,lineJoin:.round)).frame(width:22,height:22).foregroundStyle(Palette.accent).frame(width:42,height:42).background(Palette.card,in:Circle()).overlay(Circle().stroke(Palette.line,lineWidth:1))}.buttonStyle(.plain).help(upload ? "上传文字剪贴板":"下载文字剪贴板").accessibilityLabel(upload ? "上传文字剪贴板":"下载文字剪贴板") }
}
struct Device: Identifiable {
    var id: String; var name: String; var platform: String; var online = false; var local = false; var transport = ""; var access:[String:Any] = [:]
    var symbol: String { platform == "android" ? "iphone" : "desktopcomputer" }
}

// No WebView: AppKit windows, SwiftUI controls and inherited process pipes.
@MainActor final class Model: ObservableObject {
    weak var appDelegate: AppDelegate?
    private var appliedTheme = ""
    func applyTheme() {
        let theme = state["theme"] as? String ?? "system"
        guard appliedTheme != theme else { return }
        appliedTheme = theme
        NSApp.appearance = theme == "dark" ? NSAppearance(named: .darkAqua) : theme == "light" ? NSAppearance(named: .aqua) : nil
    }
    @Published var state: [String: Any] = [:]
    @Published var devices: [Device] = []
    @Published var notice = "正在连接设备服务…"
    @Published var selected: String?
    @Published var layoutDragID: String?
    @Published var layoutDragPoint: CGPoint = .zero
    @Published var layoutDragTranslation: CGSize = .zero
    var layoutCanvasFrame: CGRect = .zero
    var layoutLocalCenter: CGPoint = .zero
    func finishLayoutDrag(_ point: CGPoint) {
        defer { layoutDragID = nil }
        guard let id = layoutDragID, layoutCanvasFrame.contains(point) else { return }
        let x = point.x - layoutLocalCenter.x, y = point.y - layoutLocalCenter.y
        place(id, abs(x) > abs(y) ? (x < 0 ? "left" : "right") : (y < 0 ? "top" : "bottom"))
    }
    @Published var screen: NSImage?
    @Published var remoteID: String?
    @Published var remoteSidebarCollapsed=false
    @Published var remoteSidebarExtent=0.0;var remoteSidebarAnimation:Task<Void,Never>?
    @Published var remoteCodec="--"
    @Published var remoteFPS=0.0
    var remoteFrameTimes:[Double]=[];var remoteMetrics:Task<Void,Never>?
    func remotePresented(){remoteFrameTimes.append(ProcessInfo.processInfo.systemUptime)}
    @Published var remoteBitrate=0.0
    @Published var firstDevicePermissionGuide = false
    var remoteGeometryRestored=false
    @Published var controlling = false
    @Published var remotePhone = false
    @Published var adbDevice: Device?
    @Published var permissionsVisible = false
    @Published var sidebarPage = "devices"
    @Published var stagedFiles:[[String:Any]]=[];var stageHoverSessions:[String:String]=[:];var fileDragEdges:FileDragEdges?
    @Published var controlConflict = false
    var fallbackAccepted = false; var showingInputModeDialog = false; var handlingFallback = false
    var freshControl = true; var remoteOpenedAt = 0.0
    @Published var autoControl = false
    @Published var clipboardToast="";var clipboardToastTask:Task<Void,Never>?
    @Published var startupEnabled = SMAppService.mainApp.status == .enabled
    var pendingFeatureID:String?;var pendingFeature:(()->Void)?
    var accessController:AccessController?;var lastAccess="";var disclosure:ConsentDisclosure?;var adbGuide: AdbGuideController?; var lastAdbGuide = ""; var consentAlert: NSAlert?; var consentRequest = ""; var permissionWindow: NSWindow?
    var controlVersion = 0
    var root = Bundle.main.resourceURL!
    var process: Process?; var input = Pipe(); var output = Pipe(); var errors = Pipe()
    var pending: [String: CheckedContinuation<[String: Any], Error>] = [:]
    var sequence = 0; var closing = false; var poll: Task<Void, Never>?
    var startupFailure: String?
    var remoteTask: Task<Void, Never>?; var inputTail: Task<Void, Never>?
    var remoteControlTask: Task<Void, Never>?; var controlReleaseTask: Task<Void, Never>?
    var remoteTeardown: Task<Void, Never>?; var remoteOpenVersion = 0
    weak var remoteSurface: InputSurface?; var recoveringInput=false; var remoteRatio = 0.5; var remotePrepared = false; var preparingControl = false; var acquiredAt = Date.distantPast; var resumeRemote = false
    var stream = Data(); var statusItem: NSStatusItem?; var remoteWindow: NSWindow?
    var edges: [String: String] { state["edges"] as? [String: String] ?? [:] }
    var dwell: Int { state["dwell"] as? Int ?? 100 }
    var lastEdgeGlow=""
    var isMaster: Bool { state["master"] as? Bool ?? false }
    var permissions: [String: Bool] { state["permissions"] as? [String: Bool] ?? [:] }
    func collaborationSystemReady(_ feature: String) -> Bool {
        guard feature == "input" || feature == "screen" else { return true }
        let capabilities = state["capabilities"] as? [String: Any] ?? [:]
        return capabilities["simulated"] as? Bool != true && capabilities[feature] as? Bool == true
    }
    func collaborationBinding(_ feature: String) -> Binding<Bool> {
        Binding(get: { self.collaborationSystemReady(feature) && self.permissions[feature] == true }, set: { enabled in
            if enabled && !self.collaborationSystemReady(feature) {
                self.firstDevicePermissionGuide = true
                return
            }
            self.perform("settings", ["permissions": [feature: enabled]])
        })
    }
    func checkPermissionPageEntry(hasOtherDevices: Bool) {
        if hasOtherDevices && !collaborationSystemReady("input") { firstDevicePermissionGuide = true }
    }
    func showFirstDevicePermissionGuideIfNeeded(wasFirstDevice: Bool) {
        let capabilities = state["capabilities"] as? [String: Any] ?? [:]
        guard wasFirstDevice, capabilities["simulated"] as? Bool != true,
              capabilities["input"] as? Bool != true || capabilities["host"] as? Bool != true || capabilities["screen"] as? Bool != true else { return }
        let id = state["id"] as? String ?? "local"
        let key = "LanLink.systemPermissionGuide.v2.\(id)"
        guard !UserDefaults.standard.bool(forKey: key) else { return }
        UserDefaults.standard.set(true, forKey: key)
        firstDevicePermissionGuide = true
    }
    func launch() {
        let p = Process(); p.executableURL = root.appendingPathComponent("runtime/node")
        let args = CommandLine.arguments
        let data = appDelegate?.instance?.directory ?? InstanceLease.defaultDirectory
        p.arguments = [root.appendingPathComponent("src/native-host.js").path, "--data", data]
        if args.contains("--simulate") { p.arguments!.append("--simulate") }
        if args.contains("--no-discovery") { p.arguments!.append("--no-discovery") }
        if let index = args.firstIndex(of: "--port"), index + 1 < args.count,
           let port = Int(args[index + 1]), (0...65535).contains(port) {
            p.arguments!.append(contentsOf: ["--port", String(port)])
        }
        p.standardInput = input; p.standardOutput = output; p.standardError = errors
        output.fileHandleForReading.readabilityHandler = { [weak self] handle in
            let data = handle.availableData
            Task { @MainActor in self?.consume(data) }
        }
        errors.fileHandleForReading.readabilityHandler = { handle in _ = handle.availableData }
        p.terminationHandler = { [weak self] _ in Task { @MainActor in
            guard let self else { return }
            self.failed(self.startupFailure ?? "设备服务已退出，请重新启动 LanLink")
        } }
        do { try p.run(); process = p } catch { notice = error.localizedDescription }
        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        let brandIcon = Bundle.main.url(forResource: "MenuBarTemplate", withExtension: "png").flatMap { NSImage(contentsOf: $0) }
        brandIcon?.size = NSSize(width: 18, height: 18); brandIcon?.isTemplate = true
        item.button?.image = brandIcon ?? NSImage(systemSymbolName: "link", accessibilityDescription: "LanLink")
        item.button?.setAccessibilityLabel("LanLink")
        let menu = NSMenu(); menu.addItem(withTitle: "打开 LanLink", action: #selector(AppDelegate.showMain), keyEquivalent: "")
        menu.addItem(withTitle: "返回本机键鼠", action: #selector(AppDelegate.releaseControl), keyEquivalent: "")
        menu.addItem(.separator()); menu.addItem(withTitle: "退出 LanLink", action: #selector(AppDelegate.quitApp), keyEquivalent: "q")
        for entry in menu.items where !entry.isSeparatorItem { entry.target = appDelegate }
        item.menu = menu; statusItem = item
    }
    func consume(_ bytes: Data) {
        if bytes.isEmpty { return }; stream.append(bytes)
        while let index = stream.firstIndex(of: 10) {
            let line = stream.prefix(upTo: index); stream.removeSubrange(...index)
            guard let msg = try? JSONSerialization.jsonObject(with: line) as? [String: Any] else { continue }
            if msg["ready"] as? Bool == true {
                notice = "设备服务已连接"
                poll = Task { while !Task.isCancelled { await refresh(); try? await Task.sleep(nanoseconds: 1_500_000_000) } }
            }
            if msg["event"] as? String == "edge-feedback",let feedback=msg["feedback"] as? [String:Any]{EdgeGlowPanel.feedback(feedback);continue}
            if let error = msg["fatal"] as? String { startupFailure = error; failed(error) }
            guard let id = msg["id"] as? String, let continuation = pending.removeValue(forKey: id) else { continue }
            if let error = msg["error"] as? String { continuation.resume(throwing: failure(error)) }
            else { continuation.resume(returning: msg["result"] as? [String: Any] ?? [:]) }
        }
    }
    func failure(_ text: String) -> NSError { NSError(domain: "LanLink", code: 1, userInfo: [NSLocalizedDescriptionKey: text]) }
    func failed(_ message: String) { EdgeGlowPanel.feedback(["phase":"clear"]); for promise in pending.values { promise.resume(throwing: failure(message)) }; pending.removeAll(); poll?.cancel(); if !closing { notice = message; devices = devices.map { var d = $0; if !d.local { d.online = false }; return d } } }
    func call(_ method: String, _ args: [String: Any] = [:]) async throws -> [String: Any] {
        guard let p = process, p.isRunning else { throw failure("设备服务不可用") }
        sequence += 1; let id = String(sequence)
        var line = try JSONSerialization.data(withJSONObject: ["id": id, "method": method, "args": args]); line.append(10)
        return try await withCheckedThrowingContinuation { continuation in
            pending[id] = continuation
            do { try input.fileHandleForWriting.write(contentsOf: line) } catch { pending.removeValue(forKey: id)?.resume(throwing: error) }
            Task { try? await Task.sleep(nanoseconds: 130_000_000_000); if let promise = pending.removeValue(forKey: id) { promise.resume(throwing: failure("操作超时")) } }
        }
    }
    var clipboardSettings:[String:Any]{state["clipboardSettings"] as? [String:Any] ?? [:]}
    func clipboardAction(_ method:String,_ args:[String:Any]=[:]){Task{do{let result=try await call(method,args);clipboardToast=result["message"] as? String ?? "剪贴板已同步";await refresh()}catch{clipboardToast=error.localizedDescription};clipboardToastTask?.cancel();clipboardToastTask=Task{@MainActor in do{try await Task.sleep(nanoseconds:2_400_000_000);clipboardToast=""}catch{}}}}
    func perform(_ method: String, _ args: [String: Any] = [:], success: String = "") { Task { do { _ = try await call(method, args); notice = success; await refresh() } catch { notice = error.localizedDescription } } }
    func refresh() async {
        do {
            state = try await call("state"); applyTheme(); var all = [Device(id: state["id"] as? String ?? "local", name: state["name"] as? String ?? "本机", platform: "darwin", online: true, local: true)]
            showFirstDevicePermissionGuideIfNeeded(wasFirstDevice: (state["groupMemberCount"] as? Int ?? 1) > 1 || !(state["knownDevices"] as? [String: Any] ?? [:]).isEmpty)
            refreshRequests(); startupEnabled = SMAppService.mainApp.status == .enabled
            for (id, info) in state["knownDevices"] as? [String: [String: Any]] ?? [:] { all.append(Device(id: id, name: info["name"] as? String ?? "已配对设备", platform: info["platform"] as? String ?? "")) }
            for info in state["peers"] as? [[String: Any]] ?? [] {
                guard let id = info["id"] as? String else { continue }; all.removeAll { $0.id == id }
                all.append(Device(id: id, name: info["name"] as? String ?? "已配对设备", platform: info["platform"] as? String ?? "", online: true, transport: info["transport"] as? String ?? "", access:info["access"] as? [String:Any] ?? [:]))
            }
            for id in edges.values where !id.isEmpty && !all.contains(where: { $0.id == id }) { all.append(Device(id: id, name: "已设置设备", platform: "")) }
            if sidebarPage=="staging" { await refreshStaging() };if fileDragEdges==nil { fileDragEdges=FileDragEdges(self) };fileDragEdges?.update()
            devices = all.sorted { if $0.local != $1.local { return $0.local }; return $0.name < $1.name }
            if let at = state["shutdownAt"] as? Double { notice = "收到关机请求，\(max(0, Int((at / 1000 - Date().timeIntervalSince1970).rounded(.up)))) 秒后执行；点击本机设备可取消" }
            if let remoteID, state["active"] as? String != remoteID { controlling = false }
        } catch { notice = error.localizedDescription }
    }
    func place(_ id: String, _ edge: String) {
        guard devices.contains(where: { $0.id == id && !$0.local }) else { return }
        var next = edges; let old = next.first { $0.value == id }?.key; let occupied = next[edge] ?? ""
        for key in Array(next.keys) where next[key] == id { next[key] = "" }
        if let old, old != edge { next[old] = occupied }; next[edge] = id
        perform("settings", ["edges": next, "master": true], success: "布局已保存")
    }
    func removeLayout(_ id: String) { var next = edges; for key in Array(next.keys) where next[key] == id { next[key] = "" }; perform("settings", ["edges": next], success: "布局已保存") }
    func sendFiles(_ d: Device) {
        let dialog = NSOpenPanel(); dialog.canChooseDirectories = true; dialog.allowsMultipleSelection = true
        dialog.message = "文件将传输到接收设备的暂存区。"
        if dialog.runModal() == .OK { perform("stage-share", ["id": d.id, "files": dialog.urls.map(\.path)], success: "文件已发送到对方暂存区") }
    }
    func shutdown(_ d: Device) { let alert = NSAlert(); alert.messageText = "请求 \(d.name) 在 30 秒后关机？"; alert.informativeText = "请先保存目标设备中的工作。"; alert.addButton(withTitle: "关机"); alert.addButton(withTitle: "取消"); if alert.runModal() == .alertFirstButtonReturn { perform("shutdown", ["id": d.id], success: "已发送关机请求，可在设备菜单中取消") } }
    func openRemote(_ d: Device) {
        if let w = remoteWindow, remoteID == d.id { w.deminiaturize(nil); w.makeKeyAndOrderFront(nil); return }
        remoteOpenVersion += 1; let version = remoteOpenVersion
        let teardown = tearDownRemote()
        Task { @MainActor in
            await teardown?.value
            guard version == remoteOpenVersion, !closing else { return }
            createRemoteWindow(d)
        }
    }
    private func createRemoteWindow(_ d: Device) {
        remoteID = d.id; remotePrepared = false; controlling = false; screen = nil; remotePhone = d.platform == "android"; autoControl = true; freshControl = true; fallbackAccepted = false; controlConflict = false; remoteOpenedAt = Date().timeIntervalSince1970*1000
        remoteGeometryRestored=false;remoteCodec="--";remoteFPS=0;remoteBitrate=0;remoteSidebarCollapsed=(UserDefaults.standard.object(forKey:"LanLink.Remote.Sidebar."+d.id) as? Bool) ?? true
        remoteFrameTimes=[];remoteMetrics?.cancel();remoteMetrics=Task{while !Task.isCancelled&&remoteID==d.id{let now=ProcessInfo.processInfo.systemUptime;remoteFrameTimes.removeAll{$0<=now-1};remoteFPS=Double(remoteFrameTimes.count);try? await Task.sleep(nanoseconds:250_000_000)}}
        remoteSidebarAnimation?.cancel();remoteSidebarExtent=remoteSidebarCollapsed ? 0:64
        let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 800, height: 560), styleMask: [.titled, .closable, .resizable, .miniaturizable], backing: .buffered, defer: false)
        w.appearance=NSAppearance(named:.darkAqua);w.backgroundColor = .black;w.titlebarAppearsTransparent=true
        w.title = "桌面控制 · " + d.name; w.contentViewController = NSHostingController(rootView: RemoteView(model: self)); w.isReleasedWhenClosed = false; w.delegate = appDelegate; w.center(); w.makeKeyAndOrderFront(nil); remoteWindow = w
        let peer = (state["peers"] as? [[String: Any]] ?? []).first { $0["id"] as? String == d.id }
        let caps = peer?["capabilities"] as? [String: Any] ?? [:]
        fitRemote(width: caps["width"] as? Double ?? 1280, height: caps["height"] as? Double ?? 720)
        w.center();let frameName="LanLink.Remote."+d.id;_ = w.setFrameUsingName(frameName);w.setFrameAutosaveName(frameName);remoteGeometryRestored=true
        remoteTask = Task { var retry = Date.distantPast; var streaming = false; var legacy = false; var jpegOnly = false; var h264Only = false; var pictureFitted = false; while !Task.isCancelled && remoteID == d.id {
            if let ended = state["controlEnded"] as? [String: Any], ended["id"] as? String == d.id, (ended["at"] as? Double ?? 0) >= remoteOpenedAt { closeRemote(); notice="对方已结束控制"; break }
            if controlling && Date().timeIntervalSince(acquiredAt)>2 && state["active"] as? String != d.id { controlling=false }
            if autoControl && !controlling && Date() >= retry { retry = Date().addingTimeInterval(0.5); await beginRemoteControl() }
            if Task.isCancelled || remoteID != d.id { break }
            if controlConflict || !remotePrepared || (autoControl && !controlling) { try? await Task.sleep(nanoseconds: 500_000_000); continue }
            do {
                if !legacy && !streaming { let opened = try await call("stream-open", ["id": d.id, "jpegOnly": jpegOnly]); h264Only = opened["h264Only"] as? Bool ?? false; streaming = true }
                if Task.isCancelled || remoteID != d.id { _ = try? await call("stream-close", ["id": d.id]); break }
                let frame = try await call(streaming ? "stream-next" : "screen", ["id": d.id])
                if remoteID == d.id, let text = frame["data"] as? String, let data = Data(base64Encoded: text) { if frame["unchanged"] as? Bool != true { screen = NSImage(data: data) }; if let pixels=screen?.representations.first { screen?.size=NSSize(width:pixels.pixelsWide,height:pixels.pixelsHigh) }; if remotePhone, let picture=screen, !pictureFitted || abs(remoteRatio-picture.size.width/picture.size.height)>0.0001 { let changed=abs(remoteRatio-picture.size.width/picture.size.height)>0.0001;pictureFitted=true;if !remoteGeometryRestored || changed{fitRemote(width: picture.size.width,height: picture.size.height)} }; remoteCodec=streaming ? (frame["codec"] as? String == "h264" ? "H.264":"JPEG"):"截图";remoteBitrate=frame["bitrate"] as? Double ?? 0;notice = streaming ? "\(frame["codec"] as? String == "h264" ? "H.264 串流" : "兼容串流") · \(frame["fps"] as? Int ?? 0) fps" : "截图兼容模式" }
                if legacy { try? await Task.sleep(nanoseconds: 50_000_000) }
            } catch {
                if Task.isCancelled || remoteID != d.id { break }
                notice = error.localizedDescription
                if notice.hasPrefix("CONTROL_ENDED:") { closeRemote(); notice="对方已结束控制"; break }
                controlling=false; if autoControl { remotePrepared=false }; if terminalControlError(notice) { controlConflict = true; autoControl = false }
                if notice.hasPrefix("VIDEO_H264_REQUIRED:") || notice.contains("Root 投屏需要 H.264") { h264Only=true; jpegOnly=false; legacy=false }
                else if !h264Only && (notice.contains("不支持") || notice.contains("接口不存在")) { legacy = true }
                else if !h264Only && notice.contains("解码") { jpegOnly = true }
                if streaming { _ = try? await call("stream-close", ["id": d.id]); streaming = false }
                try? await Task.sleep(nanoseconds: 1_500_000_000)
            }
        } }
    }
    func toggleRemoteSidebar(){
        guard let w=remoteWindow else{return};remoteSidebarAnimation?.cancel();remoteSidebarCollapsed.toggle()
        if let id=remoteID{UserDefaults.standard.set(remoteSidebarCollapsed,forKey:"LanLink.Remote.Sidebar."+id)}
        let from=remoteSidebarExtent,target=remoteSidebarCollapsed ? 0.0:64.0,base=w.frame.width-from,origin=w.frame.origin,height=w.frame.height
        remoteSidebarAnimation=Task{@MainActor in
            let began=Date()
            while !Task.isCancelled && remoteWindow === w{
                let t=min(1,Date().timeIntervalSince(began)/0.22),ease=t*t*(3-2*t)
                remoteSidebarExtent=(from+(target-from)*ease).rounded()
                w.setFrame(NSRect(x:origin.x,y:origin.y,width:base+remoteSidebarExtent,height:height),display:true)
                if t>=1{break};try? await Task.sleep(nanoseconds:16_000_000)
            }
        }
    }
    func fitRemote(width: Double, height: Double) {
        guard width.isFinite, height.isFinite, width > 0, height > 0, let w = remoteWindow else { return }
        let area = (w.screen ?? NSScreen.main)?.visibleFrame.size ?? NSSize(width: 1280, height: 800)
        remoteRatio=width/height
        w.contentView?.layoutSubtreeIfNeeded()
        var chrome = remotePhone ? 84.0 : 36.0, side = 8.0+remoteSidebarExtent
        if remotePhone, let surface=remoteSurface, let content=w.contentView, surface.bounds.width>0, surface.bounds.height>0 { side=content.bounds.width-surface.bounds.width; chrome=content.bounds.height-surface.bounds.height }
        let bounds=(w.screen ?? NSScreen.main)?.frame.size ?? area;let extent=min(bounds.width,bounds.height)*0.8;let titleHeight=w.frame.height-(w.contentView?.bounds.height ?? w.frame.height);let desiredHeight=remoteGeometryRestored ? (w.contentView?.bounds.height ?? 560)-chrome:min(extent-titleHeight,area.height-30)-chrome;let widthLimit=remoteGeometryRestored ? area.width-30:min(area.width-30,extent)
        let scale = min((widthLimit-side) / width, min(area.height-chrome-50,desiredHeight) / height)
        w.setContentSize(NSSize(width: max((remotePhone ? 270 : 320)+remoteSidebarExtent, width * scale + side), height: max(320, height * scale + chrome)))
    }
    func beginRemoteControl() async {
        guard let id = remoteID, autoControl, !controlling, !handlingFallback, !preparingControl, !recoveringInput else { return }
        if remotePhone, inputMode(id) == "accessibility-limited", !fallbackAccepted { fallbackAccepted = confirmFallback(); if !fallbackAccepted { autoControl = false; notice = "备用控制未启用，可从 ADB 开启增强控制"; return } }
        preparingControl=true; let version = controlVersion; let fresh = freshControl; let priorRelease = controlReleaseTask
        let attempt = Task { @MainActor in
            defer { preparingControl = false }
            await priorRelease?.value
            guard remoteID == id, autoControl, version == controlVersion else { return }
            do { _ = try await call("control", ["id": id, "capture": false, "screen": true, "newSession": fresh, "fallbackAccepted": fallbackAccepted]); guard remoteID == id, autoControl, version == controlVersion else { _ = try? await call("release", ["id": id]); return }; freshControl=false; controlling = true; remotePrepared=true; acquiredAt=Date(); notice = "正在控制" }
            catch { guard remoteID == id, version == controlVersion else { return }; remotePrepared=false; notice = error.localizedDescription; if notice.hasPrefix("CONSENT_") || notice.hasPrefix("PERMISSION_") { freshControl=false }; if terminalControlError(notice) { controlConflict=true; autoControl=false } }
        }
        remoteControlTask = attempt
        await attempt.value
    }
    func terminalControlError(_ error: String) -> Bool { error.hasPrefix("CONSENT_DENIED:") || error.hasPrefix("CONTROL_ENDED:") || (error.hasPrefix("CONTROL_CONFLICT:") && !error.contains("正在切换控制") && !error.contains("正在连接该设备的画面")) }
    func release() { queueControlRelease(remoteID) }
    func releaseAll() { queueControlRelease(nil) }
    private func queueControlRelease(_ id: String?) {
        resumeRemote=false; autoControl = false; fallbackAccepted = false; controlVersion += 1; controlling = false
        let prior = controlReleaseTask
        controlReleaseTask = Task { @MainActor in await prior?.value; _ = try? await call("release", id.map { ["id": $0] } ?? [:]) }
    }
    func inputMode(_ id: String) -> String { ((state["peers"] as? [[String: Any]] ?? []).first { $0["id"] as? String == id }?["capabilities"] as? [String: Any])?["inputMode"] as? String ?? "" }
    func confirmFallback() -> Bool {
        showingInputModeDialog = true; defer { showingInputModeDialog = false }
        let alert = NSAlert(); alert.messageText = "使用无障碍备用控制？"
        alert.informativeText = "此手机尚未启用 Root / ADB 增强控制。备用模式支持点击、长按和滑动；拖动会在松开鼠标后执行，悬停、游戏和复杂快捷键受限。此旧版手机请升级到 0.3.9 并启用 ADB / Root 输入助手。\n\n可在设备菜单选择「ADB 键鼠授权」开启增强控制。"
        alert.addButton(withTitle: "使用备用模式"); alert.addButton(withTitle: "取消"); return alert.runModal() == .alertFirstButtonReturn
    }
    func switchControl(_ device: Device) {
        let fallback = device.platform == "android" && inputMode(device.id) == "accessibility-limited"
        if fallback && !confirmFallback() { return }
        perform("control", ["id": device.id, "capture": true, "newSession": true, "fallbackAccepted": fallback])
    }
    func showAdb(_ device: Device) { sidebarPage = "adb"; permissionsVisible = false; adbDevice = device; (appDelegate)?.showMain() }
    func removeDevice(_ device: Device) {
        let alert=NSAlert(); alert.messageText="移出设备？"; alert.informativeText="将 "+device.name+" 移出组。该设备需要重新配对，其他离线成员会保留身份。"; alert.addButton(withTitle:"移出"); alert.addButton(withTitle:"取消")
        if alert.runModal() == .alertFirstButtonReturn { perform("device-remove",["id":device.id],success:"设备已移出组") }; selected=nil
    }
    var controlledBy: String? {
        let owner = state["owner"] as? String ?? ""
        guard let id = owner.isEmpty ? (state["viewers"] as? [String])?.first : owner, !id.isEmpty else { return nil }; return devices.first { $0.id==id }?.name ?? "其他设备"
    }
    func setStartup(_ enabled: Bool) {
        do { if enabled { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }; startupEnabled = SMAppService.mainApp.status == .enabled; if SMAppService.mainApp.status == .requiresApproval { SMAppService.openSystemSettingsLoginItems(); notice = "请在系统登录项中允许 LanLink" } }
        catch { notice = error.localizedDescription; startupEnabled = SMAppService.mainApp.status == .enabled }
    }
    func refreshRequests() {
        let access=state["accessNotice"] as? [String:Any]
        if let current=accessController,current.requestID != access?["requestId"] as? String{current.end()}
        if let access,let id=access["requestId"] as? String,accessController==nil,lastAccess != id{lastAccess=id;accessController=AccessController(model:self,request:access)}
        let guide = state["adbGuide"] as? [String:Any]
        if let current = adbGuide, current.requestID != guide?["requestId"] as? String { current.end() }
        if let guide, let id = guide["requestId"] as? String, adbGuide == nil, lastAdbGuide != id { lastAdbGuide=id; adbGuide=AdbGuideController(model:self,request:guide) }
        if let fallback = state["fallbackNotice"] as? [String: Any], !handlingFallback {
            handlingFallback = true
            Task {
                let accepted = confirmFallback()
                do {
                    _ = try await call("fallback-dismiss")
                    if let id = fallback["id"] as? String, remoteID == id { controlling = false; fallbackAccepted = accepted; autoControl = accepted; freshControl = true; if accepted { remoteWindow?.makeKeyAndOrderFront(nil) } }
                    else if accepted { _ = try await call("control", ["id": fallback["id"] ?? "", "edge": fallback["edge"] ?? "", "capture": fallback["capture"] ?? false, "newSession": true, "fallbackAccepted": true]) }
                } catch { notice = error.localizedDescription }
                handlingFallback = false
            }
        }
        let requests = state["consentRequests"] as? [[String: Any]] ?? []
        if let alert = consentAlert, !requests.contains(where: { $0["requestId"] as? String == consentRequest }) { alert.window.sheetParent?.endSheet(alert.window, returnCode: .cancel); consentAlert = nil }
        if consentAlert == nil, let request = requests.first, let main = (appDelegate)?.mainWindow {
            let alert = NSAlert(); alert.messageText = "设备权限请求"; alert.informativeText = "\(request["name"] as? String ?? "设备") 请求恢复这台 Mac 上受限的功能，可在高级选项中调整。"
            alert.addButton(withTitle: "同意并记住"); alert.addButton(withTitle: "暂不允许")
            let keys = ["input", "screen", "clipboard", "shutdown"], names = ["键鼠标操作", "屏幕共享", "剪贴板文件共享", "远程关机"]
            let stack = NSStackView(); stack.orientation = .vertical; stack.alignment = .leading; stack.spacing = 12
            let checks = names.map { name -> NSButton in let b = NSButton(checkboxWithTitle: name, target: nil, action: nil); b.state = .on; b.isHidden=true; stack.addArrangedSubview(b); return b }
            let disclosure=ConsentDisclosure(alert:alert,stack:stack,checks:checks);self.disclosure=disclosure;let advanced=NSButton(title:"高级选项",target:disclosure,action:#selector(ConsentDisclosure.toggle));stack.insertArrangedSubview(advanced,at:0);
            stack.frame = NSRect(x: 0, y: 0, width: 340, height: 32); alert.accessoryView = stack; consentAlert = alert; consentRequest = request["requestId"] as? String ?? ""
            main.makeKeyAndOrderFront(nil); NSApp.activate(ignoringOtherApps: true)
            alert.beginSheetModal(for: main) { [weak self] response in Task { @MainActor in
                guard let self else { return }
                let permissions = Dictionary(uniqueKeysWithValues: zip(keys, checks.map { $0.state == .on }))
                do { _ = try await self.call("consent-decide", ["id": request["id"] ?? "", "requestId": request["requestId"] ?? "", "accepted": response == .alertFirstButtonReturn, "permissions": permissions]) } catch { self.notice = error.localizedDescription }
                self.consentAlert = nil
            } }
        }
        if state["permissionNotice"] is [String: Any], !firstDevicePermissionGuide { appDelegate?.showMain(); firstDevicePermissionGuide = true }
    }
    func closeRemote() { remoteOpenVersion += 1; _ = tearDownRemote() }
    @discardableResult private func tearDownRemote() -> Task<Void, Never>? {
        guard let id = remoteID else { return remoteTeardown }
        let oldWindow = remoteWindow, oldStream = remoteTask, oldInput = inputTail, oldControl = remoteControlTask, oldRelease = controlReleaseTask, prior = remoteTeardown
        remoteMetrics?.cancel(); remoteMetrics=nil; remoteFrameTimes=[]; remoteFPS=0
        remoteSidebarAnimation?.cancel(); remoteSidebarAnimation=nil
        if let w = oldWindow { let target=remoteSidebarCollapsed ? 0.0:64.0; var frame=w.frame; frame.size.width+=target-remoteSidebarExtent; remoteSidebarExtent=target; w.setFrame(frame,display:false); w.saveFrame(usingName:"LanLink.Remote."+id) }
        resumeRemote=false; autoControl=false; fallbackAccepted=false; controlVersion += 1; controlling=false; remotePrepared=false
        remoteSurface?.latestMove=nil; remoteSurface?.held=false; remoteSurface=nil
        oldStream?.cancel(); remoteTask=nil; remoteID=nil; remoteWindow=nil; screen=nil; inputTail=nil
        oldWindow?.orderOut(nil); oldWindow?.close()
        let teardown = Task { @MainActor in
            await prior?.value
            _ = try? await call("adb-guide-cancel", ["id": id])
            _ = try? await call("access-cancel", ["id": id])
            _ = try? await call("stream-close", ["id": id])
            await oldControl?.value; await oldInput?.value; await oldStream?.value; await oldRelease?.value
            _ = try? await call("release", ["id": id])
        }
        remoteTeardown = teardown
        return teardown
    }
    func sendInput(_ event: [String: Any]) {
        guard controlling, let id = remoteID else { return }; let prior = inputTail; let version = controlVersion
        inputTail = Task { await prior?.value; guard controlling, remoteID == id, version == controlVersion else { return }; do { _ = try await call("input", ["id": id, "input": event]) } catch { guard remoteID == id, version == controlVersion else { return }; notice = error.localizedDescription; recoveringInput=true; controlling=false; remotePrepared=false; if terminalControlError(notice) { autoControl=false; controlConflict=true }; _ = try? await call("release", ["id": id]); recoveringInput=false } }
    }
    func quit() {
        guard !closing else { return }; closing = true; poll?.cancel(); remoteTask?.cancel(); try? input.fileHandleForWriting.close()
        Task { for _ in 0..<90 { if process?.isRunning != true { NSApp.reply(toApplicationShouldTerminate: true); return }; try? await Task.sleep(nanoseconds: 100_000_000) }; process?.terminate(); NSApp.reply(toApplicationShouldTerminate: true) }
    }
}

struct SidebarItem: View {
    let title: String; let icon: String; let selected: Bool; let action: () -> Void
    @State private var hovering = false
    var body: some View {
        Button(action: action) {
            Label(title, systemImage: icon).padding(.horizontal, 12).padding(.vertical, 11)
                .frame(maxWidth: .infinity, alignment: .leading)
                .foregroundStyle(selected ? Palette.accent : hovering ? Palette.ink : Palette.muted)
                .background(selected ? Color.blue.opacity(0.14) : hovering ? Palette.card.opacity(0.78) : Color.clear, in: RoundedRectangle(cornerRadius: 12))
                .contentShape(RoundedRectangle(cornerRadius: 12))
        }.buttonStyle(.plain).onHover { hovering = $0 }
    }
}

struct Glass: NSViewRepresentable {
    var material: NSVisualEffectView.Material = .sidebar
    var mode: NSVisualEffectView.BlendingMode = .behindWindow
    func makeNSView(context: Context) -> NSVisualEffectView { let view = NSVisualEffectView(); view.material = material; view.blendingMode = mode; view.state = .active; return view }
    func updateNSView(_ view: NSVisualEffectView, context: Context) { }
}
struct DeviceTile: View {
    @ObservedObject var model: Model; let device: Device; var compact = false; var canvasSize: CGSize?
    @State private var actionsVisible = false
    var detail: String { device.local ? (model.isMaster ? "本机 · 主设备" : "本机") : model.state["active"] as? String == device.id ? (device.transport=="USB + 局域网" ? "融合控制中":"正在控制") : device.online ? (device.access["status"] as? String ?? "未就绪") : "离线" }
    var body: some View {
        Group { if compact { HStack(spacing: 14) { icon; labels } } else { VStack(spacing: canvasSize == nil ? 12 : 6) { icon; labels } } }
            .padding(canvasSize == nil ? (compact ? 16 : 14) : 10)
            .frame(width: canvasSize?.width ?? (compact ? 212 : device.platform == "android" ? 164 : 248),
                   height: canvasSize?.height ?? (compact ? 78 : device.platform == "android" ? 170 : 148))
            .background(device.local ? Color.blue.opacity(0.075) : Palette.card, in: RoundedRectangle(cornerRadius: 18))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(model.selected == device.id ? Color.blue.opacity(0.45) : Palette.line))
            .overlay(alignment:.topTrailing){if !compact && ((model.state["active"] as? String ?? "").isEmpty ? device.local:model.state["active"] as? String==device.id){Label("鼠标在此",systemImage:"cursorarrow").font(.caption2).padding(5).background(Color.blue.opacity(0.08),in:Capsule()).offset(y:-18)}}
            .contentShape(RoundedRectangle(cornerRadius: 16)).onTapGesture { model.selected = device.id; actionsVisible = true }
            .popover(isPresented: Binding(get: { actionsVisible && model.selected == device.id }, set: { actionsVisible = $0; if !$0 { model.selected = nil } })) { DeviceActions(model: model, device: device).padding(14).frame(width: 230).background(Glass(material: .popover, mode: .withinWindow)) }
            .contextMenu { DeviceActions(model: model, device: device) }
            .simultaneousGesture(DragGesture(minimumDistance: 6, coordinateSpace: .named("deviceLayout"))
                .onChanged { value in
                    guard !device.local else { return }
                    actionsVisible = false; model.selected = nil
                    model.layoutDragID = device.id; model.layoutDragPoint = value.location; model.layoutDragTranslation = value.translation
                }
                .onEnded { value in model.finishLayoutDrag(value.location) })
            .onDrop(of:[UTType.fileURL],delegate:DeviceFileDrop(model:model,device:device))
    }
    var icon: some View { Image(systemName: device.symbol).font(.system(size: compact || canvasSize != nil ? 24 : 30, weight: .light)).foregroundStyle(Palette.accent).frame(width: 36, height: 32) }
    var statusColor:Color {switch detail {case "在线":return Color(red:0.21,green:0.53,blue:0.41);case "未就绪":return Color(red:0.71,green:0.54,blue:0.22);case "未授权":return Color(red:0.75,green:0.39,blue:0.44);case "有限授权":return Color(red:0.51,green:0.44,blue:0.73);case "正在控制","融合控制中":return .blue;default:return .gray}}
    var labels: some View {
        VStack(alignment: compact ? .leading : .center, spacing: canvasSize == nil ? 6 : 4) {
            Text(device.name).font(.system(size: canvasSize == nil ? 14 : 13, weight: .medium))
                .lineLimit(1).minimumScaleFactor(0.85)
            HStack(spacing: 5) {
                if !device.local { Circle().fill(statusColor).frame(width: 6, height: 6) }
                Text(detail).font(.system(size: canvasSize == nil ? 12 : 11)).foregroundStyle(statusColor)
                    .lineLimit(1).minimumScaleFactor(0.85)
            }.fixedSize(horizontal: false, vertical: true)
        }
    }
}
struct DeviceActions: View {
    @ObservedObject var model: Model; let device: Device
    func feature(_ title:String,_ keys:[String],_ action:@escaping ()->Void)->some View { Button(title){model.feature(device,keys,action)}.foregroundStyle(model.restricted(device,keys) ? Color.gray : Color.primary) }
    var body: some View { VStack(alignment: .leading, spacing: 12) {
        Text(device.name).font(.headline)
        if !device.local && device.online && device.access["status"] as? String == "未就绪" {
            Text("已连接到组，部分功能仍需准备。请在对方设备检查应用开关和系统权限。")
                .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            Button("查看未就绪原因") { model.selected = nil; model.sidebarPage = "permissions"; model.permissionsVisible = true }
        }
        Divider()
        if device.local {
            Button("返回本机键鼠") { model.releaseAll(); model.selected = nil }
            Button("取消关机") { model.perform("cancel-shutdown"); model.selected = nil }.disabled(model.state["shutdownAt"] == nil || model.state["shutdownAt"] is NSNull)
        } else {
            Group {
                if device.platform != "android" {Button("设备详情与权限") { model.selected = nil; model.sidebarPage = "permissions"; model.permissionsVisible = true }}
                feature("桌面控制",["input","screen"]) { model.selected = nil; model.openRemote(device) }
                feature("切换键鼠到此设备",["input"]) { model.switchControl(device); model.selected = nil }

                feature("粘贴共享内容",["input","clipboard"]) { model.perform("device-paste", ["id": device.id]); model.selected = nil }
                feature("发送文件…",["clipboard"]) { model.selected = nil; model.sendFiles(device) }
            }.disabled(!device.online)
            Divider()
            if device.platform != "android" {Button("撤销此设备的本机权限") { model.perform("consent-revoke", ["id": device.id], success: "已停用此设备的本机权限，可重新发起授权"); model.selected = nil }}
            DisclosureGroup("调整方位") {VStack(spacing:6){direction("top","↑ 上方");HStack{direction("left","← 左侧");Text("本机").font(.caption).foregroundStyle(.secondary);direction("right","右侧 →")};direction("bottom","↓ 下方")}.padding(8)}
            Button("移出设备…") { model.removeDevice(device) }
            Button("移出布局") { model.removeLayout(device.id); model.selected = nil }.disabled(!model.edges.values.contains(device.id))
            if device.platform != "android" { Divider(); feature("关机…",["shutdown"]) { model.selected = nil; model.shutdown(device) }.disabled(!device.online); Button("取消关机") { model.perform("cancel-shutdown", ["id": device.id]) }.disabled(!device.online) }
        }
    }.buttonStyle(.plain) }
    func direction(_ edge:String,_ title:String)->some View{Button(title){model.place(device.id,edge);model.selected=nil}.padding(8).background(model.edges[edge]==device.id ? Color.blue.opacity(0.14):Color.gray.opacity(0.05),in:RoundedRectangle(cornerRadius:10))}
}
struct DeviceLayoutGeometry {
    let card: CGSize
    let center: CGPoint
    let horizontalStep: CGFloat
    let verticalStep: CGFloat
    init(size: CGSize, edges: Set<String>) {
        let left = edges.contains("left") ? 1 : 0, right = edges.contains("right") ? 1 : 0
        let top = edges.contains("top") ? 1 : 0, bottom = edges.contains("bottom") ? 1 : 0
        let columns = CGFloat(1 + left + right), rows = CGFloat(1 + top + bottom)
        let gap: CGFloat = 32, margin: CGFloat = 16
        let scale = max(0.1, min(1, (size.width - 2 * margin - (columns - 1) * gap) / (columns * 180), (size.height - 2 * margin - (rows - 1) * gap) / (rows * 112)))
        card = CGSize(width: 180 * scale, height: 112 * scale)
        let horizontalGap = columns > 1 ? min(56, max(gap, (size.width - 2 * margin - columns * card.width) / (columns - 1))) : gap
        let verticalGap = rows > 1 ? min(56, max(gap, (size.height - 2 * margin - rows * card.height) / (rows - 1))) : gap
        horizontalStep = card.width + horizontalGap
        verticalStep = card.height + verticalGap
        center = CGPoint(x: size.width / 2 + CGFloat(left - right) * horizontalStep / 2, y: size.height / 2 + CGFloat(top - bottom) * verticalStep / 2)
    }
    func point(_ edge: String) -> CGPoint {
        switch edge {
        case "left": return CGPoint(x: center.x - horizontalStep, y: center.y)
        case "right": return CGPoint(x: center.x + horizontalStep, y: center.y)
        case "top": return CGPoint(x: center.x, y: center.y - verticalStep)
        case "bottom": return CGPoint(x: center.x, y: center.y + verticalStep)
        default: return center
        }
    }
}
struct LayoutCanvas: View {
    @ObservedObject var model: Model
    @Environment(\.accessibilityReduceMotion) var reduceMotion
    var body: some View { GeometryReader { geometry in
        let size = geometry.size
        let occupied = Set(model.edges.filter { !$0.value.isEmpty }.map(\.key))
        let layout = DeviceLayoutGeometry(size: size, edges: occupied)
        let card = layout.card
        let frame = geometry.frame(in: .named("deviceLayout"))
        let center = layout.center
        ZStack {
            TimelineView(.animation(minimumInterval: 1.0 / 30, paused: reduceMotion || (model.state["active"] as? String ?? "").isEmpty)) { timeline in
                Canvas { ctx, _ in
                    for x in stride(from: 15, to: Int(size.width), by: 24) {
                        for y in stride(from: 10, to: Int(size.height), by: 24) {
                            ctx.fill(Path(ellipseIn: CGRect(x: CGFloat(x), y: CGFloat(y), width: 1.3, height: 1.3)), with: .color(.blue.opacity(0.13)))
                        }
                    }
                    for (edge, id) in model.edges where !id.isEmpty {
                        let point = layout.point(edge)
                        let horizontal = edge == "left" || edge == "right"
                        let dx = horizontal ? (point.x < center.x ? -card.width / 2 - 4 : card.width / 2 + 4) : 0
                        let dy = horizontal ? 0 : (point.y < center.y ? -card.height / 2 - 4 : card.height / 2 + 4)
                        let fusion = model.devices.first(where: { $0.id == id })?.transport == "USB + 局域网"
                        for offset in (fusion ? [CGFloat(-3), CGFloat(3)] : [CGFloat(0)]) {
                            var line = Path()
                            line.move(to: CGPoint(x: center.x + dx + (horizontal ? 0 : offset), y: center.y + dy + (horizontal ? offset : 0)))
                            line.addLine(to: CGPoint(x: point.x - dx + (horizontal ? 0 : offset), y: point.y - dy + (horizontal ? offset : 0)))
                            ctx.stroke(line, with: .color(.blue.opacity(0.24)), lineWidth: 1.5)
                            if model.state["active"] as? String == id {
                                let phase = reduceMotion ? 0 : timeline.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 0.9) / 0.9
                                ctx.stroke(line, with: .color(.indigo.opacity(0.7)), style: StrokeStyle(lineWidth: 2, dash: [6, 16], dashPhase: -22 * phase))
                            }
                        }
                    }
                }
            }.allowsHitTesting(false)
            ForEach(model.devices) { device in
                let edge = device.local ? "local" : model.edges.first(where: { $0.value == device.id })?.key
                if edge != nil || model.layoutDragID == device.id {
                    let dragging = model.layoutDragID == device.id
                    let origin = layout.point(edge ?? "local")
                    let point = dragging ? (edge != nil ? CGPoint(x: origin.x + model.layoutDragTranslation.width, y: origin.y + model.layoutDragTranslation.height) : CGPoint(x: model.layoutDragPoint.x - frame.minX, y: model.layoutDragPoint.y - frame.minY)) : origin
                    DeviceTile(model: model, device: device, canvasSize: CGSize(width: 180, height: 112))
                        .scaleEffect(card.width / 180).frame(width: card.width, height: card.height)
                        .position(point).zIndex(dragging ? 1 : 0)
                        .animation(model.layoutDragID != nil || reduceMotion ? nil : .easeInOut(duration: 0.25), value: point)
                        .animation(model.layoutDragID != nil || reduceMotion ? nil : .easeInOut(duration: 0.25), value: card)
                }
            }
            if model.devices.count == 1 {
                Text("将上方设备拖入画布，或直接拖动设备调整方位")
                    .font(.caption).foregroundStyle(.secondary).position(x: center.x, y: size.height - 18)
            }
        }.frame(width: size.width, height: size.height).clipped()
        .onAppear { model.layoutCanvasFrame = frame; model.layoutLocalCenter = CGPoint(x: frame.minX + center.x, y: frame.minY + center.y) }
        .onChange(of: frame) { _, value in model.layoutCanvasFrame = value; model.layoutLocalCenter = CGPoint(x: value.minX + center.x, y: value.minY + center.y) }
        .onChange(of: center) { _, value in model.layoutLocalCenter = CGPoint(x: frame.minX + value.x, y: frame.minY + value.y) }
    } }
}

final class MainAppWindow: NSWindow {
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { true }
}

struct WindowDragArea: NSViewRepresentable {
    final class DragView: NSView {
        override func mouseDown(with event: NSEvent) {
            if event.clickCount == 2 { window?.zoom(nil) }
            else { window?.performDrag(with: event) }
        }
    }
    func makeNSView(context: Context) -> DragView { DragView() }
    func updateNSView(_ view: DragView, context: Context) { }
}

struct WindowControl: View {
    let title: String; let symbol: String; let color: Color; let action: () -> Void
    @State private var hovering = false
    var body: some View {
        Button(action: action) {
            Image(systemName: symbol).font(.system(size: 8, weight: .bold))
                .foregroundStyle(Color.black.opacity(hovering ? 0.7 : 0))
                .frame(width: 13, height: 13).background(color, in: Circle())
                .frame(width: 24, height: 30).contentShape(Rectangle())
        }.buttonStyle(.plain).onHover { hovering = $0 }.help(title).accessibilityLabel(title)
    }
}

struct AppTitleBar: View {
    @ObservedObject var model: Model
    var body: some View {
        HStack(spacing: 0) {
            WindowControl(title: "关闭窗口", symbol: "xmark", color: Color(red: 1, green: 0.37, blue: 0.34)) { model.appDelegate?.closeMainWindow() }
            WindowControl(title: "最小化", symbol: "minus", color: Color(red: 1, green: 0.74, blue: 0.18)) { model.appDelegate?.mainWindow.miniaturize(nil) }
            WindowControl(title: "切换全屏", symbol: "arrow.up.left.and.arrow.down.right", color: Color(red: 0.16, green: 0.79, blue: 0.26)) { model.appDelegate?.mainWindow.toggleFullScreen(nil) }
            WindowDragArea().overlay(Text("LanLink").font(.system(size: 12, weight: .medium)).foregroundStyle(Palette.muted).allowsHitTesting(false))
        }.padding(.horizontal, 14).frame(height: 38)
    }
}

struct Home: View {
    @ObservedObject var model: Model; @State var settings = false; @State var pairing = false
    func navigate(_ page: String) { model.layoutDragID = nil; settings = page == "settings"; model.adbDevice = nil; model.permissionsVisible = page == "permissions"; model.sidebarPage = page }
    var body: some View { ZStack {
        Glass(material: .sidebar)
        VStack(spacing: 0) {
        AppTitleBar(model: model)
        GeometryReader { layout in
        let contentWidth = max(0, layout.size.width - 190 - 16 - 52)
        let contentHeight = max(0, layout.size.height - 16 - 52)
        HStack(spacing: 0) {
        VStack(alignment: .leading, spacing: 14) {
            Label("LanLink", systemImage: "square.on.square").font(.title2).padding(.bottom, 18)
            SidebarItem(title: "设备", icon: "desktopcomputer", selected: model.sidebarPage == "devices") { navigate("devices") }
            SidebarItem(title: "暂存文件", icon: "folder", selected: model.sidebarPage == "staging") { navigate("staging") }
            SidebarItem(title: "本组设备权限", icon: "checkmark.shield", selected: model.sidebarPage == "permissions") { navigate("permissions") }
            SidebarItem(title: "安卓授权", icon: "iphone.gen3.radiowaves.left.and.right", selected: model.sidebarPage == "adb") { navigate("adb") }
            SidebarItem(title: "剪贴板", icon: "doc.on.clipboard", selected: model.sidebarPage == "clipboard") { navigate("clipboard") }
            SidebarItem(title: "设置", icon: "gearshape", selected: model.sidebarPage == "settings") { navigate("settings") }
            Spacer()
            if let name = model.controlledBy { VStack(alignment: .leading, spacing: 10) { Label("正在被控制", systemImage: "dot.radiowaves.left.and.right").font(.caption.weight(.semibold)); Text(name + " 正在操作或查看本机").font(.caption).fixedSize(horizontal: false, vertical: true); Button("取消桌面控制") { model.perform("stop-inbound") }.buttonStyle(FlatButtonStyle()) }.padding(12).frame(maxWidth: .infinity, alignment: .leading).background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16)).overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.green.opacity(0.20))) }
            SidebarItem(title: "我的信息", icon: "person.crop.circle", selected: model.sidebarPage == "information") { navigate("information") }
        }.buttonStyle(.plain).padding(16).frame(width: 190)
        ZStack {
            VStack(alignment: .leading, spacing: 18) {
                HStack(spacing:12) { Text("设备与布局").font(.system(size: 26, weight: .semibold)); Spacer();if model.clipboardSettings["showShortcuts"] as? Bool ?? false{HStack(spacing:8){ClipboardQuickButton(upload:true){model.clipboardAction("clipboard-upload")};ClipboardQuickButton(upload:false){model.clipboardAction("clipboard-download")}}}; Button("＋ 添加设备") { model.sidebarPage = "information" }.buttonStyle(FlatButtonStyle()) }
                ScrollView(.horizontal) { HStack(spacing: 10) { ForEach(model.devices) { d in DeviceTile(model: model, device: d, compact: true).opacity(model.layoutDragID == d.id ? 0.35 : 1) } }.padding(1) }.frame(height: 82)
                Text("拖动调整方位，点击设备进行操作。").font(.caption).foregroundStyle(.secondary)
                LayoutCanvas(model: model).padding(10).frame(maxHeight: .infinity).layoutPriority(1).background(Palette.canvas, in: RoundedRectangle(cornerRadius: 20))
                Text(model.notice).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                Divider(); HStack { Text("键鼠控制：" + (model.devices.first { $0.id == (model.state["active"] as? String) }?.name ?? "本机")).font(.caption); Spacer(); Button("返回本机 · Control + Option + F12") { model.releaseAll() }.buttonStyle(.plain).font(.caption) }
            }.coordinateSpace(name: "deviceLayout").opacity(settings || model.adbDevice != nil || model.sidebarPage != "devices" ? 0 : 1).allowsHitTesting(!settings && model.adbDevice == nil && model.sidebarPage == "devices").accessibilityHidden(settings || model.adbDevice != nil || model.sidebarPage != "devices")
            SettingsView(model: model, active: settings).opacity(settings && model.adbDevice == nil && !model.permissionsVisible ? 1 : 0).allowsHitTesting(settings && model.adbDevice == nil && !model.permissionsVisible).accessibilityHidden(!settings || model.adbDevice != nil || model.permissionsVisible)
            if model.permissionsVisible { DevicePermissionsView(model: model) }
            if model.sidebarPage == "adb", model.adbDevice == nil { AdbHubView(model: model) }
            if model.sidebarPage=="staging"{StagingView(model:model)}
            if model.sidebarPage=="clipboard"{ClipboardHistoryView(model:model)}
            if model.sidebarPage == "information" { InformationView(model: model) }
            if let device = model.adbDevice { Group { if device.id.isEmpty { LocalAdbAuthorizationView(model:model) } else { AdbAuthorizationView(model:model,device:device) } }.id(device.id) }
        }.frame(width: contentWidth, height: contentHeight, alignment: .topLeading).clipped().overlay(alignment:.topTrailing){if !model.clipboardToast.isEmpty{Text(model.clipboardToast).font(.callout).padding(16).frame(maxWidth:320).background(.ultraThinMaterial,in:RoundedRectangle(cornerRadius:16)).padding(16)}}.padding(26).background(Palette.surface.opacity(0.78), in: RoundedRectangle(cornerRadius: 22)).overlay(RoundedRectangle(cornerRadius: 22).stroke(Palette.line.opacity(0.82), lineWidth: 1)).padding(.trailing, 16).padding(.bottom, 16)
    }.frame(width: layout.size.width, height: layout.size.height).onChange(of: model.adbDevice?.id) { _, id in if id != nil { settings = false } }
        .sheet(isPresented: $model.firstDevicePermissionGuide, onDismiss: { if model.state["permissionNotice"] is [String: Any] { model.perform("permission-dismiss") } }) { FirstDevicePermissionGuide(model: model) }
        }
        }
    }
    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    .frame(minWidth: 900, minHeight: 678)
    }
}
struct ClipboardHistoryView:View{
    @ObservedObject var model:Model
    var records:[[String:Any]]{model.clipboardSettings["history"] as? [[String:Any]] ?? []}
    var body:some View{VStack(alignment:.leading,spacing:20){Text("剪贴板").font(.system(size:26,weight:.semibold));Text("最近 10 分钟 · 最多 30 条文字 · 新内容在前").font(.caption).foregroundStyle(.secondary);ScrollView{VStack(spacing:12){if records.isEmpty{Text("暂无同步记录，复制文字后将在这里出现。").foregroundStyle(.secondary).frame(maxWidth:.infinity,alignment:.leading)};ForEach(records,id: \.masterChoiceID){record in VStack(alignment:.leading,spacing:14){HStack{Text(record["name"] as? String ?? "设备").font(.caption).foregroundStyle(.secondary);Text(Date(timeIntervalSince1970:(record["at"] as? Double ?? 0)/1000),style:.time).font(.caption).foregroundStyle(.secondary);Spacer();Button("复制"){model.clipboardAction("clipboard-copy",["id":record["id"] as? String ?? ""])}};Text(record["text"] as? String ?? "").lineLimit(6).textSelection(.enabled).frame(maxWidth:.infinity,alignment:.leading);if (record["length"] as? Int ?? 0)>2000{Text("长文本预览 · 复制可获取完整内容").font(.caption).foregroundStyle(.secondary)}}.padding(20).background(Palette.card,in:RoundedRectangle(cornerRadius:18)).overlay(RoundedRectangle(cornerRadius:18).stroke(Palette.line))}}}.buttonStyle(FlatButtonStyle())}}
}
struct InformationView: View {
    @ObservedObject var model: Model
    var body: some View { ScrollView { VStack(alignment: .leading, spacing: 24) {
        Text("我的信息").font(.title2)
        VStack(alignment: .leading, spacing: 18) {
            Text(model.state["name"] as? String ?? "本机").font(.title3)
            Text("设备标识：" + (model.state["id"] as? String ?? "")).textSelection(.enabled)
            Text("所属组：" + (model.state["groupId"] as? String ?? "")).textSelection(.enabled)
        }.padding(24).frame(maxWidth: .infinity, alignment: .leading).background(Palette.card, in: RoundedRectangle(cornerRadius: 20))
        PairingView(model: model)
    }.frame(maxWidth: .infinity, alignment: .leading) }.frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading).buttonStyle(FlatButtonStyle()) }
}
struct AdbHubView: View {
    @ObservedObject var model: Model
    @State private var devices: [[String: Any]] = []
    @State private var message = "正在读取授权状态…"
    func refresh() async {
        do { let result = try await model.call("adb-group"); devices = result["devices"] as? [[String: Any]] ?? []; message = devices.isEmpty ? "本组暂无安卓设备，请在我的信息中添加手机。" : "已授权的手机会自动恢复输入连接。" }
        catch { message = error.localizedDescription }
    }
    var body: some View { VStack(alignment: .leading, spacing: 24) {
        Text("安卓授权").font(.title2)
        Text(message).foregroundStyle(.secondary)
        ScrollView { VStack(spacing: 16) { ForEach(devices.indices, id: \.self) { index in
            let item = devices[index]
            HStack {
                VStack(alignment: .leading, spacing: 8) { Text(item["name"] as? String ?? "手机"); Text(item["status"] as? String ?? "状态暂不可用").font(.caption).foregroundStyle(.secondary);if let reason=item["message"] as? String,!reason.isEmpty{Text(reason).font(.caption).foregroundStyle(.secondary)} }
                Spacer()
                if item["ready"] as? Bool != true { Button("连接授权") { model.perform("adb-guide-open",["id":item["id"] ?? ""]) }.disabled(item["online"] as? Bool != true) }
            }.padding(24).background(Palette.card, in: RoundedRectangle(cornerRadius: 20))
        } } }
        HStack { Button("刷新状态") { Task { await refresh() } };Spacer();Button { model.showAdb(Device(id:"",name:"其他设备",platform:"android",online:true)) } label:{Text("帮助其他组的设备ADB授权").font(.caption).underline()}.buttonStyle(.plain) }
    }.frame(maxWidth: .infinity,maxHeight: .infinity,alignment: .topLeading).buttonStyle(FlatButtonStyle()).task { await refresh() } }
}
struct DevicePermissionsView: View {
    @ObservedObject var model: Model
    @State private var devices: [[String: Any]] = []
    @State private var message = "正在读取组内权限…"
    @State private var checkedEntry = false
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack { Text("本组设备权限").font(.title2); Spacer(); Button("返回设备") { model.permissionsVisible = false; model.sidebarPage = "devices" } }
            Text(message).font(.caption).foregroundStyle(.secondary)
            ScrollView { VStack(spacing: 16) {
                ForEach(devices.indices, id: \.self) { index in deviceCard(devices[index]) }
            } }
        }.frame(maxWidth: .infinity, maxHeight: .infinity).buttonStyle(FlatButtonStyle())
        .task {
            while !Task.isCancelled {
                do {
                    let result = try await model.call("group-permissions")
                    guard !Task.isCancelled else { return }
                    devices = result["devices"] as? [[String: Any]] ?? []
                    await model.refresh()
                    guard !Task.isCancelled else { return }
                    message = "自动刷新 · 应用开关、系统能力以及对本电脑的授权"
                    if !checkedEntry {
                        checkedEntry = true
                        model.checkPermissionPageEntry(hasOtherDevices: devices.contains { $0["local"] as? Bool != true })
                    }
                }
                catch { message = error.localizedDescription }
                do { try await Task.sleep(nanoseconds: 4_000_000_000) } catch { break }
            }
        }
    }
    func permissionSummary(_ row: [String: Any]) -> String {
        let app = row["app"] as? String ?? "未知"
        let system = row["system"] as? String ?? "未知"
        let consent = row["consent"] as? String ?? "未知"
        return "应用：\(app)   系统：\(system)   对本机：\(consent)"
    }
    func deviceCard(_ device: [String: Any]) -> some View {
        let rows = device["permissions"] as? [[String: Any]] ?? []
        return VStack(alignment: .leading, spacing: 14) {
            Text((device["name"] as? String ?? "设备") + (device["online"] as? Bool == true ? " · 在线" : " · 离线")).font(.headline)
            if let note = device["message"] as? String, !note.isEmpty { Text(note).font(.caption).foregroundStyle(Palette.muted) }
            ForEach(rows.indices, id: \.self) { index in
                let row = rows[index]
                let key = row["key"] as? String ?? ""
                let local = device["local"] as? Bool == true
                let ready = model.collaborationSystemReady(key)
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 5) {
                        Text(row["label"] as? String ?? "").font(.callout.weight(.medium))
                        Text(permissionSummary(row)).font(.caption).foregroundStyle(Palette.muted)
                    }
                    Spacer()
                    Text(local ? (!ready ? "系统未就绪" : model.permissions[key] == true ? "已就绪" : "应用未开启") : row["status"] as? String ?? "未知")
                        .font(.caption).foregroundStyle(local && ready && model.permissions[key] == true ? .green : Palette.muted)
                    if local {
                        Toggle("开启" + (row["label"] as? String ?? "协作功能"), isOn: model.collaborationBinding(key))
                            .labelsHidden().toggleStyle(.checkbox)
                            .help(ready ? "开启或关闭此 Mac 的协作功能" : "点击查看并授予所需的系统权限")
                    }
                }.padding(.vertical, 5)
            }
            if let engine = device["engine"] as? [String: Any] { Text(engine["message"] as? String ?? "").font(.caption).foregroundStyle(Palette.muted) }
            if device["platform"] as? String == "android", device["online"] as? Bool == true {
                Button("ADB 键鼠授权 / 自动恢复") { model.showAdb(Device(id: device["id"] as? String ?? "", name: device["name"] as? String ?? "手机", platform: "android", online: true)) }
            }
        }.padding(22).frame(maxWidth: .infinity, alignment: .leading).background(Palette.card, in: RoundedRectangle(cornerRadius: 20))
    }
}

struct MacSystemPermissionsView: View {
    @ObservedObject var model: Model
    var capabilities: [String: Any] { model.state["capabilities"] as? [String: Any] ?? [:] }
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("加入组成功不代表系统已允许控制。系统权限与本组设备授权分别管理。")
                .font(.caption).foregroundStyle(.secondary)
            if capabilities["simulated"] as? Bool == true {
                Text("当前为模拟模式，系统键鼠和录屏能力未启用。").font(.caption)
            } else {
                permission("辅助功能 · 接收键鼠控制", key: "input", pane: "Privacy_Accessibility")
                permission("输入监控 · 使用本机键鼠控制其他设备", key: "host", pane: "Privacy_ListenEvent")
                permission("屏幕录制 · 向其他设备分享画面", key: "screen", pane: "Privacy_ScreenCapture")
                Text("在系统设置中允许 LanLink；授权后完全退出并重新打开应用，再刷新状态。")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
    }
    func permission(_ title: String, key: String, pane: String) -> some View {
        HStack {
            Text(title).font(.callout)
            Spacer()
            Text(capabilities[key] as? Bool == true ? "已就绪" : "未就绪").font(.caption)
            Button("系统设置") { NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.preference.security?" + pane)!) }
        }
    }
}

struct PermissionHelpView: View {
    @ObservedObject var model: Model
    var body: some View { VStack(alignment: .leading, spacing: 18) {
        Text("开启协作权限").font(.title2)
        Text("完成授权后，等待中的远程窗口会自动重试。").font(.caption).foregroundStyle(.secondary)
        MacSystemPermissionsView(model: model)
        ForEach(["input", "screen", "clipboard", "shutdown"], id: \.self) { key in
            let allowed = model.permissions[key] == true
            let system = (model.state["capabilities"] as? [String: Any])?[key] as? Bool == true
            HStack {
                Text(["input": "键鼠操作", "screen": "屏幕录制", "clipboard": "剪贴板与文件", "shutdown": "远程关机"][key]!)
                Spacer()
                Button(allowed && system ? "已授权" : "开启权限") {
                    if !allowed { model.perform("settings", ["permissions": [key: true]]) }
                    if !system && (key == "input" || key == "screen") { NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.preference.security?" + (key == "input" ? "Privacy_Accessibility" : "Privacy_ScreenCapture"))!) }
                }.disabled(allowed && system)
            }
        }
        Text("若 macOS 要求重启应用，请完成授权后重新打开 LanLink。").font(.caption).foregroundStyle(.secondary)
    }.padding(24).buttonStyle(FlatButtonStyle()).background(Palette.surface) }
}

struct FirstDevicePermissionGuide: View {
    @ObservedObject var model: Model
    @Environment(\.dismiss) private var dismiss
    var capabilities: [String: Any] { model.state["capabilities"] as? [String: Any] ?? [:] }
    func status(_ key: String) -> String { capabilities[key] as? Bool == true ? "已就绪" : "待开启" }
    func open(_ pane: String) { NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.preference.security?" + pane)!) }
    var body: some View {
        ScrollView { VStack(alignment: .leading, spacing: 22) {
            HStack(alignment: .top, spacing: 14) {
                Image(systemName: "checkmark.shield.fill").font(.system(size: 32)).foregroundStyle(Palette.accent)
                    .frame(width: 54, height: 54).background(Color.blue.opacity(0.12), in: RoundedRectangle(cornerRadius: 16))
                VStack(alignment: .leading, spacing: 6) {
                    Text("完成 Mac 协作权限设置").font(.title2.weight(.semibold))
                    Text("系统权限决定键鼠、屏幕共享等功能是否可用。现在开启，之后可以随时在“本组设备权限”中调整。")
                        .font(.callout).foregroundStyle(Palette.muted).fixedSize(horizontal: false, vertical: true)
                }
            }
            VStack(spacing: 10) {
                permissionRow(icon: "accessibility", title: "辅助功能", detail: "允许其他已授权设备控制这台 Mac", status: status("input"), action: { open("Privacy_Accessibility") })
                permissionRow(icon: "cursorarrow.rays", title: "输入监控", detail: "允许本机键鼠在设备间切换", status: status("host"), action: { open("Privacy_ListenEvent") })
                permissionRow(icon: "rectangle.inset.filled", title: "屏幕录制", detail: "允许向已授权设备分享屏幕", status: status("screen"), action: { open("Privacy_ScreenCapture") })
            }
            if status("input") != "已就绪" {
                Text("如果“辅助功能”列表中没有 LanLink，请在 Finder 的“应用程序”中找到 LanLink.app，将它拖入列表，再打开对应开关。")
                    .font(.caption).foregroundStyle(Palette.muted).padding(14).frame(maxWidth: .infinity, alignment: .leading)
                    .background(Palette.canvas, in: RoundedRectangle(cornerRadius: 14))
            }
            VStack(alignment: .leading, spacing: 12) {
                Text("本机功能开关").font(.headline)
                Toggle("允许键鼠控制", isOn: model.collaborationBinding("input"))
                Toggle("允许屏幕共享", isOn: model.collaborationBinding("screen"))
                Toggle("共享剪贴板与文件", isOn: model.collaborationBinding("clipboard"))
            }.padding(18).background(Palette.card, in: RoundedRectangle(cornerRadius: 16)).overlay(RoundedRectangle(cornerRadius: 16).stroke(Palette.line))
            Text("完成系统授权后，请完全退出并重新打开 LanLink，再查看权限状态。")
                .font(.caption).foregroundStyle(Palette.muted)
            HStack { Spacer(); Button("稍后设置") { dismiss() }; Button("查看权限状态") { dismiss(); model.sidebarPage = "permissions"; model.permissionsVisible = true } }
        }.padding(28) }.frame(width: 600, height: min(660, (NSScreen.main?.visibleFrame.height ?? 800) - 100)).background(Palette.surface).buttonStyle(FlatButtonStyle()).toggleStyle(.switch)
    }
    func permissionRow(icon: String, title: String, detail: String, status: String, action: @escaping () -> Void) -> some View {
        HStack(spacing: 14) {
            Image(systemName: icon).font(.system(size: 18)).foregroundStyle(Palette.accent).frame(width: 38, height: 38).background(Palette.canvas, in: RoundedRectangle(cornerRadius: 11))
            VStack(alignment: .leading, spacing: 3) { Text(title).font(.headline); Text(detail).font(.caption).foregroundStyle(Palette.muted) }
            Spacer()
            if status == "已就绪" {
                Label("已授予", systemImage: "checkmark.circle.fill").font(.caption.weight(.medium)).foregroundStyle(.green)
            } else {
                Button("授予权限", action: action)
            }
        }.padding(16).background(Palette.card, in: RoundedRectangle(cornerRadius: 16)).overlay(RoundedRectangle(cornerRadius: 16).stroke(Palette.line))
    }
}
extension Dictionary where Key == String, Value == Any { var masterChoiceID:String { self["id"] as? String ?? "" } }
struct SettingsView: View {
    @ObservedObject var model: Model; let active: Bool
    @State var name = ""; @State var masterId = ""; @State var permissions: [String: Bool] = [:]
    @State var dirty = false; @State var saving = false; @State var status = "修改后点击保存；切换页面会保留草稿"
    @State var logs = false;@State var switchMode="dwell";@State var edgeHotkey="ctrl-alt";@State var dwell=100.0
    func mark() { dirty = true; status = "有未保存的更改 · 切换页面会保留草稿" }
    func load() { name = model.state["name"] as? String ?? ""; masterId = model.state["masterId"] as? String ?? model.state["id"] as? String ?? ""; permissions = model.permissions;switchMode=model.state["switchMode"] as? String ?? "dwell";edgeHotkey=model.state["edgeHotkey"] as? String ?? "ctrl-alt";dwell=model.state["dwell"] as? Double ?? 100; dirty = false }
    func save() {
        saving = true
        var values: [String: Any] = ["name": name, "switchMode": switchMode, "edgeHotkey": edgeHotkey, "dwell": Int(dwell)]
        if !masterId.isEmpty { values["masterId"] = masterId }
        Task { do { _ = try await model.call("settings", values); await model.refresh(); load(); status = "本机设置已保存" } catch { status = error.localizedDescription }; saving = false }
    }
    var body: some View { VStack(alignment: .leading, spacing: 20) {
        HStack(alignment: .top) { VStack(alignment: .leading, spacing: 8) { Text("设置").font(.system(size: 26, weight: .semibold)); Text("管理设备名称、主设备与启动选项").font(.caption).foregroundStyle(.secondary) }; Spacer(); Button("保存设置", action: save).disabled(saving || model.state["id"] == nil) }
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                VStack(alignment: .leading, spacing: 16) {
                    Text("通用").font(.headline)
                    Picker("外观", selection: Binding(get: { model.state["theme"] as? String ?? "system" }, set: { model.perform("settings", ["theme": $0]) })) {
                        Text("跟随系统").tag("system")
                        Text("深色").tag("dark")
                        Text("浅色").tag("light")
                    }
                    Text("仅对本设备生效，切换后立即应用。").font(.caption).foregroundStyle(.secondary)
                    TextField("设备名称", text: Binding(get: { name }, set: { name = $0; mark() })).textFieldStyle(.roundedBorder).frame(maxWidth: 420)
                    Toggle("登录 Mac 后自动启动 LanLink", isOn: Binding(get: { model.startupEnabled }, set: { model.setStartup($0) }))
                }.padding(22).background(Palette.card, in: RoundedRectangle(cornerRadius: 18))
                VStack(alignment: .leading, spacing: 16) {
                    Text("无界鼠标").font(.headline)
                    Picker("键鼠主设备",selection:Binding(get:{masterId},set:{masterId=$0;mark()})){
                        if masterId.isEmpty { Text("尚未选择").tag("") }
                        ForEach(model.state["masterDevices"] as? [[String:Any]] ?? [],id: \.masterChoiceID){device in Text((device["name"] as? String ?? "电脑")+((device["online"] as? Bool ?? false) ? "":" · 离线")).tag(device["id"] as? String ?? "")}
                    }
                    Toggle("键鼠切换时显示边缘光效",isOn:Binding(get:{model.state["edgeGlowEnabled"] as? Bool ?? true},set:{model.perform("settings",["edgeGlow":$0])}))
                    Toggle("鼠标移走后隐藏光效",isOn:Binding(get:{model.state["edgeGlowHideAway"] as? Bool ?? false},set:{model.perform("settings",["edgeGlowHideAway":$0])}))
                    Text("以下切换设置仅对本机生效").font(.caption).foregroundStyle(.secondary)
                    Picker("键鼠切换设备方式",selection:Binding(get:{switchMode},set:{switchMode=$0;mark()})){Text("边缘停留").tag("dwell");Text("快捷键 + 边缘").tag("hotkey")}.pickerStyle(.segmented)
                    if switchMode=="dwell"{Text("边缘停留时间 · \(Int(dwell)) 毫秒");Slider(value:Binding(get:{dwell},set:{dwell=$0;mark()}),in:0...3000,step:50);if dwell==0{Text("即使停留时间为零，创建连接仍需要少量等待时间。").font(.caption).foregroundStyle(.secondary)}}else{Picker("按住组合键到达边缘",selection:Binding(get:{edgeHotkey},set:{edgeHotkey=$0;mark()})){Text("Control + Option").tag("ctrl-alt");Text("Control + Shift").tag("ctrl-shift");Text("Option + Shift").tag("alt-shift");Text("Command + Shift").tag("meta-shift")}}
                }.padding(22).background(Palette.card,in:RoundedRectangle(cornerRadius:18))
                VStack(alignment:.leading,spacing:16){
                    Text("共享剪贴板").font(.headline)
                    HStack{Toggle("允许自动上传剪贴板信息到组",isOn:Binding(get:{model.clipboardSettings["upload"] as? Bool ?? true},set:{model.perform("settings",["clipboardUpload":$0])}));if !(model.clipboardSettings["upload"] as? Bool ?? true){Button("上传剪贴板"){model.clipboardAction("clipboard-upload")}}}
                    HStack{Toggle("允许自动获取组剪贴板到本机",isOn:Binding(get:{model.clipboardSettings["download"] as? Bool ?? true},set:{model.perform("settings",["clipboardDownload":$0])}));if !(model.clipboardSettings["download"] as? Bool ?? true){Button("下载剪贴板"){model.clipboardAction("clipboard-download")}}}
                    if !(model.clipboardSettings["upload"] as? Bool ?? true) || !(model.clipboardSettings["download"] as? Bool ?? true){Toggle("在首页显示快捷同步剪贴板按钮",isOn:Binding(get:{model.clipboardSettings["shortcuts"] as? Bool ?? false},set:{model.perform("settings",["clipboardShortcuts":$0])}))}
                    Text("同步到组内在线设备，保留单独设置的设备授权限制。").font(.caption).foregroundStyle(.secondary)
                }.padding(22).background(Palette.card,in:RoundedRectangle(cornerRadius:18))
                VStack(alignment:.leading,spacing:16){
                    Text("其他").font(.headline)

                    HStack { Button("运行日志") { logs.toggle() }; Button("退出应用") { NSApp.terminate(nil) } }
                    if logs { ForEach(Array((model.state["logs"] as? [[String: Any]] ?? []).enumerated()), id: \.offset) { item in Text(item.element["message"] as? String ?? "").font(.caption).foregroundStyle(.secondary) } }
                }.padding(22).background(Palette.card, in: RoundedRectangle(cornerRadius: 18))
            }.frame(maxWidth: 820).frame(maxWidth: .infinity)
        }
        Text(status).font(.caption).foregroundStyle(.secondary)
    }.buttonStyle(FlatButtonStyle()).toggleStyle(.switch)
        .onAppear { load() }.onChange(of: active) { _, shown in if shown && !dirty { load() } }
        .onChange(of: model.state["groupId"] as? String) { _, _ in load() }
        .onChange(of: model.state["masterId"] as? String) { _, _ in if !dirty { load() } }
    }
}
struct PairingView: View {
    @ObservedObject var model: Model; @Environment(\.dismiss) var dismiss
    @Environment(\.colorScheme) private var colorScheme
    @State private var code = ""; @State private var input = ""; @State private var qr: NSImage?
    @State private var showing = false; @State private var joining = false; @State private var busy = false
    @State private var loading = false; @State private var alive = true; @State private var error = ""
    @State private var deadline = Date.distantPast; @State private var now = Date(); @State private var duration = 60.0
    @State private var host = ""
    @State private var groupEstablished = false
    @State private var initialPeers: Set<String>?
    @State private var waitingConnection = false
    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()
    private var remaining: Double { max(0, deadline.timeIntervalSince(now)) }
    private var formatted: String { code.count == 8 && remaining > 0 ? String(code.prefix(4)) + " " + String(code.suffix(4)) : "···· ····" }
    private var darkQRCode: Bool { (model.state["theme"] as? String == "dark") || (model.state["theme"] as? String != "light" && colorScheme == .dark) }
    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text("让设备相遇").font(.system(size: 25, weight: .semibold))
            Text("在同一局域网中，扫码或输入配对码即可加入你的离线组。").font(.callout).foregroundStyle(.secondary)
            HStack(alignment: .center, spacing: 28) {
                VStack(spacing: 12) {
                    ZStack {
                        if showing, remaining > 0, let qr {
                            if darkQRCode {
                                Image(nsImage: qr).resizable().interpolation(.none).scaledToFit().colorInvert()
                                    .padding(10).background(Color.black, in: RoundedRectangle(cornerRadius: 12))
                            } else {
                                Image(nsImage: qr).resizable().interpolation(.none).scaledToFit()
                                    .padding(10).background(Color.white, in: RoundedRectangle(cornerRadius: 12))
                            }
                        }
                    }.frame(width: 190, height: 190)
                    Button(showing ? "隐藏二维码" : "点击查看二维码") { showing.toggle(); qr = nil; Task { await refresh() } }.disabled(loading)
                    Text("二维码与数字码同步刷新").font(.caption).foregroundStyle(.secondary)
                }.padding(18).background(Palette.canvas, in: RoundedRectangle(cornerRadius: 22))
                VStack(alignment: .leading, spacing: 12) {
                    Text("其他设备加入本组")
                    Text(formatted).font(.system(size: 34, weight: .semibold, design: .monospaced)).foregroundStyle(.indigo).textSelection(.enabled)
                    ProgressView(value: min(remaining / duration, 1)).tint(.indigo)
                    Text(remaining > 0 ? "\(Int(ceil(remaining))) 秒后刷新 · 每分钟自动更换" : "正在刷新配对码…").font(.caption).foregroundStyle(.secondary)
                    Text("让新设备扫码，或在「加入其他组」中输入上方八位数字。").font(.caption).foregroundStyle(.secondary)
                    Button(joining ? "收起输入 ↑" : groupEstablished ? "解散当前组并加入其他组 →" : "加入其他组 →") { joining.toggle() }.disabled(busy)
                    if joining {
                        TextField("其他设备的八位数字配对码", text: $input).textFieldStyle(.roundedBorder).disabled(busy)
                            .onChange(of: input) { _, value in input = String(value.filter { $0 >= "0" && $0 <= "9" }.prefix(8)) }
                        Button(busy ? "正在配对…" : groupEstablished ? "解散当前组并加入其他组" : "配对并加入") { Task { await join() } }.disabled(busy)
                    }
                }.frame(minWidth: 0, maxWidth: .infinity, alignment: .leading)
            }
            if !error.isEmpty {
                Text(error).font(.callout).foregroundStyle(Palette.ink)
                    .fixedSize(horizontal: false, vertical: true).padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Palette.canvas, in: RoundedRectangle(cornerRadius: 10))
            }
            DisclosureGroup("已配对设备无法自动连接？") {
                HStack { TextField("已配对设备的局域网 IP", text: $host).textFieldStyle(.roundedBorder); Button("手动连接") { model.perform("connect", ["host": host, "port": 45831], success: "正在连接设备") } }.padding(.top, 8)
            }.font(.caption).foregroundStyle(.secondary)
        }.buttonStyle(FlatButtonStyle()).interactiveDismissDisabled(busy)
        .task { alive = true; await refresh() }
        .onReceive(timer) { value in now = value; if remaining <= 0 { qr = nil }; Task { await refresh() } }
        .onDisappear { alive = false; qr = nil; Task { _ = try? await model.call("pairing-stop") } }
    }
    private func refresh() async {
        guard alive, !loading, !busy else { return }; loading = true; defer { loading = false }
        do {
            let state = try await model.call("state"); guard alive else { return }
            let online = Set((state["peers"] as? [[String: Any]] ?? []).compactMap { $0["id"] as? String })
            if let initialPeers, waitingConnection ? !online.isEmpty : !online.isSubset(of: initialPeers) {
                alive = false; model.sidebarPage = "devices"; model.notice = "设备已连接"; return
            }
            if initialPeers == nil { initialPeers = online }
            let result = try await model.call("pairing", ["reveal": showing]); guard alive else { return }
            groupEstablished = result["groupEstablished"] as? Bool ?? false
            code = result["code"] as? String ?? ""; now = Date(); deadline = now.addingTimeInterval((result["remainingMs"] as? Double ?? 0) / 1000)
            duration = (result["durationMs"] as? Double ?? 60000) / 1000
            if showing, let data = result["qr"] as? String, let suffix = data.split(separator: ",").last, let bytes = Data(base64Encoded: String(suffix)) { qr = NSImage(data: bytes) }
        } catch { code = ""; qr = nil; deadline = .distantPast; self.error = error.localizedDescription }
    }
    private func join() async {
        guard input.count == 8, input.allSatisfy({ $0 >= "0" && $0 <= "9" }) else { error = "请输入完整的八位数字配对码。"; return }
        guard !busy else { return }
        busy = true; error = "正在准备加入…"; defer { busy = false }
        do {
            let state = try await model.call("state")
            groupEstablished = state["groupEstablished"] as? Bool ?? false
            let wasFirstDevice = (state["knownDevices"] as? [String: [String: Any]] ?? [:]).isEmpty && (state["groupMemberCount"] as? Int ?? 1) <= 1
            let alert = NSAlert(); alert.messageText = groupEstablished ? "解散当前组并加入其他组？" : "加入其他组？"
            alert.informativeText = groupEstablished ? "原组所有设备都会退出，并断开控制。离线设备收到解散通知后退出。先验证新组配对码，成功后才解散原组。" : "本机将加入对方的组，在同一局域网内自动连接。"
            alert.addButton(withTitle: "加入"); alert.addButton(withTitle: "取消")
            guard let window = model.appDelegate?.mainWindow else {
                error = "无法显示加入确认，请重新打开主窗口。"; return
            }
            window.makeKeyAndOrderFront(nil)
            let response: NSApplication.ModalResponse = await withCheckedContinuation { continuation in
                alert.beginSheetModal(for: window) { continuation.resume(returning: $0) }
            }
            guard response == .alertFirstButtonReturn else { error = "已取消加入。"; return }
            error = "正在查找设备并配对…"
            _ = try await model.call("join", ["code": input, "dissolveCurrentGroup": groupEstablished, "expectedGroup": state["groupId"] as? String ?? ""])
            await model.refresh(); model.showFirstDevicePermissionGuideIfNeeded(wasFirstDevice: wasFirstDevice); waitingConnection = true; initialPeers = []; input = ""; joining = false; showing = false; qr = nil; error = "已配对，正在建立加密连接…"
        } catch { self.error = error.localizedDescription }
    }
}

@MainActor final class AppDelegate: NSObject, NSApplicationDelegate, NSWindowDelegate {
    let model = Model(); var mainWindow: NSWindow!
    private(set) var instance: InstanceLease?
    private var redirecting = false
    func applicationWillFinishLaunching(_ notification: Notification) {
        let args = CommandLine.arguments
        let data = args.firstIndex(of: "--data").flatMap { $0 + 1 < args.count ? args[$0 + 1] : nil } ?? InstanceLease.defaultDirectory
        let lease = InstanceLease(dataDirectory: data); instance = lease
        do {
            guard try lease.acquire() else { redirectToExistingInstance(); return }
            // Older released bundles have no lease implementation. Prefer their
            // existing default-profile window instead of starting a rival host.
            let defaultDirectory = InstanceLease(dataDirectory: InstanceLease.defaultDirectory).directory
            if lease.directory == defaultDirectory,
               let legacy = NSRunningApplication.runningApplications(withBundleIdentifier: "com.lanlink.native")
                .filter({ app in
                    guard app.processIdentifier != ProcessInfo.processInfo.processIdentifier, !app.isTerminated,
                          let url = app.bundleURL else { return false }
                    return Bundle(url: url)?.object(forInfoDictionaryKey: "LanLinkInstanceProtocol") as? Int != 1
                }).sorted(by: { ($0.launchDate ?? .distantPast) < ($1.launchDate ?? .distantPast) }).first {
                redirectToExistingInstance(legacy); return
            }
            DistributedNotificationCenter.default().addObserver(self, selector: #selector(receiveReopen(_:)), name: InstanceLease.reopenNotification, object: lease.directory)
        } catch {
            redirecting = true
            let alert = NSAlert(); alert.messageText = "无法启动 LanLink"; alert.informativeText = error.localizedDescription
            alert.runModal(); NSApp.terminate(nil)
        }
    }
    private func redirectToExistingInstance(_ legacy: NSRunningApplication? = nil) {
        redirecting = true
        Task { @MainActor in
            // A simultaneous launch may find the lease before its owner has
            // written the PID or installed the reopen observer.
            for _ in 0..<20 {
                guard let lease = instance else { break }
                DistributedNotificationCenter.default().postNotificationName(InstanceLease.reopenNotification, object: lease.directory, userInfo: nil, deliverImmediately: true)
                let app = legacy ?? lease.ownerPID().flatMap { NSRunningApplication(processIdentifier: $0) }
                if let app, !app.isTerminated, app.bundleIdentifier == "com.lanlink.native", let url = app.bundleURL {
                    app.activate(options: [.activateAllWindows])
                    let configuration = NSWorkspace.OpenConfiguration(); configuration.activates = true; configuration.createsNewApplicationInstance = false
                    await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                        NSWorkspace.shared.openApplication(at: url, configuration: configuration) { _, _ in continuation.resume() }
                    }
                    break
                }
                if legacy == nil, (try? lease.acquire()) == true {
                    // The owner may exit while a second launch is handing over.
                    // Recover ownership instead of leaving the user with no app.
                    redirecting = false
                    DistributedNotificationCenter.default().addObserver(self, selector: #selector(receiveReopen(_:)), name: InstanceLease.reopenNotification, object: lease.directory)
                    startMainInterface()
                    return
                }
                try? await Task.sleep(nanoseconds: 100_000_000)
            }
            NSApp.terminate(nil)
        }
    }
    @objc private func receiveReopen(_ notification: Notification) { if instance?.isOwner == true && !redirecting { showMain() } }
    func applicationDidFinishLaunching(_ notification: Notification) { startMainInterface() }
    private func startMainInterface() {
        guard instance?.isOwner == true, !redirecting, mainWindow == nil else { return }
        model.appDelegate = self
        mainWindow = MainAppWindow(contentRect: NSRect(x: 0, y: 0, width: 1024, height: 720), styleMask: [.borderless, .closable, .resizable, .miniaturizable], backing: .buffered, defer: false)
        mainWindow.collectionBehavior.insert(.fullScreenPrimary)
        mainWindow.title = "LanLink"; mainWindow.titleVisibility = .hidden; mainWindow.titlebarAppearsTransparent = false; mainWindow.titlebarSeparatorStyle = .none; mainWindow.isOpaque = false; mainWindow.backgroundColor = .clear; mainWindow.isReleasedWhenClosed = false; mainWindow.delegate = self
        mainWindow.contentViewController = NSHostingController(rootView: Home(model: model).foregroundStyle(Palette.ink).font(.system(size: 14))); if !mainWindow.setFrameUsingName("LanLink.MainWindow") { mainWindow.center() }; mainWindow.setFrameAutosaveName("LanLink.MainWindow"); mainWindow.makeKeyAndOrderFront(nil); NSApp.activate(ignoringOtherApps: true); model.launch();NSApp.servicesProvider=self;NSUpdateDynamicServices()
        let menu = NSMenu(); let root = NSMenuItem(); menu.addItem(root); let appMenu = NSMenu(); root.submenu = appMenu; appMenu.addItem(withTitle: "退出 LanLink", action: #selector(quitApp), keyEquivalent: "q")
        let edit = NSMenuItem(); edit.title = "编辑"; menu.addItem(edit); let editMenu = NSMenu(title: "编辑"); edit.submenu = editMenu; editMenu.addItem(withTitle: "复制", action: #selector(NSText.copy(_:)), keyEquivalent: "c"); editMenu.addItem(withTitle: "粘贴", action: #selector(NSText.paste(_:)), keyEquivalent: "v"); editMenu.addItem(withTitle: "全选", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a"); NSApp.mainMenu = menu
    }
    func closeMainWindow() { mainWindow.orderOut(nil); model.releaseAll() }
    @objc func showMain() { guard let mainWindow, !model.closing else { return }; mainWindow.deminiaturize(nil); mainWindow.makeKeyAndOrderFront(nil); NSApp.activate(ignoringOtherApps: true) }
    @objc func releaseControl() { model.releaseAll() }
    @objc func quitApp() { NSApp.terminate(nil) }
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool { showMain(); return true }
    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply { if redirecting || mainWindow == nil { return .terminateNow }; model.quit(); return .terminateLater }
    func applicationWillTerminate(_ notification: Notification) { DistributedNotificationCenter.default().removeObserver(self); instance?.release() }
    func windowShouldClose(_ sender: NSWindow) -> Bool { if sender === mainWindow { closeMainWindow(); return false }; if sender === model.remoteWindow { model.closeRemote() }; if sender === model.permissionWindow { model.permissionWindow = nil; model.perform("permission-dismiss") }; return true }
    func windowDidResignKey(_ notification: Notification) { if let w = notification.object as? NSWindow, w === model.remoteWindow, model.controlling, !model.showingInputModeDialog { let resume=model.autoControl; model.release(); model.resumeRemote=resume } }
    func windowDidBecomeKey(_ notification: Notification) { if let w=notification.object as? NSWindow, w === model.remoteWindow, model.resumeRemote { model.resumeRemote=false; model.autoControl=true } }
    func windowWillResize(_ sender: NSWindow, to size: NSSize) -> NSSize {
        guard sender === model.remoteWindow, model.remotePhone else { return size }
        guard let surface=model.remoteSurface, surface.bounds.width>0 else { return size }
        let extraW=sender.frame.width-surface.bounds.width, extraH=sender.frame.height-surface.bounds.height
        let width=max(270+model.remoteSidebarExtent,max(size.width,(360-extraH)*model.remoteRatio+extraW))
        return NSSize(width:width,height:(width-extraW)/model.remoteRatio+extraH)
    }
}

#if !LANLINK_MODEL_TEST
@main struct LanLinkApp: App { @NSApplicationDelegateAdaptor(AppDelegate.self) var delegate; var body: some Scene { Settings { EmptyView() } } }
#endif

@MainActor final class EdgeGlowPanel:NSPanel{
    static var current:EdgeGlowPanel?;var counting=false
    static func feedback(_ f:[String:Any]){
        let phase=f["phase"] as? String ?? "",edge=f["edge"] as? String ?? ""
        if phase=="clear" || phase=="cancel"{if phase=="clear" || current?.counting==true{current?.close();current=nil};return}
        guard ["left","right","top","bottom"].contains(edge) else{return}
        if phase=="countdown"{
            if current?.counting != true || (current?.contentView as? EdgeGlowDrawing)?.edge != edge{show(edge:edge,countdown:true)}
            let p=max(0,min(1,f["progress"] as? Double ?? 0));current?.alphaValue=0.5*p
            if let drawing=current?.contentView as? EdgeGlowDrawing{drawing.thickness=10+22*p;drawing.needsDisplay=true};return
        }
        guard ["depart","return","hold"].contains(phase) else{return}
        if phase != "depart" || current?.counting != true || (current?.contentView as? EdgeGlowDrawing)?.edge != edge{show(edge:edge)}
        guard let panel=current else{return};panel.counting=false
        let hide=f["hideAway"] as? Bool ?? false,back=phase=="return"
        let start:Double=back ? (hide ? 0:0.5):0.5,end:Double=(back || hide) ? 0:0.5,peak:Double=(back && hide) ? 0.5:1
        panel.alphaValue=start
        if phase=="hold"{panel.alphaValue=end;if end==0{panel.close();current=nil};return}
        Task{@MainActor in
            let began=Date(),duration=back ? 0.26:0.34
            while current === panel{
                let t=Date().timeIntervalSince(began)
                if t>=duration{panel.alphaValue=end;if end==0{panel.close();current=nil};break}
                panel.alphaValue=t<0.1 ? start+(peak-start)*t/0.1:peak+(end-peak)*(t-0.1)/(duration-0.1)
                try? await Task.sleep(nanoseconds:16_000_000)
            }
        }
    }
    static func show(edge:String,countdown:Bool=false){guard let screen=NSScreen.main else{return};current?.close();var r=screen.frame
        if edge=="left"{r.size.width=64};if edge=="right"{r.origin.x=r.maxX-64;r.size.width=64};if edge=="top"{r.origin.y=r.maxY-64;r.size.height=64};if edge=="bottom"{r.size.height=64}
        let panel=EdgeGlowPanel(contentRect:r,styleMask:[.borderless,.nonactivatingPanel],backing:.buffered,defer:false);current=panel;panel.counting=countdown;panel.isReleasedWhenClosed=false;panel.isOpaque=false;panel.backgroundColor = .clear;panel.hasShadow=false;panel.ignoresMouseEvents=true;panel.level = .floating;panel.collectionBehavior=[.canJoinAllSpaces,.fullScreenAuxiliary];panel.contentView=EdgeGlowDrawing(frame:NSRect(origin:.zero,size:r.size),edge:edge);panel.alphaValue=0;panel.orderFrontRegardless()
    }
    override var canBecomeKey:Bool{false}
}
final class EdgeGlowDrawing:NSView{
    let edge:String;var thickness=32.0
    init(frame:NSRect,edge:String){self.edge=edge;super.init(frame:frame)}
    required init?(coder:NSCoder){return nil}
    override func draw(_ dirtyRect:NSRect){var strip=bounds;if edge=="left"{strip.size.width=thickness};if edge=="right"{strip.origin.x=strip.maxX-thickness;strip.size.width=thickness};if edge=="top"{strip.origin.y=strip.maxY-thickness;strip.size.height=thickness};if edge=="bottom"{strip.size.height=thickness};NSGradient(starting:NSColor.systemPurple.withAlphaComponent(0.9),ending:.clear)?.draw(in:strip,angle:edge=="left" ? 0:edge=="right" ? 180:edge=="top" ? 270:90)}
}
