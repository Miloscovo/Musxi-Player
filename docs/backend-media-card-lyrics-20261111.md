# 媒体卡片标题歌词：方案 B 后端交付

2026-11-11：用户在原生锁屏探针后选择先做[需求单第 4 项](backend-requests-20261111-2.md#4--方案-b媒体卡片标题显示当前行p2默认关)。本轮实现方案 B，默认关闭；ColorOS 原生发布器（第 2 项）继续暂缓。不修改 `app/src/main/kotlin/tech/musxi/player/ui/`，不提交、不推送，不占用 CC 当前的手机安装窗口。

## CC 接线接口

- 读取 `app.settings: StateFlow<AppSettings>` 的 `mediaCardLyrics: Boolean`，默认 `false`。
- 调用 `app.settingsRepository.setMediaCardLyrics(enabled): AppSettings`。同其他设置一样，在调用方线程同步保存，建议 `Dispatchers.IO`；保存成功后才发布状态，失败抛异常，前端处理失败后保持原状态。也支持既有 `update { it.copy(mediaCardLyrics = enabled) }`。
- 存储键 `settings.media-card-lyrics`，值为字符串 `true`／`false`；旧快照缺键或畸形值均回退关闭。与账号、方案 A 的未来开关独立。
- 项目最低 Android 8.0／API 26，方案 B 使用通用平台会话，不按 OPPO／ColorOS 厂商门禁隐藏。各系统卡片、通知、蓝牙与车机选择显示哪个 metadata 字段不同，不能仅凭通用 API 声称所有设备都已经验证显示。
- 开关文案需说明：开启后系统媒体标题会显示当前原句，蓝牙／车机等也可能将歌词当作歌名。默认关闭，没有新增逐字或译文开关。

## 展示与恢复语义

| 场景 | 行为 |
| --- | --- |
| 有对应的定时歌词，且到达某行 | 平台 `TITLE` 和 `DISPLAY_TITLE` 显示当前原句；`ARTIST` 和 `DISPLAY_SUBTITLE` 显示“原歌名 · 原歌手”；`ALBUM` 保留原专辑 |
| 长文本 | 沿用已有平台歌手展示文本；原句和副标题压为单行、最多 512 个 UTF-16 单元，不截断代理对 |
| 第一行之前、空行间奏、无歌词／纯文本／加载／错误、本地音频 | 保持或恢复平台原始标题／歌手／原有 display 字段；不猜歌词，不拿译文替代 |
| 暂停／缓冲 | 停止行边界计时，按当前播放位置保留这一行；暂停拖动进度仍重新计算 |
| seek、倍速、恢复播放、offset 修改 | 从服务的实际 `player.currentPosition` 重新取样，按实际 speed 安排下一行；正 offset 与应用内一致，表示延迟歌词 |
| 切歌／换源／账号失效／停止／播放结束 | 不让前一目标的歌词继续覆盖；恢复自己仍持有的字段，等待新目标就绪 |
| 关闭开关／服务销毁 | 取消计时、恢复自己最后写入且尚未被外部替换的字段；原先缺失的 display 键重新移除，不覆盖外部更新 |
| 封面或原始 metadata 更新 | 在最新平台 metadata 上重新附加；保留新封面、时长、评分、0 值 Long 和厂商键。遇不支持的值类型或整份 Parcel 超过 512KiB 时跳过写入，不重建媒体项 |

行内仅展示原句，不叠加译文，不做逐字扫光；应用内歌词的翻译设置和临时翻译开关不影响此模式。最后一行保留到结束／切歌，空文本行恢复原标题。

## 后端关联与时钟

`LyricsState` 末尾新增 `queueKey: MediaKey? = null`，标明歌词所属的原始队列歌曲，区别于实际 `sourceKey`；loading／error／timed／plain／empty 均与当前目标一致，取消后清空。旧具名构造与默认构造兼容。UI 可继续使用原有字段，无需持有平台会话。

`PlaybackHost.mediaCardLyrics: Flow<MediaCardLyricsState>` 提供只读展示数据，默认空关闭；`MusxiApplication` 组合既有播放、歌词、设置与换源状态，校验 queueKey、sourceKey 后发出文档。没有第二个 LyricsBrowser，没有重复歌词请求。相同文档的 activeIndex／字词进度 tick 不触发 metadata 刷新。服务再核对当前 MediaItem.mediaId、QueueSources 实际音源与账号代次；换源等待／失败不使用旧文档。

`MediaCardLyricsPublisher` 只写现有 `android.media.session.MediaSession`，通过主源码集的自写 `androidx.media3.session.PlatformSessionAccess` 取得 Media3 1.8.0 内部会话。没有反射、root、包名伪装或新增会话。这个内部访问链需要在 Media3 升级时重新编译验证。

计时在服务主线程以 `Handler` 按下一行绝对歌曲时间安排，墙钟延迟按 player speed 换算；醒来后重读 player.currentPosition，跳到实际当前行，不补写已经错过的每行。暂停、seek、缓冲、倍速、媒体项与平台 metadata 事件均重新计算；不依赖应用每 500ms 的进度 tick，不持有额外唤醒锁。平台字段已经等于预期时跳过写入，防止自己的回调形成循环。

Media3 的 `MediaItem`、队列、应用内原标题、最近播放和音源标题均不替换。保留 `lyricInfo`／`oplus.media.config` 等其他键；正式方案 A 未实现，因此未验证 A／B 同开时 SystemUI 如何读取歌名。旧 debug A1／A2 payload 会从平台 TITLE 取 songName，本轮不把它当作 A／B 同开兼容保证。

## 验证与手机交接

本轮自动验证证据、源码清单和保留 APK 由 `.local/artifacts/media-card-lyrics-20261111/final-summary.json` 索引，实际通过／失败以该索引为准，旧探针的 APK 与设备证据继续保留在原目录。

已执行统一入口 `scripts/verify.ps1`，全量测试仍有 CC 前端 `SheetPhysicsTest.releaseVelocityUsesTheBandsLocalSlope`（第 129 行，raw=0 的橡皮筋斜率断言）失败，完整日志与原始 summary 独立保留。没有修改 CC 的实现或测试，也没有放宽断言；本轮后端专项、release 编译和 Lint 由同一 `build.ps1` 文件锁内另行收集，局部通过不等于全量通过。

最终后端专项 **24 项通过，0 失败／错误／跳过**：core 9、accounts 4、playback 11。accounts／playback／app 的 Lint 均 0 错误，警告分别 3／3／109；playback release AAR 编译通过，含主源码集发布器与访问类，不含 debug 探针或测试 shadow。18 份本轮源码散列在专项前后保持一致。汇总脚本的 PowerShell 计数错误已修正，最终汇总使用同一锁内已保存的报告，没有重复运行通过的测试。

保留 debug APK 为 `.local/artifacts/media-card-lyrics-20261111/app-debug-media-card-lyrics.apk`，28,164,099 bytes，SHA-256 为 `0eec0b70d03e7d668afd9217aa04608e7225831faf6f8c35ae679f05971589c8`。这是方案 B 包，不是 16:36 安装的原生探针包；当前没有安装。完整工作区的统一冻结校验因前端测试失败未到达，不将专项 18 文件冻结等同于整个工作区全量通过。

新增检查覆盖：旧设置默认关、往返与重建、保存失败不发布；队列／实际音源配对与旧回调拒绝；行边界、正负 offset、重复时间戳、空行和 Unicode 限长；没有应用 tick 时的按行刷新、暂停／seek／倍速／缓冲／结束、关闭／切歌／换源恢复、封面回写、自回调无循环、display 字段恢复、未知类型与大 Parcel 拒绝，以及主源码集访问链返回同一个平台 token。

Robolectric 使用测试专属 shadow 模拟系统服务将 MediaSession 写入交给 MediaController；它验证逻辑，不能替代真实 Binder、系统卡片显示或真人听音。本包未覆盖安装手机，未执行 B 模式的真实锁屏、蓝牙、车机或 A／B 同开验收，不复用 A1／A2 的设备结果宣称 B 已显示。CC 可以在接好开关后统一安装，保持数据；建议分别验收开启／关闭、暂停／进度拖动／切歌／换源、空歌词及封面更新，再由用户锁屏观察实际卡片和听音同步。
