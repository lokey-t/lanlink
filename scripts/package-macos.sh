#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
VERSION=$(node -p "JSON.parse(require('fs').readFileSync('package.json')).version")
case "$(uname -m)" in arm64) ARCH=arm64;; x86_64) ARCH=x64;; *) echo 'Unsupported architecture'; exit 1;; esac
# Extract an official Node.js 22+ distribution for this Mac before building.
if [ -z "$LANLINK_NODE_DIST" ]; then echo 'Set LANLINK_NODE_DIST to an extracted official Node.js distribution'; exit 1; fi
test -x "$LANLINK_NODE_DIST/bin/node"
test -f "$LANLINK_NODE_DIST/LICENSE"
"$LANLINK_NODE_DIST/bin/node" -e 'if(Number(process.versions.node.split(".")[0])<22)process.exit(1)'
DEST="$(pwd)/dist/LanLink-$VERSION-macos-$ARCH"
BUNDLE="$DEST/LanLink.app"
if [ -e "$DEST" ]; then echo 'Build already exists'; exit 1; fi
sh native/macos/build.sh
mkdir -p "$BUNDLE/Contents/MacOS" "$BUNDLE/Contents/Resources/runtime"
xcrun swiftc -O -swift-version 5 -target "$(uname -m)-apple-macos14.0" -framework AppKit -framework SwiftUI -framework ServiceManagement desktop/macos/LanLink.swift desktop/macos/Remote.swift -o "$BUNDLE/Contents/MacOS/LanLink"
for name in src native vendor; do cp -R "$name" "$BUNDLE/Contents/Resources/"; done
cp package.json "$BUNDLE/Contents/Resources/"
cp "$LANLINK_NODE_DIST/bin/node" "$BUNDLE/Contents/Resources/runtime/node"
cp "$LANLINK_NODE_DIST/LICENSE" "$BUNDLE/Contents/Resources/runtime/NODE-LICENSE"
# Optional standalone FFmpeg distribution: preserve its executable, libraries and notices.
if [ -n "${LANLINK_FFMPEG_DIST:-}" ]; then
  test -x "$LANLINK_FFMPEG_DIST/bin/ffmpeg"
  test -f "$LANLINK_FFMPEG_DIST/LICENSE.txt"
  mkdir -p "$BUNDLE/Contents/Resources/runtime/video/bin"
  cp "$LANLINK_FFMPEG_DIST/bin/ffmpeg" "$BUNDLE/Contents/Resources/runtime/video/bin/ffmpeg"
  cp "$LANLINK_FFMPEG_DIST/LICENSE.txt" "$BUNDLE/Contents/Resources/runtime/FFMPEG-LICENSE.txt"
  if [ -d "$LANLINK_FFMPEG_DIST/lib" ]; then cp -R "$LANLINK_FFMPEG_DIST/lib" "$BUNDLE/Contents/Resources/runtime/video/"; fi
  codesign --force --sign - "$BUNDLE/Contents/Resources/runtime/video/bin/ffmpeg"
fi
cat > "$BUNDLE/Contents/Info.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleIdentifier</key><string>com.lanlink.native</string>
<key>CFBundleName</key><string>LanLink</string>
<key>CFBundleExecutable</key><string>LanLink</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleShortVersionString</key><string>$VERSION</string>
<key>LSMinimumSystemVersion</key><string>14.0</string>
<key>NSHighResolutionCapable</key><true/>
<key>NSAppleEventsUsageDescription</key><string>在用户启用关机权限后执行设备关机。</string>
</dict></plist>
EOF
codesign --force --sign - "$BUNDLE/Contents/Resources/native/macos/LanLinkBridge"
codesign --force --sign - "$BUNDLE"
cp README.md THIRD_PARTY_NOTICES.md "$DEST/"
cp -R docs "$DEST/"
ditto -c -k --sequesterRsrc --keepParent "$DEST" "$DEST.zip"
echo "Built $BUNDLE (ad-hoc signed, not notarized)"
