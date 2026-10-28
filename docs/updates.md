# 客户端更新 / Client updates

Windows、macOS 和 Android 默认自动检查正式版本，每 6 小时检查一次；设置中的“自动检测更新”仅影响本机，关闭后仍可手动检查。首次启动也会检查。Android 在回到前台时检查是否到期。

Windows and macOS check stable releases at startup and every six hours. Android checks when returning to the foreground if six hours have elapsed. The local setting disables automatic checks; manual checks remain available.

## Release 资产

在 GitHub `lokey-t/lanlink` 和 Gitee 同名仓库的 Release 上传**完全相同**的资产，tag 使用 `v版本号`。客户端先尝试 GitHub，再尝试 Gitee；下载按清单中的 SHA-256 和字节数校验。尚未上传更新清单的旧 Release 不会触发更新。

每个版本只有一个 `LanLink-版本-delta.json.gz`，同时包含所有已构建平台的增量数据；另上传 `lanlink-update.json` 和各平台全量资产。桌面全量资产为完整安装树的压缩清单，Android 全量资产为 APK。常规 ZIP/安装包可继续发布，更新器使用此脚本生成的 full 资产。

Upload identical assets to both Releases, tagged `vVERSION`: one combined delta, the update manifest, and each target's full asset. Conventional distribution ZIPs may coexist; the updater uses the generated full assets.

## 生成

先完成各平台正式打包，保留上个版本的原始安装目录及 APK。创建输入 JSON，例如：

```json
{
  "version": "0.3.48",
  "targets": {
    "win32-x64": {"from":"0.3.47", "previous":"old/windows", "current":"new/windows"},
    "darwin-arm64": {"from":"0.3.47", "previous":"old/LanLink.app", "current":"new/LanLink.app"},
    "android": {"from":"0.3.47", "previous":"old.apk", "current":"new.apk"}
  }
}
```

```sh
node scripts/build-update.js release-input.json dist/update-0.3.48
```

Windows 输入为含 `LanLink.exe` 和 `runtime` 的目录；macOS 输入为完整、已签名的 `.app`；Android 输入为与旧版本相同签名的 APK。可增加 `darwin-x64` 等实际支持的架构。生成器拒绝覆盖已有资产。保留包内相对符号链接，拒绝指向包外的链接。

Build final distribution trees first. Windows input is the directory containing `LanLink.exe` and `runtime`; macOS input is the signed `.app`; Android input is an APK signed with the existing installation key. The generator refuses existing output assets and links escaping the package; relative internal symbolic links are preserved.

## 失败与安装

补丁仅针对清单的 `from` 版本；跨版本、基础文件校验失败、损坏或缺失补丁、合成失败均自动下载全量资产。全量下载也失败时保留原安装并显示错误。旧版本无需发布多个增量包。

桌面先构建完整的新目录，退出 UI 和服务后才替换安装目录；替换失败恢复旧目录，错误保存在更新临时目录的 `plan.json.error`。原目录保留为 `.backup-时间戳`。需有安装目录及其父目录的写权限；源码运行不支持自替换。macOS 更新后沿用新包的签名，实际权限行为应在 Mac 真机验证。

Android 校验并合成完整 APK 后打开系统安装器；首次需允许“安装未知应用”，授权返回后继续。拒绝安装不会覆盖现有应用。系统安装失败（如签名不一致）需修正发布包；全量 APK 不能绕过系统签名要求。

An unsupported base version, corrupt/missing delta or reconstruction failure triggers a full download. Desktop replacement occurs only after both processes exit, with directory rollback on replacement errors and a retained backup. Android installation requires user confirmation and matching package signatures. SHA-256 protects asset integrity against corruption; trust is rooted in HTTPS and the repository's Release publishing permissions.
