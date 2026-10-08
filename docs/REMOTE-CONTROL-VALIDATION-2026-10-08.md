# Mac / Android 远程控制修复验证（2026-10-08）

## 修改

- Mac 安装包强制包含 FFmpeg 8.0.3 视频运行时；从官方源码构建，关闭 GPL / nonfree 组件，仅链接系统动态库。对应源码、构建脚本、配置和 LGPL 许可证随安装包提供。
- Mac 屏幕 JPEG 输入显式指定 MJPEG 编码，避免视频编码器无法识别截图流。
- 全局键鼠捕获解除物理鼠标与光标关联、隐藏并固定本机光标；返回本机、超时、紧急快捷键、输入拦截失效及原生模块退出时恢复。
- 原有固定 480 × 360 权限窗口改为主窗口中统一的权限引导；引导限制高度并可滚动。远程画面尚未连接时显示当前状态。
- Android 虚拟键鼠注册及事件使用数字 Linux ABI 值，兼容 Android 14 uinput；在 Android 14 上不发送不支持的 updateTimeBase 命令。
- Android 主界面接管键盘及导航配置变化，防止虚拟键盘连接/断开时重建正在使用的控制界面。

## 验证

- npm test：161 通过，0 失败。
- npm run check：通过。
- Android Debug 应用和 instrumentation 编译：通过（Java 21、Gradle 8.11.1）。
- Android 14 模拟器定向集成：自动身份匹配、持久 ADB 助手重连、真实 H.264 捕获及 Mac 解码、实际点击/拖动/按键、36 次按键顺序、虚拟鼠标及键盘注册、跨屏切换后实际点击、界面线程阻塞期间输入、Home 导航与释放均通过。
- 同一次定向集成：Android 收到并解码真实 Mac 屏幕 JPEG；Android 输入送达 Mac 原生模块；辅助通道移除后可切回局域网。
- 真正 Mac 屏幕视频链路：ScreenCaptureKit → VideoToolbox H.264 → FFmpeg → JPEG，编码 22 帧、解码 19 帧，通过。
- Mac 原生捕获：模拟移动事件被转发，本机光标坐标保持不变，释放操作成功。
- Mac 软件编译、签名及 ZIP 校验：通过。

定向模拟器命令：

```sh
LANLINK_SERIAL=emulator-5554 LANLINK_FFMPEG="$PWD/.tools/video-macos-dist/bin/ffmpeg" node scripts/adb-smoke.js --auto-match --dual-link --real-mac --control-only
```

定向脚本完成全部功能检查后未自行退出，已手动停止该临时测试进程；退出清理不计为验证通过。

测试范围为真实 Mac 原生模块与 Android 14 模拟器，未验收用户的实体手机、其他 Android 版本或实际物理鼠标的长时间操作。系统快速设置齿轮测试不属于本次定向验证。

## 产物

- `dist/LanLink-0.3.44-macos-arm64.zip`
- `dist/LanLink-0.3.44-android-remote-control-fix.apk`（Debug 测试版）

两端均需更新，以包含 Android 虚拟键鼠兼容性修复。
