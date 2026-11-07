import AppKit
import Charts
import SwiftUI
import UniformTypeIdentifiers

private struct QualityState: Decodable {
    struct Device: Decodable, Identifiable {
        let id: String
        let name: String
        let online: Bool
        let status: String?
        let usbMessage: String?
        let build: String?
        let capabilities: Capabilities?
        let routes: [Route]?

        var preferredRoute: Route? { routes?.first(where: { $0.mouseRoute }) ?? routes?.first }
    }

    struct Capabilities: Decodable { let quality: Bool }
    struct Sample: Decodable { let ms: Double?; let bytes: Int?; let error: String? }
    struct Latency: Decodable {
        let averageMs: Double?
        let peakMs: Double?
        let jitterMs: Double?
        let received: Int
        let attempts: Int
        let timeouts: Int
        let timeoutPercent: Double?
    }
    struct Throughput: Decodable { let mbps: Double? }
    struct Route: Decodable {
        let kind: String
        let protocolName: String
        private enum CodingKeys: String, CodingKey {
            case kind, mouseRoute, latency, mouse, samples, mouseSamples, upload, download, uploadSummary, downloadSummary
            case protocolName = "protocol"
        }
        let mouseRoute: Bool
        let latency: Latency
        let mouse: Latency?
        let samples: [Sample]
        let mouseSamples: [Sample]?
        let upload: [Sample]
        let download: [Sample]
        let uploadSummary: Throughput?
        let downloadSummary: Throughput?
    }
    struct Session: Decodable {
        let status: String
        let scope: String
        let cancelling: Bool?
        let startedAt: String
        let currentDevice: String?
        let stage: String?
        let devices: [Device]
    }

    let devices: [Device]
    let session: Session?
}

struct ConnectionQualityView: View {
    @ObservedObject var model: Model
    @State private var state = QualityState(devices: [], session: nil)
    @State private var selected = ""
    @State private var commandPending = false
    @State private var generation = 0
    @State private var error = ""

    private var running: Bool { state.session?.status == "running" }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            ViewThatFits(in: .horizontal) {
                HStack(alignment: .top) { heading; Spacer(minLength: 8); controls }
                VStack(alignment: .leading, spacing: 8) { heading; controls }
            }
            ScrollView(.horizontal) {
                HStack(spacing: 8) {
                    tab("", L("全部"), icon: "square.grid.2x2")
                    ForEach(state.devices) { device in
                        tab(device.id, device.name, online: device.online)
                    }
                }
            }.scrollIndicators(.hidden)
            statusStrip
            if !error.isEmpty { Text(Model.displayMessage(error)).foregroundStyle(.red) }
            if let session = state.session {
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        if selected.isEmpty {
                            overview(session.devices)
                        } else if let device = session.devices.first(where: { $0.id == selected }) {
                            deviceResult(device)
                        } else {
                            Text(L("最近一次测试未包含此设备。")).foregroundStyle(Palette.muted)
                        }
                    }.frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            Spacer(minLength: 0)
        }
        .buttonStyle(FlatButtonStyle())
        .task {
            while !Task.isCancelled {
                let version = generation
                do {
                    let response = try await model.call("quality-state")
                    if !Task.isCancelled, !commandPending, version == generation { try accept(response) }
                } catch {
                    if !Task.isCancelled, !commandPending { self.error = error.localizedDescription }
                }
                try? await Task.sleep(for: .milliseconds(800))
            }
        }
    }

    private var heading: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(L("连接质量分析")).font(.system(size: 26, weight: .semibold))
            Text(L("从本机到本组设备")).font(.caption).foregroundStyle(Palette.muted)
        }
    }

    private var controls: some View {
        HStack(spacing: 8) {
            Button { Task { await saveReport() } } label: {
                Label(L("另存为文本"), systemImage: "doc")
            }.disabled(running || commandPending || state.session == nil)
            Button {
                let id = selected
                Task {
                    if running { await command("quality-stop") }
                    else if id.isEmpty { await command("quality-start") }
                    else { await command("quality-start", ["id": id]) }
                }
            } label: {
                Label(L(running ? "取消测试" : selected.isEmpty ? "开始测试全组" : "测试此设备"),
                      systemImage: running ? "stop" : "play")
                    .fontWeight(.semibold)
            }
            .buttonStyle(QualityPrimaryButton())
            .disabled(commandPending || (running ? state.session?.cancelling == true : state.devices.isEmpty))
        }.fixedSize(horizontal: true, vertical: false)
    }

    private func tab(_ id: String, _ title: String, icon: String? = nil, online: Bool? = nil) -> some View {
        let active = id == selected
        return Button { selected = id } label: {
            HStack(spacing: 7) {
                if let icon { Image(systemName: icon).font(.caption) }
                if let online { Circle().fill(online ? Self.uploadColor : Palette.muted).frame(width: 6, height: 6) }
                Text(title).lineLimit(1).font(.caption.weight(active ? .semibold : .regular))
            }
            .padding(.horizontal, 10).padding(.vertical, 8)
            .foregroundStyle(active ? Palette.accent : Palette.ink)
            .background(active ? Palette.accent.opacity(0.14) : Color.clear, in: RoundedRectangle(cornerRadius: 10))
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(active ? Color.clear : Palette.line))
        }.buttonStyle(.plain)
            .accessibilityLabel(online == false ? title + " · " + L("离线") : title)
            .accessibilityAddTraits(active ? .isSelected : [])
    }

    private var statusStrip: some View {
        let completed = state.session?.status == "completed"
        return HStack(spacing: 8) {
            Image(systemName: completed ? "checkmark.circle" : "info.circle").accessibilityHidden(true)
            Text(summary)
            Spacer(minLength: 0)
            if !running, let session = state.session {
                Text(L("最近一次") + " · " + localTime(session.startedAt)).foregroundStyle(Palette.muted)
            }
        }
        .font(.caption).foregroundStyle(completed ? Self.uploadColor : Palette.accent)
        .padding(.horizontal, 11).padding(.vertical, 8)
        .background(Palette.canvas, in: RoundedRectangle(cornerRadius: 10))
    }

    private var summary: String {
        guard let session = state.session else { return L("选择设备或测试全组，获取从本机到目标设备的连接数据。") }
        if running {
            let name = session.devices.first(where: { $0.id == session.currentDevice })?.name ?? ""
            return [L(session.cancelling == true ? "正在取消测试" : "测试中"), name, stageLabel(session.stage)].filter { !$0.isEmpty }.joined(separator: " · ")
        }
        let offline = session.devices.filter { $0.status == "offline" }.count
        let scope = session.status == "completed" && session.scope == "group"
            ? LF("{0} 台在线 · {1} 台离线", String(session.devices.count - offline), String(offline))
            : L(session.scope == "device" ? "单台设备" : "全部")
        return [stateLabel(session.status), scope].joined(separator: " · ")
    }

    private func localTime(_ value: String) -> String {
        let date = ISO8601DateFormatter()
        date.formatOptions.insert(.withFractionalSeconds)
        return date.date(from: value)?.formatted(date: .abbreviated, time: .shortened) ?? value
    }

    private func accept(_ response: [String: Any]) throws {
        state = try JSONDecoder().decode(QualityState.self, from: JSONSerialization.data(withJSONObject: response))
        if !state.devices.contains(where: { $0.id == selected }) { selected = "" }
    }

    private func command(_ method: String, _ args: [String: Any] = [:]) async {
        guard !commandPending else { return }
        commandPending = true
        generation += 1
        error = ""
        defer { commandPending = false }
        do { try accept(await model.call(method, args)) }
        catch { self.error = error.localizedDescription }
    }

    private func saveReport() async {
        guard !commandPending else { return }
        commandPending = true
        error = ""
        defer { commandPending = false }
        do {
            let response = try await model.call("quality-export")
            guard let text = response["text"] as? String else { return }
            let panel = NSSavePanel()
            panel.nameFieldStringValue = "LanLink-connection-quality.txt"
            panel.allowedContentTypes = [.plainText]
            let result: NSApplication.ModalResponse = await withCheckedContinuation { continuation in
                panel.begin { continuation.resume(returning: $0) }
            }
            if result == .OK, let url = panel.url { try text.write(to: url, atomically: true, encoding: .utf8) }
        } catch { self.error = error.localizedDescription }
    }

    private func stateLabel(_ value: String?) -> String {
        switch value {
        case "pending": return L("等待测试")
        case "testing": return L("测试中")
        case "completed": return L("已完成")
        case "offline": return L("离线")
        case "unreachable": return L("无法连接")
        case "cancelled": return L("已取消")
        case "failed": return L("测试失败")
        default: return ""
        }
    }

    private func stageLabel(_ value: String?) -> String {
        switch value {
        case "usb": return L("验证 USB 通道")
        case "latency": return L("延迟与稳定性")
        case "mouse": return L("鼠标通道延迟")
        case "upload": return L("上传速度")
        case "download": return L("下载速度")
        default: return ""
        }
    }

    private func metric(_ value: Double?, _ units: String) -> String {
        guard let value else { return "—" }
        return value.formatted(.number.precision(.fractionLength(1))) + (units.isEmpty ? "" : " " + units)
    }

    private static let latencyColor = Palette.adaptive(0x557CCE, 0x99B9FF)
    private static let uploadColor = Palette.adaptive(0x3D8B73, 0x77C7A9)

    private func card<Content: View>(_ title: String, note: String = "", chart: Bool = false, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                Text(title).font(.system(size: 15, weight: .semibold))
                Spacer(minLength: 6)
                if !note.isEmpty { Text(note).font(.caption2).foregroundStyle(Palette.muted) }
            }
            content()
        }
        .padding(14).frame(maxWidth: .infinity, minHeight: chart ? 156 : nil, alignment: .topLeading)
        .background(Palette.card, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Palette.line))
    }

    private func transport(_ route: QualityState.Route?) -> String {
        guard let route else { return "—" }
        if route.kind == "usb" { return "USB · ADB / TCP" }
        return L("局域网") + " · " + (route.protocolName.hasPrefix("QUIC") ? "QUIC" : "TCP")
    }

    private struct Reading {
        let label: String
        let value: Double?
        var upload = false
    }

    private func rates(_ label: String, _ route: QualityState.Route?) -> [Reading] {
        [Reading(label: label + " ↑", value: route?.uploadSummary?.mbps, upload: true),
         Reading(label: label + " ↓", value: route?.downloadSummary?.mbps)]
    }

    private func scaleMax(_ value: Double) -> Double {
        guard value > 0 else { return 1 }
        let step = pow(10, floor(log10(value)))
        return ceil(value / step) * step
    }

    @ViewBuilder private func bars(_ readings: [Reading], units: String) -> some View {
        let rows = readings.filter { $0.value != nil }
        if rows.isEmpty {
            Text(L("暂无测量数据")).font(.caption).foregroundStyle(Palette.muted)
        } else {
            let maximum = scaleMax(rows.compactMap(\.value).max() ?? 0)
            ForEach(Array(rows.enumerated()), id: \.offset) { row in
                let entry = row.element
                let value = entry.value ?? 0
                HStack(spacing: 6) {
                    Text(entry.label).lineLimit(1).frame(width: 83, alignment: .leading).help(entry.label)
                    GeometryReader { area in
                        Capsule().fill(Palette.canvas)
                            .overlay(alignment: .leading) {
                                Capsule().fill(entry.upload ? Self.uploadColor : Self.latencyColor)
                                    .frame(width: area.size.width * CGFloat(value / maximum))
                            }
                    }.frame(height: 7).accessibilityHidden(true)
                    Text(metric(value, "")).monospacedDigit().frame(width: 42, alignment: .trailing)
                }.font(.caption).accessibilityElement(children: .ignore)
                    .accessibilityLabel(entry.label + " · " + metric(value, units))
            }
            HStack {
                Text("0"); Spacer(minLength: 0)
                Text((maximum / 2).formatted()); Spacer(minLength: 0)
                Text(maximum.formatted())
            }.font(.caption2).foregroundStyle(Palette.muted).padding(.leading, 89).padding(.trailing, 48)
                .accessibilityHidden(true)
        }
    }

    private func chartPair<Left: View, Right: View>(@ViewBuilder left: () -> Left, @ViewBuilder right: () -> Right) -> some View {
        HStack(alignment: .top, spacing: 12) { left(); right() }
    }

    private func tableRow(_ labels: [String], header: Bool = false, badge: String? = nil, action: (() -> Void)? = nil) -> some View {
        HStack(alignment: .center, spacing: 8) {
            ForEach(Array(labels.enumerated()), id: \.offset) { entry in
                Group {
                    if entry.offset == 0, let action {
                        Button(action: action) { Text(entry.element).foregroundStyle(Palette.accent) }.buttonStyle(.plain)
                    } else if entry.offset == labels.count - 1, let badge {
                        statusBadge(badge)
                    } else {
                        Text(entry.element).foregroundStyle(header ? Palette.muted : Palette.ink)
                    }
                }.frame(maxWidth: .infinity, alignment: .leading)
            }
        }.font(.caption).padding(.vertical, 3)
    }

    private func statusBadge(_ status: String) -> some View {
        let complete = status == "completed"
        return Text(stateLabel(status)).font(.caption2)
            .foregroundStyle(complete ? Self.uploadColor : Palette.muted)
            .padding(.horizontal, 7).padding(.vertical, 3)
            .background(complete ? Self.uploadColor.opacity(0.12) : Palette.canvas, in: Capsule())
    }

    private func overview(_ devices: [QualityState.Device]) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            chartPair {
                card(L("鼠标通道延迟"), note: "ms", chart: true) {
                    bars(devices.map { Reading(label: $0.name, value: $0.preferredRoute?.mouse?.averageMs) }, units: "ms")
                }
            } right: {
                card(L("传输速度"), note: "↑ " + L("上传") + "  ↓ " + L("下载") + " · Mb/s", chart: true) {
                    bars(devices.flatMap { rates($0.name, $0.preferredRoute) }, units: "Mb/s")
                }
            }
            card(L("设备明细"), note: L("显示当前通道")) {
                tableRow([L("设备"), L("连接通道"), L("延迟") + " · ms", L("上传 / 下载") + " · Mb/s", L("状态")], header: true)
                ForEach(devices) { device in
                    Divider()
                    let route = device.preferredRoute
                    let rate = metric(route?.uploadSummary?.mbps, "") + " / " + metric(route?.downloadSummary?.mbps, "")
                    tableRow([device.name, transport(route), metric(route?.mouse?.averageMs, ""), rate, ""],
                             badge: device.status) { selected = device.id }
                }
            }
        }
    }

    private func deviceResult(_ device: QualityState.Device) -> some View {
        let routes = device.routes ?? []
        return VStack(alignment: .leading, spacing: 12) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text(device.name).font(.headline)
                    if let usb = device.usbMessage { Text(L(usb)).font(.caption).foregroundStyle(Palette.muted) }
                }
                Spacer()
                if let status = device.status { statusBadge(status) }
            }
            if !routes.isEmpty {
                chartPair {
                    card(L("往返延迟趋势"), note: "ms", chart: true) { trend(routes) }
                } right: {
                    card(L("实测传输速度"), note: "↑ " + L("上传") + "  ↓ " + L("下载") + " · Mb/s", chart: true) {
                        bars(routes.flatMap { rates($0.kind == "usb" ? "USB" : transport($0), $0) }, units: "Mb/s")
                    }
                }
                card(L("通道稳定性")) {
                    tableRow([L("连接通道"), L("平均") + " · ms", L("峰值") + " · ms", L("抖动") + " · ms", L("超时")], header: true)
                    ForEach(Array(routes.enumerated()), id: \.offset) { item in
                        let route = item.element
                        let stats = route.latency
                        Divider()
                        tableRow([transport(route), metric(stats.averageMs, ""), metric(stats.peakMs, ""),
                                  metric(stats.jitterMs, ""), "\(stats.timeouts) / \(stats.attempts)"])
                    }
                    DisclosureGroup(L("查看诊断明细")) {
                        diagnostics(device, routes)
                    }.font(.caption).id(device.id)
                }
            }
        }
    }

    private func diagnostics(_ device: QualityState.Device, _ routes: [QualityState.Route]) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            if let build = device.build { Text(L("对端构建") + " · " + build) }
            ForEach(Array(routes.enumerated()), id: \.offset) { entry in
                let route = entry.element
                let errors = Set((route.samples + (route.mouseSamples ?? []) + route.upload + route.download).compactMap { $0.error })
                Text(transport(route) + " · " + route.protocolName).fontWeight(.semibold)
                Text(L("响应 / 总次数") + " \(route.latency.received)/\(route.latency.attempts) · " + L("鼠标 RTT") + " " + metric(route.mouse?.averageMs, "ms"))
                if !errors.isEmpty { Text(L("诊断代码") + " · " + errors.sorted().joined(separator: ", ")) }
                diagnosticTrend("鼠标通道延迟", samples: route.mouseSamples ?? [], usb: route.kind == "usb")
                diagnosticTrend("上传速度", samples: route.upload, usb: route.kind == "usb", speed: true)
                diagnosticTrend("下载速度", samples: route.download, usb: route.kind == "usb", speed: true)
            }
            if device.capabilities?.quality == false { Text(L("对端版本仅支持基础延迟测试。")) }
        }.frame(maxWidth: .infinity, alignment: .leading).foregroundStyle(Palette.muted).padding(.top, 6)
    }

    private struct Point: Identifiable {
        let id: String
        let sample: Int
        let value: Double
        let route: Int
        let segment: Int
        let usb: Bool
    }

    @ViewBuilder private func trend(_ routes: [QualityState.Route]) -> some View {
        let points = routes.enumerated().flatMap { index, route in
            trendPoints(route.samples, route: index, usb: route.kind == "usb")
        }
        if points.isEmpty {
            Text(L("暂无测量数据")).font(.caption).foregroundStyle(Palette.muted)
        } else {
            plot(points, units: "ms")
            HStack(spacing: 8) {
                if points.contains(where: { !$0.usb }) { Text("━ " + L("局域网")).foregroundStyle(Self.latencyColor) }
                if points.contains(where: { $0.usb }) { Text("┄ USB").foregroundStyle(Self.uploadColor) }
            }.font(.caption2)
        }
    }

    @ViewBuilder private func diagnosticTrend(_ title: String, samples: [QualityState.Sample], usb: Bool, speed: Bool = false) -> some View {
        let points = trendPoints(samples, route: 0, usb: usb, speed: speed)
        if !points.isEmpty {
            let units = speed ? "Mb/s" : "ms"
            Text(L(title) + " · " + units)
            plot(points, units: units)
        }
    }

    private func plot(_ points: [Point], units: String) -> some View {
        Chart(points) { point in
            LineMark(x: .value(L("采样"), point.sample), y: .value(units, point.value),
                     series: .value(L("连接通道"), "\(point.route)-\(point.segment)"))
                .foregroundStyle(point.usb ? Self.uploadColor : Self.latencyColor)
                .lineStyle(StrokeStyle(lineWidth: 2, dash: point.usb ? [4, 3] : []))
            PointMark(x: .value(L("采样"), point.sample), y: .value(units, point.value))
                .foregroundStyle(point.usb ? Self.uploadColor : Self.latencyColor).symbolSize(18)
        }
        .chartYScale(domain: 0...scaleMax(points.map(\.value).max() ?? 0))
        .chartXAxis { AxisMarks(values: .automatic(desiredCount: 4)) }
        .frame(height: 108)
    }

    private func trendPoints(_ samples: [QualityState.Sample], route: Int, usb: Bool, speed: Bool = false) -> [Point] {
        var segment = 0
        return samples.enumerated().compactMap { index, sample in
            guard let ms = sample.ms else { segment += 1; return nil }
            return Point(id: "\(route)-\(index)", sample: index + 1, value: speed ? Double(sample.bytes ?? 0)*0.008/max(0.01,ms) : ms, route: route, segment: segment, usb: usb)
        }
    }
}

private struct QualityPrimaryButton: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 13))
            .padding(.horizontal, 12).padding(.vertical, 8)
            .foregroundStyle(Palette.accent)
            .background(Palette.accent.opacity(configuration.isPressed ? 0.22 : 0.13), in: RoundedRectangle(cornerRadius: 10))
            .opacity(enabled ? 1 : 0.45)
    }
}
