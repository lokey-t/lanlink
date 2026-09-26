# Android Studio 调试

新版手机界面分为「设备 / 设置」。设备气泡可横向滚动，点击设备后显示对应操作；
离线设备保留名称和状态。配对使用独立全屏页，二维码默认隐藏，动态数字码直接展示。
弹出操作面板在支持跨窗口模糊的 Android 12+ 系统使用背景模糊，否则使用清晰的浅色背景。

## 手机收到控制请求

- 同组设备首次请求控制时，手机弹出确认，可分别允许或禁止键鼠、查看屏幕和剪贴板。选择同意后保存在本机，之后无需重复确认；设备菜单可重置授权。
- 同意设备控制后，若远程输入未允许或ADB / Root 输入助手不可用，手机显示权限提示。
- 应用前台显示「查看并开启 / 暂不允许」卡片；后台通过「协作权限提醒」通知进入。
  系统通知未允许时，后台无法显示通知，回到应用仍可在请求有效期内查看卡片；设置页提供通知开启入口。
- 引导分别提供远程输入开关、开发者选项 / ADB 授权说明、屏幕共享系统授权、剪贴板开关。
- 已具备的权限显示「已授权」。请求不会自动授予系统权限，也不会重放失败的输入。控制窗口等待确认或系统权限时会自动重试；单次菜单操作需手动重试。
- 同一设备同一需求 30 秒内合并提醒；提示 2 分钟后过期，设备断开或更换组时清除。

## 构建环境

使用 Android Studio 打开仓库的 `android` 目录。项目使用 Android SDK 35、
Build Tools 35.0.0、Java 17、Gradle Wrapper 8.11.1 和 Android Gradle Plugin 8.9.2。
Studio 自身的 Java 运行环境与项目 Gradle JDK 可以不同。

在 Settings → Build, Execution, Deployment → Build Tools → Gradle 中，
Distribution 选择 Wrapper，Gradle JDK 选择 Java 17。本工作区使用
`.tools/jdk/jdk-17.0.20.1+1`。SDK 路径保存在不分发的 `android/local.properties`。
本机 Gradle 依赖下载沿用现有代理 127.0.0.1:7890，下载时需保持代理运行；
如果以后修改代理端口，同步修改用户目录 `.gradle/gradle.properties` 中的代理配置。

## 连接手机

1. 手机开启开发者选项和 USB 调试，用支持数据传输的 USB 线连接电脑。
2. 在手机上确认允许此电脑调试；电脑无法代替手机完成这个确认。
3. Studio 顶部选择手机和 `app`，点击 Run 安装启动；点击 Debug 可以断点调试。
4. 通过底部 Logcat 选择该手机和 `com.lanlink.app` 查看日志。
5. 手机和电脑接入同一局域网，双方打开「添加设备」，输入动态八位数字码配对。

构建输出为 `android/app/build/outputs/apk/debug/app-debug.apk`。
本工作区存在 `.tools/android-debug.p12` 时，Studio 复用原测试签名，
可以覆盖安装此前同签名的调试包并保留应用数据；其他电脑没有此文件时使用默认调试签名。

ADB 调试信任和屏幕共享仍需在手机上按功能授权。电脑端可通过手机设备菜单的
「ADB 键鼠授权」启用助手；也保留手机端生成手动启动命令的方式，详见
[手机控制与 ADB 授权](ANDROID-CONTROL.md)。普通安装和调试不需要 Root。
构建成功仅验证编译和打包，键鼠、剪贴板、屏幕共享仍需按 `ACCEPTANCE.md` 实机验证。

## 本机模拟器（已配置并启动）

`LanLinkUi` 使用 Android 15 / API 35 x86_64，WHPX 硬件加速已检查可用。
它已注册到用户的 `.android/avd`，数据保存在工作区 `.tools/avd`，系统镜像在
`.tools/emulator-image`；不要删除这两个目录。原有 Resizable 模拟器保留。

Android Studio 顶部选择正在运行的 Android 设备及 `app`，Run 可重新安装运行，
Debug 可启动断点调试。模拟器关闭后可在 Device Manager 找到 `LanLink UI Check`；
若列表尚未刷新，重新打开 Device Manager。也可在 PowerShell 启动已验证的配置：

```powershell
& "$env:LOCALAPPDATA/Android/Sdk/emulator/emulator.exe" -avd LanLinkUi -gpu swiftshader -no-snapshot -port 5580
```

本次已安装 0.3.4 调试版。模拟器处于独立测试组，没有与真实电脑组配对；
目前完成的是页面、首次控制确认、逐项授权和系统权限引导验证。

## 开发测试

纯 Java 提示生命周期测试：用 JDK 17 编译 `PermissionNotice.java` 与
`tests/PermissionNoticeProbe.java`，运行 `com.lanlink.app.PermissionNoticeProbe`。
覆盖重复提醒、合并、不同设备隔离、关闭后的冷却、断线、到期和组重置。

`app/src/androidTest` 内还提供独立模拟器专用的 `PermissionFlowTest`，覆盖真实服务的
权限拒绝、未认证请求不生成提示、开关不能替代系统授权、拒绝后不产生控制权等行为。
测试会修改测试应用的权限开关并停止测试服务，只应运行在无用户数据的模拟器上。
构建任务为 `:app:assembleDebugAndroidTest`。本机 `emulator-5580` 已执行通过。
当前测试检查页面切换、二维码查看 / 隐藏、空码拒绝、权限弹窗以及开发者选项设置 Intent。
0.3.4 增加首次确认、禁止单项权限、授权持久化与已授权状态原地刷新的检查。
测试 peer 不含网络传输，因此测试期间暂停服务心跳，结束后停止服务。
通知权限需先在专用模拟器中允许，否则首次系统弹窗可能阻塞测试启动。

安装 app-debug.apk 与 app-debug-androidTest.apk 后，在无用户数据的专用模拟器执行：

```powershell
$adb = "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
& $adb -s emulator-5580 shell pm grant com.lanlink.app android.permission.POST_NOTIFICATIONS
& $adb -s emulator-5580 shell am force-stop com.lanlink.app
& $adb -s emulator-5580 shell am instrument -w com.lanlink.app.test/com.lanlink.app.PermissionFlowTest
```

以输出中的 `PASS` 为成功依据；测试失败时会输出 `FAIL` 与调用栈。
截图保存在模拟器 `/sdcard/Android/data/com.lanlink.app/files/ui-check/`。
0.3.4 截图已导出至 `dist/android-0.3.4-check/`；无障碍系统页面的跳转意图由测试拦截验证，
不会因此授予远程操作权限。后台通知和真实电脑联调仍待验证。
