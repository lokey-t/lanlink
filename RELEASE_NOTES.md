# LanLink v0.92.0

## 更新日志

- 新增 Windows 和 macOS 组内蓝牙 HID 键鼠通道。跨屏控制优先使用已就绪的 HID 通道，可在获授权时回退到网络。
- 修复增量更新错误。Windows x64、macOS arm64 和 Android 各自提供独立增量包，并在补丁不可用时下载对应的全量包。
- 优化 Android 界面和横屏触控板，修复若干问题。

## Changes

- Added a Bluetooth HID keyboard and mouse channel for grouped Windows and macOS devices. Cross-screen input prefers a ready HID channel and can fall back to the network when authorized.
- Fixed incremental updates. Windows x64, macOS arm64, and Android each get a separate delta and fall back to their full package when needed.
- Improved the Android interface and landscape touchpad, and fixed several issues.

## 下载 / Downloads

| 设备 / 安装方式 | GitHub 下载 | Gitee 发行页 |
| --- | --- | --- |
| Windows 10/11 x64 · 便携版 | [LanLink-0.92.0-windows-x64.zip](https://github.com/lokey-t/lanlink/releases/download/v0.92.0/LanLink-0.92.0-windows-x64.zip) | [发行页](https://gitee.com/lokey-t/lanlink/releases/tag/v0.92.0) |
| Windows 10/11 x64 · 安装版 | [LanLink-0.92.0-windows-x64-setup.exe](https://github.com/lokey-t/lanlink/releases/download/v0.92.0/LanLink-0.92.0-windows-x64-setup.exe) | [发行页](https://gitee.com/lokey-t/lanlink/releases/tag/v0.92.0) |
| macOS 14+ · Apple Silicon DMG | [LanLink-0.92.0-macos-arm64.dmg](https://github.com/lokey-t/lanlink/releases/download/v0.92.0/LanLink-0.92.0-macos-arm64.dmg) | [发行页](https://gitee.com/lokey-t/lanlink/releases/tag/v0.92.0) |
| macOS 14+ · Apple Silicon ZIP | [LanLink-0.92.0-macos-arm64.zip](https://github.com/lokey-t/lanlink/releases/download/v0.92.0/LanLink-0.92.0-macos-arm64.zip) | [发行页](https://gitee.com/lokey-t/lanlink/releases/tag/v0.92.0) |
| Android 10+ · 正式签名 | [LanLink-0.92.0-android.apk](https://github.com/lokey-t/lanlink/releases/download/v0.92.0/LanLink-0.92.0-android.apk) | [发行页](https://gitee.com/lokey-t/lanlink/releases/tag/v0.92.0) |

Android APK 沿用 v0.91.1 的正式签名证书。若旧版使用不同证书，Android 会拒绝覆盖安装。

The Android APK uses the same production signing certificate as v0.91.1. Android rejects in-place upgrades from builds signed with a different certificate.
