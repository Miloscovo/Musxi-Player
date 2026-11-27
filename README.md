<p align="center">
  <img src="docs/media/readme/icon.png" width="120" alt="Musxi Player 图标">
</p>

<h1 align="center">Musxi Player Android</h1>

<p align="center">把三平台音乐和自己的音频，放进同一个播放器。</p>

<p align="center">
  <a href="https://github.com/qwt1011/Musxi-Player-Android/releases/latest"><img src="https://img.shields.io/github/v/release/qwt1011/Musxi-Player-Android?style=flat-square&label=Release" alt="最新版本"></a>
  <a href="https://github.com/qwt1011/Musxi-Player-Android/actions/workflows/android-verify.yml"><img src="https://github.com/qwt1011/Musxi-Player-Android/actions/workflows/android-verify.yml/badge.svg" alt="Android verification"></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 8.0 及以上">
  <img src="https://img.shields.io/badge/Kotlin-Compose-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin 与 Jetpack Compose">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0--or--later-blue?style=flat-square" alt="GPL-3.0-or-later"></a>
</p>

<p align="center">
  <a href="https://github.com/qwt1011/Musxi-Player-Android/releases/latest">下载安装包</a> ·
  <a href="docs/android-v0.3.1-release.md">v0.3.1 更新说明</a> ·
  <a href="docs/README.md">开发文档</a> ·
  <a href="https://github.com/qwt1011/Musxi-Player-Android/issues">反馈问题</a>
</p>

---

Musxi Player 的原生安卓版本，基于 [Miloscovo/Musxi-Player](https://github.com/Miloscovo/Musxi-Player) 持续开发。网易云音乐、QQ 音乐、酷狗与本地音频共用播放服务，在曲库、歌词和队列之间保持连贯的使用体验。

项目坚持“小而精”：平台请求在手机本地完成，无配套业务服务器；优先使用 Android 系统能力和现有播放器。平台音乐按账号、网络和版权条件播放，不下载或缓存平台音频；用户主动导入的本地副本独立保存。

## v0.3.1 的进展

- **登录与播放更稳定**：适配网易云取流，改善三平台登录恢复、重新授权、取消和资源重新获取；修复 QQ 授权回桌面，完善酷狗授权返回。
- **长歌单与队列**：分页补全检查顺序，目录重开保留展示，恢复队列保持暂停；搜索与歌词匹配增加旧请求保护。
- **本地音频**：启用 DSF 的 DSD64／128／256 到 PCM 播放，完善本地库升级、目录刷新和整理一致性。
- **界面统一**：歌单与本地音频对话框沿用 Musxi 浮层，网易云网页登录跟随主题；新增锁屏歌词页和设置入口。

功能与实机覆盖范围见 [v0.3.1 说明](docs/android-v0.3.1-release.md)。CUE 分曲、检索筛选与导出备份已有后端进展，部分正式入口仍待接入。

## 可以做什么

- **三平台音乐**：连接账号，浏览曲库与歌单，搜索歌曲、查看推荐；保留原队列身份的手动／自动换源，音质以当前歌曲和账号实际可用档位为准。
- **自己的音频库**：导入音乐、音声、有声书和录音，选择复制或授权读取；使用文件夹与批量操作整理内容，检查来源或重新关联文件。
- **接着听下去**：本地音频播放进度、书签、按条目记忆倍速；支持提取视频声音，不播放视频画面。
- **读歌词**：原文与译文、支持字级时间的逐字高亮、手动匹配和字号设置；可选择让媒体卡片标题显示当前歌词。
- **换一种背景**：独立的界面壁纸与播放页壁纸，移动取景、缩放和强度调节；配合主题色、深色模式与按系统能力回退的玻璃效果。
- **控制播放**：随机／循环、分区队列与拖动、移除撤销、通知控制、输出选择，以及分钟／曲末定时停止。
- **直接更新**：应用内检查正式版本，核对安装包大小、SHA-256、包名、版本和签名后交给系统安装。

## 界面预览

**个性化实机效果**

| 播放页壁纸 | 双语歌词 | 透明玻璃队列 |
| :---: | :---: | :---: |
| <img src="docs/media/readme/personalized-player.png" width="220" alt="深色模式与自定义壁纸下，SAKURA 的真实播放页"> | <img src="docs/media/readme/personalized-lyrics.png" width="220" alt="自定义壁纸上的日文歌词、中文译文与封面取色高亮"> | <img src="docs/media/readme/personalized-queue.png" width="220" alt="透出播放页壁纸与歌词的玻璃播放队列"> |

使用开发者手机上已保存的个性化设置，在实际应用窗口中截取。示例歌曲为 **《SAKURA》— いきものがかり**，封面、歌词与译文来自当前歌曲；队列中也为真实歌曲。截图排除了系统状态栏、导航栏和其他应用的悬浮小窗，未拼接或重绘界面。

| 设置 | 本组截图使用的值 |
| --- | --- |
| 外观 | 深色模式；松石青（TEAL）；透明效果开启 |
| 播放页壁纸 | 开启；强度 `150 / 150`；缩放 `1.0x` |
| 界面壁纸 | 开启；强度 `150 / 150`；缩放 `1.0x` |
| 歌词 | 大字号；颜色跟随封面；译文自动判断（本曲显示中文译文） |

壁纸强度是应用的可见程度参数，并非透明度百分比。界面壁纸作用于曲库等页面，本组三张截图仅展示播放页壁纸。截图设备为 Android 16，使用 0.3.0 开发包；玻璃效果会按系统能力回退。详细参数与素材来源见 [预览来源](docs/media/readme/README.md)。

**布局预览（合成数据）**

| 播放页 | 歌词页 | 本地音频 |
| :---: | :---: | :---: |
| <img src="docs/media/readme/player.png" width="220" alt="自定义背景的 Musxi 播放页"> | <img src="docs/media/readme/lyrics.png" width="220" alt="Musxi 歌词与播放控制"> | <img src="docs/media/readme/local-audio.png" width="220" alt="本地音频文件夹和继续播放"> |

本行三张由实际 Compose 页面使用合成数据生成，展示布局和外观，不含真实账号资料，也不替代实机验收。素材说明见 [预览来源](docs/media/readme/README.md)。

## 下载与安装

当前版本为 [Android v0.3.1](https://github.com/qwt1011/Musxi-Player-Android/releases/tag/v0.3.1)，应用版本 **0.3.1／versionCode 7**。支持 Android 8.0 / API 26 及以上。

在 [GitHub Releases](https://github.com/qwt1011/Musxi-Player-Android/releases/latest) 下载 `Musxi-Player-0.3.1.apk`，并取得 `SHA256SUMS.txt` 和应用内更新清单。沿用 v0.2／v0.3.0 的正式包名 `tech.musxi.player` 与长期签名，可覆盖更新并保留应用数据。

开发包 `tech.musxi.player.dev` 与正式包可并存，账号、设置及队列独立保存，不能用正式包自动迁移开发包数据。具体变化及验证边界见 [v0.3.1 发布说明](docs/android-v0.3.1-release.md)。

本版包含本地 DSF 的 DSD64／128／256 到 PCM 播放，开发包已补正常启动、复制／引用与重启实机验收，见 [启用记录](docs/backend-dsf-enablement-20261117.md)。不提供原生 DSD 或 USB 独占输出；其他 Android 版本、数小时后台／深度休眠、来电与更多音频设备继续按需验证；完整状态见 [能力状态](docs/feature-status.md)。

## 构建与开发

需要 JDK 17、Android SDK 36、PowerShell 7，设置 `JAVA_HOME` 和 `ANDROID_HOME`。工具链为 Gradle 8.13、AGP 8.13.2、Kotlin 2.3.21。

```powershell
git clone https://github.com/qwt1011/Musxi-Player-Android.git
cd Musxi-Player-Android
pwsh -NoProfile -File scripts/verify.ps1
pwsh -NoProfile -File build.ps1 -Install
```

`verify.ps1` 编译主包和设备测试包，执行全部 JVM 测试与 Lint，记录对应源码和证据；不自动执行真实平台写入。`-Install` 覆盖安装开发包并打开应用，保留该包的数据。所有构建共用文件锁，详见 [验证工作流](docs/validation-workflow.md)；正式包使用仓库外密钥，见 [Release 构建](docs/release-build.md)。

| 模块 | 职责 |
| --- | --- |
| `app` | Compose 界面、应用装配、本地导入与 SQLite |
| `core` | 曲库、歌词、队列、本地音频与持久化策略 |
| `accounts` | 账号调度、登录、曲库与播放能力接口 |
| `playback` | Media3 服务、通知、播放与恢复 |
| `platform-kugou` / `platform-qq` / `platform-netease` | 三平台协议适配与原始许可 |

继续开发从 [当前接续](docs/next-session-handoff.md)、[能力状态](docs/feature-status.md) 和 [前端接口](docs/frontend-contract.md) 开始；工作规则见 [AGENTS.md](AGENTS.md)。临时材料与验收包放项目 `.local/`，不提交。

## 反馈与许可

Musxi Player Android 是个人学习与研究用途的非官方开源项目，与网易云音乐、QQ 音乐、酷狗音乐及其运营方没有隶属或合作关系，也不代表获得其官方播放授权或背书。项目本身不提供平台曲库或音频资源；在线播放取决于平台的版权、账号权益和服务可用性。平台商标与音乐内容归各自权利人所有。

欢迎在 [议题](https://github.com/qwt1011/Musxi-Player-Android/issues) 反馈，附应用版本、手机型号、系统版本、操作步骤和实际现象；请先遮住账号、验证码及私人内容。

基于 Musxi Player，Copyright (c) 2026 Miloscovo and contributors，沿用 **GPL-3.0-or-later**。感谢原项目作者及所使用的开源项目；完整声明见 [LICENSE](LICENSE)、[第三方声明](THIRD_PARTY_NOTICES.md) 与模块随附许可。

README 展示结构参考 [Sibuxiangx/LoveACE](https://github.com/Sibuxiangx/LoveACE)，使用 Musxi 自有图标和页面素材。
