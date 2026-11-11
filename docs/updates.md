# 客户端更新 / Client updates

Windows、macOS 和 Android 默认自动检查正式版本，每 6 小时检查一次；设置中的“自动检测更新”仅影响本机，关闭后仍可手动检查。首次启动也会检查。Android 在回到前台时检查是否到期。

Windows and macOS check stable releases at startup and every six hours. Android checks when returning to the foreground if six hours have elapsed. The local setting disables automatic checks; manual checks remain available.

设置中的“更新通道”决定检查和下载哪个发行版。正式版通道只接受正式发行，测试版通道只接受公开的预发行版。切换通道后可手动检查；若已安装版本高于该通道的版本，更新器会保留当前版本。

The update channel controls which releases can be checked and downloaded. Stable accepts stable releases and Beta accepts public prereleases. After changing the channel, check manually to look for an update immediately. The updater keeps the installed version when it is newer than the release on the selected channel.

## Release 资产

在 GitHub `lokey-t/lanlink` 和 Gitee 同名仓库的 Release 使用同一个 `v版本号` 标签和更新清单。两站共有的附件必须逐字节一致。超过 Gitee 附件大小限制的文件在 Gitee 更新说明中链接到 GitHub。客户端按清单中的 SHA-256 和字节数校验下载内容。尚未上传更新清单的旧 Release 不会触发更新。

发布测试版时，在镜像上都标为预发行版，并为每次构建使用独立的数字版本号，例如 `v0.92.1`。后续要让测试版用户升级到正式版时，正式版的数字版本号和 Android `versionCode` 均需更高。

每次正式打包后，为 Windows、macOS、Android 各生成独立的增量包和全量包。桌面按架构分别生成。每个客户端只下载自己对应的资产。将 `lanlink-update.json` 和清单列出的所有资产随该版本上传到 Release。桌面全量资产是完整安装树的压缩清单，Android 全量资产是已签名的 APK。

Upload the update manifest and each target's delta and full package to both Releases tagged `vVERSION`. Desktop targets are architecture-specific. Conventional installers and ZIPs can be published alongside the update assets.

发布前检查两个公开 Release 的附件及超限文件链接，并核对清单中的字节数和 SHA-256。还需验证下载区的 Windows EXE/ZIP、macOS DMG/ZIP 和 Android APK 链接。

## 生成

三端正式包都编译并签名后，使用紧邻的上一发行版原始安装目录及 APK 生成资产。多个通道的用户也可以通过全量包跨版本升级。创建输入 JSON，例如：

```json
{
  "version": "0.3.48",
  "previousVersion": "0.3.47",
  "targets": {
    "win32-x64": {"previous":"old/windows", "current":"new/windows"},
    "darwin-arm64": {"previous":"old/LanLink.app", "current":"new/LanLink.app"},
    "android": {"previous":"old.apk", "current":"new.apk"}
  }
}
```

```sh
npm run package:update -- release-input.json dist/update-0.3.48
```

Windows 输入为含 `LanLink.exe` 和 `runtime` 的目录，macOS 输入为完整、已签名的 `.app`，Android 输入为正式签名的通用 APK。设置 `ANDROID_HOME`（含 SDK Build Tools）和 `JAVA_HOME`，或将 Java 加入 PATH。生成器核对桌面构建版本和 Android 包名，要求 Android `versionCode` 递增、沿用上一版签名、继续支持上一版的最低系统版本和 CPU 架构。可增加 `darwin-x64` 等已构建的架构。输出目录必须是新的。

Use the exact previous release as the delta base and the final signed builds as the new inputs. Configure the Android SDK Build Tools and Java. The generator verifies the build versions, Android package identity, increasing Android version code and matching signing certificates. Supply a new output directory.

macOS 正式构建须给 `scripts/package-macos.sh` 提供 `LANLINK_MAC_SIGN_IDENTITY`（Developer ID Application）和 `LANLINK_MAC_NOTARY_PROFILE`（已配置的 notarytool 钥匙串配置名）。打包流程签署并公证应用和 DMG。使用完成公证的应用生成更新资产。后续版本保持 Team ID、`com.lanlink.native` 和 `com.lanlink.native.bridge` 不变，并验证从前一个正式签名版本更新后辅助功能、输入监控和屏幕录制仍可用。从临时签名版迁移时，系统可能要求重新授权。

## 失败与安装

三端在基础版本匹配且增量包小于全量包时尝试增量。跨版本、基础文件校验失败、损坏或缺失补丁、合成失败均使用全量。旧桌面客户端仍可读取新清单中的全量资产，新桌面客户端也可读取旧版合并增量清单。发布前在三端用上一正式版实际覆盖升级，并验证增量不可用时的全量回退以及设置和配对保留。

桌面先构建完整的新目录，退出 UI 和服务后才替换安装目录。重新打开后，更新页面显示上次安装结果。替换失败时尝试恢复旧目录，并将详细错误写到更新临时目录的 `plan.json.error`。成功替换后原目录保留为 `.backup-时间戳`。需有安装目录及其父目录的写权限；源码运行不支持自替换。macOS 更新后沿用新包的签名，实际权限行为应在 Mac 真机验证。

Android 以已安装的 APK 为基础重建新 APK，按全量包的大小和 SHA-256 校验产物。增量重建与全量下载共用系统安装流程。校验完成后选择「立即安装」，若未授权则先前往系统设置允许 LanLink「安装未知应用」，返回后打开系统安装确认。取消安装后可继续使用已下载的 APK。

GitHub 发布的 Android 0.91.0 和 0.91.1 使用不同签名，升级时需要先保留加入原组的方式，再卸载旧版、安装新版并重新加入原组。后续发行版沿用 0.91.1 的发布签名，以支持覆盖安装。

All three clients fall back to a full download if the base is incompatible or the delta cannot be used. Desktop replacement occurs after both processes exit, with directory rollback on replacement errors and a retained backup. Android uses the system installer after permission is granted and keeps the verified APK available if installation is canceled. Test upgrades from the exact previous release and full fallback on all three platforms before publishing.
