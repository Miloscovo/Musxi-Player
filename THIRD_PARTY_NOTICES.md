# Musxi Android 许可与来源

2026-10-23 安卓0.1验收候选复用 Musxi PC 应用图标：来自本地PC v0.5.1工作树 `assets/icons/musxi.png`，PC CEF构建同目录 `musxi.ico` 用于exe。来源提交 `e72fba9feb6da22a1b8b207ba2fa07b29b96d36c`，沿用本项目的Musxi品牌素材及GPL来源记录；安卓只做不同密度缩放与自适应适配；alpha02 在 108dp 前景中增加 18dp inset，将完整图案收至可见 72dp 区域，避免主标识被裁切，未引入第三方图标包。AndroidX设备端Compose验收依赖仅进入androidTest测试APK，沿用AndroidX Apache-2.0声明，不进入产品运行依赖。

本安卓工程源自 [Miloscovo/Musxi-Player](https://github.com/Miloscovo/Musxi-Player)，早期在个人 fork 的 android/development 分支开发，现于 [qwt1011/Musxi-Player-Android](https://github.com/qwt1011/Musxi-Player-Android) 独立保存。最初 PC 检出基线为 `80743c6aae9ab6b56c13200f53b0ebbb8e852de1`；上次安卓 PR #24 的提交为 `ac3ac80f4259244a50fb5269aa0fdf5bd1c59afd`，上游合并提交为 `c5821a70659a16ddbe08ad8fad6bf60cce5cf532`。独立仓库初始提交保留同一安卓源码树、许可与来源，不携入整个 PC 历史。

基于 Musxi Player，Copyright (c) 2026 Miloscovo and contributors；派生工程采用 GPL-3.0-or-later，完整 GPLv3 文本见 [LICENSE](LICENSE)。本次归档安卓源码，未新增生产依赖或发布 APK。正式分发二进制时应提供匹配的源码及构建说明。

模块中的原始版权、改编说明和完整许可均随本目录保留：

| 部分 | 来源与许可 | 本地记录 |
| --- | --- | --- |
| 酷狗协议、曲库、搜索、取流、收藏同步 | MakcRe/KuGouMusicApi 1.6.0，a5a98013cce79fe0ae2ad65fc84b68176ebcfc1e，Copyright (c) 2023 MakcRe，MIT | [platform-kugou-NOTICE.txt](platform-kugou/src/main/assets/platform-kugou-NOTICE.txt)、同目录 licenses/ |
| QQ 协议、曲库、搜索、取流、收藏同步 | qqmusic-api-python 0.8.1 / L-1124/QQMusicApi，3fc57f02e4eddb68a069bba4e0e12a7893f2a814，Luren，GPL-3.0-or-later | [platform-qq-NOTICE.txt](platform-qq/src/main/assets/platform-qq-NOTICE.txt)、同目录 licenses/ |
| 网易云协议、曲库、搜索、取流、收藏同步 | NeteaseCloudMusicApiEnhanced/api-enhanced，2aab9957dfd5231e5b192aecdb177f93ace4c92e，Copyright (c) 2013-2022 Binaryify，MIT | [platform-netease-NOTICE.txt](platform-netease/src/main/assets/platform-netease-NOTICE.txt)、同目录 licenses/ |
| QR 生成 | Project Nayuki QR-Code-generator，3c6d0b3cefb4e049dc337e82237c9644399716a8，MIT | platform-netease 原源码头及 assets/licenses/QR-Code-generator-MIT.txt |
| Kotlin 2.2.21、coroutines 1.10.2 | JetBrains，Apache-2.0 | app/src/main/assets/licenses/ |
| AndroidX / Media3 1.8.0 | Android Open Source Project，Apache-2.0 | app/src/main/assets/licenses/AndroidX-Apache-2.0.txt |
| Guava 33.3.1-android / failureaccess 1.0.2 | Google / Guava Authors，Apache-2.0 | app/src/main/assets/licenses/Guava-LICENSE.txt、Guava-NOTICE.txt |
| Gradle 8.13 wrapper | Gradle，Apache-2.0，构建工具 | wrapper 原文件头、app/src/main/assets/licenses/Gradle-Apache-2.0.txt |
| JUnit 4.13.2 / Hamcrest 1.3 / JSON-java 20240303 | EPL-1.0 / BSD / Public Domain，仅宿主测试 | test-licenses/ |

版本固定见 Gradle 脚本。Android 框架由设备提供，Gradle/AGP/Kotlin 编译器是构建工具；不随 APK 包含桌面 CEF/FFmpeg、Node/Python 运行时、LoveACE 实现或品牌素材。平台官方页面和路由仅为兼容性研究引用，不表示平台 SDK 集成或 API 授权。

历史模块 NOTICE 中的 `android/player/` 或 PoC 路径记录原始来源；当前对应源码分别位于本目录的 platform-kugou/、platform-qq/、platform-netease/，构建入口是本目录 build.ps1 / Gradle wrapper。

2026-10-20 P0 增量继续改编上述固定版本的三平台歌曲搜索分页和图片元数据，不新增协议来源或生产依赖；原模块声明、版权和完整许可保留。cc 工作区已加入的 AndroidX Compose / Material3 沿用 Apache-2.0，随包许可使用现有 AndroidX-Apache-2.0.txt；Roborazzi（Apache-2.0）和 Robolectric（MIT）仅宿主截图测试，不进入 APK。本轮后端不改前端依赖版本。

同日 P1 标准歌词继续使用同一固定来源：酷狗 module/search_lyric.js、module/lyric.js；网易云 module/lyric.js；QQ qqmusic-api-python 0.8.1 的 modules/lyric.py、models/lyric.py（通过 PyPI 原版本 wheel 只读核对，未安装或打包 Python）。标准 LRC 解析、队列元数据持久化及最近播放使用现有 Kotlin/Java/Android API，无新增生产依赖；不含 QRC/KRC 逐字解密。模块 assets NOTICE 已同步。

同日 P2 收藏同步继续改编上述固定来源：酷狗 module/playlist_tracks_add.js、module/playlist_tracks_del.js；网易云 module/likelist.js、like.js；QQ qqmusic-api-python 0.8.1 的 qqmusic_api/modules/songlist.py 中 add_songs、del_songs、like_song、unlike_song，用于单曲收藏同步（通过 PyPI 原版本 wheel 只读核对，未安装或打包 Python）。酷狗和网易云沿用现有 MIT 许可，QQ 沿用现有 GPL-3.0-or-later 来源及许可；无新增生产依赖或许可更改。模块 assets NOTICE 已同步。

酷狗歌词后续兼容修复将搜索路径从固定vendor的 `/v1/search` 改为同一官方 HTTPS 主机实测可用的 `/search`，其余来源标识、签名及标准LRC下载解码沿用；未引入新第三方代码或依赖。
