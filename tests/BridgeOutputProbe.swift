import Foundation

@main struct BridgeOutputProbe {
    static func main() {
        let entered = DispatchSemaphore(value: 0), resume = DispatchSemaphore(value: 0), done = DispatchSemaphore(value: 0)
        var records: [[String: Any]] = []
        let output = BridgeOutput { value in
            if value["blocked"] as? Bool == true { entered.signal(); resume.wait() }
            records.append(value)
        }
        output.emit(["blocked": true])
        precondition(entered.wait(timeout: .now() + 5) == .success)
        let start = ProcessInfo.processInfo.systemUptime
        for position in 0..<10000 { output.emit(["type": "cursor", "position": position]) }
        for id in 0..<100 { output.emit(["replyTo": id]) }
        output.emit(["type": "cursor", "position": 10000])
        output.emit(["last": true]) { done.signal() }
        let enqueueMs = (ProcessInfo.processInfo.systemUptime - start) * 1000
        precondition(enqueueMs < 500, "input producers waited for the blocked output writer")
        resume.signal()
        precondition(done.wait(timeout: .now() + 5) == .success)
        precondition(records.count == 104, "cursor updates did not coalesce")
        precondition(records[1]["position"] as? Int == 9999)
        precondition(records.compactMap { $0["replyTo"] as? Int } == Array(0..<100), "reliable replies lost order")
        precondition(records[102]["position"] as? Int == 10000, "cursor crossed a reliable barrier")
        var now: Int64 = 0, motions: [[String: Any]] = []
        let blocked = DispatchSemaphore(value: 0), go = DispatchSemaphore(value: 0), flushed = DispatchSemaphore(value: 0)
        let moves = BridgeOutput(now: { now }) { value in
            if value["blocked"] as? Bool == true { blocked.signal(); go.wait() }
            motions.append(value)
        }
        moves.emit(["blocked": true]); precondition(blocked.wait(timeout: .now() + 5) == .success)
        func move(_ dx: Int) { moves.emit(["type": "input", "kind": "move", "dx": dx, "dy": 0]) }
        move(10); now = 100; move(20); now = 150; move(30); moves.emit(["type": "input", "kind": "button"]); now = 200; move(5)
        moves.emit(["flushed": true]) { flushed.signal() }; go.signal(); precondition(flushed.wait(timeout: .now() + 5) == .success)
        precondition(motions.count == 5 && motions[1]["dx"] as? Double == 50 && motions[2]["kind"] as? String == "button" && motions[3]["dx"] as? Double == 5, "raw coalescing crossed a button or replayed expired displacement")
        moves.emit(["blocked": true]); precondition(blocked.wait(timeout: .now() + 5) == .success)
        now = 1000; move(400); now = 2000; moves.emit(["flushed": true]) { flushed.signal() }; go.signal(); precondition(flushed.wait(timeout: .now() + 5) == .success)
        precondition(motions.count == 7, "fully stale raw motion must not replay")
        print("MAC_BRIDGE_OUTPUT_OK enqueueMs=\(enqueueMs) replies=100 cursorUpdates=2 motionBarriers=preserved")
    }
}
