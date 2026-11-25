# LanLink v0.93.0

## 更新日志

- 优化 Windows、macOS 和 Android 的远程串流逻辑，使远程画面与操作更流畅。
- 优化三端 UI，统一界面样式与交互。
- 新增控制中心，点击状态栏目即可快捷执行相关操作。

## Changes

- Optimized remote streaming across Windows, macOS, and Android for smoother video and interaction.
- Refined the UI across all three platforms for a consistent appearance and interaction.
- Added Control Center shortcuts: click a status entry to quickly access its related actions.

## 下载 / Downloads

| 设备 / 安装方式 | GitHub 下载 | Gitee 发行页 |
| --- | --- | --- |
| Windows 10/11 x64 · 便携版 | [LanLink-0.93.0-windows-x64.zip](https://github.com/lokey-t/lanlink/releases/download/v0.93.0/LanLink-0.93.0-windows-x64.zip) | [发行页](https://gitee.com/lokey-t/lanlink/releases/tag/v0.93.0) |
| Windows 10/11 x64 · 安装版 | [LanLink-0.93.0-windows-x64-setup.exe](https://github.com/lokey-t/lanlink/releases/download/v0.93.0/LanLink-0.93.0-windows-x64-setup.exe) | [发行页](https://gitee.com/lokey-t/lanlink/releases/tag/v0.93.0) |
| macOS 14+ · Apple Silicon DMG | [LanLink-0.93.0-macos-arm64.dmg](https://github.com/lokey-t/lanlink/releases/download/v0.93.0/LanLink-0.93.0-macos-arm64.dmg) | [发行页](https://gitee.com/lokey-t/lanlink/releases/tag/v0.93.0) |
| macOS 14+ · Apple Silicon ZIP | [LanLink-0.93.0-macos-arm64.zip](https://github.com/lokey-t/lanlink/releases/download/v0.93.0/LanLink-0.93.0-macos-arm64.zip) | [发行页](https://gitee.com/lokey-t/lanlink/releases/tag/v0.93.0) |
| Android 10+ · 正式签名 | [LanLink-0.93.0-android.apk](https://github.com/lokey-t/lanlink/releases/download/v0.93.0/LanLink-0.93.0-android.apk) | [发行页](https://gitee.com/lokey-t/lanlink/releases/tag/v0.93.0) |

Windows x64、macOS arm64 和 Android 各提供基于 v0.92.1 的独立增量更新；其他版本或补丁不可用时使用对应全量更新。

Windows x64, macOS arm64, and Android each include a separate delta from v0.92.1, with a full update for other versions or when the delta cannot be used.

macOS 发行包适用于 Apple Silicon，采用 ad-hoc 签名，未经过 Apple 公证。

The macOS packages are built for Apple Silicon, ad-hoc signed, and not notarized.

Android APK 沿用 v0.92.1 的正式签名证书。若旧版使用不同证书，Android 会拒绝覆盖安装。

The Android APK retains the production signing certificate used for v0.92.1. Android rejects in-place upgrades from builds signed with a different certificate.
