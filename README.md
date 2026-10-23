# Musxi Player Android

Musxi Player 的原生安卓开发项目，PC 来源：[Miloscovo/Musxi-Player](https://github.com/Miloscovo/Musxi-Player)。安卓仓库：[qwt1011/Musxi-Player-Android](https://github.com/qwt1011/Musxi-Player-Android)。保留原项目版权、许可和素材来源。

**v0.1 非常不完善。本次公开现有进展，仅为后续修改和实机测试奠定基础，不代表正式版或稳定版。** 当前源码版本为 `0.1.0-alpha02`；尚未发布正式 APK 或 Release。

## 当前进展

- 网易云音乐、QQ 音乐、酷狗账号连接与加密保存；本人曲库、歌单、分页搜索及已浏览元数据缓存。
- Compose 正式页面、迷你/全屏播放器、暂停/seek/上下曲、随机/循环、通知控制与保守跨平台换源。
- 三平台行级双语歌词、译文开关/重试/独立刷新、红心状态；歌词字重、中英文排版、操作对齐和留白已做首轮打磨。
- 队列行点击、长按排序、删除和清空；通过 revision 校验拒绝过时操作，跳转保留原队列和票据。
- 后台播放前台资格门禁、连接恢复、收藏写后核实与缓存失效、独立 AUDIO 请求通道。
- 应用图标复用 PC exe 同源素材，并适配 Android 自适应图标的安全区域，避免主标识被裁切。

平台请求在手机本地完成，无配套业务服务器。可播放范围受平台账号、网络及接口限制；不下载或缓存音频文件。

## 主要缺口

持久队列恢复与最近播放已有后端，尚缺正式产品入口；音乐封面仍有占位图，主题设置、更多菜单及输出选择尚未完善。浅底系统状态栏对比度、完整大字体视觉、队列编辑、各平台主流程、长时间后台/深度 Doze、进程回收及来电/输出切换仍需继续修正和实机验收。逐字歌词/QRC 尚未实现。

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
- [前端接口入口](docs/frontend-contract.md)、[队列/收藏](docs/frontend-backend-contract-p2.md)、[双语歌词](docs/frontend-backend-contract-lyrics-translation.md)、[来源匹配](docs/frontend-backend-contract-b4.md)
- [播放器与歌词设计](docs/design/player-sheets.md)、[许可与来源](THIRD_PARTY_NOTICES.md)

所有本机日志、APK、截图、快照和辅助工作树统一放 `.local/`，不提交。文档中的历史测试数量只对应当时范围，本地证据路径不代表公开下载链接。

## 许可

基于 Musxi Player，Copyright (c) 2026 Miloscovo and contributors；沿用 GPL-3.0-or-later，见 [LICENSE](LICENSE)。第三方代码、素材与测试依赖说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 及各模块随附声明。
