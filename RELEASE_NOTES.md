# LanLink v0.91.0

[简体中文 README](README.md) · [English README](README.en.md)

## 中文

局域网内的 Windows、Mac 和 Android 共用键鼠、远程画面、剪贴板与文件暂存，无需账号。支持组内共享设备布局、手机触控板、逐设备权限与可选 USB 辅助通道。

- 新增自动检测更新、设置开关与手动检查，使用 GitHub / Gitee Release。
- 每个版本一个通用增量包；基础版本不匹配、补丁缺失、校验或合成失败时回退全量下载。
- Windows 提供便携 ZIP 和当前用户安装 EXE；macOS 提供 Apple Silicon DMG；Android 提供 APK。
- 桌面在退出后替换安装目录，保留旧目录；Android 更新由系统安装器确认。
- 中英 README 包含功能、使用方式、同类工具比较、技术栈、目录结构和平台下载入口。

系统要求：Windows 10/11 x64、.NET Framework 4.8；macOS 14+ arm64；Android 10+。Mac 为 ad-hoc 签名，未公证。Android 沿用此前开发包签名以支持覆盖更新，尚未配置独立生产签名。首次从不带更新功能的旧版升级，请手动下载完整包。

## English

Share keyboard/mouse input, remote screens, clipboard and staged files among Windows, Mac and Android devices on a LAN, without an account. Includes shared device layout, a phone touchpad, per-device permissions and an optional USB helper path.

- Automatic update checks, a local opt-out and manual checking through GitHub / Gitee Releases.
- One combined delta per version; unsupported bases, missing patches and verification or reconstruction failures fall back to full downloads.
- Windows portable ZIP and per-user setup EXE, Apple Silicon macOS DMG, and Android APK.
- Desktop installation occurs after exit and retains the old directory; Android requires system installation confirmation.
- Bilingual READMEs cover features, usage, comparisons, technology, source layout and platform downloads.

Requirements: Windows 10/11 x64 with .NET Framework 4.8; macOS 14+ arm64; Android 10+. Mac builds are ad-hoc signed and not notarized. The APK retains the previous development signing identity for in-place upgrades; a separate production signer is not configured. Clients without an updater require a manual first upgrade.
