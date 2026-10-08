import Foundation
import AppKit

@main struct MacRemoteProbe {
    @MainActor static func main() async {
        let application = NSApplication.shared
        application.setActivationPolicy(.prohibited)
        let model = Model(), process = Process()
        process.executableURL = URL(fileURLWithPath: CommandLine.arguments[1])
        process.arguments = Array(CommandLine.arguments.dropFirst(2))
        process.standardInput = model.input; process.standardOutput = model.output; process.standardError = model.errors
        model.output.fileHandleForReading.readabilityHandler = { handle in
            let bytes = handle.availableData
            Task { @MainActor in model.consume(bytes) }
        }
        do {
            try process.run(); model.process = process
            let a = Device(id: "A", name: "Android A", platform: "android")
            let b = Device(id: "B", name: "Desktop B", platform: "win32")
            let c = Device(id: "C", name: "Android C", platform: "android")
            let d = Device(id: "D", name: "Desktop D", platform: "darwin")
            model.openRemote(a)
            for _ in 0..<100 { if model.preparingControl { break }; try await Task.sleep(nanoseconds: 10_000_000) }
            require(model.preparingControl, "first control request was not started")
            model.openRemote(b); model.openRemote(c)
            for _ in 0..<200 { if model.remoteID == "C" && model.controlling { break }; try await Task.sleep(nanoseconds: 10_000_000) }
            require(model.remoteID == "C" && model.controlling, "latest selected target must win after delayed acquire")
            require(model.remoteWindow?.title == "桌面控制 · Android C", "window must show the selected device")
            let window = model.remoteWindow
            model.openRemote(c)
            require(model.remoteWindow === window, "same target must reuse its window")
            model.sendInput(["kind": "move", "x": 0.5, "y": 0.5])
            try await Task.sleep(nanoseconds: 40_000_000)
            model.openRemote(d)
            for _ in 0..<200 { if model.remoteID == "D" && model.controlling { break }; try await Task.sleep(nanoseconds: 10_000_000) }
            require(model.remoteID == "D" && model.controlling, "old input error must not release the new target")
            require(!model.controlConflict, "old device error must not mark the new session conflicted")
            model.closeRemote(); await model.remoteTeardown?.value
            require(model.remoteID == nil && model.remoteWindow == nil, "closing must clear the current target")
            try model.input.fileHandleForWriting.close()
            for _ in 0..<100 { if !process.isRunning { break }; try await Task.sleep(nanoseconds: 10_000_000) }
            if process.isRunning { process.terminate() }
            model.output.fileHandleForReading.readabilityHandler = nil
            print("MAC_REMOTE_PROBE_OK")
            exit(0)
        } catch { fputs(error.localizedDescription + "\n", stderr); if process.isRunning { process.terminate() }; exit(1) }
    }
    static func require(_ condition: Bool, _ message: String) {
        if !condition { fputs(message + "\n", stderr); exit(1) }
    }
}
