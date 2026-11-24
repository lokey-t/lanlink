#!/bin/sh
set -eu
cd "$(dirname "$0")"
xcrun swiftc -O -swift-version 5 -target "$(uname -m)-apple-macos14.0" -framework AppKit -framework CoreGraphics -framework ScreenCaptureKit -framework CoreBluetooth -framework AVFoundation -framework VideoToolbox Bridge.swift BridgeOutput.swift BluetoothHid.swift StreamAudio.swift StreamVideo.swift -o LanLinkBridge
codesign --force --sign - LanLinkBridge
echo "Built LanLinkBridge. Grant Accessibility / Input Monitoring / Screen Recording to the executable or its launching terminal."
