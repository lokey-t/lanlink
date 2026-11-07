# LanLink v0.91.1

[简体中文 README](README.md) · [English README](README.en.md)

## 中文

Windows、macOS 和 Android 的设置中新增「导出日志」。每台设备保留本次运行最近 100 条记录。导出时并行收集本机与在线同组设备的日志，按设备合并为一个 UTF-8 文本文件。文件会标明离线、未响应或版本不支持的设备。

- 新增连接质量分析，可检查组内设备的往返延迟、抖动和探测超时情况。
- 安卓首页在本机未就绪时显示红色状态和带叉号的手机图标。点击图标即可在首页打开授权教程。
- 修复结束远程控制后，迟到的旧会话消息可能影响新会话的问题，并修正离线设备的切换路径及拖放暂存行为。

Android 发行包使用正式版证书签名。使用旧证书的 v0.91.0 无法覆盖安装，请先保留需要的应用数据，再卸载旧版并安装新版。macOS 安装包适用于 Apple Silicon，应用使用临时签名，未经过 Apple 公证。

## English

Export Logs is available in Settings on Windows, macOS, and Android. Each device retains up to 100 activity entries from the current run. Export collects available logs from the local device and online group members in parallel and groups them by device in one UTF-8 text file. The report identifies devices that were offline, did not respond, or did not support log export.

- Connection quality analysis reports round-trip latency, jitter, and probe timeouts for group devices.
- When the local Android device is unready, the home screen shows a red status and a phone icon with a red cross. Tap the icon to open the setup guide.
- Fixed late messages from ended remote-control sessions affecting newer sessions, and corrected offline-device switching paths and drag-and-drop staging behavior.

The Android release APK uses the production signing certificate. Installations of v0.91.0 signed with the previous certificate require a fresh install. Preserve any app data you need before uninstalling. The macOS package supports Apple Silicon and uses an ad-hoc signature without Apple notarization.
