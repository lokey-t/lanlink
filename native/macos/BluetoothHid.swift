import CoreBluetooth
import Foundation

final class BluetoothHid: NSObject, CBPeripheralManagerDelegate {
    private var manager: CBPeripheralManager?
    private var enabled = false
    private var serviceId: UUID?
    private var rendezvousReady = false
    private let challengeId = CBUUID(string: "B3E14843-F7AB-4B94-A10C-C5B2408F2432")
    static let inputId = CBUUID(string: "B3E14844-F7AB-4B94-A10C-C5B2408F2432")
    private var inputCharacteristic: CBMutableCharacteristic?
    private var inputSubscribers: [UUID: CBCentral] = [:]
    private var inputQueue: [(UUID, Data)] = []
    private var challenges: [String: (host:UUID, expiry:Date)] = [:]
    private var descriptor = Data()
    private var reports: [CBMutableCharacteristic] = []
    private var subscribers: [UUID: (central: CBCentral, reports: Set<Int>)] = [:]
    private var registered = false
    private var publishing = false
    private var failureReason = ""
    private var pending: (central: CBCentral, values: [(Int, Data)], reply: (Bool) -> Void)?
    private var resetHosts = Set<UUID>()
    private var dirtyHosts = Set<UUID>()
    private var sendGeneration = 0

    func start(_ descriptor: Data, serviceId: UUID? = nil) {
        enabled = true
        self.serviceId = serviceId
        guard manager == nil else {
            if manager?.state == .poweredOn && !registered && !publishing { publish() }
            return
        }
        self.descriptor = descriptor
        manager = CBPeripheralManager(delegate: self, queue: .main, options: [CBPeripheralManagerOptionShowPowerAlertKey: false])
    }

    private func uuid(_ id: String) -> CBUUID { CBUUID(string: "0000\(id)-0000-1000-8000-00805F9B34FB") }

    private func characteristic(_ id: String, _ properties: CBCharacteristicProperties, _ value: Data? = nil) -> CBMutableCharacteristic {
        CBMutableCharacteristic(type: uuid(id), properties: properties, value: value,
            permissions: properties.contains(.writeWithoutResponse) ? [.writeEncryptionRequired] : [.readEncryptionRequired])
    }

    private func publish() {
        guard enabled, let manager else { return }
        failureReason = ""
        publishing = true
        reports = (1...2).map { id in
            let report = characteristic("2A4D", [.read, .notify, .notifyEncryptionRequired])
            report.descriptors = [CBMutableDescriptor(type: uuid("2908"), value: Data([UInt8(id), 1]))]
            return report
        }
        let service = CBMutableService(type: uuid("1812"), primary: true)
        service.characteristics = [
            characteristic("2A4A", [.read], Data([0x11, 0x01, 0, 0x02])),
            characteristic("2A4B", [.read], descriptor),
            characteristic("2A4C", [.writeWithoutResponse]),
        ] + reports
        manager.add(service)
    }

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        if peripheral.state == .poweredOn {
            if enabled && !registered && !publishing { publish() }
        } else {
            registered = false
            publishing = false
            rendezvousReady = false
            challenges.removeAll()
            subscribers.removeAll()
            inputSubscribers.removeAll()
            inputQueue.removeAll()
            dirtyHosts.removeAll()
            resetHosts.removeAll()
            finish(false)
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        guard enabled && peripheral.state == .poweredOn else { return }
        if service.uuid == uuid("1812") {
            if let error { publishing = false; failureReason = "gatt_\((error as NSError).code)"; return }
            registered = true
            if let serviceId {
                let rendezvous = CBMutableService(type:CBUUID(nsuuid:serviceId), primary:true)
                let input = CBMutableCharacteristic(type:Self.inputId, properties:[.notify], value:nil, permissions:[])
                inputCharacteristic = input
                rendezvous.characteristics = [
                    CBMutableCharacteristic(type:challengeId, properties:[.read], value:nil, permissions:[.readable]),
                    input,
                ]
                peripheral.add(rendezvous)
                return
            }
        } else {
            rendezvousReady = error == nil
        }
        publishing = false
        let advertised = rendezvousReady && serviceId != nil
            ? [CBUUID(nsuuid:serviceId!), CBUUID(string:"1812")] : [uuid("1812")]
        peripheral.startAdvertising([CBAdvertisementDataLocalNameKey:"LanLink", CBAdvertisementDataServiceUUIDsKey:advertised])
    }

    func stop() {
        enabled = false
        reset()
        if let manager, manager.state == .poweredOn {
            manager.stopAdvertising()
            manager.removeAllServices()
        }
        registered = false
        publishing = false
        rendezvousReady = false
        subscribers.removeAll()
        inputSubscribers.removeAll()
        inputQueue.removeAll()
        challenges.removeAll()
    }

    func claim(_ nonce: String) -> UUID? {
        guard let challenge = challenges.removeValue(forKey:nonce), challenge.expiry > Date() else { return nil }
        return challenge.host
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        if let error { failureReason = "advertising_\((error as NSError).code)" }
    }

    func status() -> [String: Any] {
        guard let manager else { return [:] }
        let powered = manager.state == .poweredOn
        let stateReason: String
        switch manager.state {
        case .unknown: stateReason = "initializing"
        case .resetting: stateReason = "resetting"
        case .unsupported: stateReason = "unsupported"
        case .unauthorized: stateReason = "unauthorized"
        case .poweredOff: stateReason = "powered_off"
        case .poweredOn: stateReason = "powered_on"
        @unknown default: stateReason = "unknown"
        }
        var hosts: [[String: Any]] = subscribers.map { id, host in [
            "id": id.uuidString, "hostConnected": true,
            "reportSubscribed": host.reports.count == 2,
            "ready": registered && host.reports.count == 2 && !resetHosts.contains(id),
        ] }
        for id in inputSubscribers.keys where !hosts.contains(where: { $0["id"] as? String == id.uuidString }) {
            hosts.append([
                "id": id.uuidString, "hostConnected": true,
                "reportSubscribed": true, "ready": true, "link": true,
            ])
        }
        var state: [String: Any] = [
            "hidServiceReady": registered, "advertising": manager.isAdvertising,
            "rendezvousReady": registered && rendezvousReady && manager.isAdvertising,
            "reason": failureReason.isEmpty ? stateReason : failureReason,
            "linkReady": !inputSubscribers.isEmpty,
            "hosts": hosts,
        ]
        if powered || manager.state == .poweredOff { state["adapterPresent"] = true }
        if registered || manager.state == .unsupported || failureReason == "gatt_8" {
            state["peripheralSupported"] = registered
        }
        return state
    }

    private func zero(_ index: Int) -> Data { Data(repeating: 0, count: index == 0 ? 4 : 8) }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        if characteristic.uuid == Self.inputId {
            inputSubscribers[central.identifier] = central
            return
        }
        guard let index = reports.firstIndex(where: { $0 === characteristic }) else { return }
        var indices = subscribers[central.identifier]?.reports ?? []
        indices.insert(index)
        subscribers[central.identifier] = (central, indices)
        // A new subscription stays idle. Only retry a release from an interrupted session.
        if indices.count == 2 && resetHosts.contains(central.identifier) {
            peripheralManagerIsReady(toUpdateSubscribers: peripheral)
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        if characteristic.uuid == Self.inputId {
            inputSubscribers.removeValue(forKey: central.identifier)
            return
        }
        guard let index = reports.firstIndex(where: { $0 === characteristic }) else { return }
        subscribers[central.identifier]?.reports.remove(index)
        if subscribers[central.identifier]?.reports.isEmpty == true { subscribers.removeValue(forKey: central.identifier) }
        if dirtyHosts.remove(central.identifier) != nil { resetHosts.insert(central.identifier) }
        if pending?.central.identifier == central.identifier { finish(false) }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        if request.characteristic.uuid == challengeId {
            guard enabled, request.offset == 0 else { peripheral.respond(to:request, withResult:.invalidOffset); return }
            let now = Date()
            challenges = challenges.filter { $0.value.expiry > now && $0.value.host != request.central.identifier }
            guard challenges.count < 64 else { peripheral.respond(to:request, withResult:.insufficientResources); return }
            var random = UUID().uuid
            let bytes = withUnsafeBytes(of:&random) { Data($0) }
            let nonce = bytes.map { String(format:"%02x", $0) }.joined()
            challenges[nonce] = (request.central.identifier, now.addingTimeInterval(12))
            request.value = bytes
            peripheral.respond(to:request, withResult:.success)
            return
        }
        guard let index = reports.firstIndex(where: { $0 === request.characteristic }) else {
            peripheral.respond(to: request, withResult: .attributeNotFound)
            return
        }
        let value = zero(index)
        guard request.offset <= value.count else { peripheral.respond(to: request, withResult: .invalidOffset); return }
        request.value = value.dropFirst(request.offset)
        peripheral.respond(to: request, withResult: .success)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        guard let request = requests.first else { return }
        peripheral.respond(to: request, withResult: request.characteristic.uuid == uuid("2A4C") ? .success : .writeNotPermitted)
    }

    func send(_ id: UUID, values: [(Int, Data)], reply: @escaping (Bool) -> Void) {
        let neutral = values.count == 2 && Set(values.map { $0.0 }) == [0, 1] && values.allSatisfy { $0.1.allSatisfy { $0 == 0 } }
        if neutral && pending?.central.identifier == id { finish(false) }
        guard registered, pending == nil, let host = subscribers[id], host.reports.count == 2,
              neutral || !resetHosts.contains(id) else { reply(false); return }
        dirtyHosts.insert(id)
        sendGeneration += 1
        let generation = sendGeneration
        pending = (host.central, values, { sent in
            if neutral && sent {
                self.dirtyHosts.remove(id)
                self.resetHosts.remove(id)
            }
            reply(sent)
        })
        flush()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.55) { [weak self] in
            guard let self, self.sendGeneration == generation, self.pending != nil else { return }
            self.resetHosts.insert(id)
            self.finish(false)
        }
    }

    func sendInput(host: UUID, data: Data) -> Bool {
        guard let manager, let characteristic = inputCharacteristic else { return false }
        let target: [CBCentral]
        if let central = inputSubscribers[host] { target = [central] }
        else { target = Array(inputSubscribers.values) }
        guard !target.isEmpty else { return false }
        if manager.updateValue(data, for: characteristic, onSubscribedCentrals: target) { return true }
        // CoreBluetooth signals a full transmit queue with false; keep FIFO order and flush on ready.
        inputQueue.append((host, data))
        if inputQueue.count > 128 { inputQueue.removeFirst(inputQueue.count - 128) }
        return true
    }

    func reset() {
        finish(false)
        resetHosts.formUnion(dirtyHosts)
        if let manager { peripheralManagerIsReady(toUpdateSubscribers: manager) }
    }

    private func flush() {
        guard let manager, let job = pending else { return }
        for (offset, report) in job.values.enumerated() {
            if !manager.updateValue(report.1, for: reports[report.0], onSubscribedCentrals: [job.central]) {
                pending?.values = Array(job.values.dropFirst(offset))
                return
            }
        }
        finish(true)
    }

    private func finish(_ sent: Bool) {
        guard let job = pending else { return }
        pending = nil
        job.reply(sent)
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        while let item = inputQueue.first, let characteristic = inputCharacteristic {
            guard let central = inputSubscribers[item.0] ?? inputSubscribers.values.first else {
                inputQueue.removeFirst()
                continue
            }
            if !peripheral.updateValue(item.1, for: characteristic, onSubscribedCentrals: [central]) { break }
            inputQueue.removeFirst()
        }
        for id in Array(resetHosts) {
            guard let host = subscribers[id] else { resetHosts.remove(id); continue }
            if host.reports.allSatisfy({ peripheral.updateValue(zero($0), for: reports[$0], onSubscribedCentrals: [host.central]) }) {
                resetHosts.remove(id)
                dirtyHosts.remove(id)
            }
        }
        flush()
    }
}
