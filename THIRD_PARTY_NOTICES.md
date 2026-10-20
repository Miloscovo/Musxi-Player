# Musxi Android 许可与来源

本独立目录于 2026-10-20 从 `C:\text\Musxi-Player\Musxi-Player\android\player` 复制，保留完整安卓开发内容，并在个人 fork 的 `android/development` 分支进行版本管理。原仓库为 https://github.com/qwt1011/Musxi-Player ，上游为 https://github.com/Miloscovo/Musxi-Player ，原检出基线 `80743c6aae9ab6b56c13200f53b0ebbb8e852de1`。该基线自身不代表已经收录后续安卓改动。

基于 Musxi Player，Copyright (c) 2026 Miloscovo and contributors；派生工程采用 GPL-3.0-or-later，完整 GPLv3 文本见 [LICENSE](LICENSE)。本次归档安卓源码，未新增生产依赖或发布 APK。正式分发二进制时应提供匹配的源码及构建说明。

模块中的原始版权、改编说明和完整许可均随本目录保留：

| 部分 | 来源与许可 | 本地记录 |
| --- | --- | --- |
| 酷狗协议、曲库、搜索、取流 | MakcRe/KuGouMusicApi 1.6.0，a5a98013cce79fe0ae2ad65fc84b68176ebcfc1e，Copyright (c) 2023 MakcRe，MIT | [platform-kugou-NOTICE.txt](platform-kugou/src/main/assets/platform-kugou-NOTICE.txt)、同目录 licenses/ |
| QQ 协议、曲库、搜索、取流 | qqmusic-api-python 0.8.1 / L-1124/QQMusicApi，3fc57f02e4eddb68a069bba4e0e12a7893f2a814，Luren，GPL-3.0-or-later | [platform-qq-NOTICE.txt](platform-qq/src/main/assets/platform-qq-NOTICE.txt)、同目录 licenses/ |
| 网易云协议、曲库、搜索、取流 | NeteaseCloudMusicApiEnhanced/api-enhanced，2aab9957dfd5231e5b192aecdb177f93ace4c92e，Copyright (c) 2013-2022 Binaryify，MIT | [platform-netease-NOTICE.txt](platform-netease/src/main/assets/platform-netease-NOTICE.txt)、同目录 licenses/ |
| QR 生成 | Project Nayuki QR-Code-generator，3c6d0b3cefb4e049dc337e82237c9644399716a8，MIT | platform-netease 原源码头及 assets/licenses/QR-Code-generator-MIT.txt |
| Kotlin 2.2.21、coroutines 1.10.2 | JetBrains，Apache-2.0 | app/src/main/assets/licenses/ |
| AndroidX / Media3 1.8.0 | Android Open Source Project，Apache-2.0 | app/src/main/assets/licenses/AndroidX-Apache-2.0.txt |
| Guava 33.3.1-android / failureaccess 1.0.2 | Google / Guava Authors，Apache-2.0 | app/src/main/assets/licenses/Guava-LICENSE.txt、Guava-NOTICE.txt |
| Gradle 8.13 wrapper | Gradle，Apache-2.0，构建工具 | wrapper 原文件头、app/src/main/assets/licenses/Gradle-Apache-2.0.txt |
| JUnit 4.13.2 / Hamcrest 1.3 / JSON-java 20240303 | EPL-1.0 / BSD / Public Domain，仅宿主测试 | test-licenses/ |

版本固定见 Gradle 脚本。Android 框架由设备提供，Gradle/AGP/Kotlin 编译器是构建工具；不随 APK 包含桌面 CEF/FFmpeg、Node/Python 运行时、LoveACE 实现或品牌素材。平台官方页面和路由仅为兼容性研究引用，不表示平台 SDK 集成或 API 授权。

历史模块 NOTICE 中的 `android/player/` 或 PoC 路径记录原始来源；当前对应源码分别位于本目录的 platform-kugou/、platform-qq/、platform-netease/，构建入口是本目录 build.ps1 / Gradle wrapper。
