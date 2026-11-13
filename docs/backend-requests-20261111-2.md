# 后端需求单 · 2026-11-11（二）：锁屏歌词（前端 → Codex）

> **状态：当前需求单（2026-11-11）。** 用户 11-11 确定：锁屏歌词走 ColorOS 原生锁屏歌词页（方案 A），**先做探针确认非 root 能否显示，再决定是否正式实现**；“媒体卡片标题显示当前行”（方案 B，默认关）一并列入计划。完成后由 Codex 在末尾“交付说明”填写接口与验证边界。

> **用户后续安排：**原生探针 A1／A2 均未显示，酷狗显示的是自有锁屏页，原生页对照仍未完成。用户随后选择先做第 4 项方案 B；第 2 项原生发布器继续暂缓。

读者：负责 playback / core / app 后端层的 Codex。约定同前几批：不改 `app/src/main/kotlin/tech/musxi/player/ui/`（CC 负责）。行号以 2026-11-11 工作区为准，开工前复核。事实来源：CC 组织的只读调研（会话、歌词、ColorOS 协议三路 + 复核），关键结论已抽查。

## 0 · 先读：最大的不确定性

ColorOS 的锁屏歌词页由 SystemUI 读取播放器**平台媒体会话**上 `MediaMetadata["lyricInfo"]`（JSON 字符串）显示。但 ColorOS-Live-Lyrics-Bridge（Apache-2.0）仓库里的反编译证据显示 SystemUI 有按包名放行的闸门：

- `resolveLyricSupport(pkg, metadata)`：`getLyricEnable(pkg) == 1` 时要求 metadata 里 `"oplus.media.config" == "1"`；
- `getLyricEntrance(pkg)` 入口查询，以及 RUS 配置 `app_systemui_oplus_media_controller_config.xml` 的 whiteList；
- 反例：Readify 真机发布 lyricInfo 成功，但仍需 Bridge（root）放行才显示（`PlayerSystemUiPolicy.java:32-34`）。

所以**非白名单的第三方包很可能不显示**。方案 A 的全部投入取决于第 1 项探针结果。

## 1 · 探针（P0，仅 debug 构建）

目的：区分“没写进平台会话”和“系统不显示”。

1. 只在 debug 构建（debug sourceSet 或 `BuildConfig.DEBUG`）里提供一个触发入口：沿用 `ValidationReceiver` 固定步骤，或一条只对 app controller 开放的 SessionCommand。
2. 拿到平台会话：Media3 1.8.0 没有公开 API。复核结论：`MediaSession.getImpl()`（包内）→ `MediaSessionImpl.getMediaSessionLegacyStub()`（protected）→ `MediaSessionLegacyStub.getSessionCompat()`（public）→ `MediaSessionCompat.getMediaSession()`（public，返回 Object，强转 `android.media.session.MediaSession`）。需在 `androidx.media3.session` 包里放一个 Java 辅助类调用，**自己写，不拷 MeiloX（GPLv3）**。
3. 在当前平台 metadata 上追加 `lyricInfo`（最小 payload 见第 3 项），写入后把平台 `MediaController.metadata` 里的 `lyricInfo` 读回并打日志，证明确已写入。
4. 三组对照，**由用户本人锁屏、解锁观察，不自动输入密码**：
   - A1：只写 `lyricInfo`；
   - A2：`lyricInfo` + `"oplus.media.config" = "1"`；
   - 对照组：用已知受支持的官方 App（如酷狗、QQ 音乐）播放一首歌，确认这台 PJF110 的锁屏歌词页本身存在。
5. 记录 SystemUI 版本（`dumpsys package com.android.systemui` 的 versionName）、ColorOS 版本和三组结果，写入新的设备验收文档。

Media3 1.8.0 只在媒体项切换、metadata 变化、时间线变化（含 `replaceMediaItem`）和封面位图异步加载成功时重写平台 metadata，定期进度同步不重写。探针要在平台 `MediaController.Callback.onMetadataChanged` 里重新附加，否则封面加载完就被冲掉。

## 2 · 正式实现（探针 A1 或 A2 显示后再做）

1. **桥接**：歌词在 app 层（`MusxiApplication.lyrics.state`，由 appScope 跟随播放驱动，与界面无关），平台会话在服务里，两者同进程。建议在 `PlaybackHost`（`playback/.../QueueSources.kt`）上新增一个只读歌词流成员，由 `MusxiApplication` 组合；**不要**在服务里再建一个 `LyricsBrowser`（会重复拉取和缓存）。
2. **歌曲关联**：`LyricsState` 只有 `sourceKey`，没有 mediaId。桥接层需同时给出平台 `METADATA_KEY_MEDIA_ID`（等于 Media3 `MediaItem.mediaId`）和对应歌词；发布器只在两者一致时附加。换源后 mediaId 不变而 sourceKey 变化，先清掉旧歌词再等新歌词。
3. **发布器规则**（参照 MeiloX 的行为，代码自写）：
   - 只追加/删除自己写的 `lyricInfo`，不改标题、封面、控制按钮；
   - 复制 metadata 时按值类型逐个复制（MeiloX 注释称 ColorOS 下用 `Builder(existing)` 会丢位图），遇未知类型整次放弃；
   - Parcel 实测整份 metadata 不超过 512KiB，超出只放弃歌词；
   - 切歌立即清空，加载回来后校验 mediaId 再发布；
   - 平台回调和 `setMetadata` 在主线程，JSON 构建对长歌词的耗时需评估。
4. **本地音频**：目前必然无歌词（`localMetadata` 不写队列平台，`follow` 得到 null）。本地播放时写 `noLyric = true` 或不写，以探针结果为准。
5. **设置**：`AppSettings` 加 `lockScreenLyrics: Boolean`（默认值待探针后定），并提供“本机是否支持”的判断（至少 `Build.MANUFACTURER` 覆盖 OPPO/OnePlus/realme/OPlus；能否探测 SystemUI 歌词页另议）。前端只在支持时显示开关。
6. **翻译**：跟随全局 `lyricsTranslationPreference`。歌曲级手动开关目前只在 Compose 状态里，锁屏**不跟随**它。判断函数 `translationShown` 在 UI 包，如需在后端用，请下沉到 core 并告知 CC 改引用。
7. **格式化器**放 core，便于单测。

## 3 · lyricInfo 数据格式（来自 MeiloX 源码与 Bridge 协议文档，只借格式）

JSON 键：`songName`(String)、`artist`(String，多歌手用 `" / "`)、`songId`(String，取 mediaId)、`lyricType`(Int，标准时间轴为 0)、`lyric`(String，逐行 LRC，**原生显示必需**)、`noLyric`(Boolean)、`provider`(String，包名)、`sessionGeneration`(Long，切歌递增)；可选 `rawLyric`（逐字）、`translationLyric`（翻译）、`album`。

- 时间标签 `[mm:ss.mmm]`，歌曲绝对毫秒，Locale.ROOT；文本中的换行替换为空格。
- `lyric` 每行 `[行起点]文本`。
- `rawLyric` 每行 `[行起点]<字1起点>字1<字2起点>字2…<末字结束>`；无逐字的行退回普通 LRC 行。Musxi 的 `LyricWord` 是 UTF-16 区间 + 未加偏移的绝对毫秒；手动偏移 `offsetMs` 是否直接加进时间戳，请在交付说明写明。
- `translationLyric` 为逐行 LRC，时间用对应原句起点，只输出有译文的行。
- JSON 超过约 128K 字符不发布。
- 进度不用反复写 metadata：SystemUI 从 PlaybackState 自己计时。
- 原版 SystemUI（无 Bridge）是否显示逐字和翻译**未知**，可能只显示逐行；探针时一并观察。

## 4 · 方案 B：媒体卡片标题显示当前行（P2，默认关）

1. **只能在平台会话这一层覆盖 TITLE**，不能 `replaceMediaItem` 改 Media3 的 MediaItem：`PlaybackConnection` 从 MediaItem 读标题和来源，`updateMetadata` 会把歌词当变化写回原标题。
2. 按行边界定时刷新，定时用服务里的 `player.currentPosition`；不能依赖应用侧每 500ms、仅播放时的进度 tick。暂停、拖动进度时重新排定。
3. 原歌名放哪个字段（ARTIST / ALBUM）、蓝牙/车机每行刷新的副作用、与方案 A 同开时 SystemUI 是否把改过的 TITLE 当歌名，都请在交付说明写明实测结果。
4. 设置项 `mediaCardLyrics: Boolean`，默认 false。

## 5 · 前端（CC）

“我的 → 歌词”加“锁屏歌词”开关（只在支持时显示，下附一句说明）和方案 B 的开关。等后端设置项与支持判断交付后接线。

## 交付说明

### 用户后续选择：第 4 项方案 B

已实现 `AppSettings.mediaCardLyrics: Boolean = false`、持久化键 `settings.media-card-lyrics` 与 `app.settingsRepository.setMediaCardLyrics(Boolean): AppSettings`。保存语义同既有设置：调用方线程同步保存成功后发布，失败不推进状态，前端在 IO dispatcher 调用并处理异常。旧数据缺键／畸形值均保持关闭。未修改 `ui/`，由 CC 接开关。

平台 `TITLE`／`DISPLAY_TITLE` 显示当前原句，原歌名与原歌手合并放入 `ARTIST`／`DISPLAY_SUBTITLE`，原专辑保留。只覆盖现有平台会话 metadata，不 `replaceMediaItem`，应用内歌名、队列与最近播放不随歌词变化。服务用真实 player.currentPosition 和 speed 按行边界安排，暂停／seek／倍速／缓冲重新计算；offset 沿用正值延迟语义。关闭、无可用歌词、切歌／换源失配、结束或销毁时恢复自己仍持有的原字段，保留最新封面与外部更新。歌词文档同时校验原队列 queueKey 与实际 sourceKey，避免换源旧歌词泄漏。

蓝牙和车机可能将每行歌词视为歌名，本轮没有相关实体设备验证；默认关闭。方案 A 正式发布器仍未实现，A／B 同开时 SystemUI 是否将替换后的 TITLE 作为歌名未验证。通用平台 API 不依赖 ColorOS，但各系统卡片／通知渲染策略不同，不能承诺所有手机显示相同。接口、恢复语义、验证与实机边界见[方案 B 交付](backend-media-card-lyrics-20261111.md)，实际验证结果由 `.local/artifacts/media-card-lyrics-20261111/final-summary.json` 索引。本包未安装手机，CC 仍持有安装窗口；不提交、不推送。

本轮方案 B 专项 24 项通过，accounts／playback／app Lint 均 0 错误，playback release 编译与 debug 探针隔离检查通过；源码专项冻结为 18 文件。全量 `scripts/verify.ps1` 未通过：CC 前端 `SheetPhysicsTest.releaseVelocityUsesTheBandsLocalSlope` 第 129 行期望 0.55、实际 1.0，未改其实现或断言。新 APK 与完整结果保留于上述独立目录，不能使用原生 A1／A2 的设备结果代替 B 实机验收。

### 前阶段：第 1 项 debug 原生探针与设备结果

2026-11-11：按用户明确范围，**本轮仅实现第 1 项 debug 探针，A1／A2 实机观察已完成，原生歌词页阳性对照未完成**。16:36 已保留数据安装固定探针包。PJF110／Android 16／ColorOS V16.0.0／SystemUI 16.00.14 的 A1、A2 平台写入与观察后读回均成功；A1 不带 config，A2 的 config 为字符串 `1`。用户两组锁屏观察均只有音乐卡片，没有歌词相关入口或内容。官方酷狗 20.5.6（20569）由用户操作，最初反馈 App 内与锁屏均有歌词；16:59 澄清为“酷狗自己的一整页锁屏界面”，不能据此认定 ColorOS 原生歌词页可用。16:50 已采日志并关闭探针，读回确认两个测试字段均已清除；16:54 已保存证据并释放安装窗口给 CC。第 2 项正式实现和第 4 项媒体卡片标题方案继续暂缓。

- 固定入口：现有 DUMP 保护的 debug `ValidationReceiver` 新增 `lock_lyrics_a1`、`lock_lyrics_a2`、`lock_lyrics_status`、`lock_lyrics_off`。先正常播放，再触发；不自行换队列或自动播放。
- 自写 Java 辅助类位于 `playback/src/debug/java/androidx/media3/session/PlatformSessionProbeAccess.java`，按需求的 Media3 1.8.0 包内调用链取得**现有平台会话**，无反射／root／Bridge。探针及辅助类只编入 debug，未改 main/release、`ui/`、真实歌词桥接、设置或翻译逻辑。
- A1 写入 `lyricInfo`，显式不带 `oplus.media.config`；A2 额外写字符串 `"1"`。平台 `MediaController.metadata` 实际读回并与预期比较；日志 tag 为 `MusxiLockLyricsProbe`，`WRITE_READ_BACK_OK` 表示字段已写入，系统是否显示仍需用户判断。私有 JSON 报告为 `files/lockscreen-lyrics-probe.json`。
- payload 使用醒目的**合成测试歌词**，覆盖行级、逐字段落和英文副行，偏移为 0；songId 与当前平台 mediaId 一致。它不代表真实歌曲歌词或正式翻译接线。JSON 上限 128Ki 字符，完整 metadata Parcel 上限 512KiB；逐类型保留原 metadata，未知类型放弃。
- 主线程监听平台 metadata 变化，封面等重写后重新附加；值已一致时不重写，避免回调循环。切歌／会话销毁停止、10 分钟超时检查、`off` 清理并注销回调；只恢复探针两个键，保留最新标题／封面，切歌不继承上一曲原有歌词。深睡时不保证准时清理，须等主线程执行任务／再次观察，不额外持有唤醒锁。
- 操作命令、环境版本记录、三组结果表和判断边界见[本轮设备验收记录](backend-lockscreen-lyrics-probe-20261111.md)。A1 实机已确认 metadata 回调后重新附加，两组观察后字段均保留，但系统均未显示歌词；关闭后字段清理通过。用户确认官方酷狗显示于其自有锁屏页，原生歌词页阳性对照未完成；同曲目对照、逐字／译文和 root／Bridge 状态未单独确认。当前仅能判断 Musxi 本次 debug 探针未显示，不能认定唯一原因是系统白名单，也不能外推正式包名或其他设备。第 2 项要求 A1／A2 至少一组显示的前提尚未满足；后续若继续原生路线，应先补有效原生页对照。
- 专项单测、playback release AAR 隔离检查和统一验证的实际结果与 APK 由 `.local/artifacts/lockscreen-lyrics-probe-20261111/final-summary.json` 索引；源码编译／Robolectric 不能代替 PJF110 锁屏观察。不提交、不推送，准备阶段不安装或操作手机。
