# macOS 构建与验证记录（2026-10-07）

## 结论

0.3.44 在本机 Apple Silicon Mac 上经修复后可编译、打包、启动；160 项自动测试全部通过。此结论不等同于跨设备实机功能全部验收。

## 环境与产物

- 来源： https://github.com/lokey-t/lanlink ，基础提交 `59d03b8`，附本次本地修复（未提交）。
- macOS 15.7.7 / arm64，Apple Swift 6.0.2，Node.js 24.21.0，Rust 1.99.0，CMake 4.4.4。
- 应用：`dist/LanLink-0.3.44-macos-arm64/LanLink.app`。
- 压缩包：`dist/LanLink-0.3.44-macos-arm64.zip`，约 64 MiB。
- 本机构建采用 ad-hoc 签名，未进行 Apple 开发者签名或公证；仅生成 arm64 版，未验证 Intel Mac。

## 修复

1. 主界面剪贴板快捷按钮条件分支缺少闭合括号，导致 Swift 编译失败。
2. 布局网格的 CGRect 混用 Int 与小数尺寸，改为显式 CGFloat；拆分连接线绘制与权限说明表达式以解决类型检查失败。
3. 尚未选择主设备时，保存普通设置错误提交空 masterId；现在仅提交已选择的主设备，并显示“尚未选择”。保存普通设置不会隐式启用主设备。
4. 服务启动完成后清除“正在连接”提示，显示“设备服务已连接”。

## 验证结果

- `npm ci --ignore-scripts`：通过。
- `npm run check`：通过。
- macOS 原生 Bridge 编译及能力读取：通过，识别 1440 × 900 屏幕；input / host / screen 为 false，系统权限尚未授权；clipboard / shutdown 仅为能力声明，没有执行实际剪贴板读写或关机。
- Swift 原生桌面编译：通过，保留一项 onChange 旧接口弃用警告。
- Rust QUIC release 编译及完整打包：通过。
- `npm test`：最终 160 通过，0 失败，0 跳过，包含原生 QUIC Datagram、可靠流、证书校验和暂存文件测试。
- 首次并发测试曾出现组成员移除测试 3 秒超时；该文件单独复测 26/26 通过，后续两次完整测试均通过。保留为时序稳定性观察项。
- `codesign --verify --deep --strict`、Info.plist 检查、arm64 架构检查：通过。
- 原生界面使用隔离数据、模拟模式验证：设备页、设置页、权限页、剪贴板空列表、文件暂存空列表、动态八位配对码和二维码显示正常。
- 首次未选主设备时保存名称成功，界面提示“本机设置已保存”，隔离配置中名称已持久化。
- 最终应用包使用自带 Node.js 启动，界面显示本机设备与“设备服务已连接”。

## 未覆盖与限制

- 未授予辅助功能、输入监控、屏幕录制权限；真实键鼠捕获/注入、截图、跨屏切换仍待授权实测。
- 无第二台已配对设备，未进行真实 Windows / Android / Mac 之间的配对、文件拖出、远程控制和弱网测试。
- 当前应用包未捆绑 FFmpeg，H.264 编解码不可视为已验证；JPEG 回退也未进行真实屏幕验证。
- 未测试关机、登录自启动、锁屏、系统特权窗口和长时间稳定性。
- 两个独立测试实例同时启动时默认服务端口冲突；已关闭临时实例后验证最终包。

## 复现构建

```sh
npm ci --ignore-scripts
LANLINK_NODE_DIST=/path/to/node-distribution sh scripts/package-macos.sh
npm run check
npm test
node scripts/native-smoke.js
```

Node 发行目录需包含 `bin/node` 和 `LICENSE`。构建前安装 Rust、CMake 和 Xcode Command Line Tools。已有同名 dist 目录时打包脚本会拒绝覆盖。详细本地日志位于 `.tools/`，包括 `package-macos.log`、`swift-build.log`、`test-with-quic.log`。
