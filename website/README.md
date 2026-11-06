# LanLink 官网首页

独立的静态官网，软件现有控制台仍位于 `web/`。不依赖前端框架、远程字体或动画库；设备、图标和连接示意均为 SVG。

## 本地预览与构建

使用项目要求的 Node.js 22 或更新版本，在本目录运行：

```sh
npm run dev
```

打开 `http://127.0.0.1:4173`。可通过 `PORT` 环境变量修改端口。

```sh
npm test
npm run build
```

构建产物为 `dist/`，可部署到静态网站服务。页面通过 app.bundle.js 普通脚本运行，也支持双击 index.html 预览动效。直接打开时，下载接口仍受浏览器跨域规则限制。编辑源码后运行 npm run bundle；npm run dev 和 npm run build 会自动重新生成脚本。

## 编辑入口

- `index.html`：首页结构、SVG 图标、功能与平台入口。
- `style.css`：视觉样式、响应式、滚动章节、减少动画和横屏布局。
- `app.js`：原生滚动进度、章节切换、语言与动画偏好。
- `locales.js`：简体中文、繁体中文、英语、日语、德语、法语，共 85 个文案字段。
- `assets/`：沿用 LanLink 品牌图标及新绘制的设备插画。

首次访问依照 `navigator.languages` 的优先级匹配语言，无法匹配时使用英文；中文先遵循 Hans/Hant，再匹配地区。手动选择保存在本机，也可以切回“跟随系统”。

动画使用原生滚动和按需 `requestAnimationFrame`，无滚轮劫持。系统减少动画偏好或页脚手动选择会改为静态章节；高度不超过 560px 的横屏视口也使用静态章节。无 JavaScript 时仍能阅读完整的中文内容。

## 软件版本与获取入口

平台卡片提供独立的“查看最新版本”和“直接下载”链接；前者始终进入所选源的 Release 页，后者每次点击都重新查询最新正式 Release，使用附件直链，附件未提供时按正式 tag 和打包命名规则生成直链，然后在当前页面发起下载。Windows 使用组合按钮：默认显示“便携版 ZIP”，点击主按钮直接下载 ZIP；右侧箭头可切换为安装版 EXE，再点击主按钮下载所选包。选项分别为“安装版 EXE”和“便携版 ZIP”。下载失效时用户可通过前者获取安装包。浏览器跨域下载无法可靠判断安装包下载结果，因此页面常驻显示回退提示。

平台卡片自动读取所选仓库的最新正式 Release，不固定版本号，发布新版本无需重新构建官网。Windows 可选安装版 EXE 或便携版 ZIP，macOS 为 Apple Silicon ZIP/DMG，Android 为 APK。API 请求失败时显示错误并保留独立的 Release 页入口。生成的直链依赖发布附件遵循下述命名规则；附件不存在或下载失效时用户可前往 Release 页下载。

自动模式通过 Cloudflare trace 的 loc 字段识别访问 IP 所在国家：中国大陆（CN）使用 Gitee，其他地区使用 GitHub，与页面语言无关。识别失败默认 GitHub；用户可手动切换，选择保存在本机。地区识别请求会连接第三方 Cloudflare 服务，不保存 IP。页面需能访问该服务及 GitHub/Gitee Release API；跨域请求被阻止、超时或限流时使用 Release 页面入口。

在两个仓库发布正式 Release，tag 使用 v版本号，附件命名沿用现有打包规则：
- LanLink-版本-windows-x64-setup.exe
- LanLink-版本-windows-x64.zip
- LanLink-版本-macos-arm64.zip（或 .dmg）
- LanLink-版本-android.apk

每个源独立跟随其最新正式 Release。应向两边上传相同版本；仅更新一边时另一边继续展示其已发布版本。更新器的 full/delta 资产不会被当作首次安装包。下载与地区逻辑位于 downloads.js。

## 检查范围

- 下载测试：IP 地区选择、请求失败回退、双源安装包匹配、未来版本跟随、缺失资产及 URL 验证。
- 5 项语言测试：完整字段、优先语言列表、回退、中文脚本/地区优先级、页面翻译键。
- 浏览器检查：桌面滚动章节、语言切换、手机 390px、窄屏 320px、横屏 844×390、减少动画及偏好刷新保留。
- `build.mjs` 仅打包网站资源，不包含软件源码、调试数据或安装包。

Android 下载按钮右侧的 SVG 二维码图标支持悬停、键盘聚焦和点按。二维码在本地生成，不调用第三方二维码服务；地址配置在 index.html 的 website-url 元标签，当前为 https://lanlink.sites.show/#get。Windows、macOS、Android 下载按钮均采用两行文案和一致高度。二维码生成复用 vendor/qrcode.mjs（MIT，见仓库 THIRD_PARTY_NOTICES.md），随 bundle.mjs 打包。
