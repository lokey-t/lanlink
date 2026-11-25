import Foundation
import CoreGraphics

@main struct MacInputSafetyProbe {
 static func main() {
  // No TCC changes or real input capture: simulate revocation while a remote
  // session still has a healthy lease, for each permission independently.
  for grants in [(false,true),(true,false),(false,false)] {
   inputUnavailable = false; remote = true; remoteBluetooth = true
   lease = Date().addingTimeInterval(60)
   wheelPending = 80; physicalModifiers = [55]; localModifierUps = [55]
   precondition(!refreshInputPermissions(accessibility:grants.0,monitoring:grants.1))
   precondition(!remote && !remoteBluetooth && inputUnavailable)
   precondition(wheelPending == 0 && physicalModifiers.isEmpty && localModifierUps.isEmpty)
   precondition(tap == nil && tapSource == nil)
   let retry = tapRetryAfter
   precondition(!refreshInputPermissions(accessibility:grants.0,monitoring:grants.1))
   precondition(tapRetryAfter == retry, "Repeated polls must not restart teardown")
  }
  inputSessionActive = false; inputUnavailable = false; remote = true
  precondition(!refreshInputPermissions(accessibility:true,monitoring:true))
  precondition(!remote && inputUnavailable)
  inputSessionActive = true
  precondition(refreshInputPermissions(accessibility:true,monitoring:true))
  precondition(inputUnavailable, "Permission recovery alone must not resume capture")

  let event = CGEvent(source:nil)!
  let proxy = CGEventTapProxy(bitPattern:1)! // Unused by the callback; never dereferenced.
  for type in [CGEventType.tapDisabledByTimeout, .tapDisabledByUserInput] {
   inputUnavailable = false; remote = true; tapRecoveryPending = false
   precondition(callback(proxy,type,event,nil) != nil)
   precondition(tapRecoveryPending)
   // Before queued cleanup runs, all physical input must already pass through.
   precondition(callback(proxy,.keyDown,event,nil) != nil)
   precondition(callback(proxy,.mouseMoved,event,nil) != nil)
   RunLoop.main.run(until:Date().addingTimeInterval(0.05))
   precondition(!remote && inputUnavailable && !tapRecoveryPending)
   precondition(tap == nil && tapSource == nil && tapRetryAfter > Date())
  }
  print("MAC_INPUT_SAFETY_OK")
 }
}
