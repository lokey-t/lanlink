# LanLink 核心功能深度测试报告（2026-10-09）

## 本轮结果与范围

- 当前工作树版本：package.json 为 0.3.45。Node v24.21.0，npm 12.1.0，macOS。
- `npm run check`：通过，JavaScript syntax checks passed。
- `npm test`：202 项，201 通过、0 失败、1 跳过，17.6 秒。唯一跳过项是默认需显式打开的 macOS 原生远程窗口探针。
- `LANLINK_MAC_UI_TESTS=1 node --test tests/mac-remote.test.js`：该 1 项独立补跑通过，72 秒（包括 Swift 编译）。因此这 202 项既有测试均已在本轮运行通过。
- 额外定向验证确认 6 类问题，详见下文；显式撤销剪贴板授权后无原生写入的独立正向安全检查通过。
- 首次沙箱内执行因 `listen EPERM` 导致 114 fail / 1 cancelled / 1 skipped，86 pass。升级到允许本机监听后完整套件全绿，首次结果属于环境限制，不计产品缺陷。
- 本轮只使用临时目录、内存 FakeNative、模拟管道及本机加密环回连接。没有读取或修改用户系统剪贴板、注入实际键鼠、执行真实关机、改变用户组，也没有修改产品代码或已有用户文件。
- 自动化覆盖配对码、加密、成员/权限撤销、控制争用、断线恢复、文件/剪贴板、二进制/QUIC/WebRTC 传输、传输限速、进程关闭、Mac 单实例及 Mac 远控窗口换目标等。模拟系统能力不等于真实 Windows/Android/macOS 系统输入、录屏、剪贴板与弱网体验已完成验收。
- 本报告中每个问题都有本轮运行证据。UI影响中只来自代码阅读的部分单独注明；历史验证文档不作为本轮实测通过证据。

## C1 / P1：再次复制之前用过的内容，自动剪贴板静默丢同步

**类型：本轮服务集成实测；真实加密连接，模拟系统剪贴板事件。**

正常复现：A、B 均开启自动上传和自动获取，处于同一组。A 复制文本 X，B 收到 X；B 复制 Y，A 收到 Y；A 再次复制同一文本 X。频繁复用帐号、路径或固定文字时很容易出现这一顺序。

预期：最后一次主动复制 X 是最新内容，B 自动收到 X，两端的最新共享内容也变为 X。

实际：A 的系统剪贴板为 X，B 仍为 Y；A 的共享 `latest` 也保持 Y，界面不报错。事件级复测等待 1.5 秒仍如此；脚本同时模拟应用写入后的 clipboardChanged 回调，因此不是遗漏系统回调造成。

原因：`src/clipboard-sync.js:14` 将 `lastLocal` 永久作为抑制依据；远端应用新剪贴板时 `markApplied`（第15行）仅修改 `applied`，没有使之前的本机指纹失效。`publish` 第28行把后来的真实用户复制误判为回声。

建议：用“当前观察到的系统剪贴板版本/应用写入操作”判重，并在远端内容成功写入后更新或清除旧的本机判重状态；保留防回声机制。补充 X→Y→X、X→远端文件→X、多端轮换、相同内容重新主动复制的事件级回归测试。

证据：`/tmp/lanlink-clipboard-xyx.mjs`，`/tmp/lanlink-clipboard-xyx.log`；另在 `/tmp/lanlink-core-extra.log` 独立重现。

关键输出：

```text
STEP1: A user-copy X -> B = X
STEP2: B user-copy Y -> A = Y
STEP3: A user-copy X again -> A = X, B = Y; A shared latest = Y: another device text
```

## C2 / P2：失败的批量文件发送会耗尽发送额度，重启不能恢复

**类型：本轮服务/持久化实测，目标以授权拒绝响应模拟。**

复现：目标设备已连接但关闭文件共享许可。发送方选 100 个文件发送，收到拒绝；再重试同一批一次；随后发送任意 1 个文件。

预期：未被接收方接受的发送记录应回滚或可重试/删除，恢复目标权限后能正常重试。

实际：前两次各创建并保留 100 条发送记录，尽管目标实际只收到两次 `stage-offer` 请求。第三次直接报“发送记录已满，请先移除旧的分享记录或稍后重试”。重载持久化清单后仍保留 200 条无效记录。源文件均未改动。较少文件也会每次失败累积记录。

原因：`src/staging.js:46-47` 在远端确认前登记并持久化全部 `exports`；第54行只清理临时拖动分享，普通文件发送失败不回滚。第38行上限200，44行保存7天。现有 `stage-remove` 只删除接收侧 rows，未发现供用户管理发送侧 exports 的操作（此最后一点为动作定义的静态审查）。

建议：区分已确认与未确认项目；明确拒绝的未确认项目立即清理，响应丢失的项目用幂等状态查询恢复。增加失败批次重试、取消/删除发送记录能力，错误提示对应真正可执行的入口。覆盖100文件批次中途失败、权限拒绝、对端暂存区满、断线、重启后的恢复。

证据：`/tmp/lanlink-core-failure-extra.mjs`、`/tmp/lanlink-core-failure-extra.log`。原3次单文件失败验证在 `/tmp/lanlink-core-extra.mjs`。

关键结果：`attempts=2, selectedPerAttempt=100, remoteRequests=2, outgoingAfterFailure=200, outgoingAfterReload=200`。

## C3 / P2：重复手动上传同一文件后，“最新共享”指向已被清理的文件

**类型：本轮文件分享逻辑实测。**

复现：复制一个文件并手动上传；内容不变再手动上传20次；对方此时才请求最新分享中的文件，或者在这期间保持关闭自动获取后再下载。

预期：重复上传成功提示对应的最新分享始终可被下载；重复操作不应消耗文件快照额度。

实际：最新 offer id 一直复用首次的 id，但每次 snapshot 都登记一个新 offer，20条上限将首次真正对外公开的 offer 淘汰。读取该最新文件报“文件分享已过期或范围无效，请重新复制”，虽内容未改且距离正常过期时间很远。

原因：`src/service.js:282` 先创建快照并执行20条清理；随后 `src/clipboard-sync.js:25-26` 因内容相同转为广播旧 `latest`。新记录未使用，旧记录却被淘汰。

建议：在登记文件快照前判断可复用的有效 offer，或对最新/在传输的 offer 做保留；如果后台句柄已经失效则创建并广播新的完整 offer，而非复用旧 id。对重复上传与边界容量做回归验证。

证据：`/tmp/lanlink-core-extra.mjs`、`/tmp/lanlink-core-extra.log`：`offerCount=20, latestOfferPresent=false`。

## C4 / P2：在线设备改名后，其他设备继续显示旧名称

**类型：本轮两端加密服务实测。**

复现：在线且已交换身份的 A、B，A 在设置中改名 A-Renamed；刷新两端状态并执行心跳。

预期：B 的在线设备名称、记住的设备名称及选择器及时更新。

实际：A 的 state 为 A-Renamed，但 B 在线 peer.info.name 和 knownDevices.name 均仍为 A；服务没有因改名发送新身份。

原因：`src/service.js:506` 仅修改配置，`src/service.js:521` 仅保存。身份消息在握手时交换，改名路径未更新/发布身份和组内自身元数据。

建议：保存后发布受认证的新身份，同步组成员元数据与缓存，并明确旧消息的优先级，避免旧成员信息覆盖新名称。增加在线改名、多端显示、断线重连后的持久化验证。

证据：`/tmp/lanlink-core-extra.mjs`、`/tmp/lanlink-core-extra.log`：`local=A-Renamed, remote=A, remoteSaved=A`。并未宣称测试了真实 UI 文本提交。

## C5 / P2：授权请求期间设备离线后，120秒超时不再生效

**类型：本轮状态函数定向实测；原生窗口影响为静态审查。**

复现：已有一个授权请求，目标离线，请求 expires 已在过去；轮询 `access-status`。

预期：请求到期后返回 ended 并允许用户重新发起或选择另一台设备；窗口明确提示离线/超时。

实际：返回 `{ready:false,message:'设备已离线，等待重新连接'}`，没有 ended。离线条件一直成立时永远不会检查到 expires。`Access.request` 还会因为旧 notice 而阻止其他设备的授权请求，直到用户手动取消。

原因：`src/access.js:67` 离线分支在71行超时判断之前提前返回。授权请求的120秒期限定义在47行。

UI静态证据：`desktop/macos/AdbGuide.swift:68-71` 的窗口正文固定为请在目标设备确认，轮询只处理 ended/ready，没有显示返回的离线 message；Windows 的 `desktop/windows/Access.cs:22` 会显示该消息，但同样依赖 ended 关闭。

建议：首先处理绝对期限，再判断在线状态；到期取消并清理 notice/待续功能。Mac窗口同步显示后端状态，给出重试和取消。增加“等待授权→目标离线→请求过期”的完整窗口回归测试。

证据：`/tmp/lanlink-core-failure-extra.mjs`、`/tmp/lanlink-core-failure-extra.log`。通过将 expires 设为过去验证，不声称实际等满两分钟或操作过这个真实窗口。

## C6 / P3：一项设置校验失败时，其余设置可能已部分生效

**类型：本轮动作/持久化定向实测；普通原生界面常逐字段提交，故低优先级。**

复现：先保存深色主题，再提交一个包含 `{theme:'light',name:''}` 的混合设置请求。

预期：整次请求拒绝时保持原设置，或明确返回哪些设置成功。

实际：返回“设备名称应为 1–80 字符”，但内存主题已经为 light，磁盘仍为 dark。随后其他成功保存还可能把先前失败的修改落盘。

原因：`src/service.js:499-521` 边检查边直接修改当前配置，直到结尾才持久化。

建议：先完整验证并构建候选配置，再统一提交；存储失败时保持状态一致，包含选择主控产生的副作用。补充组合非法字段与保存失败验证。

证据：`/tmp/lanlink-core-extra.mjs`、`/tmp/lanlink-core-extra.log`：`inMemoryTheme=light, persistedTheme=dark`。

## 已复核但不列为产品缺陷

- 最新剪贴板 `latest` 比10分钟文字历史保留更久：README明确限制的是历史条目，当前剪贴板与历史的语义可能不同，不应据此认定过期缺陷。
- 控制/屏幕/关机默认组内信任：README明确0.3.45采用默认信任，不把旧版本逐次同意预期套用到当前版本。
- 配对换组主路径的错误码、元数据校验、原组邀请无害、明确解散确认、离线解散通知、重启续做等既有实测均通过。没有为了制造场景操作用户实际组。
- 显式禁止某来源剪贴板后，其文本 materialize 请求被拒绝且无 native.call，额外正向边界检查通过。

## 证据清单

- `/tmp/lanlink-qa-tests-unsandboxed.log`：本轮完整既有测试。
- `/tmp/lanlink-qa-tests.log`：首次沙箱监听被拒的环境诊断。
- `/tmp/lanlink-core-mac-remote.log`：macOS原生远控窗口补跑通过。
- `/tmp/lanlink-core-extra.mjs`、`/tmp/lanlink-core-extra.log`、`/tmp/lanlink-core-extra-results.json`：5个问题初步复现及1个安全边界。
- `/tmp/lanlink-clipboard-xyx.mjs`、`/tmp/lanlink-clipboard-xyx.log`：X→Y→X事件级独立复测。
- `/tmp/lanlink-core-failure-extra.mjs`、`/tmp/lanlink-core-failure-extra.log`：批量失败达到200条且重载仍满；离线授权超时。
