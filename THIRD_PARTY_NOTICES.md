# Third-party components

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

Windows uses system WPF / .NET Framework. macOS uses AppKit / SwiftUI.
These UI frameworks are supplied by the operating system. No Electron runtime is included.
The packaged Node.js runtime includes its distribution license at runtime/NODE-LICENSE
(macOS: inside the app Contents/Resources/runtime).
