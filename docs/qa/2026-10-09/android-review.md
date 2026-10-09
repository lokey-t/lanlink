# LanLink Android 深度测试记录（2026-10-09）

## 范围与环境

- 当前工作树，Android 应用版本 0.3.45；使用本轮开始前刚生成的 app-debug.apk（01:48）与 app-debug-androidTest.apk（01:47）。测试未修改产品源码或原测试源码。
- 独立只读模拟器 `emulator-5566`：API 34 / Android 14，arm64，1080×2400 / 420 dpi，2 核 / 1536 MB，无窗口。原 `emulator-5554` 未安装、重置或操作。
- 只读副本启动后清除了**该副本**中的 LanLink 数据，建立隔离身份。桌面联调用临时身份及 FixtureNative，不调用真实桌面键鼠、剪贴板或屏幕；未真实关机。
- Android 操作通过仓库已有 instrumentation/probes；未自行使用 adb input / UIAutomator 编写操作流程。ADB 用于安装、运行探针、截屏、日志和管理一次性模拟器。

## 已通过的实际检查

| 检查 | 结果和范围 | 日志 |
|---|---|---|
| 默认综合探针 | PASS；原生页面、二维码显隐、空配对码拒绝、设置改名、权限/撤权/持久化、系统权限入口、触控板与系统键盘、视口缩放、控制竞争与断线清理、ADB-only 门控、组加密等 | `/tmp/lanlink-android-qa-default-clean.log` |
| 全屏 UI | PASS；进入横屏、左预览/右菜单、返回层级、开关保存、长宽比适配、渲染面稳定、退出恢复 | `/tmp/lanlink-android-qa-fullscreen-ui.log` |
| 全屏手势 | PASS；坐标映射、黑边不发点击、双指缩放平移、三指键盘、取消手势、拖拽释放 | `/tmp/lanlink-android-qa-fullscreenGestureProbe.log` |
| 输入专用触控板 | PASS；输入获取/释放不要求屏幕权限、不建视频流、取消和远端终止、能力门控 | `/tmp/lanlink-android-qa-inputOnlyTouchpadProbe.log` |
| 控制中心触控板 | PASS；3 个功能块、在线/离线/权限过滤、真实回环加密输入和退出释放 | `/tmp/lanlink-android-qa-touchpadCenterProbe.log` |
| 多组员授权 | PASS；4 个独立组员、默认授权、单设备限制、撤权持久化、恢复与共享编码生命周期 | `/tmp/lanlink-android-qa-groupConsentProbe.log` |
| 自适应传输 | PASS；鼠标短/长丢包、迟到点击、重排、码率/带宽恢复 | `/tmp/lanlink-android-qa-adaptiveTransportProbe.log` |
| JNI QUIC | PASS；握手、边界/突发丢包恢复、去重/重排、点击顺序屏障、uinput 指令 | `/tmp/lanlink-android-qa-quicProbe.log` |
| Root 拒绝 | PASS；无 su 时拒绝且保持非 Root 可用 | `/tmp/lanlink-android-qa-rootDenied.log` |
| Android↔Node 扫码配对 | PASS；扫描结果自动提交、真实 SRP 和加密连接、返回主页、显式撤权后恢复 | `/tmp/lanlink-android-qa-pairing-integration.log` |
| Android↔Node 文件暂存数据流 | PASS（见下方原版测试阻断说明）；文件/文件夹校验、失败保存保留、成功保存清理、临时拖动信息撤回、8 文件队列、系统分享选择器→桌面、QUIC 可靠流 | `/tmp/lanlink-android-qa-staging-dataflow.log` |
| Android↔Node 剪贴板 | PASS；双向文本、自动/手动开关、无回声、历史复制、ADB 助手后台读写 | `/tmp/lanlink-android-qa-clipboard-integration.log` |

截图：`/tmp/lanlink-android-qa-screens`（17 张）以及 `/tmp/lanlink-android-qa-screens-later`（24 张，包括前者相同截图与全屏/触控板新增截图）。已人工查看配对、错误输入、设置、触控板、全屏菜单与全屏设置布局；这些截图中未发现主操作被遮挡或明显渲染破损。

## 修改建议（按证据类型区分）

### A1 · P1 · 文件尚未收到就提示“已发送到”【实际复现】

- 复现：Android 系统分享 2 个测试文件到桌面；测试夹具把接收端下载启动延迟 8 秒，保留正常加密连接；在点击分享后约 4.5 秒截图并读取接收端任务状态。
- 实际：手机出现“已发送到 文件分享测试电脑”；同一时刻接收端两文件都是 `queued`，`received=0`，大小分别 400001 / 400002 字节。
- 影响：用户可能据此关闭应用、切断网络或以为文件已安全送达；发送端又没有可见的后续完成/失败记录。
- 预期/建议：元数据回执只显示“已开始发送/等待传输”；显示发送进度和失败重试；收到 `stage-complete` 之后才显示完成，应用离开前仍可查看状态。
- 验收：网络暂停时不得显示完成；接收方明确完成后才结束；断线时发送方能看到失败或待恢复。
- 证据：`/tmp/lanlink-android-qa-premature-success.png`；`/tmp/lanlink-android-qa-staging-slow.log` 的 `SLOW-RECEIVER SNAPSHOT`。最终文件继续传完并通过哈希检查，非人为制造文件损坏。
- 定位：`android/app/src/main/java/com/lanlink/app/MainActivity.java:1448`（share 返回即成功 Toast）；`StagingFiles.java:80`（等待的是 stage-offer）；`src/staging.js:62`（异步 start 后立即返回）。

### A2 · P2 · 扫描页承诺“仍需确认加入”与首次配对自动加入不一致【流程实测 + 文案静态证据】

- 复现：未建组的手机，扫描合法邀请（本轮 PairingFlowProbe 的真实 SRP 联调），不额外点击确认。
- 实际：自动提交、建立连接并返回主页；这是现有 PairingFlowProbe 明确断言的既定行为。扫描页却写“识别后仍需确认加入，不会自动解散组”。
- 建议：保留既定自动入组行为时，文案改为明确区分首次入组和“已有组时需确认解散”；或者统一增加入组确认并展示对方名称/组信息。此处不把既定自动入组策略本身判为安全漏洞。
- 验收：首次入组、已建组换组两个路径的提示与实际行为一致。
- 定位：`ScanActivity.java:56`；`MainActivity.java:393`（仅已建组弹确认）和 `MainActivity.java:411`（扫描结果自动 beginJoin）；`PairingFlowProbe.java:138`。

### A3 · P2 · 触控板掉线后“重新请求”按钮仍可点但无动作【静态路径确认，尚未独立动态复现】

- 复现条件：正在触控板会话时对端断线；当前会话持有旧 Wire 实例。
- 实际路径：界面显示“设备已离线，请返回列表并等待设备上线”，同时 `setReady(false)` 显示并启用“重新请求”；按钮调用在 `!peer.connected` 时直接返回。对端重连替换 Wire 后旧会话仍持有旧对象。
- 建议：离线时把按钮改为“返回设备列表”；若提供原地重连，重取当前在线 Wire 后重新申请，明确显示等待/失败状态。
- 验收：断线后每个可点击控件都有明确有效反馈；对端恢复后能按界面指引继续。
- 定位：`TouchpadDialog.java:37`、`:49`、`:154`、`:171`。

### A4 · P2 · 分享选择器请求中无禁用/进度，快速重复点击可能重复发送【静态路径确认，尚未动态连击复现】

- 复现条件：系统分享选择目标时，网络慢或 permission-status 回应慢，连续点击同一目标。
- 实际路径：每次点击都会进入单线程 actions 队列；目标行不禁用、不设 in-flight 标记；对话框在首个回复之后才关，已经入队的闭包没有检查 dialog 是否仍显示，仍会调用 `staging.share`。每次 share 都生成新的 offer UUID。
- 建议：首次点击同步设置忙碌态；本次分享使用一次性请求标识并做幂等，失败后再恢复选择。
- 验收：慢网连续点击只生成一份发送任务；取消后不继续发送新请求。
- 定位：`MainActivity.java:1448`；`StagingFiles.java:76`。

### A5 · P2 · 暂存区 X 直接永久移除已接收但未保存文件【静态操作审查】

- 复现条件：接收成功但尚未“保存到…”的文件，点击行尾 X。
- 实际路径：直接 `staging.remove`，索引删除并递归删除本地副本，无确认/撤销。
- 建议：将 X 明确标为“删除”，为已接收未保存内容提供确认或短暂撤销，区分关闭展示与删除内容。
- 验收：误触可恢复；用户明确知道该操作会删除手机收到的副本。
- 定位：`MainActivity.java:1365`；`StagingFiles.java:88`、`:96`。

### A6 · P3 · 共享内容说明引用不存在的设置名【静态文案审查】

- “本机内容”面板说需在设置开启“剪贴板与文件”，当前设置页条目是“共享剪贴板”“组设备授权管理”，没有同名总开关。
- 建议：直接链接到对应设置，分别解释自动同步开关与单设备共享权限。
- 定位：`MainActivity.java:1473` 与 `:1451`、`:690` 附近。

## 测试体系需要修正，不能当作产品已通过

1. **独立 ADB 探针重复失败**：`adbPermissionProbe` 两次在 `AdbPermissionProbe.java:44` 的 `phone confirmation is visible` 断言失败。日志 `/tmp/lanlink-android-qa-adbPermissionProbe.log` 和 `...-retry.log`。综合探针同一撤权恢复路径却通过且有 `adb-allow-request.png`。可能是独立夹具、前台/生命周期或假连接被定时器清理的时序问题，根因未确认。建议先稳定夹具再判断产品，报告不可写“所有测试通过”。
2. **原版文件联调被过时标签阻断**：`StagingProbe.java:38` 查找“文件暂存区功能/剪贴板功能”，当前 `Ui.controlTile` 描述已改为“文件暂存区/剪贴板，已选择”。原日志 `/tmp/lanlink-android-qa-staging-integration.log`。本轮只在 `/tmp` 主机脚本副本中跳过 `step('ui')`，其余数据流全部跑通；原断言未悄悄修改。应同步更新现有探针标签或稳定测试 ID。
3. **首轮截图瞬态失败**：首次默认探针在 `capture("join-group")` 返回 null。清空隔离身份后综合探针重试通过，17 张截图正常。原日志 `/tmp/lanlink-android-qa-default.log` 保留，未据此判定应用崩溃。
4. **源码视频测试环境**：首次 `--transport --quic` 验证了两向 WebRTC 帧片、QUIC 文件校验后，真实屏幕因源码环境未配置 FFmpeg 路径而返回 `VIDEO_H264_REQUIRED`。包内 FFmpeg 存在；另设该已打包路径重跑（最终结果补在末尾）。

## 尚未覆盖的现实环境

真实手机相机光学扫码、Android 29/35 与不同厂商系统、Root 成功路径、真实 Wi-Fi 漫游/AP 隔离、真机功耗/发热/数小时后台、超大文件与低磁盘容量、TalkBack/大字体、真实多台计算机同时操控。全屏使用模拟画面验证 UI/手势；真实编码传输结果另记。没有触发真实关机。

## 收尾补充

- **真实视频/输入联调成功**：显式配置包内 FFmpeg 后，`/tmp/lanlink-android-qa-transport-ffmpeg.log` 全部 PASS：两向 36 KB WebRTC 帧片；800/850 KB QUIC 可靠文件传输与 SHA-256；真实 Android ADB helper 注入鼠标坐标和点击；模拟器真实屏幕 H.264 → QUIC Datagram → 桌面 FFmpeg 连续解码 10 帧；随后剪贴板后台回归再次通过。此结果是短时真实编码/传输链检查，不是长时间压力或真机性能结论。
- **APK 时间一致性**：逐个检查 `android/app/src/main` 文件，没有任何文件晚于应用 APK 的 2026-10-09 01:48:35；`src/androidTest` 没有文件晚于测试 APK 的 01:47:45；根 Gradle、settings、properties、app/build.gradle 与 Manifest 均早于 APK。当前源码离线构建验证结果随后记录。
- **隔离资源已清理**：测试后仅终止 emulator-5566；ADB 设备列表再次确认只保留原 emulator-5554 在线。只读副本丢弃全部测试修改，截图与日志保留在 /tmp。
- **当前源码离线构建成功**：Gradle 8.11.1，使用用户已缓存依赖，`:app:assembleDebug :app:assembleDebugAndroidTest --offline --no-daemon`，`BUILD SUCCESSFUL in 41s`，60 个任务（9 executed / 51 up-to-date）。日志 `/tmp/lanlink-android-qa-build.log`。构建仅产生 API deprecated/JDK native-access 提示；本轮没有产品源码变更，因此未重复所有已通过设备套件。测试 APK 为测试前现有构建，最终源码构建另行成功，二者明确区分。
