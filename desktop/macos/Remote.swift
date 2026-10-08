import SwiftUI
import AppKit

struct RemoteView: View {
    @ObservedObject var model:Model;@State var text="";@State var textPopover=false
    func start(){model.autoControl=true;model.freshControl=true;model.fallbackAccepted=false;model.controlConflict=false;Task{await model.beginRemoteControl()}}
    func paste(){if let id=model.remoteID{model.perform("paste",["id":id])}}
    func sendText(){if let id=model.remoteID{model.perform("text",["id":id,"text":text]);text="";textPopover=false}}
    var metrics:some View{VStack(alignment:.trailing,spacing:4){Text(model.controlling ? "正在控制":"仅查看");Text(model.remoteCodec);Text(String(format:"%.0f fps",model.remoteFPS));Text(model.remoteBitrate>0 ? String(format:"%.1f Mbps",model.remoteBitrate/1_000_000):"-- Mbps")}.font(.system(size:10)).foregroundStyle(.gray).lineLimit(1).truncationMode(.tail).frame(width:58,alignment:.trailing).clipped().help(model.notice)}
    var body:some View{HStack(spacing:0){
        VStack(spacing:4){
            RemoteSurface(model:model).frame(maxWidth:.infinity,maxHeight:.infinity).background(.black).clipped()
                .overlay { if model.screen == nil { VStack(spacing: 14) { if !model.controlConflict { ProgressView() }; Text("正在连接设备画面").font(.headline); Text(model.notice).font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true) }.padding(24).frame(maxWidth: 300) } }
            ZStack(alignment:.trailing){
                if model.remotePhone{HStack(spacing:4){ForEach(["back","home","recents"],id:\.self){action in RemoteNavigationTool(symbol:["back":"chevron.left","home":"circle","recents":"square"][action]!,title:["back":"返回","home":"主页","recents":"最近任务"][action]!){model.sendInput(["kind":"navigation","action":action])}.disabled(!model.controlling)}}.frame(maxWidth:.infinity)}
                Button(action:model.toggleRemoteSidebar){Image(systemName:model.remoteSidebarCollapsed ? "chevron.right":"chevron.left").frame(width:28,height:40)}.buttonStyle(.plain).foregroundStyle(.gray).help(model.remoteSidebarCollapsed ? "展开侧栏":"收起侧栏")
            }.frame(maxWidth:.infinity).frame(height:model.remotePhone ? 48:40)
        }
        VStack(spacing:4){
            RemoteTool(symbol:"play",title:"开始控制",action:start)
            RemoteTool(symbol:"pause",title:"停止键鼠控制",action:model.release)
            RemoteTool(symbol:"doc.on.clipboard",title:"粘贴",action:paste).disabled(!model.controlling)
            RemoteTool(symbol:"keyboard",title:"输入文字"){textPopover=true}.disabled(!model.controlling).popover(isPresented:$textPopover){VStack(alignment:.leading,spacing:12){Text("发送文字").font(.headline);TextField("输入文本",text:$text);Button("发送",action:sendText)}.padding(18).frame(width:260)}
            if model.remotePhone{RemoteTool(symbol:"cable.connector",title:"ADB 授权"){if let id=model.remoteID{model.perform("adb-guide-open",["id":id])}}}
            Spacer(minLength:8);metrics.padding(.bottom,6)
        }.frame(width:64).padding(.bottom,model.remotePhone ? 52:44).frame(width:model.remoteSidebarExtent,alignment:.leading).clipped().disabled(model.remoteSidebarCollapsed).accessibilityHidden(model.remoteSidebarCollapsed)
    }.padding(4).frame(minWidth:(model.remotePhone ? 270:320)+model.remoteSidebarExtent,minHeight:360).background(.black).preferredColorScheme(.dark)}
}
struct RemoteNavigationTool: View {
    let symbol: String; let title: String; let action: () -> Void
    var body: some View { Button(action: action) { Image(systemName: symbol).font(.system(size: 26)).frame(width: 64, height: 46).contentShape(RoundedRectangle(cornerRadius: 10)) }.buttonStyle(.plain).foregroundStyle(.secondary).help(title).accessibilityLabel(title) }
}
struct RemoteTool: View {
    let symbol: String; let title: String; let action: () -> Void
    var body: some View { Button(action: action) { Image(systemName: symbol).font(.system(size: 17)).frame(width: 40, height: 38).contentShape(RoundedRectangle(cornerRadius: 10)) }.buttonStyle(.plain).foregroundStyle(Color(red: 0.36, green: 0.44, blue: 0.57)).help(title).accessibilityLabel(title) }
}
struct RemoteSurface: NSViewRepresentable {
    @ObservedObject var model: Model
    func makeNSView(context: Context) -> InputSurface { let view = InputSurface(); view.model = model; model.remoteSurface=view; return view }
    func updateNSView(_ view: InputSurface, context: Context) { view.picture = model.screen; view.needsDisplay = true }
}
@MainActor final class InputSurface: NSView {
    weak var model: Model?; var picture: NSImage?; var tracking: NSTrackingArea?
    var held = false; var latestMove: [String: Any]?; var moving = false
    override var acceptsFirstResponder: Bool { true }
    override var isFlipped: Bool { true }
    var pictureRect: NSRect {
        guard let image = picture, image.size.width > 0, image.size.height > 0 else { return .zero }
        let scale = min(bounds.width / image.size.width, bounds.height / image.size.height)
        let size = NSSize(width: image.size.width * scale, height: image.size.height * scale)
        return NSRect(x: (bounds.width - size.width) / 2, y: (bounds.height - size.height) / 2, width: size.width, height: size.height)
    }
    var countedPicture:NSImage?
    override func draw(_ dirtyRect: NSRect) { picture?.draw(in: pictureRect, from: .zero, operation: .sourceOver, fraction: 1, respectFlipped: true, hints: nil);if picture !== countedPicture{countedPicture=picture;if picture != nil{model?.remotePresented()}} }
    override func updateTrackingAreas() { super.updateTrackingAreas(); if let tracking { removeTrackingArea(tracking) }; let area = NSTrackingArea(rect: .zero, options: [.activeInKeyWindow, .mouseMoved, .inVisibleRect], owner: self); addTrackingArea(area); tracking = area }
    func coordinates(_ event: NSEvent) -> [String: Any]? {
        let point = convert(event.locationInWindow, from: nil); let rect = pictureRect
        guard rect.width > 0, rect.height > 0, held || rect.contains(point) else { return nil }
        return ["kind": "move", "x": max(0, min(1, (point.x - rect.minX) / rect.width)), "y": max(0, min(1, (point.y - rect.minY) / rect.height))]
    }
    override func mouseMoved(with event: NSEvent) {
        guard model?.controlling == true else { return }; latestMove = coordinates(event)
        if moving { return }; moving = true
        Task { while let move = latestMove, model?.controlling == true { latestMove = nil; model?.sendInput(move); await model?.inputTail?.value }; moving = false }
    }
    override func mouseDragged(with event: NSEvent) { mouseMoved(with: event) }
    override func rightMouseDragged(with event: NSEvent) { mouseMoved(with: event) }
    override func otherMouseDragged(with event: NSEvent) { mouseMoved(with: event) }
    func button(_ event: NSEvent, _ button: Int, _ down: Bool) {
        guard model?.controlling == true, let move = coordinates(event) else { return }
        latestMove = nil; window?.makeFirstResponder(self); model?.sendInput(move); held = down
        model?.sendInput(["kind": "button", "button": button, "down": down])
    }
    override func mouseDown(with event: NSEvent) { button(event, 0, true) }
    override func mouseUp(with event: NSEvent) { button(event, 0, false) }
    override func rightMouseDown(with event: NSEvent) { button(event, 2, true) }
    override func rightMouseUp(with event: NSEvent) { button(event, 2, false) }
    override func otherMouseDown(with event: NSEvent) { button(event, 1, true) }
    override func otherMouseUp(with event: NSEvent) { button(event, 1, false) }
    override func scrollWheel(with event: NSEvent) { model?.sendInput(["kind": "wheel", "delta": max(-2400, min(2400, Int(event.scrollingDeltaY * 12)))]) }
    static let codes: [UInt16: String] = [0:"KeyA",1:"KeyS",2:"KeyD",3:"KeyF",4:"KeyH",5:"KeyG",6:"KeyZ",7:"KeyX",8:"KeyC",9:"KeyV",11:"KeyB",12:"KeyQ",13:"KeyW",14:"KeyE",15:"KeyR",16:"KeyY",17:"KeyT",18:"Digit1",19:"Digit2",20:"Digit3",21:"Digit4",22:"Digit6",23:"Digit5",24:"Equal",25:"Digit9",26:"Digit7",27:"Minus",28:"Digit8",29:"Digit0",30:"BracketRight",31:"KeyO",32:"KeyU",33:"BracketLeft",34:"KeyI",35:"KeyP",36:"Enter",37:"KeyL",38:"KeyJ",39:"Quote",40:"KeyK",41:"Semicolon",42:"Backslash",43:"Comma",44:"Slash",45:"KeyN",46:"KeyM",47:"Period",48:"Tab",49:"Space",50:"Backquote",51:"Backspace",53:"Escape",55:"MetaLeft",54:"MetaRight",56:"ShiftLeft",60:"ShiftRight",58:"AltLeft",61:"AltRight",59:"ControlLeft",62:"ControlRight",57:"CapsLock",123:"ArrowLeft",124:"ArrowRight",125:"ArrowDown",126:"ArrowUp",115:"Home",119:"End",116:"PageUp",121:"PageDown",117:"Delete",122:"F1",120:"F2",99:"F3",118:"F4",96:"F5",97:"F6",98:"F7",100:"F8",101:"F9",109:"F10",103:"F11",111:"F12",82:"Numpad0",83:"Numpad1",84:"Numpad2",85:"Numpad3",86:"Numpad4",87:"Numpad5",88:"Numpad6",89:"Numpad7",91:"Numpad8",92:"Numpad9"]
    override func keyDown(with event: NSEvent) {
        guard model?.controlling == true else { return }
        if event.keyCode == 9 && !event.modifierFlags.intersection([.command, .control]).isEmpty { if !event.isARepeat, let id = model?.remoteID { model?.perform("paste", ["id": id]) }; return }
        if let code = Self.codes[event.keyCode] { model?.sendInput(["kind": "key", "code": code, "down": true]) }
    }
    override func keyUp(with event: NSEvent) { if let code = Self.codes[event.keyCode] { model?.sendInput(["kind": "key", "code": code, "down": false]) } }
    override func flagsChanged(with event: NSEvent) {
        guard let code = Self.codes[event.keyCode] else { return }
        let flag: NSEvent.ModifierFlags = code.hasPrefix("Meta") ? .command : code.hasPrefix("Shift") ? .shift : code.hasPrefix("Alt") ? .option : code.hasPrefix("Control") ? .control : .capsLock
        model?.sendInput(["kind": "key", "code": code, "down": event.modifierFlags.contains(flag)])
    }
}
