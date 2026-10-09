import AppKit
import SwiftUI
import UniformTypeIdentifiers

@MainActor extension Model {
    func refreshStaging() async {
        let generation = serviceGeneration
        do { let result = try await call("stage-list"); guard generation == serviceGeneration else { return }; stagedFiles = result["files"] as? [[String: Any]] ?? []; outgoingFiles = result["outgoing"] as? [[String: Any]] ?? [] }
        catch { if generation == serviceGeneration { notice = error.localizedDescription } }
    }
    func stageAction(_ method:String, _ id:String) {
        guard !stageBusy.contains(id) else { return }
        let version = (stageActionVersions[id] ?? 0) + 1; stageActionVersions[id] = version
        let blocks = method != "stage-start" && method != "stage-outgoing-retry"
        if blocks { stageBusy.insert(id) }
        Task { defer { if blocks { stageBusy.remove(id) } }; do {
            let result = try await call(method, ["id": id])
            guard stageActionVersions[id] == version else { return }
            let defaults = ["stage-cancel": "传输已取消，可重新传输", "stage-remove": "已删除暂存文件", "stage-start": "已开始接收文件", "stage-outgoing-cancel": "发送已取消", "stage-outgoing-retry": "已重新开始发送", "stage-outgoing-remove": "已清除发送记录"]
            notice = result["message"] as? String ?? defaults[method] ?? "操作已完成"
            await refreshStaging()
        } catch { if stageActionVersions[id] == version { notice = error.localizedDescription } } }
    }
    func openStaged(_ id:String) {
        Task { do { let result = try await call("stage-ready", ["id": id]); if let path = result["path"] as? String, !NSWorkspace.shared.open(URL(fileURLWithPath: path)) { notice = "无法打开文件，请使用“保存到…”后选择其他应用打开。" } } catch { notice = error.localizedDescription } }
    }
    func revealStaged(_ id: String) {
        Task { do { let result = try await call("stage-ready", ["id": id]); if let path = result["path"] as? String { NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: path)]) } } catch { notice = error.localizedDescription } }
    }
    func saveStaged(_ row: [String: Any]) {
        guard let id = row["id"] as? String, !stageBusy.contains(id) else { return }
        let panel = NSSavePanel(); panel.nameFieldStringValue = row["name"] as? String ?? "文件"
        panel.title = L("保存暂存文件"); panel.message = L("选择新的名称和位置。保存成功后移出暂存区；同名文件不会被覆盖。"); panel.canCreateDirectories = true
        guard panel.runModal() == .OK, let destination = panel.url else { return }
        stageBusy.insert(id)
        Task { defer { stageBusy.remove(id) }; do {
            let result = try await call("stage-save", ["id": id, "destination": destination.path])
            notice = "文件已保存到“" + destination.path + "”并移出暂存区"
            await refreshStaging()
            NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: result["path"] as? String ?? destination.path)])
        } catch { notice = "保存失败：" + Model.readableMessage(error.localizedDescription) + "。暂存原件已保留，可更换位置或名称后重试。" } }
    }
    func removeStaged(_ row: [String: Any]) {
        guard let id = row["id"] as? String, !stageBusy.contains(id) else { return }
        if row["state"] as? String == "ready" {
            let alert = NSAlert(); alert.messageText = L("删除尚未保存的暂存文件？")
            alert.informativeText = LF("“{0}”的暂存副本将被删除。请先使用“保存到…”保留文件。", row["name"] as? String ?? L("文件")); alert.addButton(withTitle: L("取消")); alert.addButton(withTitle: L("删除"))
            guard alert.runModal() == .alertSecondButtonReturn else { return }
        }
        stageAction("stage-remove", id)
    }
    func clearOutgoing(_ row: [String: Any]) {
        guard let id = row["id"] as? String, !stageBusy.contains(id) else { return }
        let alert = NSAlert(); alert.messageText = L("清除这条发送记录？")
        alert.informativeText = LF("本机原文件会保留。清除后，对方尚未接收完成的“{0}”需要重新发送。", row["name"] as? String ?? L("文件"))
        alert.addButton(withTitle: L("取消")); alert.addButton(withTitle: L("清除记录"))
        if alert.runModal() == .alertSecondButtonReturn { stageAction("stage-outgoing-remove", id) }
    }
    func chooseShareDevice(_ urls:[URL]) {
        guard !urls.isEmpty else { return }
        appDelegate?.showMain()
        Task {
            await refresh()
            let online=devices.filter { $0.online && !$0.local }
            let alert=NSAlert(); alert.messageText=L("发送到设备"); alert.informativeText=LF("{0} 个文件或文件夹 · 接收到对方暂存区", String(urls.count))
            let choice=NSPopUpButton(frame:NSRect(x:0,y:0,width:300,height:32)); choice.addItems(withTitles:online.map(\.name)); alert.accessoryView=choice
            if online.isEmpty { alert.informativeText=L("暂无在线设备。请打开对方 LanLink，并保持同一网络。"); alert.addButton(withTitle:L("关闭")); alert.runModal(); return }
            alert.addButton(withTitle:L("发送")); alert.addButton(withTitle:L("取消"))
            if alert.runModal() == .alertFirstButtonReturn {
                let device=online[choice.indexOfSelectedItem]
                feature(device,["clipboard"]) { self.perform("stage-share",["id":device.id,"files":urls.map(\.path)],success:"已开始发送，文件将在对方暂存区排队接收") }
            }
        }
    }
}

struct StagingView:View {
    @ObservedObject var model:Model
    var body:some View {
        VStack(alignment:.leading,spacing:20) {
            HStack { Text(L("暂存文件")).font(.system(size:26,weight:.semibold)); Spacer(); Button(L("刷新")) { Task { await model.refreshStaging() } } }
            Text(L("接收文件 · 使用“保存到…”或拖到文件夹保存")).font(.caption).foregroundStyle(.secondary)
            ScrollView { LazyVStack(spacing:14) {
                if !model.outgoingFiles.isEmpty {
                    Text(L("发送记录")).font(.headline).frame(maxWidth: .infinity, alignment: .leading)
                    ForEach(model.outgoingFiles, id: \.masterChoiceID) { row in OutgoingStagingCard(model: model, row: row) }
                    Divider(); Text(L("接收文件")).font(.headline).frame(maxWidth: .infinity, alignment: .leading)
                }
                if model.stagedFiles.isEmpty { Text(L("暂存区是空的，其他设备发送的文件会出现在这里。")).foregroundStyle(.secondary).frame(maxWidth:.infinity,alignment:.leading).padding(20) }
                ForEach(model.stagedFiles,id:\.masterChoiceID) { row in StagingCard(model:model,row:row) }
            } }
        }.buttonStyle(FlatButtonStyle()).task { await model.refreshStaging() }
    }
}
struct StagingCard:View {
    @ObservedObject var model:Model; let row:[String:Any]
    var id:String { row["id"] as? String ?? "" }
    var state:String { row["state"] as? String ?? "pending" }
    var status:String { if state=="ready" { return "已接收" }; if state=="receiving" { let received=row["received"] as? Double ?? 0,size=max(1,row["size"] as? Double ?? 0); return LF("传输中 · {0}%", String(Int(received*100/size))) }; return state=="pending" ? "待传输":state=="queued" ? "等待传输":state=="cancelled" ? "已取消":"传输失败" }
    var body:some View {
        VStack(alignment:.leading,spacing:14) {
            HStack { Image(systemName:row["type"] as? String=="folder" ? "folder":"doc").foregroundStyle(.blue); Text(row["name"] as? String ?? "文件").font(.headline).lineLimit(2); Spacer(); Text(L(status)).font(.caption).foregroundStyle(.secondary) }
            HStack { Text(row["sourceName"] as? String ?? "设备"); Text(ByteCountFormatter.string(fromByteCount:Int64(row["size"] as? Double ?? 0),countStyle:.file)); Text(Date(timeIntervalSince1970:(row["created"] as? Double ?? 0)/1000),style:.time) }.font(.caption).foregroundStyle(.secondary)
            HStack(spacing:12) {
                if state=="ready" {
                    Button(L("保存到…")) { model.saveStaged(row) }
                    Button(L("查看文件")) { model.openStaged(id) }
                    Button(L("在文件夹中显示")) { model.revealStaged(id) }
                } else if state != "receiving" && state != "queued" { Button(L(state=="pending" ? "开始传输":"重新传输")) { model.stageAction("stage-start",id) } }
                if state == "receiving" || state == "queued" { Button(L("取消传输")) { model.stageAction("stage-cancel", id) } }
                else { Button(L("删除")) { model.removeStaged(row) } }
            }.disabled(model.stageBusy.contains(id))
            if state == "ready" { StagedDragOut(model: model, id: id).frame(width: 160, height: 30).disabled(model.stageBusy.contains(id)) }
            if let error=row["error"] as? String,!error.isEmpty { Text(Model.displayMessage(error)).font(.caption).foregroundStyle(.red) }
        }.padding(20).frame(maxWidth:.infinity,alignment:.leading).background(Palette.card,in:RoundedRectangle(cornerRadius:20)).overlay(RoundedRectangle(cornerRadius:20).stroke(Palette.line))
    }
}

struct OutgoingStagingCard: View {
    @ObservedObject var model: Model; let row: [String: Any]
    var id: String { row["id"] as? String ?? "" }
    var state: String { row["state"] as? String ?? "queued" }
    var status: String {
        switch state {
        case "ready", "completed", "complete": return "对方已完整接收"
        case "sending", "receiving": return "对方正在接收"
        case "failed": return "发送失败"
        case "cancelled": return "已取消"
        case "pending": return "等待对方开始接收"
        default: return "已排队，等待对方接收"
        }
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack { Text(row["name"] as? String ?? "文件").font(.headline); Spacer(); Text(L(status)).font(.caption) }
            Text(LF("发送到“{0}”", row["targetName"] as? String ?? L("设备")) + " · " + ByteCountFormatter.string(fromByteCount: Int64(row["size"] as? Double ?? 0), countStyle: .file)).font(.caption).foregroundStyle(.secondary)
            if let error = row["error"] as? String, !error.isEmpty { Text(Model.displayMessage(error)).font(.caption).foregroundStyle(.red) }
            HStack {
                if state == "failed" || state == "cancelled" { Button(L("重新发送")) { model.stageAction("stage-outgoing-retry", id) } }
                if !["ready", "completed", "complete", "cancelled"].contains(state) { Button(L("取消发送")) { model.stageAction("stage-outgoing-cancel", id) } }
                if ["failed", "cancelled", "completed", "complete", "ready"].contains(state) { Button(L("清除记录")) { model.clearOutgoing(row) } }
            }.disabled(model.stageBusy.contains(id))
        }.padding(18).frame(maxWidth: .infinity, alignment: .leading).background(Palette.card, in: RoundedRectangle(cornerRadius: 18))
    }
}

// A real AppKit drag source exposes a file URL, and removes the row only after a copy succeeds.
struct StagedDragOut:NSViewRepresentable {
    var model:Model;var id:String
    func makeNSView(context:Context)->StagedDragView { let view=StagedDragView(); view.model=model;view.id=id;return view }
    func updateNSView(_ view:StagedDragView,context:Context) { view.model=model;view.id=id }
}
@MainActor final class StagedDragView:NSView,NSDraggingSource {
    weak var model:Model?;var id="";var path:String?;var down:NSEvent?;var preparing=false
    override init(frame:NSRect) { super.init(frame:frame);let label=NSTextField(labelWithString:"拖到文件夹以保存");label.textColor=NSColor.systemBlue;label.translatesAutoresizingMaskIntoConstraints=false;addSubview(label);NSLayoutConstraint.activate([label.centerXAnchor.constraint(equalTo:centerXAnchor),label.centerYAnchor.constraint(equalTo:centerYAnchor)]);label.isSelectable=false }
    required init?(coder:NSCoder) { fatalError("init(coder:) has not been implemented") }
    override func hitTest(_ point:NSPoint)->NSView? { bounds.contains(convert(point,from:superview)) ? self:nil }
    override func mouseDown(with event:NSEvent) { down=event;preparing=false }
    override func mouseUp(with event:NSEvent) { down=nil }
    override func mouseDragged(with event:NSEvent) {
        guard let down,!preparing,let model else { return }; guard abs(event.locationInWindow.x-down.locationInWindow.x)+abs(event.locationInWindow.y-down.locationInWindow.y)>6 else { return }; preparing=true
        Task { do { let result=try await model.call("stage-ready",["id":id]);guard self.down != nil,NSEvent.pressedMouseButtons&1 != 0,let path=result["path"] as? String else { preparing=false;return };let item=NSDraggingItem(pasteboardWriter:URL(fileURLWithPath:path) as NSURL);item.setDraggingFrame(bounds,contents:NSWorkspace.shared.icon(forFile:path));beginDraggingSession(with:[item],event:event,source:self) } catch { model.notice=error.localizedDescription;preparing=false } }
    }
    func draggingSession(_ session:NSDraggingSession,sourceOperationMaskFor context:NSDraggingContext)->NSDragOperation { .copy }
    func ignoreModifierKeys(for session:NSDraggingSession)->Bool { true }
    func draggingSession(_ session:NSDraggingSession,endedAt point:NSPoint,operation:NSDragOperation) { down=nil;preparing=false;if operation.contains(.copy) { model?.stageAction("stage-remove",id);model?.notice="已保存到目标位置并移出暂存区" } }
}

@MainActor struct DeviceFileDrop:DropDelegate {
    var model:Model;var device:Device
    func validateDrop(info:DropInfo)->Bool { device.online && !device.local && info.hasItemsConforming(to:[UTType.fileURL]) }
    func dropEntered(info:DropInfo) {
        guard validateDrop(info:info),model.stageHoverSessions[device.id]==nil else { return }
        let session=UUID().uuidString;model.stageHoverSessions[device.id]=session
        let providers=info.itemProviders(for:[UTType.fileURL])
        Task { @MainActor in
            var paths:[String]=[]
            for provider in providers {
                let url:URL?=await withCheckedContinuation { continuation in provider.loadItem(forTypeIdentifier:UTType.fileURL.identifier,options:nil) { item,_ in
                    if let url=item as? URL { continuation.resume(returning:url) } else if let data=item as? Data { continuation.resume(returning:URL(dataRepresentation:data,relativeTo:nil)) } else { continuation.resume(returning:nil) }
                } }
                if let url,url.isFileURL { paths.append(url.path) }
            }
            guard model.stageHoverSessions[device.id]==session,!paths.isEmpty else { return }
            do { _=try await model.call("stage-share",["id":device.id,"files":paths,"start":false,"session":session]);model.notice="待传输文件已添加到对方暂存区" } catch { model.notice=error.localizedDescription }
            if model.stageHoverSessions[device.id] != session { _=try? await model.call("stage-drag-end",["session":session]) }
        }
    }
    func dropUpdated(info:DropInfo)->DropProposal? { DropProposal(operation:.copy) }
    func dropExited(info:DropInfo) { end() }
    func performDrop(info:DropInfo)->Bool { end();return false }
    func end() { if let session=model.stageHoverSessions.removeValue(forKey:device.id) { model.perform("stage-drag-end",["session":session]) } }
}

@MainActor extension AppDelegate {
    @objc func shareFiles(_ pasteboard:NSPasteboard,userData:String?,error:AutoreleasingUnsafeMutablePointer<NSString?>) {
        var urls=pasteboard.readObjects(forClasses:[NSURL.self],options:[.urlReadingFileURLsOnly:true]) as? [URL] ?? []
        if urls.isEmpty,let paths=pasteboard.propertyList(forType:NSPasteboard.PasteboardType("NSFilenamesPboardType")) as? [String] { urls=paths.map { URL(fileURLWithPath:$0) } }
        if urls.isEmpty { error.pointee="请选择文件或文件夹";return };model.chooseShareDevice(urls)
    }
}

// Only genuine file URL drags create metadata offers at configured screen edges.
@MainActor final class FileDragEdges {
    weak var model:Model?;var panels:[NSPanel]=[];var signature="";var visibility:Timer?
    init(_ model:Model) { self.model=model;visibility=Timer(timeInterval:0.04,repeats:true){[weak self] _ in Task{@MainActor in guard let self else{return};for panel in self.panels {panel.ignoresMouseEvents=NSEvent.pressedMouseButtons&1==0}}};RunLoop.main.add(visibility!,forMode:.common) }
    func stop() { signature = ""; for panel in panels { panel.close() }; panels = [] }
    func update() {
        guard let model else { return };let next=model.serviceReady && model.isMaster && (model.state["active"] as? String ?? "").isEmpty ? model.edges.sorted { $0.key<$1.key }.map { $0.key+":"+$0.value }.joined(separator:"|"):""
        guard next != signature else { return };signature=next;for panel in panels { panel.close() };panels=[];guard !next.isEmpty else { return }
        for screen in NSScreen.screens { for (edge,id) in model.edges where !id.isEmpty {
            var frame=screen.frame;switch edge {case "left":frame.size.width=4;case "right":frame.origin.x=frame.maxX-4;frame.size.width=4;case "top":frame.origin.y=frame.maxY-4;frame.size.height=4;case "bottom":frame.size.height=4;default:continue}
            let panel=NSPanel(contentRect:frame,styleMask:[.borderless,.nonactivatingPanel],backing:.buffered,defer:false);panel.level = .statusBar;panel.isOpaque=false;panel.backgroundColor=NSColor.black.withAlphaComponent(0.005);panel.hasShadow=false;panel.ignoresMouseEvents=true;panel.collectionBehavior=[.canJoinAllSpaces,.fullScreenAuxiliary];let view=FileEdgeTarget();view.model=model;view.edge=edge;view.id=id;panel.contentView=view;panel.orderFrontRegardless();panels.append(panel)
        } }
    }
}
@MainActor final class FileEdgeTarget:NSView {
    weak var model:Model?;var edge="",id="";var hover=false;var triggered=false;var waiting:Task<Void,Never>?
    override init(frame:NSRect) { super.init(frame:frame);registerForDraggedTypes([.fileURL,NSPasteboard.PasteboardType("NSFilenamesPboardType")]) }
    required init?(coder:NSCoder) { fatalError("init(coder:) has not been implemented") }
    override func draggingEntered(_ sender:NSDraggingInfo)->NSDragOperation {
        guard let model,!triggered else { return [] };let urls=sender.draggingPasteboard.readObjects(forClasses:[NSURL.self],options:[.urlReadingFileURLsOnly:true]) as? [URL] ?? [];guard !urls.isEmpty else { return [] }
        if model.state["switchMode"] as? String=="hotkey" { let required:[String:NSEvent.ModifierFlags] = ["ctrl-alt":[.control,.option],"ctrl-shift":[.control,.shift],"alt-shift":[.option,.shift],"meta-shift":[.command,.shift]];guard NSEvent.modifierFlags.isSuperset(of:required[model.state["edgeHotkey"] as? String ?? "ctrl-alt"] ?? [.control,.option]) else { return [] } }
        hover=true;waiting?.cancel();waiting=Task { try? await Task.sleep(nanoseconds:UInt64(model.state["switchMode"] as? String=="hotkey" ? 0:model.dwell)*1_000_000);guard !Task.isCancelled,hover,!triggered else { return };triggered=true;model.perform("stage-edge",["id":id,"edge":edge,"files":urls.map(\.path)]) };return .copy
    }
    override func draggingUpdated(_ sender:NSDraggingInfo)->NSDragOperation { hover ? .copy:[] }
    override func draggingExited(_ sender:NSDraggingInfo?) { hover=false;waiting?.cancel() }
    override func performDragOperation(_ sender:NSDraggingInfo)->Bool { false }
}
