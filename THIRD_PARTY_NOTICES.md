# Musxi Android 许可与来源

2026-11-13 发布后CI检查将自动换源的时间测试改为协程虚拟时钟，新增 `kotlinx-coroutines-test:1.10.2`（JetBrains，Apache-2.0）作为 playback 的 `testImplementation`，与既有 coroutines 版本一致。仅供宿主测试，不进入产品APK；许可全文沿用 `app/src/main/assets/licenses/` 中既有 Kotlin/coroutines 声明。

2026-10-23 安卓0.1验收候选复用 Musxi PC 应用图标：来自本地PC v0.5.1工作树 `assets/icons/musxi.png`，PC CEF构建同目录 `musxi.ico` 用于exe。来源提交 `e72fba9feb6da22a1b8b207ba2fa07b29b96d36c`，沿用本项目的Musxi品牌素材及GPL来源记录；安卓只做不同密度缩放与自适应适配；alpha02 在 108dp 前景中增加 18dp inset，将完整图案收至可见 72dp 区域，避免主标识被裁切，未引入第三方图标包。AndroidX设备端Compose验收依赖仅进入androidTest测试APK，沿用AndroidX Apache-2.0声明，不进入产品运行依赖。

本安卓工程源自 [Miloscovo/Musxi-Player](https://github.com/Miloscovo/Musxi-Player)，早期在个人 fork 的 android/development 分支开发，现于 [qwt1011/Musxi-Player-Android](https://github.com/qwt1011/Musxi-Player-Android) 独立保存。最初 PC 检出基线为 `80743c6aae9ab6b56c13200f53b0ebbb8e852de1`；上次安卓 PR #24 的提交为 `ac3ac80f4259244a50fb5269aa0fdf5bd1c59afd`，上游合并提交为 `c5821a70659a16ddbe08ad8fad6bf60cce5cf532`。独立仓库初始提交保留同一安卓源码树、许可与来源，不携入整个 PC 历史。

基于 Musxi Player，Copyright (c) 2026 Miloscovo and contributors；派生工程采用 GPL-3.0-or-later，完整 GPLv3 文本见 [LICENSE](LICENSE)。v0.1 以 [预发布](https://github.com/qwt1011/Musxi-Player-Android/releases/tag/v0.1) 方式提供 debug 签名 APK；匹配源码固定于 [v0.1 标签](https://github.com/qwt1011/Musxi-Player-Android/tree/v0.1)，构建说明见 README.md。此轮发布未新增生产依赖。2026-10-27 新增 Coil 3 用于封面加载。

模块中的原始版权、改编说明和完整许可均随本目录保留：

| 部分 | 来源与许可 | 本地记录 |
| --- | --- | --- |
| 酷狗协议、曲库、搜索、取流、收藏同步 | MakcRe/KuGouMusicApi 1.6.0，a5a98013cce79fe0ae2ad65fc84b68176ebcfc1e，Copyright (c) 2023 MakcRe，MIT | [platform-kugou-NOTICE.txt](platform-kugou/src/main/assets/platform-kugou-NOTICE.txt)、同目录 licenses/ |
| QQ 协议、曲库、搜索、取流、收藏同步 | qqmusic-api-python 0.8.1 / L-1124/QQMusicApi，3fc57f02e4eddb68a069bba4e0e12a7893f2a814，Luren，GPL-3.0-or-later | [platform-qq-NOTICE.txt](platform-qq/src/main/assets/platform-qq-NOTICE.txt)、同目录 licenses/ |
| 网易云协议、曲库、搜索、取流、收藏同步 | NeteaseCloudMusicApiEnhanced/api-enhanced，2aab9957dfd5231e5b192aecdb177f93ace4c92e，Copyright (c) 2013-2022 Binaryify，MIT | [platform-netease-NOTICE.txt](platform-netease/src/main/assets/platform-netease-NOTICE.txt)、同目录 licenses/ |
| QR 生成 | Project Nayuki QR-Code-generator，3c6d0b3cefb4e049dc337e82237c9644399716a8，MIT | platform-netease 原源码头及 assets/licenses/QR-Code-generator-MIT.txt |
| Kotlin 2.3.21、coroutines 1.10.2 | JetBrains，Apache-2.0 | app/src/main/assets/licenses/ |
| AndroidX / Media3 1.8.0 | Android Open Source Project，Apache-2.0 | app/src/main/assets/licenses/AndroidX-Apache-2.0.txt |
| Coil 3 3.3.0（io.coil-kt.coil3:coil-compose / coil-network-okhttp，封面图片加载；会引入 OkHttp 与 Okio，均为 Apache-2.0） | Coil Contributors，Apache-2.0 | 许可文本复用 app/src/main/assets/licenses/AndroidX-Apache-2.0.txt；OkHttp、Okio 同为 Apache-2.0，沿用同一文本 |
| Haze 1.6.10（dev.chrisbanes.haze:haze，用于浮卡、菜单、Sheet 等控件层的液态玻璃材质，见 ui/components/Glass.kt；不支持实时模糊时退回实色） | Chris Banes，Apache-2.0 |
| Backdrop 2.0.0-alpha03（io.github.kyant0:backdrop，Android 13 及以上为同一玻璃材质加折射，见 ui/components/Glass.kt） | Kyant，Apache-2.0 | 许可文本复用 app/src/main/assets/licenses/AndroidX-Apache-2.0.txt | 许可文本复用 app/src/main/assets/licenses/AndroidX-Apache-2.0.txt |
| Guava 33.3.1-android / failureaccess 1.0.2 | Google / Guava Authors，Apache-2.0 | app/src/main/assets/licenses/Guava-LICENSE.txt、Guava-NOTICE.txt |
| Gradle 8.13 wrapper | Gradle，Apache-2.0，构建工具 | wrapper 原文件头、app/src/main/assets/licenses/Gradle-Apache-2.0.txt |
| JUnit 4.13.2 / Hamcrest 1.3 / JSON-java 20240303 | EPL-1.0 / BSD / Public Domain，仅宿主测试 | test-licenses/ |

版本固定见 Gradle 脚本。Android 框架由设备提供，Gradle/AGP/Kotlin 编译器是构建工具；不随 APK 包含桌面 CEF/FFmpeg、Node/Python 运行时、LoveACE 实现或品牌素材。平台官方页面和路由仅为兼容性研究引用，不表示平台 SDK 集成或 API 授权。

历史模块 NOTICE 中的 `android/player/` 或 PoC 路径记录原始来源；当前对应源码分别位于本目录的 platform-kugou/、platform-qq/、platform-netease/，构建入口是本目录 build.ps1 / Gradle wrapper。

2026-10-20 P0 增量继续改编上述固定版本的三平台歌曲搜索分页和图片元数据，不新增协议来源或生产依赖；原模块声明、版权和完整许可保留。cc 工作区已加入的 AndroidX Compose / Material3 沿用 Apache-2.0，随包许可使用现有 AndroidX-Apache-2.0.txt；Roborazzi（Apache-2.0）和 Robolectric（MIT）仅宿主截图测试，不进入 APK。本轮后端不改前端依赖版本。

2026-11-01 第三批更新功能沿用 Robolectric 4.17（MIT），增加 accounts 模块的宿主 JVM 状态、持久化和主线程回调测试；仍仅为 testImplementation，不进入 APK。

2026-11-08 定时停止升级在 playback 模块沿用同一 Robolectric 4.17（MIT）做宿主 Handler/监听适配测试，仅 testImplementation、不进入产品 APK。许可全文沿用现有 test-licenses，未新增运行时依赖或复制第三方业务实现。

同日 P1 标准歌词继续使用同一固定来源：酷狗 module/search_lyric.js、module/lyric.js；网易云 module/lyric.js；QQ qqmusic-api-python 0.8.1 的 modules/lyric.py、models/lyric.py（通过 PyPI 原版本 wheel 只读核对，未安装或打包 Python）。标准 LRC 解析、队列元数据持久化及最近播放使用现有 Kotlin/Java/Android API，无新增生产依赖；不含 QRC/KRC 逐字解密。模块 assets NOTICE 已同步。

2026-11-07 酷狗 KRC 字词增量沿用现有容器/协议来源，并参考 Musxi PC 固定提交 `824a1dfd8089f2cd9699f6da490e05727d3fe335` 的歌词解析与合成测试输入（GPL-3.0-or-later）。MeiloX `bc038c9e9a01f37484a13295b2c3a8c3e44d8804` 的时间轴、QRC/YRC解析与测试仅作设计参考，未复制源码、资源或引入依赖；主体GPLv3。新增字词模型、采样和缓存兼容由本项目实现。详见 `docs/lyrics-word-timing-20261107.md`。

同日 QQ QRC 增量移植固定 QQMusicApi `3fc57f02e4eddb68a069bba4e0e12a7893f2a814` 的 `algorithms/tripledes.py` 为 `QrcCipher.java`。该文件单独标注 Copyright (c) 2024 沉默の金 <cmzj@cmzj.org>、**GPL-3.0-only**，并引用 WXRIW/QQMusicDecoder `0837a3a1281e58f6db3e3e1dcb1d5441fb0ac268`。保留文件声明及既有 GPLv3 全文，不将该文件重新许可为 GPL-3.0-or-later；未新增运行时依赖。普通LRC请求与可选QRC独立，容器/范围/对齐由本项目实现，详见 [QQ QRC接续](docs/lyrics-qq-qrc-20261107.md) 及模块NOTICE。

同日网易云YRC沿用现有MIT协议来源 `NeteaseCloudMusicApiEnhanced/api-enhanced` 固定 `2aab9957dfd5231e5b192aecdb177f93ace4c92e` 的 `module/lyric_new.js` 和既有EAPI传输，保留Binaryify归属与MIT全文。YRC有界解析、时间轴归一化和缓存升级由本项目实现；MeiloX上述固定归档的解析/测试仅作设计参考，未复制代码或引入依赖。见 [网易云YRC接续](docs/lyrics-netease-yrc-20261107.md) 和模块NOTICE。

同日 P2 收藏同步继续改编上述固定来源：酷狗 module/playlist_tracks_add.js、module/playlist_tracks_del.js；网易云 module/likelist.js、like.js；QQ qqmusic-api-python 0.8.1 的 qqmusic_api/modules/songlist.py 中 add_songs、del_songs、like_song、unlike_song，用于单曲收藏同步（通过 PyPI 原版本 wheel 只读核对，未安装或打包 Python）。酷狗和网易云沿用现有 MIT 许可，QQ 沿用现有 GPL-3.0-or-later 来源及许可；无新增生产依赖或许可更改。模块 assets NOTICE 已同步。

2026-11-01 后端菜单与歌单读取继续使用相同固定版本：酷狗 module/playlist_track_all.js、playlist_tracks_add.js；网易云 module/playlist_tracks.js；QQ songlist.py 的本人歌单及 AddSonglist 协议。新增局部收藏核实、排队和播放服务装配为本项目实现。沿用各模块既有来源与许可，无新增生产依赖。

2026-11-01 第二批后端继续使用上述固定来源：QQ modules/user.py、models/user.py 的只读会员查询；酷狗 module/privilege_lite.js、user_verify.js、song_auth.js、song_url_auth.js 的账号授权取流与高级档位；网易云固定会员/hires 接口。保留各模块 GPL-3.0-or-later/MIT 声明，不引入自动领取会员或新增生产依赖。

酷狗歌词后续兼容修复将搜索路径从固定vendor的 `/v1/search` 改为同一官方 HTTPS 主机实测可用的 `/search`，其余来源标识、签名及标准LRC下载解码沿用；未引入新第三方代码或依赖。

2026-11-06 歌单归属管理继续使用上述固定版本：酷狗 `module/playlist_tracks_del.js` 的普通歌单实际 fileid 移除；QQ 0.8.1 `songlist.py` 的 `del_songs/create` 及 `models/songlist.py` 创建结果字段；网易云 `playlist_tracks.js`、`playlist_create.js`、`playlist_name_update.js` 的增删、创建和改名。保留原 MIT/GPL 声明，没有新增运行时依赖。

同日后续接入酷狗本人普通歌单改名，专用云歌单加密改编自同一 MakcRe/KuGouMusicApi 的固定提交 `7ac64e1cdd333ad9bfff644ded82a0ffeef002d1`（`module/playlist_update.js`、`util/request.js#createCloudRequest`、`util/crypto.js`）；保留 MakcRe MIT 版权及现有完整许可。网易云整单订阅和新鲜令牌获取继续改编同一固定 MIT 来源的 `module/playlist_subscribe.js`、`register_checktoken_v3.js` 和 `util/request.js`；未捆绑或执行远端 JavaScript/验证码 SDK。上游订阅模块使用 v2，安卓 v3 替代路径尚需真实账号确认。

2026-11-13 酷狗普通歌单生产创建及设备测试源码 `KugouPlaylistCreationProbeDeviceTest` 适配同一固定提交 `7ac64e1cdd333ad9bfff644ded82a0ffeef002d1` 的 `module/playlist_add.js` 请求形状，复用现有Android签名；精确响应ID核实与写后读回由本项目实现。保留 MakcRe MIT 版权与已有完整许可，无新增运行时依赖，探针不进入主APK。

同日曲库整单删除继续改编现有固定来源：QQMusicApi 0.8.1（GPL-3.0-or-later）`3fc57f02e4eddb68a069bba4e0e12a7893f2a814` 的 `modules/songlist.py` 中 `songlist.delete`／`PlaylistBaseWrite.DelPlaylist`；网易云 MIT `2aab9957dfd5231e5b192aecdb177f93ace4c92e` 的 `module/playlist_delete.js` 明确WEAPI形状。酷狗同一MIT固定提交的 `playlist_del.js` 仅作为未启用自建删除的调查依据。用户排序存储、权限与写后核实为本项目实现；原版权、完整许可和模块NOTICE保留，无新增依赖或远端脚本。

同日酷狗删除验证探针 `KugouPlaylistDeletionProbeDeviceTest` 仅在androidTest适配上述MakcRe MIT固定提交的 `playlist_del.js`、`util/crypto.js`、`util/request.js`、`util/helper.js` 请求形状；复用现有AES／RSA实现与Android签名，未启用生产删除。原MIT完整许可与版权继续保留，无新增运行时依赖。

该单次实机探针成功后，同日按用户授权把已验证的酷狗普通自建歌单删除profile接入生产 `KugouApi`／`KugouLibraryApi`，仍改编同一固定MIT来源 `playlist_del.js` 及上述工具；Base64文本正文与改名的二进制profile分开，权限、完整列表核实和不重发由本项目实现。原版权、完整MIT许可与模块NOTICE保留，无新增运行时依赖。

QQ 整单收藏协议另以官方公开网页脚本 `common.chunk.5b378f933e2203b7da48.js` 和 `playlist_edit.chunk.2725441057c73cad7c10.js`（`https://y.qq.com/ryqq/js/`，2026-11-06 读取）作兼容性研究依据，Android 独立实现严格限定的请求与状态读取，不打包官方脚本。脚本快照和 SHA-256 留在本地验收参考材料中。MeiloX、Glasmio 未引入产品代码或资源；无新增运行时依赖。

同日 QQ 普通歌单改名继续依据上述两份官方公开脚本，独立实现 `uniform_get_Dissinfo` 和保留原元数据的完整 `EditPlaylist` 请求；固定 `mask=95` 来自编辑页原请求，不推导字段位定义。来源散列与边界见 [接续记录](docs/backend-qq-playlist-rename-20261106.md)，不打包脚本、没有新增依赖或许可变更。

同日后续按上述网易云固定 MIT 来源 `playlist_subscribe.js`、`register_checktoken_v2.js` 与 `util/request.js` 复核版本选择；移除此前不兼容的默认 v3 匿名令牌获取器，兼容 v2 提供者未接入时在写前拒绝。未移植或打包 Watchman SDK、浏览器特征模拟或远端脚本，没有新增依赖，原有版权和许可保留。

2026-11-12 歌曲推荐沿用既有固定来源：网易云 `recommend_songs.js` 的WEAPI日推（MIT）；酷狗 `recommend_songs.js` 及 `util/helper.js` 的标准Android签名与每日歌曲（MIT）；QQ 0.8.1 `recommend.py`/`models/recommend.py` 的登录态猜你喜欢电台（GPL-3.0-or-later，不承诺每日更新）。保留各模块NOTICE与许可全文，新增缓存/状态及播放整合为本项目实现，没有新增运行时依赖、业务服务器或远端脚本执行。
