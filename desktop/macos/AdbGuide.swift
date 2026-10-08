import SwiftUI
import AppKit

struct LocalAdbAuthorizationView:View {
    @ObservedObject var model:Model;@State var devices:[[String:Any]]=[];@State var serial="";@State var busy=false;@State var message=""
    func refresh() async{guard !busy else{return};busy=true;defer{busy=false};do{let r=try await model.call("adb-local-devices");devices=r["devices"] as? [[String:Any]] ?? [];serial="";message=r["message"] as? String ?? ""}catch{message=error.localizedDescription}}
    func authorize() async{guard !busy,!serial.isEmpty else{return};busy=true;defer{busy=false};do{let r=try await model.call("adb-local-authorize",["serial":serial]);message=r["message"] as? String ?? "已就绪";serial=""}catch{message=error.localizedDescription}}
    var body:some View{VStack(alignment:.leading,spacing:20){
        HStack{Text("安卓设备ADB授权").font(.title2.bold());Spacer();Button("返回设备"){model.adbDevice=nil}}
        Text("帮助其他组的设备完成 ADB 配置，无需加入本组。请在手机打开新版 LanLink，开启 USB 调试并信任此电脑。").foregroundStyle(.secondary)
        ScrollView{VStack(alignment:.leading,spacing:14){ForEach(devices.indices,id:\.self){i in let d=devices[i];let id=d["serial"] as? String ?? "";let ready=d["authorized"] as? Bool == true
            Button{serial=id}label:{HStack{Image(systemName:serial==id ? "largecircle.fill.circle":"circle");Text((d["model"] as? String ?? "Android")+" · "+id);Spacer();Text(ready ? "已就绪":d["status"] as? String == "device" ? "可授权":"等待 USB 调试")}}.disabled(ready || d["status"] as? String != "device" || busy)
        }}}
        Text(message).font(.callout).foregroundStyle(.secondary)
        HStack{Button("刷新设备"){Task{await refresh()}}.disabled(busy);Spacer();Button("为选中的手机授权"){Task{await authorize()}}.disabled(serial.isEmpty || busy)}
    }.buttonStyle(FlatButtonStyle()).task{await refresh()}}
}

struct AdbAuthorizationView: View {
    @ObservedObject var model:Model;let device:Device;var guide=false
    @State var targetId="";@State var connected=false;@State var busy=false;@State var message="等待数据线连接"
    var id:String {device.id.isEmpty ? targetId : device.id}
    func scan() async {guard !busy,!id.isEmpty else{return};busy=true;defer{busy=false};do{let r=try await model.call("adb-match",["id":id]);connected=r["connected"] as? Bool == true;message=r["message"] as? String ?? "等待连接"}catch{connected=false;message=error.localizedDescription}}
    func connect() async {guard connected,!busy else{return};busy=true;defer{busy=false};do{let r=try await model.call("adb-connect",["id":id]);message=r["message"] as? String ?? "ADB 已就绪"}catch{message=error.localizedDescription}}
    var body:some View {VStack(alignment:.leading,spacing:20){
        HStack {Text(guide ? "连接一次，自由控制":"ADB 键鼠授权").font(.title2.bold());Spacer();if !guide{Button("返回设备"){model.adbDevice=nil;model.sidebarPage="adb"}}}
        if device.id.isEmpty {Picker("本组手机",selection:$targetId){Text("请选择组内手机").tag("");ForEach(model.devices.filter{$0.platform=="android" && $0.online}){Text($0.name).tag($0.id)}}.onChange(of:targetId){_,_ in connected=false;Task{await scan()}}}
        ScrollView {VStack(alignment:.leading,spacing:18){
            Text("为「"+device.name+"」准备 ADB 连接").font(.headline)
            Text("01  开启 USB 调试\n手机设置 → 关于手机，连续点击系统版本开启开发者选项，再打开 USB 调试。\n\n02  用数据线连接电脑\n解锁手机，允许 USB 调试并勾选始终允许此电脑，保持手机 LanLink 在线。\n\n03  自动匹配，点击连接\n核对组内设备身份后，点击连接自动配置 ADB，无需选择调试设备。").lineSpacing(7)
            Text("小米等机型如出现系统输入失败，还需开启 USB 调试（安全设置）。").font(.caption).foregroundStyle(.secondary)
            Text("助手就绪后，同组其他电脑可直接使用，无需重复连接数据线。手机重启后，连接已信任的电脑恢复助手即可。系统 USB 调试信任按电脑分别保存，新电脑恢复助手时仍需手机确认。").font(.callout).padding(16).background(Color.blue.opacity(0.06),in:RoundedRectangle(cornerRadius:14))
        }.frame(maxWidth:.infinity,alignment:.leading)}
        Text(message).font(.callout).foregroundStyle(.secondary).fixedSize(horizontal:false,vertical:true)
        HStack {if guide{Button("取消"){model.adbGuide?.window.close()}};Spacer();Button("检测连接"){Task{await scan()}}.disabled(busy);Button(busy ? "正在处理…":"连接"){Task{await connect()}}.disabled(busy || !connected)}
    }.buttonStyle(FlatButtonStyle()).task {while !Task.isCancelled {await scan();try? await Task.sleep(for:.seconds(6))}}}
}

@MainActor final class ConsentDisclosure:NSObject {
    let alert:NSAlert;let stack:NSStackView;let checks:[NSButton];var expanded=false
    init(alert:NSAlert,stack:NSStackView,checks:[NSButton]){self.alert=alert;self.stack=stack;self.checks=checks;super.init()}
    @objc func toggle(){expanded.toggle();for c in checks{c.isHidden = !expanded};stack.frame.size.height=expanded ? 178:32;alert.layout()}
}
extension Model {
    func restricted(_ device:Device,_ keys:[String])->Bool {
        guard let p=device.access["permissions"] as? [String:[String:Any]] else{return true}
        return keys.contains{p[$0]?["peerAllowed"] as? Bool != true || p[$0]?["systemGranted"] as? Bool != true || p[$0]?["appEnabled"] as? Bool != true}
    }
    func cancelFeature(_ id:String){if pendingFeatureID==id{pendingFeatureID=nil;pendingFeature=nil}}
    func resumeFeature(_ id:String){if pendingFeatureID==id{pendingFeature?()}}
    func feature(_ device:Device,_ keys:[String],_ action:@escaping ()->Void) {
        if let id=pendingFeatureID,id != device.id{notice="请先完成或取消当前设备的连接请求";return}
        pendingFeatureID=device.id;pendingFeature={ [weak self] in self?.feature(device,keys,action) }
        Task {do{
            let result=try await call("feature-prepare",["id":device.id,"permissions":keys])
            guard result["ready"] as? Bool == true else{await refresh();return}
            if result["allowed"] as? Bool == true{cancelFeature(device.id);action();return}
            let alert=NSAlert();alert.messageText="需要设备授权";alert.informativeText="请在「"+device.name+"」上授权此功能。";alert.addButton(withTitle:"发起授权");alert.addButton(withTitle:"取消")
            if alert.runModal() == .alertFirstButtonReturn{_ = try await call("access-request",["id":device.id,"permissions":keys]);await refresh()}else{cancelFeature(device.id)}
        }catch{cancelFeature(device.id);notice=error.localizedDescription}}
    }
}

@MainActor final class AccessController:NSObject,NSWindowDelegate {
    let model:Model;let request:[String:Any];let window:NSWindow;var task:Task<Void,Never>?;var done=false;var closed=false
    var requestID:String{request["requestId"] as? String ?? ""}
    init(model:Model,request:[String:Any]){self.model=model;self.request=request;window=NSWindow(contentRect:NSRect(x:0,y:0,width:420,height:220),styleMask:[.titled,.closable],backing:.buffered,defer:false);super.init();window.title="等待设备授权";window.delegate=self;window.isReleasedWhenClosed=false
        window.contentView=NSHostingView(rootView:VStack(alignment:.leading,spacing:24){Text("请在被控设备允许本设备控制").font(.title3.bold());Text("请打开「"+(request["name"] as? String ?? "设备")+"」上的 LanLink，确认授权请求。");Button("取消"){self.window.close()}}.padding(24).background(Palette.surface));window.center();window.makeKeyAndOrderFront(nil)
        task=Task{[weak self] in while !Task.isCancelled{guard let self,!self.closed else{return};await self.check();try? await Task.sleep(for:.seconds(1))}}
    }
    func check() async {do{let s=try await model.call("access-status",["requestId":requestID]);guard !closed else{return};if s["ended"] as? Bool == true{window.close();return};if s["ready"] as? Bool == true{let intent=try await model.call("access-complete",["requestId":requestID]);guard !closed || done else{return};end();if intent["capture"] as? Bool == true{_ = try await model.call("control",["id":intent["id"] ?? "","capture":true,"edge":intent["edge"] ?? "right","newSession":true])}else{model.resumeFeature(request["id"] as? String ?? "")}}}catch{model.notice=error.localizedDescription}}
    func end(){done=true;window.close()}
    func windowWillClose(_ notification:Notification){closed=true;task?.cancel();task=nil;if !done{model.cancelFeature(request["id"] as? String ?? "");if model.remoteID == request["id"] as? String{model.autoControl=false;model.remotePrepared=false;model.controlling=false;model.resumeRemote=false;model.controlVersion+=1};model.perform("access-cancel",["requestId":requestID])};if model.accessController === self{model.accessController=nil};window.contentView=nil}
}

// Render the same restricted SVG paths as Windows with native SwiftUI vectors.
final class AdbSvg: NSObject, XMLParserDelegate {
    var paths: [(Path, Color, Bool)] = []
    init(asset:String="adb-connect.svg") {
        super.init()
        if let parser = XMLParser(contentsOf: Bundle.main.resourceURL!.appendingPathComponent("src/assets/"+asset)) { parser.delegate = self; parser.shouldResolveExternalEntities = false; parser.parse() }
    }
    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName qName: String?, attributes: [String: String]) {
        guard elementName == "path", let d = attributes["d"], let stroke = attributes["stroke"] else { return }
        let tokens = d.split(whereSeparator: { $0 == " " || $0 == "," }).map(String.init)
        var index = 0; var path = Path()
        func point() -> CGPoint? { guard index+1 < tokens.count, let x = Double(tokens[index]), let y = Double(tokens[index+1]) else { return nil }; index += 2; return CGPoint(x:x, y:y) }
        while index < tokens.count {
            let command = tokens[index]; index += 1
            guard let p = point() else { return }
            if command == "M" { path.move(to:p) }
            else if command == "L" { path.addLine(to:p) }
            else if command == "Q", let end = point() { path.addQuadCurve(to:end, control:p) }
            else { return }
        }
        let value = UInt32(stroke.dropFirst(), radix:16) ?? 0x7185A3
        paths.append((path,Color(red:Double((value>>16)&255)/255,green:Double((value>>8)&255)/255,blue:Double(value&255)/255),attributes["id"] == "signal"))
    }
}
struct AdbMotion: View {
    @Environment(\.accessibilityReduceMotion) var reduced
    let svg:AdbSvg
    init(asset:String="adb-connect.svg"){svg=AdbSvg(asset:asset)}
    var body: some View {
        TimelineView(.animation(minimumInterval:1.0/30, paused:reduced)) { time in
            Canvas { context, size in
                let scale = min(size.width/400,size.height/110)
                context.translateBy(x:(size.width-400*scale)/2,y:0); context.scaleBy(x:scale,y:scale)
                let phase = reduced ? 0.5 : time.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy:1.8)/1.8
                for (path,color,signal) in svg.paths {
                    var layer = context
                    if signal { layer.translateBy(x:80*phase,y:0); layer.opacity=0.25+0.75*sin(phase * .pi) }
                    layer.stroke(path,with:.color(color),style:StrokeStyle(lineWidth:3,lineCap:.round,lineJoin:.round))
                }
            }
        }.frame(height:110).accessibilityHidden(true)
    }
}
@MainActor final class AdbGuideController: NSObject, NSWindowDelegate {
    let model: Model; let request: [String:Any]; let window: NSWindow
    var task: Task<Void,Never>?; var completed=false; var closed=false;var revoked=false;var restorePending=false
    var requestID: String { request["requestId"] as? String ?? "" }
    var deviceID: String { request["id"] as? String ?? "" }
    init(model:Model,request:[String:Any]) {
        self.model=model; self.request=request
        window=NSWindow(contentRect:NSRect(x:0,y:0,width:620,height:740),styleMask:[.titled,.closable],backing:.buffered,defer:false)
        super.init(); window.title="连接手机 · ADB 引导"; window.isReleasedWhenClosed=false; window.delegate=self
        showStage(request["revoked"] as? Bool == true)
        window.center(); window.makeKeyAndOrderFront(nil)
        task=Task { [weak self] in
            while !Task.isCancelled {
                guard let self, !self.closed else { return }
                await self.check()
                try? await Task.sleep(for:.seconds(2))
            }
        }
    }
    func showStage(_ value:Bool){revoked=value;if value{restorePending=true}
        let device=Device(id:deviceID,name:request["name"] as? String ?? "Android 手机",platform:"android",online:true)
        window.contentView=NSHostingView(rootView:VStack(alignment:.leading,spacing:16){if value{Text("请在手机上允许 ADB 授权").font(.title2.bold());AdbMotion(asset:"adb-allow.svg");Text("之前的 ADB 授权已被解除。请在手机 LanLink 弹窗中点击「允许 ADB 授权」，并保持数据线连接。允许后自动继续。");Button("取消"){self.window.close()}}else{AdbMotion();AdbAuthorizationView(model:model,device:device,guide:true)}}.padding(22).background(Palette.surface))
    }
    func check() async {
        do {
            let state=try await model.call("adb-guide-status",["requestId":requestID]); guard !closed else { return }
            if state["ended"] as? Bool == true { end();return }
            let current=state["revoked"] as? Bool == true;if current != revoked{showStage(current)}
            if revoked{return}
            if restorePending && state["ready"] as? Bool != true{let match=try await model.call("adb-match",["id":deviceID]);if match["connected"] as? Bool == true{restorePending=false;_ = try await model.call("adb-connect",["id":deviceID])};return}
            if state["ready"] as? Bool == true {
                let intent=try await model.call("adb-guide-complete",["requestId":requestID]);guard !closed || completed else {return};end()
                if intent["capture"] as? Bool == true { _ = try await model.call("control",["id":deviceID,"capture":true,"edge":intent["edge"] as? String ?? "right","newSession":intent["newSession"] as? Bool ?? false]) }
                else if intent["feature"] as? Bool == true {model.resumeFeature(deviceID)}
                else if intent["setupOnly"] as? Bool != true { model.remoteWindow?.makeKeyAndOrderFront(nil) }
            }
        } catch { model.notice=error.localizedDescription }
    }
    func end() { completed=true;window.close() }
    func windowWillClose(_ notification:Notification) {
        closed=true;task?.cancel();task=nil
        if !completed {
            model.cancelFeature(deviceID)
            if model.remoteID == deviceID { model.autoControl=false;model.remotePrepared=false;model.controlling=false;model.resumeRemote=false;model.controlVersion += 1 }
            model.perform("adb-guide-cancel",["requestId":requestID])
        }
        if model.adbGuide === self { model.adbGuide=nil }
    }
}
