# Musxi Player Android

Musxi Player 的原生安卓开发项目，PC 来源：[Miloscovo/Musxi-Player](https://github.com/Miloscovo/Musxi-Player)。安卓仓库：[qwt1011/Musxi-Player-Android](https://github.com/qwt1011/Musxi-Player-Android)。保留原项目版权、许可和素材来源。

上游代码进展：[PR #25](https://github.com/Miloscovo/Musxi-Player/pull/25) 已合入 android/development；本次 `alpha03` 存档点另提交 PR（目标同上，状态以 GitHub 为准）。

**安卓端仍然很不完善，不代表正式版或稳定版。** 当前源码为 `0.1.0-alpha03`：前端完成一轮改版、后端完成收藏与换源可靠性工作，作为接下来"交互层整体重构"之前的开发存档点，**不发布新的 APK**。最近一次公开安装包仍是 [v0.1 预发布版](https://github.com/qwt1011/Musxi-Player-Android/releases/tag/v0.1) 中的 `0.1.0-alpha02` debug 签名 APK（需 Android 8.0 / API 26 或更高版本）。

## 当前进展

- 网易云音乐、QQ 音乐、酷狗账号连接与加密保存；本人曲库、歌单、分页搜索及已浏览元数据缓存。
- Compose 正式页面、迷你/全屏播放器、暂停/seek/上下曲、随机/循环、通知控制与保守跨平台换源。
- 换源严格匹配后立即尝试、独立并发搜索与成功映射复用；本轮 QQ/网易云样本约 1.9–2.2 秒开播，[实现与验收边界](docs/source-switch-speed-20261027.md)。
- 三平台行级双语歌词、译文开关/重试/独立刷新、红心状态；歌词字重、中英文排版、操作对齐和留白已做首轮打磨。
- 队列行点击、长按排序、删除和清空；通过 revision 校验拒绝过时操作，跳转保留原队列和票据。
- 歌单首批先播/后台补队列、逐曲音质选择与续播、输出设备状态和系统媒体输出入口；[接口与兼容范围](docs/backend-delivery-20261027.md)。
- 后台播放前台资格门禁、连接恢复、收藏写后核实与缓存失效、独立 AUDIO 请求通道。
- 应用图标复用 PC exe 同源素材，`alpha03` 改为全出血前景，去掉原图圆角框在系统遮罩下留下的亮边。
- `alpha03` 前端：真实封面（Coil，失败回退渐变占位）；酷狗歌单最新收藏在前；搜索页胶囊搜索框、平台分段、可编辑的最近搜索、最佳匹配、骨架屏；曲库首页"继续播放"、最近播放、"我喜欢"大卡片、连接卡片、下拉刷新；播放顺序/音质/输出改为浮动菜单，"更多"菜单（定时停止、搜索歌手/专辑、复制、分享、歌曲信息、加入歌单）；统一的歌曲操作菜单（下一首、加入队列、收藏、加入歌单）；乐观更新的红心；中性底色与纯白浮卡，导航用主题色、播放相关随封面变色；网易云网页登录接入正式页面。
- `alpha03` 后端：收藏有序排队与局部核实（759 首收藏三首同时提交均在 1.4 秒内确认）；三平台可写歌单与加入歌单；主动换源后端（逐平台进度、失败分类、候选音质）；酷狗收藏的他人歌单；Media3 网络唤醒模式。见 [后端交付](docs/backend-delivery-20261101.md)、[真机验收](docs/backend-live-acceptance-20261101.md)。

平台请求在手机本地完成，无配套业务服务器。可播放范围受平台账号、网络及接口限制；不下载或缓存音频文件。

## 主要缺口

交互层整体偏弱：没有页面栈与跟手返回、播放器不能拖动展开、弹出层不能拖拽、没有触感反馈。下一步按"iOS 的手感、安卓的手势、克制的视觉"整体重构，先在 debug 交互实验室验证手感，再按 导航外壳 → 连续播放器 → 弹出层与长按 → 滚动联动 → 我的与设置 推进。

功能缺口：主动换源界面（后端已就绪）、播放页外的换源与失败提示、播放队列重做、设置页与深色模式入口、手动选歌词、歌词磁盘缓存、最近播放完整页、歌单内搜索、锁屏歌词。后端第二批需求见 [需求单](docs/backend-requests-20261101-2.md)（失败原因细分、原曲与实际音源分开、随机播放真实顺序等）。

待实机验收：网易云网页登录全流程、真实账号的红心与普通歌单写入、成功手动换源续播、数小时自然待机、来电/进程回收；只在一台 Android 16 设备上测过。release 尚未配置签名与混淆。逐字歌词/QRC 尚未实现。

`alpha03` 存档点：776 项 JVM 测试通过，六模块 Lint 0 错误；正式包清单不含任何调试入口（开发者页已移入 debug 源集）。前端页面、浮动菜单与 debug 玻璃预览页在 Android 16 做了不修改用户数据的上机检查。

首轮 `alpha01` 集成记录：603 项 JVM 测试、3 项正式页面设备测试通过；六模块 Lint 为 0 错误/83 警告。Android 16 单设备已验证部分播放、歌词、通知及后台换源，且有真人听音确认。这些结论有明确的包和场景边界，不能视为全部功能验收。`alpha02` 为本次图标适配修正版，验证记录见 [0.1 验收](docs/android-0.1-acceptance.md)。

## 构建

需要 JDK 17、Android SDK 36；将 `JAVA_HOME` 指向 JDK，将 `ANDROID_HOME` 或 `ANDROID_SDK_ROOT` 指向 SDK。Gradle 8.13、AGP 8.13.2、Kotlin 2.2.21；首次构建需下载依赖。

```powershell
./build.ps1 -Test -Lint
./build.ps1 -Install
```

Windows 中文路径请使用此脚本，保留 Gradle daemon 的系统默认编码；不要强制 daemon UTF-8，以免 JDK 17 测试参数文件误读 classpath。其他环境可使用 Gradle wrapper，例如 `./gradlew :app:assembleDebug :core:test`。

输出为 `app/build/outputs/apk/debug/app-debug.apk`，debug 包名为 `tech.musxi.player.dev`。`-Install` 覆盖安装并打开正式 MainActivity；请勿卸载或清除数据来更新已有测试安装。

## 工程与文档

| 模块 | 职责 |
| --- | --- |
| app | Compose 页面、依赖装配、debug 专用固定验收入口 |
| core | 账号/曲库/歌词/队列模型、策略与文件存储 |
| accounts | 账号调度、登录组件、曲库/收藏/歌词 facade |
| playback | Media3 服务、系统控制、队列与来源恢复 |
| platform-kugou / platform-qq / platform-netease | 三平台本地协议适配与原始许可 |

- [v0.1 本次增量](docs/v0.1-foundation.md)、[开发接续](docs/next-session-handoff.md)、[适配规划](安卓端适配规划.md)
- [前端接口入口](docs/frontend-contract.md)、[队列/收藏](docs/frontend-backend-contract-p2.md)、[双语歌词](docs/frontend-backend-contract-lyrics-translation.md)、[来源匹配](docs/frontend-backend-contract-b4.md)、[音质](docs/frontend-backend-contract-quality.md)
- [播放器与歌词设计](docs/design/player-sheets.md)、[许可与来源](THIRD_PARTY_NOTICES.md)

所有本机日志、APK、截图、快照和辅助工作树统一放 `.local/`，不提交。文档中的历史测试数量只对应当时范围，本地证据路径不代表公开下载链接。

## 许可

基于 Musxi Player，Copyright (c) 2026 Miloscovo and contributors；沿用 GPL-3.0-or-later，见 [LICENSE](LICENSE)。第三方代码、素材与测试依赖说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 及各模块随附声明。
