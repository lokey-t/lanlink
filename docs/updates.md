# 客户端更新 / Client updates

Windows、macOS 和 Android 默认自动检查正式版本，每 6 小时检查一次；设置中的“自动检测更新”仅影响本机，关闭后仍可手动检查。首次启动也会检查。Android 在回到前台时检查是否到期。

Windows and macOS check stable releases at startup and every six hours. Android checks when returning to the foreground if six hours have elapsed. The local setting disables automatic checks; manual checks remain available.

设置中的“更新通道”决定检查和下载哪个发行版。正式版通道只接受正式发行，测试版通道只接受公开的预发行版。切换通道后可手动检查；若已安装版本高于该通道的版本，更新器会保留当前版本。

The update channel controls which releases can be checked and downloaded. Stable accepts stable releases and Beta accepts public prereleases. After changing the channel, check manually to look for an update immediately. The updater keeps the installed version when it is newer than the release on the selected channel.

## Release 资产

在 GitHub `lokey-t/lanlink` 和 Gitee 同名仓库的 Release 上传**完全相同**的资产，tag 使用 `v版本号`。客户端先尝试 GitHub，再尝试 Gitee；下载按清单中的 SHA-256 和字节数校验。尚未上传更新清单的旧 Release 不会触发更新。

发布测试版时，在镜像上都标为预发行版，并为每次构建使用独立的数字版本号，例如 `v0.92.0`。后续要让测试版用户升级到正式版时，正式版的数字版本号和 Android `versionCode` 均需更高。

每个版本只有一个 `LanLink-版本-delta.json.gz`，同时包含所有已构建平台的增量数据；另上传 `lanlink-update.json` 和各平台全量资产。桌面全量资产为完整安装树的压缩清单，Android 全量资产为 APK。常规 ZIP/安装包可继续发布，更新器使用此脚本生成的 full 资产。

Upload identical assets to both Releases, tagged `vVERSION`: one combined delta, the update manifest, and each target's full asset. Conventional distribution ZIPs may coexist; the updater uses the generated full assets.

发布前从两个公开 Release 分别检查清单列出的每个资产可下载，并核对字节数和 SHA-256。还需验证官网下载区的 Windows EXE/ZIP、macOS DMG 和 Android APK 链接。

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

macOS 正式构建须给 `scripts/package-macos.sh` 提供 `LANLINK_MAC_SIGN_IDENTITY`（Developer ID Application）和 `LANLINK_MAC_NOTARY_PROFILE`（已配置的 notarytool 钥匙串配置名）。打包流程签署并公证应用和 DMG。使用完成公证的应用生成更新资产。后续版本保持 Team ID、`com.lanlink.native` 和 `com.lanlink.native.bridge` 不变，并验证从前一个正式签名版本更新后辅助功能、输入监控和屏幕录制仍可用。从临时签名版迁移时，系统可能要求重新授权。

## 失败与安装

桌面增量仅适用于匹配的基础版本。跨版本、基础文件校验失败、损坏或缺失补丁、合成失败均下载全量资产。三端显示下载字节数、速度和校验状态。应用内下载失败时，更新页面提供目标版本的发布页；检查失败且目标版本未知时，提供官网下载区。

桌面先构建完整的新目录，退出 UI 和服务后才替换安装目录。重新打开后，更新页面显示上次安装结果。替换失败时尝试恢复旧目录，并将详细错误写到更新临时目录的 `plan.json.error`。成功替换后原目录保留为 `.backup-时间戳`。需有安装目录及其父目录的写权限；源码运行不支持自替换。macOS 更新后沿用新包的签名，实际权限行为应在 Mac 真机验证。

Android 直接下载全量 APK。校验完成后选择「立即安装」，首次安装需允许「安装未知应用」。授权返回后进入系统安装确认。取消后可继续使用已下载的 APK。旧签名安装会收到迁移提示。

An unsupported desktop base version, corrupt/missing delta or reconstruction failure triggers a full download. Desktop replacement occurs after both processes exit, with directory rollback on replacement errors and a retained backup. Android downloads the full APK and keeps the verified download available if installation is canceled. All clients show download progress and offer the official download page after an in-app download failure. SHA-256 protects asset integrity against corruption; trust is rooted in HTTPS and the repository's Release publishing permissions.
