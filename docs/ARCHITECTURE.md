# 架构与协议 v1

## 产品模型

组连接使用一份可轮换的 32 字节随机秘密，编码为 `lanlink1:<base64url>`。不是用户名
密码。每台设备另外保留独立 RSA-2048 公私钥和 UUID，组有稳定标识。私钥不离开本机，成员移出时为剩余设备分发加密更新，见 [成员密钥](GROUPS.md)。
同一身份的成员彼此信任，但仍受每台设备本地权限开关约束。

桌面：Windows WPF / macOS AppKit + SwiftUI 原生界面，不包含 WebView 或 Electron。
界面启动 Node.js 子进程 src/native-host.js，通过继承的匿名管道发送 JSON 请求/响应。
只有局域网协作端口，无本机 HTTP 控制台。界面退出关闭管道，服务释放控制权并退出。
原生系统操作继续由 C#/Swift 子进程负责，系统权限、目标授权与控制权校验仍在服务端执行。
发送文件由原生文件选择器提供路径，经本机剪贴板共享再粘贴到目标；不会绕过目标输入或剪贴板权限。
设备历史按离线身份保存；切换身份时清除历史设备及布局。配置写入按目录串行化，避免拖拽和发现同时写入损坏。
设备气泡与画布使用同一个设备集合与菜单，布局吸附为主设备四个方向；停留时间全局共用。
Windows 模糊使用系统 DWM backdrop，macOS 使用 NSVisualEffectView；系统不支持时降级。
HTTP 入口仅保留为开发测试工具，原生软件不会启动它。

Android：前台 Service 保持网络连接，Activity 提供界面，AccessibilityService
与 InputMethodService 提供基础控制。MediaProjection 处理用户授权录屏。
可选的 shell UID / root 助手仅负责输入注入，有独立环回通道和临时凭据。

## 动态配对 v2（0.3.3）

- 配对展示由本机界面调用 pairing，返回八位数字 code、随机 invitation id、expiresAt、remainingMs、durationMs。默认不返回二维码或组密钥；reveal=true 才生成二维码。
- 数字码使用密码学随机数生成，范围 00000000–99999999，有效期 60 秒。界面轮询只续展 15 秒展示租约，不延长数字码本身有效期；关闭时 pairing-stop 即刻撤销，异常退出最多 15 秒租约后失效。
- 二维码为 lanlink://pair?v=2&code=<8 digits>&id=<invitation id>。仅携带临时邀请，不含长期组密钥；二维码扫描优先匹配该 invitation，手输数字时查找局域网中正在展示邀请的设备。
- UDP 45833 接收探测，探测包含随机 nonce，响应仅带相同 nonce、邀请 id 和临时 TCP 监听端口。网络上不广播数字码、数字码哈希或组密钥。最多收集 16 个候选，同组已有连接仍使用原协议。
- TCP 握手采用 SRP-6a，固定 RFC 5054 附录 A 的 2048-bit N 和 g=2，SHA-256。I 为 lanlink-pair-v2/<id>，P 为八位数字；每次邀请随机 16 字节 salt，每次会话独立随机 256-bit 私有值。
- k=H(PAD(N)|PAD(g))，x=H(salt|H(I:code))，u=H(PAD(A)|PAD(B))；双向公共值及 u 经校验。会话键为 H(PAD(S)|context)，context=H(lanlink-pair-v2|id bytes|salt bytes|PAD(A)|PAD(B))。分别以 HMAC-SHA256(key, client|<context hex>) 与 server|<context hex> 进行双向证明。
- 服务端验证客户端证明后，消费邀请，再将长期 token 和日常设备端口以 AES-256-GCM 加密发送。沿用 wire.js 的 seq=0 / AAD=lanlink-v1:0 格式，每个握手独立会话密钥、只发送一个此类密文。
- 全局每 60 秒最多 10 次握手尝试，不因轮换或关闭重开而重置；限制并发连接、消息大小与超时。数字码相同且来自不同组时拒绝歧义结果。错误或过期邀请不会修改本机组。
- 凭据交换后保存组，清理旧设备历史和布局，并立即对配对来源建立正常的加密连接。手动输入长期 token 的旧本机 API 仅保留迁移兼容，原生界面只提供八位码。
- 算法依据：[RFC 5054](https://www.rfc-editor.org/rfc/rfc5054)。应用层消息和证明格式是本项目协议，并非 TLS-SRP，实现尚未经独立安全审计。

## 发现

- UDP 45832，向广播地址及本机网段广播。
- 外层 JSON `{body,proof}`，`proof = HMAC-SHA256(secret,"discovery|"+body)`。
- body 为原始 JSON 字符串：`{v:1,id,port,time}`，时间单位为 Unix 毫秒。
- 接收时间窗 30 秒，校验 UUID、协议版本、端口和身份签名。
- UUID 字典序较小的一端发起连接。重复连接按相同方向优先规则收敛。
- 手动连接仍执行完整身份认证；发现不是授权。

## 会话

TCP 默认 45831。两端先发逐行 JSON hello：

```json
{"kind":"hello","v":1,"id":"设备 UUID","nonce":"32 字节随机数的小写 hex"}
```

按 UUID 字典序拼接 `idA:nonceA|idB:nonceB` 得到 transcript。
双方发送自己的 `HMAC-SHA256(secret,"auth|"+transcript+"|"+自己的UUID)`。
认证失败关闭连接。名称、平台和能力在加密完成后发送。

以 SHA256(transcript) 作为 HKDF salt，secret 作为 IKM，SHA-256 派生两条
32 字节方向密钥。info 分别是 `lanlink-v1/a-b`、`lanlink-v1/b-a`。

每个加密数据包：

```json
{"seq":0,"body":"base64(ciphertext || 16-byte GCM tag)"}
```

- AES-256-GCM，nonce = 4 个零字节 + 8 字节大端序 seq。
- AAD = UTF-8 `lanlink-v1:<seq>`。
- 双方向独立、严格递增序号；错序/重放/认证失败立即断开。
- 每次连接使用全新双端随机数重新派生密钥。
- 单行最多 8 MiB；请求数量、握手时间、连接数、输出缓冲限制。
- 心跳每秒，连接无响应约 10 秒关闭；输入控制租期约 4 秒。
- 原生输入助手另有约 3 秒 watchdog，在上层退出时释放输入。

这是项目自有 PSK 协议，**没有前向保密**：身份秘密泄露可能导致历史会话
被解密。尚未经独立安全审计。产品化前应迁移到成熟、经过审计的 TLS/Noise
方案，并使用逐设备密钥与撤销机制。不能把“使用 AES”视为整体安全已获认证。

## 消息

加密载荷类型为 identity、ping/pong、clipboard、request、response。
request 包含随机 id、method、args，response 使用同一 id 和 result 或 error。

方法：acquire / release / keepControl / input / text / screen / clipboardGet /
fileChunk / paste / shutdown / cancelShutdown。

输入的键名使用 DOM KeyboardEvent.code，位置是目标主显示器的 0–1 归一化坐标。
滚轮采用 Windows 的 120 单位/格。平台层负责转换，中文通过独立 text 方法提交。
一次仅一个输入来源获得控制租约。屏幕查看受独立权限控制。

## 剪贴板与文件

剪贴板通知包含 offer id、文本、文件清单（随机文件 id、名称、大小）。
不向对端泄露本地源路径。接收方记住实际认证连接的 source UUID，粘贴时按需
从该 source 获取分块；所有设备形成直连网状网络。

文件块 192 KiB，以 SHA256 校验块内容，随后按预告长度核对。只有已发放的
offer/file id 可读，分享有效期一小时，最多保留 20 份。Windows/Mac 读取前
检查源文件大小/修改时间。安卓内容提供者可能不支持可靠修改时间，故仅核对
大小、块校验与可读性；传输过程中不要修改源文件。

接收目录每批随机创建；限制大小、文件数，拒绝路径穿越和 Windows 保留名，
不覆盖用户已有文件。传输失败移除该批次；完整接收后设置系统剪贴板。
已完成的接收文件保留，用户可清理。安卓通过只读 ContentProvider 提供内容。

## 系统限制的依据

### 设备授权与系统权限

配对组只建立加密连接。0.3.4 在控制、屏幕、剪贴板读取 / 广播、文件读取、粘贴和关机
之前增加按远端 UUID 的本机授权检查。未确认请求仅生成一个待确认条目，最多 8 个，
2 分钟过期。决定需匹配随机 requestId 和仍在线的设备；仅本机 UI 通道可提交决定。
同意时保存各功能布尔值；未勾选项拒绝执行且不重新弹窗。更换组会清除授权和请求。
拒绝后有 2 分钟冷却；失效请求有短暂冷却。设备断开清除待确认请求，保留已保存授权。

本机主动控制另一设备期间，若尚无该设备的授权记录，可临时双向接力剪贴板。
这项例外随控制结束失效，不授予反向键鼠控制；已明确禁止剪贴板的记录始终优先。

系统权限独立于设备授权。Windows / Mac 的能力由原生模块报告；Android 检查输入助手、
输入助手和当前 MediaProjection 会话。客户端遇到 `CONSENT_PENDING` / `PERMISSION_REQUIRED`
时以约 2 秒间隔重试，遇到明确拒绝时停止自动请求。UI 去除这些内部错误前缀。

Android 导航使用 `navigation` 输入类型，action 仅允许 back / home / recents，仍要求
有效控制权。ADB / Root 助手注入系统导航键。

## 控制方向与安卓增强输入（0.3.7）

每个端点一次只处于控制方或被控方。桌面在发出 acquire、videoOpen 和截图请求前同步保留控制方向，Android 远程窗口与待处理 acquire 同样保留方向。入站请求在操作前重新检查方向；取消与换组后，迟到的授权／串流会被释放，不恢复控制。截图兼容路径使用短租约并提供 screenStop 释放；被控提示覆盖输入、串流和截图。该约束也禁止一台设备在被查看时转而控制第三台设备。

ADB 页面使用本机 Platform-Tools，必须选择序列号。adbBootstrap / adbStatus 走现有加密控制连接，并受手机的设备输入授权、暂停状态和应用开关检查。桌面只接受严格格式的参数，核对所选 ADB 设备的 LanLink 安装路径，固定启动 ShellAgent，再通过目标手机确认助手心跳。安装路径检查不是设备公钥身份证明；明确选择正确的调试设备仍是用户操作步骤。

0.3.9 安卓仅使用 Root／ADB 输入助手，移除无障碍触控与 IME 服务。ShellChannel 在应用私有偏好中保存凭据和环回端口，双方使用随机挑战及角色分离 HMAC 验证，ShellAgent 断线后重连。桌面持久保存手动批准的设备公钥与调试序列号，每 30 秒检查授权和助手状态；仍为本组成员且允许输入时恢复已信任的调试设备，不选择其他手机。手机重启仍需可用的调试连接。MediaProjection 需单独确认。
Mac 登录自启动使用 [SMAppService.mainApp](https://developer.apple.com/documentation/servicemanagement/smappservice/mainapp?language=objc)，
如需系统批准，打开登录项设置；Windows 使用当前用户 Run 项，不注册系统服务。

- Windows SendInput 与完整性级别限制：
  https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-sendinput
- macOS ScreenCaptureKit：
  https://developer.apple.com/documentation/screencapturekit
- Android 每次 MediaProjection 授权：
  https://developer.android.com/media/grow/media-projection
- Android ADB：
  https://developer.android.com/tools/adb
- Android 剪贴板和文件 URI：
  https://developer.android.com/develop/ui/views/touch-and-input/copy-paste
