# Musxi Player Android · 安卓开发分支

**当前分支 `android/development` 专门保存独立安卓工程，仓库根目录就是 Android Studio / Gradle 工程入口。正式 Compose 前端仍待接入，当前为开发版本，未发布正式 APK。**

2026-10-21 增量 PR：承接上游已合并的 [#23](https://github.com/Miloscovo/Musxi-Player/pull/23)，提交 P0/P1/P2 后端、测试、接线约定及验收记录。cc 未完成的 Compose UI、截图测试与其 Gradle 插件/依赖未包含在此提交；下文提到的 cc 工作区内容是历史背景，保留于本地开发工作区。提交范围及本轮验证见[增量 PR 记录](docs/backend-pr-20261021.md)。

2026-10-21 接续入口：[前后端推进与工程收尾记录](docs/development-handoff.md)。汇总 P0/P1/P2 当前能力、正式前端接线顺序、后端连接恢复/收藏刷新/耗时测量待办、验收边界及 Git/正式包收尾；后续会话先读此文，待办不代表已经实现。

2026-10-20 后端 P0 增量：歌曲封面、兼容旧版的封面元数据缓存、当前播放专辑及原平台/实际来源平台、可取消的三平台分页搜索、搜索单曲起播。接线见 [docs/frontend-backend-contract-v2.md](docs/frontend-backend-contract-v2.md)，验证和边界见规划第 30 节。cc 的 Compose 主题与截图改动保留，正式页面仍待接入。

2026-10-20 P0 已补充本机 Android 16 真机检查，结果见 [docs/p0-device-acceptance.md](docs/p0-device-acceptance.md) 与规划第 31 节，包含三平台分页搜索/封面、搜索单曲播放、换源及曲尾接续、封面字段冷缓存回读。听音、模拟失败和实际网络结果分别记录，正式前端视觉仍待接入验收。

2026-10-20 P1 后端现提供随机/循环、本人队列/位置/模式持久化与显式暂停恢复、最近播放，以及按实际播放来源同步的标准歌词。无需等待正式前端。接口见 [docs/frontend-backend-contract-p1.md](docs/frontend-backend-contract-p1.md)，证据与平台限制见 [播放模式验收](docs/p1-playback-modes-acceptance.md) 和 [P1 数据验收](docs/p1-data-acceptance.md)。冷启动只展示快照，点恢复后仍暂停，用户点播放才重新取流；不保存音频地址。

同日酷狗歌词空响应已修复：歌词搜索切到同一官方 HTTPS 域名的 `/search`，按实际来源标识查询，三首真机标准歌词及跳转同步已通过。正式前端接线与视觉验收仍待完成。

| GitHub 分支 | 用途 |
| --- | --- |
| [android/development](https://github.com/qwt1011/Musxi-Player/tree/android/development) | 安卓账号、曲库、缓存、Media3 播放与跨平台换源开发 |
| [main](https://github.com/qwt1011/Musxi-Player/tree/main) | PC 版本，跟随 [Miloscovo/Musxi-Player](https://github.com/Miloscovo/Musxi-Player) 上游主线 |

两个分支的根目录结构不同。安卓在本目录开发，PC 在原桌面工作区开发；PC 上游同步在 `main` 进行，安卓仅按需要移植相关协议或修复，避免直接合并两套根目录。

在其他电脑获取本安卓分支：

```sh
git clone --branch android/development --single-branch https://github.com/qwt1011/Musxi-Player.git Musxi-Player-Android
```

当前独立开发目录：`C:\text\Musxi-Player安卓`（2026-10-20 从原 `Musxi-Player/android/player` 完整复制）。用 Android Studio 直接打开本目录，或在这里运行下方构建命令。七个模块均在本目录内，无需桌面工程源码；Windows 中文路径所需的 AGP 路径检查配置已放入 gradle.properties。JDK 17 的参数文件使用系统编码，因此 Gradle 守护进程沿用本机默认编码，Java 源码与测试内容通过构建脚本单独固定为 UTF-8，避免中文路径下找不到测试类。

后续安卓开发以本目录为准；原 `C:\text\Musxi-Player\Musxi-Player\android\player` 保留为迁移前副本，不自动同步。规划记录见本目录的[安卓端适配规划.md](安卓端适配规划.md)，正式前端接线见 [docs/frontend-contract.md](docs/frontend-contract.md)。历史记录中的 `android/player/` 是迁移前路径，现对应本目录。本目录已初始化 Git，`origin` 指向个人 fork，使用 `android/development` 分支归档当前安卓成果。

项目沿用 Musxi Player 的 GPL-3.0-or-later；[LICENSE](LICENSE) 和 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 保留许可与来源。原始开发基线为 `80743c6aae9ab6b56c13200f53b0ebbb8e852de1`，本分支保留该 Git 历史，并将其后本地开发的安卓成果保存为独立工程；原始基线自身不包含这些安卓功能。

迁移验证：在本中文路径执行 `./build.ps1 -Test -Lint` 成功，142 项 JUnit 全通过，六个模块 Lint 0 errors。APK 位于 `app/build/outputs/apk/debug/app-debug.apk`；本次没有安装手机或重做设备验收。详情见规划第 28 节。

2026-10-19 开始整合。Kotlin + Gradle，手机本地直连平台，无业务服务器和 Node/Python 运行时。此阶段交付后端基础与临时开发验收页；正式 Compose 前端交由 cc 后续接入。

调试包为 `tech.musxi.player.dev`，与三个既有登录 PoC、官方应用及桌面端独立。不迁移或读取 PoC 的凭证，需要用户在新包亲自重新连接。

## 模块

| 模块 | 责任 |
| --- | --- |
| core | 无 Android 依赖的账号状态机、固定错误、平台与媒体 ID、分页/队列 DTO 和有限恢复策略 |
| accounts | 进程内账号调度、恢复、取消、验证后保存、官方登录入口、三平台音乐库 facade |
| platform-kugou / platform-qq / platform-netease | 从已验证 PoC 复制的协议、加密存储和原许可，后续修改限正式模块 |
| playback | Media3 ExoPlayer、MediaSessionService、通知/系统控制、播放状态与来源校验 |
| app | 依赖装配和临时开发验收页；debug 专用、受 DUMP 权限保护的固定验收入口 |

当前按同一进程串行执行账号和音乐库 IO，尝试 generation 隔离取消、断开和旧响应。UI 不持有平台 Session、Cookie、原始平台响应。官方网页由 accounts 内的浏览窗口组件管理，使用专属 WebView 数据目录；目前只有网易云使用该浏览器，不把全局 Cookie 清理推广给其他平台。

登录期间 WebView 临时管理自己的私有 Cookie，关闭/提交/取消时清理；进程强停不能立即回调清理，下次打开清理。保存的音乐会话必须经平台验证后以 Android Keystore AES-GCM 加密，位于 noBackup 私有目录。禁用云备份和设备迁移。临时 QR 图仅导出到自己的 MediaStore 记录，清理有生命周期限制，不申请广泛相册权限。

## 构建

需要 JDK 17、Android SDK 36，`ANDROID_HOME` 或 `ANDROID_SDK_ROOT` 指向 SDK。项目固定 Gradle 8.13（发行包及官方 wrapper jar 均核对 SHA-256）、AGP 8.13.2、Kotlin 2.2.21、coroutines 1.10.2、Media3 1.8.0。首次构建需要下载官方构建依赖。

```powershell
./build.ps1 -Test -Lint
./build.ps1 -Install
```

本机 Windows 中文目录统一使用 `build.ps1`；不要通过强制 UTF-8 客户端的 `gradlew.bat` 运行测试，否则 JDK 17 参数文件可能误读中文 classpath。APK 为 `app/build/outputs/apk/debug/app-debug.apk`；本地 SDK 配置、Gradle 缓存、APK、签名文件和测试输出均忽略。不在此阶段生成或发布 release APK。

原协议回归测试移入 Gradle，覆盖酷狗 527、QQ 188、网易云 393 项既有离线检查；保留原 PoC 测试。新增账号状态、会话失效、网易云音乐库解析与媒体来源策略测试。测试数量与真机验收分别记录，不以离线 fixture 代替平台兼容结论。

## 已实现边界

- 酷狗官方同机确认、QQ 同机确认后音乐凭证兑换；网易云普通网页登录和 Android 10+ 相册扫码备用。正式包整合后的各平台需分别真机验收。
- 网易云自己的歌单分页、歌单 ID 顺序和歌曲详情分页；ID 必须带平台。详情不完整时明确失败，不把截断结果当完整歌单。当前详情最多 20,000 首，单响应最多 4 MiB；服务端限制仍适用。
- 按需获取网易云官方标准音质地址，拒绝试听结果与身份冲突；HTTP CDN 地址只尝试升级为 HTTPS。取流显式选择固定 vendor 支持的 EAPI override，并未移植默认 XEAPI 密钥注册。实际可用性必须真机验证，不获取其他来源或绕过账号权限。
- 播放服务与 Activity 生命周期分离，音频焦点、耳机拔出、系统媒体控制共用一个播放器。外部可信 controller 只能控制已有媒体，不能注入队列；服务入口再次校验本应用身份与官方 HTTPS 媒体来源。
- 完整歌单 ID 队列、任意指定歌曲起播、上一首/下一首、播完自动接续与末尾停止；临近播放才加载歌曲详情和官方流，不一次请求全表音频。队列绑定账号代数，取消加载或替换后拒绝旧请求。
- 替换/清空通过同一服务命令提交，进程内只保留最新请求，队列不通过 Binder 传输；页面等待服务确认，迟到确认不覆盖新请求。普通系统停止也取消加载，应用内预清不会误取消下一次加载。
- 播放 URL 只在数据源加载线程短期使用，不进入 MediaItem、账号状态、诊断或曲库持久记录。Media3 timeline 使用服务生成的 opaque ticket；缓存最多 3 个 capability，每次使用检查到期和账号，断开/换号时停止并清空队列。
- CDN 地址失效最多自动重取一次；不可播放可跳到下一项，连续跳过最多 3 项；网络/超时/限流/账号错误停止自动请求，等待重试。服务显式关闭底层自动重试，恢复预算统一管理。
- 账号归属的歌单/歌曲分页元数据缓存，先展示本机记录再刷新；刷新失败保留内容，来源、读取时间、登录验证和缓存读写错误分别提供。应用 noBackup 私有目录保存校验过的原子集合文件，单集合 2 MiB / 128 页、最多 128 个集合、每平台总计 16 MiB（三平台最多 48 MiB）；只缓存已浏览页面，不下载整份曲库。封面 URL 当前不持久化，音频地址、凭证和音频文件不进入缓存。
- 启动先加载全部平台的本地账号身份，再逐一联网验证；缓存磁盘工作使用独立串行队列，断开清理同样排队，磁盘操作不持有账号或界面锁。待验证的保存账号可浏览自己的缓存，不能因此视为 CONNECTED；换号或断开隔离旧请求。

当前开发页不代表正式产品 UI。顺序队列之外的随机/循环/编辑、持久队列恢复、播放中真实断网/自然过期、旋转和更多设备的集成验收仍需分别完成。分页缓存不保证跨页的同一服务端快照，不代替完整曲库或离线音频。具体真机结果记录在外层 `安卓端适配规划.md`。

## 首轮真机结果（2026-10-19）

正式开发包在 Android 16 上通过网易云官方手机号网页登录、音乐账号验证、加密保存、覆盖更新保留登录，以及单独强停重开后的账号重验恢复。读取本人 1 个歌单、1 首歌曲，获取官方标准音质地址后由 Media3 实际播放；用户已确认能听到正常声音。此结果只覆盖当前账号、歌曲与设备。

暂停后进度保持不变，暂停时跳转到 15 秒成功，继续播放后进度增长；HOME 退到桌面后约 77 秒内进度持续增长、无播放器错误，自己的播放服务保持前台运行。锁屏按钮、耳机控制、长时间后台、系统回收及整机重启尚未专项验收；单曲成功不代表完整列表队列已完成。正式包的 QQ / 酷狗登录和网易云相册备用仍需本人操作验收，不能直接沿用独立 PoC 结论。收尾已暂停播放、保留登录；账号强停恢复不代表播放队列恢复。

随后用户补充歌单至 5 首不同歌曲，本轮已通过完整队列、从第 2 首起播、前后切歌、HOME 后曲尾自动进入下一首以及最后一首结束停止。用户再次确认“声音和切歌都正常”。debug 固定故障模拟覆盖地址失效重取、重取耗尽停止、不可播放跳过和连续上限、网络停止与手动重试；这些是本机注入的异常，不代表真实平台过期/断网已验收。

## 调试验收

2026-10-19 酷狗正式包增量：本人确认、加密保存、强停重开恢复通过；读取本人 3 个歌单和 30 首歌曲首页（选中歌单共 746 首），完整建立 746 项队列。用户确认首曲有声音；前后切歌、HOME 后曲尾自动接续通过。重验另一平台不影响当前播放，重验本队列所属平台会停止并清空旧队列；网易云 5 首播放回归通过。未逐首播放 746 首，不将整份队列建立等同全部歌曲有权限。

酷狗固定步骤为 `kugou_connect`、`kugou_check`、`kugou_restore`、`kugou_playlists`、`kugou_tracks`、`kugou_audio`、`kugou_audio_second`、`kugou_source_probe`；先等待歌单/歌曲成功再继续下一步。`netease_restore` 用于跨平台重验隔离，通用 `snapshot` 增加酷狗来源和首/次曲匹配布尔。调试入口仍只在 debug，用户本人确认登录；不接受账号或任意 URL。酷狗列表现已接入独立持久分页缓存，前端通过 `app.kugouLibraryBrowser` 读取；底层直接网络接口仍为 `app.kugouLibrary`，`app.queuePlayback` 统一播放。

音频传输边界：官方 v5 返回的两个青年版 CDN HTTP 地址升级 HTTPS 时出现证书主机名不匹配。实现优先探测官方响应中的 HTTPS 主/备用，仅对原响应的 `fs.youthandroid2.kugou.com`、`fs.youthandroid.kugou.com` 精确 HTTP 音频域做有限兼容，系统配置和播放器同时限制。HTTP 音频及其媒体查询参数不受 TLS 保护；账号和曲库 API 保持 HTTPS，未关闭证书验证，不合成 HTTP 地址或替换其他来源。固定 vendor、来源许可与此兼容边界均保留；接口与分页约定见前端 contract。

只有 debug manifest 注册 `ValidationReceiver`，要求系统级 `android.permission.DUMP`；release manifest 不含该入口。它只接受固定步骤名称，不接受 URL、账号、凭证或任意接口参数。用户本人完成登录后可由 adb shell 执行：

```powershell
adb shell am broadcast -n tech.musxi.player.dev/tech.musxi.player.ValidationReceiver --es step playlists
adb shell am broadcast -n tech.musxi.player.dev/tech.musxi.player.ValidationReceiver --es step tracks
adb shell am broadcast -n tech.musxi.player.dev/tech.musxi.player.ValidationReceiver --es step audio
adb shell am broadcast -n tech.musxi.player.dev/tech.musxi.player.ValidationReceiver --es step pause
```

`playlists` 选择本页第一个非空歌单，`tracks` 读取首个歌曲分页；`audio` 以第一首建立完整歌单队列，`audio_second` 以第二首起播。`next`（兼容旧名 next_audio）、`previous`、`last` 操作当前真实队列；`finish` 跳到曲尾前 1 秒，等待真实结束事件。另有 `resume`、`toggle`、`seek`（15 秒）、`stop`、`snapshot`。网络步骤异步完成，应等待对应成功状态再执行下一步；进程重启后必须重新选择歌单和歌曲。`snapshot` 刷新实际 controller 状态，可在 Activity 停止后使用，含索引、布尔匹配与恢复计数，不保存歌曲 ID/名称或地址。

排序边界固定步骤：`immediate_play_stop` 在同一主线程回合提交队列后立即清空；`rapid_replace` 连续提交第一首、清空、第二首；`system_stop_loading` 在歌单加载中发送普通 MediaSession STOP；`system_stop_then_play` 发送普通 STOP 后 50ms 提交第二首；`system_stop_interleaved_play` 用 debug 一次性回调在旧 STOP 尚未入队时提交第二首；`reject_stale_queue` 验证服务拒绝旧账号代数，并将异步失败反馈给调用方。这些 debug 入口不代替实际通知栏按钮验收。

debug 专用 `DebugMusxiApplication` 另提供固定 `fault_expired_once` / `fault_expired_twice` / `fault_permission_once` / `fault_permission_chain` / `fault_network_once`，分别注入有限次数的本地数据源错误；先 stop、等队列清空，再设置并执行 audio。`fault_clear` 清除模拟。整个子类、故障实现和 receiver 均不在 release 源集中，release manifest 已检查使用正式 MusxiApplication。只检查自己应用的固定诊断与 MusxiQueue 固定事件日志，不读取 Cookie、数据库、会话内容或平台私有响应。

曲库缓存固定步骤：`cache_playlists` / `cache_tracks` 先读取缓存再刷新；`cache_playlists_only` / `cache_tracks_only` 仅读磁盘；先执行歌单步骤再选首个非空歌单的歌曲。`cache_refresh` 刷新当前页，`cache_network_off` / `cache_network_on` 仅模拟此调试包的曲库请求失败/恢复，不改变账号或手机网络。`cache_snapshot` 只保存来源、项目数、分页、时间是否存在、验证布尔、固定错误和请求计数，不输出缓存文件内容。真实离线冷启动验收另行记录，不将模拟故障当作断网。

账号前端接入说明见 [docs/frontend-contract.md](docs/frontend-contract.md)。依赖许可与改编来源见 APK assets 和仓库 THIRD_PARTY_NOTICES；未包含 LoveACE 代码、品牌图案或官方音乐 App 实现。


## 酷狗缓存与刷新（2026-10-19 增量）

酷狗使用独立的 `kugouLibraryBrowser`，与网易云共用缓存优先状态模型和刷新机制，分别按平台/账号归属保存已浏览页面。刷新失败保留内容、来源和读取时间；待验证身份可浏览本机缓存，播放仍需 CONNECTED。首页刷新成功淘汰该集合后续页，断开清除该平台缓存，不影响另一平台。网易云原 v1 磁盘格式和目录保留。

酷狗分页续接按 30 项步长透传；未知总数的短页仍可能有后续，不能按本页项数推算。只保存已浏览页，不声称全量同步、离线音频或持久队列。每平台缓存上限 16 MiB / 128 集合，单集合 2 MiB / 128 页；两个平台合计上限 32 MiB。

新增固定 debug 步骤 `kugou_cache_playlists` / `kugou_cache_tracks`、相应 `_only` 仅读缓存、`kugou_cache_refresh`、`kugou_cache_next`、`kugou_cache_network_off/on` 和 `kugou_cache_snapshot`。酷狗的故障包装及诊断独立，不影响网易云；原 `cache_*` 步骤仍只针对网易云。仅读缓存时若账号状态发生变化，浏览器仍会按账号状态自动刷新；真实离线时待验证身份不发曲库请求。只检查固定计数/布尔/错误，不读取私密缓存内容。

最终包在 Android 16 通过真实断网冷启动：酷狗 3 个歌单、空末页及两个歌曲页共 60 条已浏览元数据可从磁盘恢复，网易云原 1 歌单/5 歌曲缓存仍可读；两平台曲库请求为 0、登录标为待验证。恢复原网络后两家会话重验及列表刷新成功。同账号重验保留当前歌曲分页；107 项 JUnit 全通过，app/accounts Lint 0 errors。实际换号/断开仍只由自动测试覆盖，未为验收清除本人登录。


## QQ 正式包读库与播放（2026-10-19 增量）

正式开发包使用 QQ 同机确认，查询原请求后兑换并验证 QQ 音乐凭证，验证保存成功才 CONNECTED。新增原生 Java `QqLibraryApi`，参考固定 qqmusic-api-python 0.8.1 已有 GPL 许可模块，将本人自建/收藏歌单、歌曲、详情、标准 MP3_128 vkey 和 CDN 调度接到手机本地直连；无 Python/业务服务器，也不读取 PoC 或其他应用凭证。曲库合并有界、队列完整加载有取消和时间预算，不将 30 首首页视为全歌单。

前端使用缓存优先的 `app.qqLibraryBrowser`，底层直接网络接口仍为 `app.qqLibrary`；歌曲 key 为 `MediaKey(QQ, songMid)`，歌单 key 为 `MediaKey(QQ, "tid_dirid")`；均只透传。QQ 已接入独立持久分页元数据缓存；网易云/酷狗原格式和目录兼容。播放统一使用 `app.queuePlayback` 与原 Media3 服务，按所属平台 generation 校验和停止，不支持混合平台队列。

QQ 音频严格核对歌曲 MID、请求 M500 文件名及相对路径，拒绝权限错误、加密或识别到的试听。仅允许官方响应提供的 `stream.qqmusic.qq.com`（及子域）和精确 `aqqmusic.tc.qq.com` HTTPS，原 HTTP 根只升级尝试，不新增明文例外。URL 只在短期内存 capability 中，每次检查到期/账号，不进入 DTO、MediaItem 或磁盘缓存。

固定 debug 步骤新增 `qq_connect` / `qq_check` / `qq_restore`、`qq_playlists` / `qq_tracks` / `qq_audio` / `qq_audio_second` / `qq_source_probe`。先等异步成功，再运行下一步；通用 `snapshot` 增加 QQ 账号阶段、所属平台和首/次曲匹配布尔，失败诊断限预定义类别，不保存歌曲名称、ID、URL 或凭证。

当前账号首次 1 首曲目的 vkey 被平台以 104003 拒绝，不能记为可播；用户补充至 6 首后，第 2 首建立完整 6 项队列、HTTPS 取流、进度增长、无播放器错误，用户确认手机有声音。当前曲目成功不代表所有歌曲/会员音质有权限，详细验收边界见外层规划文档。

最终包当前列表第 1 首也曾实际播放成功，因此此前 104003 只代表当次请求，不代表某首稳定不可播；没有将本轮 QQ 真实权限自动跳过记为通过。完整矩阵和收尾状态见外层规划第 23 节。

最终包 119 项 JUnit 全通过，app/accounts/platform-qq/playback Lint 0 errors，默认模型只读交叉检查无遗留 P1/P2。最终重复验证 QQ 前后切歌、后台曲尾接续、三平台重验隔离及强停恢复；收尾 QQ 6 项队列暂停、三家 CONNECTED、保留登录。源码与详细验收记录见外层规划第 23 节。

## QQ 缓存与刷新（2026-10-19 增量）

QQ 页面统一使用 `app.qqLibraryBrowser` 的 `LibraryBrowseState`：先显示本机记录再刷新，失败保留内容、来源和读取时间；缓存读写错误单独提供。保存身份待验证时可以浏览自己的缓存，不能据此标记 CONNECTED 或保证播放。同账号重验保留当前歌曲分页；换号回到本人歌单，旧请求不能覆盖新账号状态。

QQ 使用独立 noBackup 目录、文件 magic 和平台/账号归属。歌单 key 为 `tid_dirid`（包括 `0_201`），歌曲为 14 位 ASCII 字母/数字 MID。每页最多 30 项，续接按实际返回项数，必须透传 nextOffset，不能套用酷狗固定 30 步长。首页刷新成功淘汰该集合后续页；空列表、缓存缺失和请求失败分别表达。每平台 16 MiB / 128 集合，每集合 2 MiB / 128 页；三平台总预算 48 MiB。只保存已浏览元数据，不缓存音频文件、音频地址或封面 URL。

新增独立 debug 固定步骤 `qq_cache_playlists/tracks`、相应 `_only`、`qq_cache_refresh`、`qq_cache_next`、`qq_cache_network_off/on`、`qq_cache_snapshot`。QQ 模拟失败不影响其他平台或真实网络。真实断网冷启动通过：QQ 1 歌单/6 歌曲从 CACHE 恢复，网易云 1/5、酷狗 3/30 首页缓存保持，三家曲库请求均 0、accountVerified=false。恢复原网络并重验后三家 CONNECTED，QQ 当前歌曲页自动刷新。QQ 6 项队列第 2 首起播并切到第 3 首，进度增长无错误，收尾暂停；本轮未要求用户重新听音确认。

新增 4 项缓存自动测试，共 123 项 JUnit、0 failures/errors；app/accounts/platform-qq/playback Lint 0 errors，release 清单无调试入口，debug APK v2 签名通过。实际换号未动本人账号，由自动测试覆盖。两端播放权限与请求兼容性的证据及后续对照见 [QQ 播放能力对照](docs/qq-playback-parity.md)，缓存成功不代表会员歌曲已解决。

## 当前开发顺序（2026-10-19）

QQ 缓存与高频使用验收已完成；跨平台来源闭环及 5 秒自动选择现已接入，见末节。原队列仍归一个平台，当前曲目可临时使用其他平台来源；后续依据统一接口交 cc 正式前端接线。用户反馈《AIZO》在 QQ 两端均失败、其他搜索结果可播，调整为扩大可用来源的产品方向，同名不代表同录音。

过程与验收状态见 [playback-acceptance.md](docs/playback-acceptance.md)，换源设计见 [cross-platform-sources.md](docs/cross-platform-sources.md)。历史阶段的“待做”以最新增量为准；代码接入、系统事件和用户听音分别记录，不将未验收事项记为通过。


本轮基础使用验收已完成：用户确认耳机断开暂停、重连不自播，以及锁屏媒体卡片/暂停继续/切歌有声；真实系统焦点、页面重建、短时锁屏和真实音频断网后显式重试通过。新增 suppression 状态供正式前端区分系统打断。123 项 JUnit 全通过，app/accounts/playback Lint 0 errors；debug 焦点测试 Activity 不进入 release。详细证据与未覆盖范围见 playback-acceptance.md，下一阶段进入跨平台手动选源闭环。

## 跨平台来源（2026-10-19 最新增量）

已接入三家首屏歌曲搜索，原曲明确不可播时在原队列下搜索另外已连接平台。候选准备后 5 秒自动尝试首个同名项，也可提前手选；没有同名项只手选。原队列 mediaId/索引/顺序保留，替代曲播完回原平台下一首。每首最多尝试 3 个来源，取消/暂停/停止/切歌拒绝旧计时与迟到结果；网络错误不会无限换源。实际使用能力、匹配与传输边界见 [cross-platform-sources.md](docs/cross-platform-sources.md)，正式前端接口见 [frontend-contract.md](docs/frontend-contract.md) 末节；历史“仅手动/尚未实现”以此增量为准。

新增固定 debug：`qq_audio_aizo` 从本人已读取首屏找 AIZO 并建立原完整 QQ 队列；`sources_choose_first/second`、`sources_cancel` 验证同一正式状态；`sources_search_aizo` 与 `sources_{qq,netease,kugou}_search_aizo` 只搜索固定关键词并记录数目/匹配布尔。`fault_permission_delayed` 在 loader 线程延迟 2 秒返回一次模拟权限错误，用于“先暂停后报错”验收；不是平台真实拒绝。新诊断只含阶段、平台、候选数量、所选索引/同名布尔及剩余毫秒，不输出歌曲身份、账号或 URL，均不进 release。

核心真机闭环已完成：QQ 7 项歌单 AIZO→自动网易云来源，用户确认声音及歌曲正确；手选第二项、替代曲中后段和曲尾接回原 QQ 下一首通过。模拟原曲加 3 候选全部拒绝后稳定停止、原队列保留，重试先恢复原平台；暂停/取消/真实 STOP/导航使旧计时失效。选源等待统一映射为媒体会话 BUFFERING，修复本机连续失败时 legacy 控制器额外 STOP 的复现，保留主动停止。debug 增加 `system_play_current` / `system_stop_current`，不构成业务 API。

142 项 JUnit 全通过，六个 Android 模块 Lint 0 errors，debug APK v2 签名及 release 清单隔离通过。最终 debug APK SHA-256：`083542d04d7859b63698f177c156f21e2750111e191335062f2c06081e98b016`。HOME 后自动选择已观测，但一次新取流 NETWORK，显式重试成功；已开始的替代音频经页面重建/HOME 短时继续。未证明所有后台起播、长时 Doze、所有歌曲或录音一致性，完整证据见 cross-platform-sources.md。正式前端尚待 cc 接线。

## 2026-10-20 后端增量

P0 封面、分页搜索及单曲播放见 [v2 接线约定](docs/frontend-backend-contract-v2.md)；P1 循环/随机、持久队列与暂停恢复、实际来源标准歌词、最近播放见 [P1 接线约定](docs/frontend-backend-contract-p1.md)。酷狗标准歌词空响应兼容已修复。

P2 已提供三平台喜欢集合同步/单曲变更与当前播放队列移动、删除、下一首插入、追加和清空，见 [P2 接线约定](docs/frontend-backend-contract-p2.md) 和 [真机验收](docs/p2-acceptance.md)。三平台各一首真实收藏切换及恢复均已通过，完整集合回到测试前状态。后端不依赖正式前端即可验收；Compose 接线、页面交互及视觉验收继续由前端推进。
