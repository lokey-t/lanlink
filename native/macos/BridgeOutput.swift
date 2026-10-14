import Foundation

// Screen replies can fill the local pipe. Neither a CGEvent tap callback nor an
// input command may wait for that pipe; the writer alone serializes and flushes.
final class BridgeOutput {
    private struct Motion { let at: Int64; var dx: Double; var dy: Double }
    private struct Item { var value: [String: Any]; let cursor: Bool; var motion: [Motion]?; let completed: (() -> Void)? }
    private let lock = NSLock()
    private let writer = DispatchQueue(label: "com.lanlink.bridge.output")
    private var pending: [Item] = []
    private var head = 0
    private var running = false
    private let write: ([String: Any]) -> Void
    private let now: () -> Int64
    init(now: @escaping () -> Int64 = { Int64(ProcessInfo.processInfo.systemUptime * 1000) }, write: @escaping ([String: Any]) -> Void) { self.now = now; self.write = write }
    func emit(_ value: [String: Any], completed: (() -> Void)? = nil) {
        let cursor = value["type"] as? String == "cursor"
        let movement = value["type"] as? String == "input" && value["kind"] as? String == "move"
        let sample: Motion? = movement ? Motion(at: now(), dx: (value["dx"] as? NSNumber)?.doubleValue ?? 0, dy: (value["dy"] as? NSNumber)?.doubleValue ?? 0) : nil
        lock.lock()
        if let sample, completed == nil, pending.count > head, pending.last?.motion != nil {
            let index = pending.count - 1
            pending[index].motion!.removeAll { sample.at - $0.at > 120 }
            if let last = pending[index].motion!.last, last.at == sample.at {
                let end = pending[index].motion!.count - 1; pending[index].motion![end].dx += sample.dx; pending[index].motion![end].dy += sample.dy
            } else { pending[index].motion!.append(sample) }
        } else if cursor, completed == nil, pending.count > head, pending.last?.cursor == true, pending.last?.completed == nil { pending[pending.count - 1].value = value }
        else { pending.append(Item(value: value, cursor: cursor, motion: sample.map { [$0] }, completed: completed)) }
        if !running { running = true; writer.async { self.drain() } }
        lock.unlock()
    }
    private func drain() {
        while true {
            lock.lock()
            guard head < pending.count else { pending.removeAll(keepingCapacity: true); head = 0; running = false; lock.unlock(); return }
            let item = pending[head]; head += 1
            if head >= 128 { pending.removeFirst(head); head = 0 }
            lock.unlock()
            if let motion = item.motion {
                let at = now(), fresh = motion.filter { at - $0.at <= 120 }
                if !fresh.isEmpty { var value = item.value; value["dx"] = fresh.reduce(0) { $0 + $1.dx }; value["dy"] = fresh.reduce(0) { $0 + $1.dy }; write(value) }
            } else { write(item.value) }
            item.completed?()
        }
    }
}
