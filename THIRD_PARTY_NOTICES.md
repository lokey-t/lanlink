# Third-party components

## Native QUIC transport (0.3.40)

The shared Rust core uses Cloudflare quiche 0.30.0 (BSD-2-Clause),
https://github.com/cloudflare/quiche, and its BoringSSL TLS backend through boring 5.2.0.
Desktop FFI uses Koffi 2.15.1 (MIT), https://koffi.dev/.
Android bundles NDK r27d libc++ for ARM64 and x86_64.
The locked dependency inventory and redistributed license/copyright notices are in
`vendor/quic-notices/`; Android carries the same notices in its assets.
Original notices remain applicable to each dependency, including BoringSSL's component licenses.

## WebRTC transport (0.3.38)

Desktop uses werift 0.24.4 (MIT), https://github.com/shinyoshiaki/werift-webrtc.
The upstream license is retained in `vendor/webrtc-notices/WERIFT-LICENSE`.
Exact runtime dependency versions and integrity checks are in `package-lock.json`;
desktop packages retain the installed dependency directories and their license files.

Android uses io.github.webrtc-sdk:android:150.7871.01 from Maven Central,
https://github.com/webrtc-sdk/android. The SDK distribution is MIT licensed;
WebRTC is BSD licensed by the WebRTC project authors. Upstream SDK / WebRTC
licenses and the WebRTC patent grant are retained in `vendor/webrtc-notices/`
and included in Android APK assets under `webrtc-notices/`.

Used as a native, LAN-only DTLS/SCTP transport; there is no configured public
STUN/TURN service. The existing native H.264 encoders and decoders are retained.

## qrcode-generator 2.0.4

`vendor/qrcode.mjs` is an unmodified distribution file from
https://www.npmjs.com/package/qrcode-generator/v/2.0.4
by Kazuhiko Arase. Source: https://github.com/kazuhikoarase/qrcode-generator.
The npm archive SHA-512 is pinned and checked by `scripts/vendor-qr.js`.

MIT License

Copyright (c) 2009 Kazuhiko Arase

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.

## ZXing core 3.5.3 (Android)

Source: https://github.com/zxing/zxing

Copyright ZXing authors. Licensed under Apache License 2.0:
https://www.apache.org/licenses/LICENSE-2.0

Used for decoding pairing QR codes from a captured or selected image.
The Maven dependency is specified in the Android build, and is included in the APK.

## Build tools

The optional local Android build uses Google's Android SDK build tools/platform
and the user's JDK. They are development
tools, not copied into the desktop application. Downloaded SDK/tools and local
debug signing keys remain in the ignored `.tools` folder.

## Electron 44.4.5 (historical 0.2 desktop only)

Official runtime: https://github.com/electron/electron/releases/tag/v44.4.5
Electron is MIT licensed. Desktop packages include the upstream LICENSE and
LICENSES.chromium.html covering Electron, Chromium, Node.js and bundled components.
The Windows runtime was verified against the release SHA256 manifest.

## Native desktop 0.3

The 0.3.5 Windows package optionally bundles an unmodified FFmpeg LGPL v3 executable
from BtbN/FFmpeg-Builds. Copyright FFmpeg contributors. The LGPL text, exact release
archive hash, build configuration and source/build links are under `vendor/ffmpeg/`
and copied to `runtime/ffmpeg-notices/`; the binary license is also at
`runtime/FFMPEG-LICENSE.txt`. FFmpeg is invoked as a separate process.

macOS packages include an unmodified FFmpeg 8.0.3 build under LGPL 2.1 or later,
with GPL and nonfree components disabled. The license is included at
`runtime/FFMPEG-LICENSE.txt`; the exact upstream source archive, build script
and build configuration are included in `runtime/video/notices/`. This separate
executable provides H.264 decoding, JPEG conversion and VideoToolbox encoding.

Windows uses system WPF / .NET Framework. macOS uses AppKit / SwiftUI.
These UI frameworks are supplied by the operating system. No Electron runtime is included.
The packaged Node.js runtime includes its distribution license at runtime/NODE-LICENSE
(macOS: inside the app Contents/Resources/runtime).
