# 低延迟串流（0.3.6）

## 当前实现

- 远程窗口自动协商 `videoOpen`。授权检查在创建监听端口前执行，首个视频客户端认证成功后才启动捕获。
- 每个会话使用随机 256 位密钥，通过现有已配对 AES-GCM 控制连接交付。独立 TCP 端口使用双向 HMAC 挑战验证，结合双方 nonce 派生视频密钥。帧使用 AES-256-GCM、严格递增序号和尺寸上限；重放或篡改会断开。
- 网络上的 H.264 / JPEG 数据是二进制，取消两层 JSON / Base64 图像传输。键鼠及文件继续使用原控制连接。
- 视频写队列过大时丢弃新画面，H.264 等待完整 IDR 恢复，避免继续播放无效参考帧。桌面接收侧只保留一张最新解码图；安卓编码侧队列容量为一帧，解码侧只呈现最新可用输出。
- 10 秒会话租约、1 秒控制通道反馈续期、独立视频保活；暂停变化的屏幕无需制造新图像。关闭播放器、断线、撤销授权、关闭屏幕开关或超时均释放媒体连接和编码资源。
- 屏幕授权与输入授权独立，可只看画面。既有首次逐项同意和系统权限引导不会被视频通道绕过。

## 网络自适应

接收侧每秒反馈控制通道 RTT 和相对于近期最小传输时间的积压，抵消设备时钟的固定偏差。
RTT 显著升高、排队超过 100 ms 或发送缓冲积压时，目标码率乘 0.65；降档至少间隔 3 秒。
连续 6 次低积压样本且距上次调节超过 6 秒时升档 15%，范围 0.5–8 Mbps。
异常反馈被忽略。此策略估计拥塞，不是可用带宽的精确测速；JPEG 兼容画面不承诺目标码率。

Android 使用 MediaCodec PARAMETER_KEY_VIDEO_BITRATE；桌面 FFmpeg 进程以新码率重启，旧代回调被丢弃，新画面等 IDR 恢复。
停止控制、移出设备及权限变更均优先释放输入和串流。无声、静止画面仍续租。

## 平台路径

| 平台 | 被控端 | 控制端 |
| --- | --- | --- |
| Windows | FFmpeg gdigrab 主屏采集，优先 NVENC / QSV / AMF，再尝试 Media Foundation / OpenH264；最大长边 1920、30 fps、初始 4 Mbps / 自适应 0.5–8 Mbps、无 B 帧、约 1 秒 IDR | FFmpeg 低延迟解码，经本机匿名管道将最新 JPEG 送至 WPF，后台解码图片 |
| Android | 已授权 MediaProjection 的同一个 VirtualDisplay 切换至 MediaCodec 输入 Surface，最大长边 1280、30 fps、初始 4 Mbps / 自适应 0.5–8 Mbps、无 B 帧；停止后恢复原截图 Surface | MediaCodec → TextureView；按比例呈现、保持点击坐标映射；退后台关闭播放器 |
| macOS | 保持 ScreenCaptureKit 原模块的权限身份，连续采集后可用 FFmpeg VideoToolbox 编码 | FFmpeg 解码后由 SwiftUI / AppKit 展示最新画面 |

Windows 在当前 RTX 5070 Ti 上验证了 NVENC 生成画面编码。其他编码器由本机试编码选择，尚未逐台实测。
Mac 源码已更新，本机没有 macOS 构建环境。Mac 当前采集仍经截图接口，尚非原生 SCStream → VideoToolbox 零拷贝链路。
桌面本机呈现仍有 JPEG 转换开销；视频网络使用 TCP，弱网丢包时会受重传影响。此版本不兼容 Moonlight / Sunshine 协议，不声称达到游戏串流级延迟。

## FFmpeg 与打包

Windows 0.3.5 包含 BtbN 提供的 FFmpeg LGPL 构建。构建日期、上游下载链接和 SHA-256 记录在 `vendor/ffmpeg/build.json`，授权及配置也随包保留。

```powershell
& scripts/package-windows.ps1 -NodeRuntime .tools/native-stage-0.3.3/runtime -VideoRuntime .tools/ffmpeg/ffmpeg-n8.1-latest-win64-lgpl-8.1
```

开发模式可设置 `LANLINK_FFMPEG` 为本机可执行文件的绝对路径。应用不会下载或执行远端传入的程序路径。
Mac 打包支持 `LANLINK_FFMPEG_DIST`，目录须包含 `bin/ffmpeg` 和 `LICENSE.txt`；动态库依赖和签名需在目标 Mac 检查。
缺少 FFmpeg 的桌面自动使用 JPEG 兼容串流。安卓使用系统编解码器，无需安装 FFmpeg。

## 验证

```powershell
node --test tests/*.test.js
$env:LANLINK_FFMPEG = (Resolve-Path .tools/ffmpeg/ffmpeg-n8.1-latest-win64-lgpl-8.1/bin/ffmpeg.exe).Path
node scripts/video-smoke.js
```

`video-smoke` 生成 320×240 动态测试图，不捕获用户屏幕，使用实际编码器、加密通道和解码器；跳过最初 30 帧再统计稳定阶段吞吐。输出不是输入到显示的端到端延迟。
`--android` 启动仅持续 90 秒的本机测试端点，把临时协商信息写到 `.tools/video-test-session.json`。
已安装测试 APK 的专用模拟器可以执行：

```powershell
$info = Get-Content .tools/video-test-session.json -Raw | ConvertFrom-Json
$adb = "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
& $adb -s emulator-5580 shell am instrument -w -e videoPort $info.port -e videoKey $info.key com.lanlink.app.test/com.lanlink.app.PermissionFlowTest
```

测试验证 Node → Android 二进制加密互通、实际 TextureView 显示及生成图案颜色；还以 EGL 生成颜色测试 Android Surface 编码 → 解码。不会授予录屏或无障碍权限。投屏授权、旋转、长时间断续网络、真实双设备鼠标到画面延迟仍需局域网实测。

设计参考：[Sunshine](https://docs.lizardbyte.dev/projects/sunshine/latest/)、[FFmpeg](https://www.ffmpeg.org/ffmpeg-all.html)、[Android MediaCodec](https://developer.android.com/reference/android/media/MediaCodec)。
