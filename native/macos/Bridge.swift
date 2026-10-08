import Foundation
import AppKit
import CoreGraphics
import ScreenCaptureKit
import ApplicationServices

// Build with the macOS 14+ SDK. Keep this executable at a stable path for TCC.
let marker: Int64 = 0x4c414e4c
let keyCodes: [String: CGKeyCode] = [
 "KeyA":0,"KeyS":1,"KeyD":2,"KeyF":3,"KeyH":4,"KeyG":5,"KeyZ":6,"KeyX":7,"KeyC":8,"KeyV":9,"KeyB":11,"KeyQ":12,"KeyW":13,"KeyE":14,"KeyR":15,"KeyY":16,"KeyT":17,
 "Digit1":18,"Digit2":19,"Digit3":20,"Digit4":21,"Digit6":22,"Digit5":23,"Equal":24,"Digit9":25,"Digit7":26,"Minus":27,"Digit8":28,"Digit0":29,"BracketRight":30,"KeyO":31,"KeyU":32,"BracketLeft":33,"KeyI":34,"KeyP":35,"Enter":36,"KeyL":37,"KeyJ":38,"Quote":39,"KeyK":40,"Semicolon":41,"Backslash":42,"Comma":43,"Slash":44,"KeyN":45,"KeyM":46,"Period":47,"Tab":48,"Space":49,"Backquote":50,"Backspace":51,"Escape":53,"MetaRight":54,"MetaLeft":55,"ShiftLeft":56,"CapsLock":57,"AltLeft":58,"ControlLeft":59,"ShiftRight":60,"AltRight":61,"ControlRight":62,"F1":122,"F2":120,"F3":99,"F4":118,"F5":96,"F6":97,"F7":98,"F8":100,"F9":101,"F10":109,"F11":103,"F12":111,"Home":115,"PageUp":116,"Delete":117,"End":119,"PageDown":121,"ArrowLeft":123,"ArrowRight":124,"ArrowDown":125,"ArrowUp":126,"Numpad0":82,"Numpad1":83,"Numpad2":84,"Numpad3":85,"Numpad4":86,"Numpad5":87,"Numpad6":88,"Numpad7":89,"Numpad8":91,"Numpad9":92
]
let reverseCodes = Dictionary(uniqueKeysWithValues: keyCodes.map { ($0.value, $0.key) })
let outLock = NSLock()
func emit(_ value: [String: Any]) {
 guard let data = try? JSONSerialization.data(withJSONObject: value), let line = String(data: data, encoding: .utf8) else { return }
 outLock.lock(); print(line); fflush(stdout); outLock.unlock()
}
var remote = false
var cursorDetached = false
var capturedCursorPosition: CGPoint?
var capturedDisplay: CGDirectDisplayID?
var syntheticCursor = false
var lease = Date.distantPast
var inputLease = Date.distantPast
var held = Set<CGKeyCode>()
var physicalModifiers = Set<CGKeyCode>()
var localModifierUps = Set<CGKeyCode>()
var buttons = Set<Int>()
var tap: CFMachPort?
var clipboardCount = NSPasteboard.general.changeCount
func post(_ event: CGEvent?) { event?.setIntegerValueField(.eventSourceUserData, value: marker); event?.post(tap: .cghidEventTap) }
func flags() -> CGEventFlags {
 var f: CGEventFlags = []
 if held.contains(55) || held.contains(54) { f.insert(.maskCommand) }
 if held.contains(56) || held.contains(60) { f.insert(.maskShift) }
 if held.contains(58) || held.contains(61) { f.insert(.maskAlternate) }
 if held.contains(59) || held.contains(62) { f.insert(.maskControl) }
 return f
}
func key(_ code: CGKeyCode, _ down: Bool) {
 if down { held.insert(code) } else { held.remove(code) }
 let event = CGEvent(keyboardEventSource: nil, virtualKey: code, keyDown: down); event?.flags = flags(); post(event)
}
func mouseButton(_ button: Int, _ down: Bool) {
 let b: CGMouseButton = button == 0 ? .left : button == 2 ? .right : .center
 let type: CGEventType = button == 0 ? (down ? .leftMouseDown : .leftMouseUp) : button == 2 ? (down ? .rightMouseDown : .rightMouseUp) : (down ? .otherMouseDown : .otherMouseUp)
 let p = CGEvent(source: nil)?.location ?? .zero
 let e = CGEvent(mouseEventSource: nil, mouseType: type, mouseCursorPosition: p, mouseButton: b); e?.flags = flags(); post(e)
 if down { buttons.insert(button) } else { buttons.remove(button) }
}
func releaseAll() { for k in Array(held) { key(k, false) }; for b in Array(buttons) { mouseButton(b, false) } }
func stopCapture() {
 remote = false
 if cursorDetached { _ = CGAssociateMouseAndMouseCursorPosition(1); cursorDetached = false }
 capturedCursorPosition = nil
 if let display = capturedDisplay { _ = CGDisplayShowCursor(display); capturedDisplay = nil }
 releaseAll()
}
let callback: CGEventTapCallBack = { _, type, event, _ in
 if type == .tapDisabledByTimeout || type == .tapDisabledByUserInput { stopCapture(); if let t = tap { CGEvent.tapEnable(tap: t, enable: true) }; emit(["type":"emergency"]); return Unmanaged.passUnretained(event) }
 if event.getIntegerValueField(.eventSourceUserData) == marker { return Unmanaged.passUnretained(event) }
 if [.mouseMoved,.leftMouseDragged,.rightMouseDragged,.otherMouseDragged].contains(type) { syntheticCursor = false }
 let code = CGKeyCode(event.getIntegerValueField(.keyboardEventKeycode))
 if type == .keyDown && code == 111 && event.flags.contains([.maskControl, .maskAlternate]) { stopCapture(); emit(["type":"emergency"]); return nil }
 if type == .flagsChanged {
  let f=event.flags;let mask=(f.contains(.maskControl) ? 1:0)|(f.contains(.maskAlternate) ? 2:0)|(f.contains(.maskShift) ? 4:0)|(f.contains(.maskCommand) ? 8:0)
  emit(["type":"modifiers","mask":mask])
  let flag:CGEventFlags = code==57 ? .maskAlphaShift:[54,55].contains(code) ? .maskCommand:[56,60].contains(code) ? .maskShift:[58,61].contains(code) ? .maskAlternate:.maskControl
  if f.contains(flag){physicalModifiers.insert(code)}else{physicalModifiers.remove(code)}
 }
 guard remote else { return Unmanaged.passUnretained(event) }
 if type == .keyDown || type == .keyUp || type == .flagsChanged {
  var down = type == .keyDown
  if type == .flagsChanged { down = physicalModifiers.contains(code) }
  if let name = reverseCodes[code] { emit(["type":"input","kind":"key","code":name,"down":down]) }
  if !down && localModifierUps.remove(code) != nil{return Unmanaged.passUnretained(event)}
 } else if [.mouseMoved, .leftMouseDragged, .rightMouseDragged, .otherMouseDragged].contains(type) {
  if let position = capturedCursorPosition { _ = CGWarpMouseCursorPosition(position) }
  emit(["type":"input","kind":"move","dx":event.getIntegerValueField(.mouseEventDeltaX),"dy":event.getIntegerValueField(.mouseEventDeltaY)])
 } else if type == .scrollWheel { emit(["type":"input","kind":"wheel","delta":max(-2400,min(2400,event.getIntegerValueField(.scrollWheelEventDeltaAxis1)*120))]) }
 else {
  let down = [.leftMouseDown,.rightMouseDown,.otherMouseDown].contains(type)
  let b = [.leftMouseDown,.leftMouseUp].contains(type) ? 0 : [.rightMouseDown,.rightMouseUp].contains(type) ? 2 : 1
  emit(["type":"input","kind":"button","button":b,"down":down])
 }
 return nil
}
func reply(_ id: String, _ result: [String: Any] = [:]) { emit(["replyTo":id,"result":result]) }
func failure(_ id: String, _ message: String) { emit(["replyTo":id,"error":message]) }
func command(_ message: [String: Any]) {
 guard let id = message["id"] as? String, let method = message["method"] as? String else { return }
 let a = message["args"] as? [String: Any] ?? [:]
 let display = CGMainDisplayID(), bounds = CGDisplayBounds(display)
 switch method {
 case "capabilities": reply(id,["relativeInput":true,"host":AXIsProcessTrusted() && (tap.map { CGEvent.tapIsEnabled(tap: $0) } ?? false),"input":AXIsProcessTrusted(),"screen":CGPreflightScreenCaptureAccess(),"clipboard":true,"shutdown":true,"width":bounds.width,"height":bounds.height])
 case "mode":
  if a["remote"] as? Bool == true {
   if !remote {
    guard AXIsProcessTrusted(), let tap, CGEvent.tapIsEnabled(tap: tap) else { failure(id,"缺少辅助功能或输入监控权限，无法接管本机键鼠"); return }
    guard CGAssociateMouseAndMouseCursorPosition(0) == .success else { failure(id,"无法暂停本机鼠标，请重新授予辅助功能权限"); return }
    cursorDetached = true; capturedCursorPosition = CGEvent(source: nil)?.location
    guard CGDisplayHideCursor(display) == .success else { stopCapture(); failure(id,"无法隐藏本机光标，已恢复本机操作"); return }
    capturedDisplay = display; localModifierUps = physicalModifiers; remote = true
   }
   lease = Date().addingTimeInterval(3)
  } else { stopCapture() }
  reply(id)
 case "file-drag-cancel": if remote { key(53,true);key(53,false);mouseButton(0,false) }; reply(id)
 case "heartbeat": lease = Date().addingTimeInterval(3); inputLease = lease; reply(id)
 case "release": releaseAll(); reply(id)
 case "warp": CGWarpMouseCursorPosition(CGPoint(x: bounds.minX + (a["x"] as? Double ?? 0.5) * bounds.width, y: bounds.minY + (a["y"] as? Double ?? 0.5) * bounds.height)); reply(id)
 case "input":
  guard AXIsProcessTrusted() else { failure(id,"请在系统设置中授予辅助功能权限，然后重启客户端"); return }
  inputLease = Date().addingTimeInterval(3)
  switch a["kind"] as? String {
  case "relative":
   syntheticCursor = true
   let cursor = CGEvent(source: nil)?.location ?? CGPoint(x: bounds.midX, y: bounds.midY)
   let p = CGPoint(x: max(bounds.minX,min(bounds.maxX-1,cursor.x+(a["dx"] as? Double ?? 0))), y: max(bounds.minY,min(bounds.maxY-1,cursor.y+(a["dy"] as? Double ?? 0))))
   let type: CGEventType = buttons.contains(0) ? .leftMouseDragged : buttons.contains(2) ? .rightMouseDragged : .mouseMoved
   post(CGEvent(mouseEventSource:nil,mouseType:type,mouseCursorPosition:p,mouseButton:buttons.contains(2) ? .right : .left))
  case "move":
   syntheticCursor = true
   let p = CGPoint(x:bounds.minX + (a["x"] as? Double ?? 0.5)*(bounds.width-1),y:bounds.minY + (a["y"] as? Double ?? 0.5)*(bounds.height-1))
   let type: CGEventType = buttons.contains(0) ? .leftMouseDragged : buttons.contains(2) ? .rightMouseDragged : .mouseMoved
   post(CGEvent(mouseEventSource:nil,mouseType:type,mouseCursorPosition:p,mouseButton:buttons.contains(2) ? .right : .left))
  case "key": if let name = a["code"] as? String, let code = keyCodes[name] { key(code,a["down"] as? Bool ?? false) }
  case "button": mouseButton(a["button"] as? Int ?? 0,a["down"] as? Bool ?? false)
  case "wheel": post(CGEvent(scrollWheelEvent2Source:nil,units:.pixel,wheelCount:1,wheel1:Int32(a["delta"] as? Int ?? 0),wheel2:0,wheel3:0))
  default: failure(id,"不支持的输入"); return
  }; reply(id)
 case "text":
  guard AXIsProcessTrusted() else { failure(id,"缺少辅助功能权限"); return }
  let text = Array((a["text"] as? String ?? "").utf16)
  for start in stride(from:0,to:text.count,by:20) { let chars = Array(text[start..<min(start+20,text.count)]); let e = CGEvent(keyboardEventSource:nil,virtualKey:0,keyDown:true); e?.keyboardSetUnicodeString(stringLength:chars.count,unicodeString:chars); post(e); let up = CGEvent(keyboardEventSource:nil,virtualKey:0,keyDown:false); up?.keyboardSetUnicodeString(stringLength:chars.count,unicodeString:chars); post(up) }; reply(id)
 case "paste": key(55,true);key(9,true);key(9,false);key(55,false); reply(id)
 case "clipboardGet":
  let pb = NSPasteboard.general
  let urls = pb.readObjects(forClasses:[NSURL.self],options:[.urlReadingFileURLsOnly:true]) as? [URL] ?? []
  reply(id,["text":pb.string(forType:.string) ?? "","files":urls.map(\.path)])
 case "clipboardSet":
  let pb = NSPasteboard.general; pb.clearContents()
  if let files = a["files"] as? [String] { pb.writeObjects(files.map { NSURL(fileURLWithPath:$0) }) } else { pb.setString(a["text"] as? String ?? "",forType:.string) }
  clipboardCount = pb.changeCount; reply(id)
 case "screen":
  guard CGPreflightScreenCaptureAccess() else { _ = CGRequestScreenCaptureAccess(); failure(id,"请授予屏幕录制权限，然后重启客户端"); return }
  let requestedWidth = a["maxWidth"] as? Double ?? 1440, requestedQuality = a["quality"] as? Double ?? 0.65
  let maxWidth = Int(requestedWidth.isFinite ? max(320,min(1920,requestedWidth)) : 1440)
  let quality = requestedQuality.isFinite ? max(0.2,min(0.85,requestedQuality)) : 0.65
  SCShareableContent.getExcludingDesktopWindows(false,onScreenWindowsOnly:true) { content,error in
   guard let screen = content?.displays.first(where: { $0.displayID == display }) else { failure(id,error?.localizedDescription ?? "无法获取屏幕"); return }
   let config = SCStreamConfiguration();config.width = min(maxWidth,screen.width);config.height = max(1,screen.height*config.width/screen.width);config.showsCursor = true
   let filter = SCContentFilter(display:screen,excludingWindows:[])
   SCScreenshotManager.captureImage(contentFilter:filter,configuration:config) { image,error in
    guard let image = image, let data = NSBitmapImageRep(cgImage:image).representation(using:.jpeg,properties:[.compressionFactor:quality]) else { failure(id,error?.localizedDescription ?? "屏幕捕获失败"); return }
    reply(id,["mime":"image/jpeg","data":data.base64EncodedString(),"width":image.width,"height":image.height])
   }
  }
 case "shutdown":
  let process = Process();process.executableURL = URL(fileURLWithPath:"/usr/bin/osascript");process.arguments = ["-e","tell application \"System Events\" to shut down"];process.standardOutput = FileHandle.standardError
  process.terminationHandler = { p in if p.terminationStatus != 0 { emit(["type":"error","message":"系统未允许关机，请检查自动化权限或未保存文档"]) } }
  do { try process.run(); reply(id) } catch { failure(id,error.localizedDescription) }
 default: failure(id,"不支持的原生操作")
 }
}
_ = NSApplication.shared
let types: [CGEventType] = [.keyDown,.keyUp,.flagsChanged,.mouseMoved,.leftMouseDragged,.rightMouseDragged,.otherMouseDragged,.leftMouseDown,.leftMouseUp,.rightMouseDown,.rightMouseUp,.otherMouseDown,.otherMouseUp,.scrollWheel]
let mask = types.reduce(CGEventMask(0)) { $0 | (CGEventMask(1) << $1.rawValue) }
tap = CGEvent.tapCreate(tap:.cghidEventTap,place:.headInsertEventTap,options:.defaultTap,eventsOfInterest:mask,callback:callback,userInfo:nil)
if let t = tap { let source = CFMachPortCreateRunLoopSource(kCFAllocatorDefault,t,0);CFRunLoopAddSource(CFRunLoopGetMain(),source,.commonModes);CGEvent.tapEnable(tap:t,enable:true) }
var ticks = 0
Timer.scheduledTimer(withTimeInterval:0.02,repeats:true) { _ in
 if remote && Date()>lease { stopCapture();emit(["type":"emergency"]) }
 if (!held.isEmpty || !buttons.isEmpty) && Date()>inputLease { releaseAll() }
 if !remote { let p=CGEvent(source:nil)?.location ?? .zero;let b=CGDisplayBounds(CGMainDisplayID());emit(["type":"cursor","x":p.x-b.minX,"y":p.y-b.minY,"width":b.width,"height":b.height,"synthetic":syntheticCursor]) }
 ticks += 1
 if ticks%25==0 && NSPasteboard.general.changeCount != clipboardCount { clipboardCount=NSPasteboard.general.changeCount;emit(["type":"clipboardChanged"]) }
}
DispatchQueue.global().async {
 while let line = readLine() { guard let data=line.data(using:.utf8),let m=(try? JSONSerialization.jsonObject(with:data)) as? [String:Any] else { continue };DispatchQueue.main.async { command(m) } }
 DispatchQueue.main.async { stopCapture();NSApplication.shared.terminate(nil) }
}
signal(SIGTERM, SIG_IGN)
let terminationSignal = DispatchSource.makeSignalSource(signal: SIGTERM, queue: .main)
terminationSignal.setEventHandler { stopCapture(); exit(0) }
terminationSignal.resume()
NSApplication.shared.run()
