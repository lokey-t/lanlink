# LanLink v0.92.1

## 更新日志

- 修复 Windows 与 macOS 组内蓝牙 HID 跨屏后鼠标无法返回的问题。回程使用被控端的实际光标位置，光标回报中断时自动恢复本机操作。
- 相比上一公开正式版 v0.91.1，本版新增组内蓝牙 HID 通道，修复增量更新，并优化 Android 界面与横屏触控板。Windows x64、macOS arm64、Android 各有独立增量包，基于 v0.91.1。

## Changes

- Fixed the mouse getting stuck after crossing screens over Bluetooth HID between grouped Windows and macOS devices. The return edge follows the target cursor, and the source releases capture if cursor feedback stops.
- Since the previous public stable release, this version adds group Bluetooth HID, fixes incremental updates, and improves the Android interface and landscape touchpad. Windows x64, macOS arm64, and Android each have a separate delta based on v0.91.1.

## 下载 / Downloads

| 设备 / 安装方式 | GitHub 下载 | Gitee 发行页 |
| --- | --- | --- |
| Windows 10/11 x64 · 便携版 | [LanLink-0.92.1-windows-x64.zip](https://github.com/lokey-t/lanlink/releases/download/v0.92.1/LanLink-0.92.1-windows-x64.zip) | [发行页](https://gitee.com/lokey-t/lanlink/releases/tag/v0.92.1) |
| Windows 10/11 x64 · 安装版 | [LanLink-0.92.1-windows-x64-setup.exe](https://github.com/lokey-t/lanlink/releases/download/v0.92.1/LanLink-0.92.1-windows-x64-setup.exe) | [发行页](https://gitee.com/lokey-t/lanlink/releases/tag/v0.92.1) |
| macOS 14+ · Apple Silicon DMG | [LanLink-0.92.1-macos-arm64.dmg](https://github.com/lokey-t/lanlink/releases/download/v0.92.1/LanLink-0.92.1-macos-arm64.dmg) | [发行页](https://gitee.com/lokey-t/lanlink/releases/tag/v0.92.1) |
| macOS 14+ · Apple Silicon ZIP | [LanLink-0.92.1-macos-arm64.zip](https://github.com/lokey-t/lanlink/releases/download/v0.92.1/LanLink-0.92.1-macos-arm64.zip) | [发行页](https://gitee.com/lokey-t/lanlink/releases/tag/v0.92.1) |
| Android 10+ · 正式签名 | [LanLink-0.92.1-android.apk](https://github.com/lokey-t/lanlink/releases/download/v0.92.1/LanLink-0.92.1-android.apk) | [发行页](https://gitee.com/lokey-t/lanlink/releases/tag/v0.92.1) |

Android APK 沿用 v0.91.1 的正式签名证书。若旧版使用不同证书，Android 会拒绝覆盖安装。

The Android APK uses the same production signing certificate as v0.91.1. Android rejects in-place upgrades from builds signed with a different certificate.
