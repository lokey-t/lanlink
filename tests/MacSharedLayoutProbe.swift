import Foundation
import AppKit
import SwiftUI

@main struct MacSharedLayoutProbe {
    @MainActor static func main() async {
        NSApplication.shared.setActivationPolicy(.prohibited)
        let model = Model(), process = Process()
        do {
            try geometry()
            process.executableURL = URL(fileURLWithPath: CommandLine.arguments[1]); process.arguments = [CommandLine.arguments[2]]
            process.standardInput = model.input; process.standardOutput = model.output; process.standardError = model.errors
            model.output.fileHandleForReading.readabilityHandler = { handle in let data = handle.availableData; Task { @MainActor in model.consume(data) } }
            try process.run(); model.process = process
            await model.refresh()
            try require(Set(model.devices.map(\.id)) == Set(["local", "A", "B", "C", "D"]), "inventory must include the complete current group and exclude unrelated known devices")
            try require(model.devices.first { $0.id == "D" }?.online == false, "offline group member must remain available for layout")
            try require(model.layoutPositions["B"] == SharedLayoutCell(x: 2, y: 0), "chain coordinates must not recenter on this computer")
            try require(model.masterID == "A", "master badge must name the same remote master")
            let remoteActions = DeviceActions(model: model, device: model.devices.first { $0.id == "A" }!)
            try require(remoteActions.deviceType == "Windows" && remoteActions.onlineStatus == "未就绪", "device actions must identify the platform and readiness independently of its master badge")
            let limited = Device(id: "limited", name: "Limited", platform: "android", online: true, access: ["status": "有限授权"])
            try require(DeviceActions(model: model, device: limited).onlineStatus == "未授权", "partial authorization must not report fully online")
            try require(DeviceActions(model: model, device: model.devices.first { $0.id == "D" }!).onlineStatus == "离线", "offline devices must report offline in their action menu")
            let before = model.layoutPositions; model.state["masterId"] = "D"
            try require(model.layoutPositions == before, "changing the master must preserve every group cell")
            model.place("D", "bottom", relativeTo: "B")
            try await waitForActions(model, count: 1)
            let layout = SharedLayoutGeometry(size: CGSize(width: 900, height: 520), cells: Array(model.layoutPositions.values))
            model.layoutGeometry = layout; model.layoutContentFrame = CGRect(x: -130, y: -40, width: layout.contentSize.width, height: layout.contentSize.height)
            model.layoutCanvasFrame = CGRect(x: 10, y: 10, width: 900, height: 520)
            let target = SharedLayoutCell(x: 1, y: 1), point = layout.point(target)
            model.layoutDragID = "local"
            model.finishLayoutDrag(CGPoint(x: point.x + model.layoutContentFrame.minX, y: point.y + model.layoutContentFrame.minY))
            try await waitForActions(model, count: 2)
            model.layoutDragID = "C"; model.finishLayoutDrag(CGPoint(x: -400, y: -400))
            try require(model.layoutDragID == nil, "dropping outside the grid must clear the drag")
            model.removeLayout("C"); try await waitForActions(model, count: 3)
            let actions = try await model.call("events")["actions"] as? [[String: Any]] ?? []
            try require(actions.count == 3, "outside drops must not mutate layout")
            try require(actions[0]["method"] as? String == "layout-place", "relative placement must use the incremental group layout action")
            let relative = actions[0]["args"] as? [String: Any] ?? [:], drag = actions[1]["args"] as? [String: Any] ?? [:]
            try require(relative["id"] as? String == "D" && relative["x"] as? Int == 2 && relative["y"] as? Int == 1, "relative controls must branch from the selected non-local neighbour")
            try require(drag["id"] as? String == "local" && drag["x"] as? Int == 1 && drag["y"] as? Int == 1, "drag must convert the scrolled content point and allow moving this computer")
            try require(actions[2]["method"] as? String == "layout-remove", "removal must use the group layout action")
            try require(actions.allSatisfy { ($0["args"] as? [String: Any])?["master"] == nil }, "layout edits must not take over the keyboard master")
            let arranged = model.layoutPositions
            let targetMaster = model.devices.first { $0.id == "B" }!
            await model.chooseLayoutMaster(targetMaster)
            try require(model.masterID == "B", "the explicit master action must update the shared master through settings")
            try require(model.layoutPositions == arranged, "choosing a layout master must not move any card")
            try require(DeviceTile(model: model, device: targetMaster).isLayoutMaster, "new master must show its dedicated badge")
            try require(!DeviceTile(model: model, device: model.devices.first { $0.id == "D" }!).isLayoutMaster, "old master badge must clear")
            await model.chooseLayoutMaster(targetMaster)
            await model.chooseLayoutMaster(model.devices.first { $0.id == "C" }!)
            let masterActions = try await model.call("events")["masterActions"] as? [[String: Any]] ?? []
            try require(masterActions.count == 1 && masterActions[0]["masterId"] as? String == "B", "same-master and mobile clicks must not send master changes")
            model.state["masterId"] = "A"
            try require(!DeviceTile(model: model, device: targetMaster).isLayoutMaster, "incoming remote master changes must clear the previous badge")
            try require(DeviceTile(model: model, device: model.devices.first { $0.id == "A" }!).isLayoutMaster, "incoming remote master changes must move the badge")
            try require(!model.layoutMasterSaving, "master selection must release its busy state")
            model.state["layout"] = ["positions": [String: Any]()]
            try require(model.layoutPositions.isEmpty, "an intentionally empty shared layout must not recreate a local star")
            model.state["masterId"] = "D"
            if let offline = model.devices.first(where: { $0.id == "D" }) { try require(DeviceTile(model: model, device: offline).detail == "主设备 · 离线", "remote offline master must retain its shared badge") }
            if CommandLine.arguments.count > 3 {
                model.state["layout"] = ["positions": ["local": ["x": 0, "y": 0], "A": ["x": 1, "y": 0], "B": ["x": 2, "y": 0], "C": ["x": 1, "y": 1], "D": ["x": 2, "y": 1]]]
                let view = NSHostingView(rootView: LayoutCanvas(model: model).coordinateSpace(name: "deviceLayout").background(Palette.canvas))
                view.frame = CGRect(x: 0, y: 0, width: 1000, height: 600); view.layoutSubtreeIfNeeded()
                if let bitmap = view.bitmapImageRepForCachingDisplay(in: view.bounds) { view.cacheDisplay(in: view.bounds, to: bitmap); try bitmap.representation(using: .png, properties: [:])?.write(to: URL(fileURLWithPath: CommandLine.arguments[3])) }
            }
            model.closing = true; model.output.fileHandleForReading.readabilityHandler = nil; try model.input.fileHandleForWriting.close()
            for _ in 0..<100 { if !process.isRunning { break }; try await Task.sleep(nanoseconds: 10_000_000) }
            if process.isRunning { process.terminate() }
            print("MAC_SHARED_LAYOUT_OK"); exit(0)
        } catch { fputs(error.localizedDescription + "\n", stderr); model.closing = true; if process.isRunning { process.terminate() }; exit(1) }
    }
    @MainActor static func waitForActions(_ model: Model, count: Int) async throws {
        for _ in 0..<100 {
            let result = try await model.call("events")
            if (result["actions"] as? [Any] ?? []).count >= count { return }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        try require(false, "layout action did not reach the host")
    }
    static func geometry() throws {
        let cells = [SharedLayoutCell(x: 0, y: 0), SharedLayoutCell(x: 1, y: 0), SharedLayoutCell(x: 2, y: 0), SharedLayoutCell(x: 1, y: -1), SharedLayoutCell(x: 2, y: 1)]
        let layout = SharedLayoutGeometry(size: CGSize(width: 840, height: 520), cells: cells)
        let limits = [SharedLayoutCell(x: -32, y: -32), SharedLayoutCell(x: 32, y: 32)]
        let extent = SharedLayoutGeometry(size: CGSize(width: 400, height: 250), cells: limits)
        for cell in limits { let point = extent.point(cell); try require(point.x >= 200 && point.x <= extent.contentSize.width - 200 && point.y >= 125 && point.y <= extent.contentSize.height - 125, "grid-limit devices must reach viewport center without moving group cells") }
        for cell in cells { try require(layout.cell(layout.point(cell)) == cell, "every chain and branch cell must round-trip through drag coordinates") }
        try require(layout.point(cells[2]).x > layout.point(cells[1]).x && layout.point(cells[1]).x > layout.point(cells[0]).x, "multi-level neighbours must occupy distinct consecutive positions")
        try require(cells[2].adjacent("bottom") == cells[4], "relative placement must work from any non-local device")
        // Check both directions, scaled cards and the two parallel fusion lines.
        for size in [CGSize(width: 840, height: 520), CGSize(width: 400, height: 250)] {
            let geometry = SharedLayoutGeometry(size: size, cells: cells)
            let origin = SharedLayoutCell(x: 0, y: 0), center = geometry.point(origin)
            for direction in ["left", "right", "top", "bottom"] {
                let neighbour = origin.adjacent(direction)!, other = geometry.point(neighbour)
                let horizontal = origin.y == neighbour.y
                let sign: CGFloat = (horizontal ? other.x - center.x : other.y - center.y) > 0 ? 1 : -1
                let half = (horizontal ? geometry.card.width : geometry.card.height) / 2
                for offset: CGFloat in [-3, 0, 3] {
                    let line = geometry.connection(origin, neighbour, offset: offset)
                    let startDistance = horizontal ? (line.start.x - center.x) * sign : (line.start.y - center.y) * sign
                    let endDistance = horizontal ? (other.x - line.end.x) * sign : (other.y - line.end.y) * sign
                    try require(startDistance > half && endDistance > half, "connection must stop outside both scaled device cards")
                    try require(horizontal ? (line.end.x - line.start.x) * sign > 0 : (line.end.y - line.start.y) * sign > 0, "connection must stay in the gap between cards")
                    let actualOffset = horizontal ? line.start.y - center.y : line.start.x - center.x
                    try require(abs(actualOffset - offset) < 0.001, "fusion connections must preserve their parallel offsets")
                }
            }
        }
        let extent = SharedLayoutGeometry(size: CGSize(width: 400, height: 250), cells: [SharedLayoutCell(x: -32, y: -32), SharedLayoutCell(x: 32, y: 32)])
        try require(extent.contentSize.width > 400 && extent.contentSize.height > 250, "large group extents must scroll instead of shrinking cards indefinitely")
        try require(extent.cell(extent.point(SharedLayoutCell(x: 33, y: 0))) == nil && SharedLayoutCell(x: 32, y: 0).adjacent("right") == nil, "drag and directional placement must respect coordinate bounds")
    }
    static func require(_ condition: Bool, _ message: String) throws { if !condition { throw NSError(domain: "probe", code: 1, userInfo: [NSLocalizedDescriptionKey: message]) } }
}
