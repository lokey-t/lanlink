# 低延迟串流（0.3.40）

0.3.40 新增原生 QUIC＋Datagram，双方可用时优先传输画面和鼠标移动。认证、分包、关键帧恢复及回退见 [QUIC 说明](QUIC.md)。下文保留 WebRTC / TCP 兼容路径说明。

## 0.3.38 WebRTC 与独立文件传输

局域网双方声明 `rtcVideo:1` 时，控制端经原有加密控制连接发送 SDP offer，被控端执行屏幕权限 / 控制方向检查后返回 answer。桌面使用 werift 0.24.4，Android 使用原生 WebRTC SDK；ICE 仅收集本机地址，显式禁用 werift ICE 层默认公网 STUN。窗口和 MediaCodec / FFmpeg 编解码入口保留。

本版本采用 DTLS/SCTP DataChannel 传输编码帧，不是 RTP/SRTP 视频轨道。普通帧通道 `ll-video` 无序、零重传；关键帧通道 `ll-key` 可靠有序。每片最多 12000 字节，重组帧最多 4MiB，同时保留最多四组分片，200ms 后清理缺片。帧序列缺失、重组超时或接收队列覆盖 H.264 帧时等待新关键帧，发送 `videoKey` 请求并由原授权路径校验。发送缓冲超限时不积压旧帧。维持原动态码率反馈，失败后 30 秒内使用 TCP，USB / ADB 路径优先保持 TCP。

文件能力字段为 `bulk:1`。`bulkOpen` 按剪贴板授权、源 offer / 文件 ID 和字节范围创建一次性文件连接，密钥仅通过配对控制通道传递；沿用挑战认证，再使用 AES-GCM 的独立 `lanlink-bulk-v1` 域传输 192KB 二进制块及 SHA-256。最多四个发送会话，桌面接收同批最多两个文件；按活动视频码率和控制状态共享限速预算，单次任务中网络异常最多两次从已写入校验块之后继续。没有跨重启持久化续传，旧端仍走原文件协议。

验证包含桌面间真实 WebRTC、协商失败回退、分片丢失 / 重复、独立文件与控制响应、已验证偏移续传、Android / 桌面两方发起 SDP 和双向分片 / 文件，以及安卓 ADB 实际屏幕 H.264 经 WebRTC 到桌面解码。合成帧测试用于编解码与协议回归，不代表实体手机上的输入到显示延迟；详见 VALIDATION.md。

## 0.3.15 恢复与呈现

Root 视频会话通过 `h264Only` 和能力字段 `videoH264Only` 告知控制端不能使用截图回退；旧控制端请求 JPEG 时返回 `VIDEO_H264_REQUIRED`，新版客户端据此恢复 H.264 协商。Root 编码 / 显示源异常在进程内重建，连续不可用超过 10 秒后退出，由窗口重连。输入助手短暂恢复不会被当成缺少 MediaProjection 授权。采集进程错误保存在应用缓存 `root-video-error.log`，编码异常使用 `LanLinkRootVideo` / `LanLinkVideo` 日志标签。

桌面接收侧在宽高变化时重建 FFmpeg 解码器，关闭旧进程并忽略其延迟输出，避免输出旧尺寸或旧比例。Windows 手机画面按像素尺寸锁定窗口，显示控件填满该比例的区域，不再套用 JPEG DPI 的另一套比例；鼠标坐标仍按整个像素画面归一化。macOS 同步使用像素尺寸与实际工具栏测量，尚待编译验证。

## TCP 兼容路径与共同会话管理

- 远程窗口自动协商 `videoOpen`。授权检查在创建监听端口前执行，首个视频客户端认证成功后才启动捕获。
- 每个会话使用随机 256 位密钥，通过现有已配对 AES-GCM 控制连接交付。独立 TCP 端口使用双向 HMAC 挑战验证，结合双方 nonce 派生视频密钥。帧使用 AES-256-GCM、严格递增序号和尺寸上限；重放或篡改会断开。
- 网络上的 H.264 / JPEG 数据是二进制，取消两层 JSON / Base64 图像传输。键鼠继续使用可靠控制连接；新版文件使用独立二进制连接，旧端文件协议仍兼容。
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
| Android Root | UID 0 镜像主屏 Surface → MediaCodec H.264；最大长边 1600、目标 30 fps、自适应 0.5–8 Mbps；方向变化重建编码器；控制端须支持 H.264 | 与普通 Android 相同 |
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
缺少 FFmpeg 的桌面可使用普通 JPEG 兼容串流，但不能查看只提供 H.264 的 Root 手机屏幕。安卓使用系统编解码器，无需安装 FFmpeg。

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

## Root 采集验证

`scripts/root-screen-smoke.js` 的 Root / 旋转测试仅允许 ranchu / goldfish 模拟器；`--adb --serial=<序列号>` 可验证真机的 ADB shell 采集。Root 测试，调用实际 UID 0 显示镜像和编码进程，检查带尺寸边界的 H.264 帧、IDR、码率控制、停止及 FFmpeg 完整解码。`--rotate` 另测横竖屏并恢复模拟器原旋转设置。

ADB / Root 助手按需启动独立采集进程。应用在 127.0.0.1 上创建临时通道，每次使用随机密钥和双向 HMAC 身份校验；认证通过后才开始捕获。控制指令和编码帧通过该设备内部通道传输，局域网仍使用原有加密视频协议。通道关闭后子进程停止。采集启动无帧有超时保护，异常退出关闭媒体会话，让现有播放器重新协商；静止屏幕使用视频心跳。重连请求关键帧时，如果编解码器已停止重复静止 Surface，则重新创建编码 Surface。关闭 Root 或到达共享结束延时会结束采集进程。

模拟器 UID 0 验证不等同 Magisk / KernelSU App 授权流程验证，后者需已 Root 真机。显示镜像接口参考 [AOSP DisplayManager](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/hardware/display/DisplayManager.java)，内部 API 的 ROM 兼容性仍有限制。
