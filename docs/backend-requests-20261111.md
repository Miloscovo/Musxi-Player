# 后端需求单 · 2026-11-11：主题色、逐字时钟与本地音频补缺（前端 → Codex）

> **状态：当前需求单（2026-11-11）。** 本文列出 CC 正式前端接线时发现的后端缺口。完成后由 Codex 在末尾“交付说明”填写接口与验证边界；交付证据另行按日期记录。当前排期仍以 [当前开发接续](next-session-handoff.md) 为准。

读者：负责 core / accounts / playback / app 后端层的 Codex。约定同前几批：不改 `app/src/main/kotlin/tech/musxi/player/ui/`（CC 负责）；接口或语义有变化写进本文末尾的交付说明。行号以 2026-11-11 工作区为准，开工前复核。各项互相独立，可按编号顺序分别交付。

## 1 · 主题色持久化（P0，小改）

用户 11-11 确认：“我的 → 外观 → 主题色”要能选并记住。界面现有雾蓝、松石青、暖琥珀三种（`ui/theme/Color.kt` 的 `Accent`），CC 还会再加两三种；但 `MainActivity` 启动时写死 `Accent.MIST`，设置模型里没有这一项，选了也无处保存。

要做：

1. `AppSettings` 加 `accent: String`，默认 `"MIST"`。存枚举**名称字符串**，不在 core 引用 UI 的 `Accent` 类型；core 不校验取值范围，读到未知名称时原样保留，由界面回退到雾蓝。这样以后增减颜色不用改后端。
2. `AppSettingsPersistenceFormat` 加键（如 `settings.accent`），并加入 `keys`、`encode`、`decode`；旧数据没有这个键时取默认值，不视为损坏。
3. `AppSettingsRepository.setAccent(value: String): AppSettings`，与现有 setter 一致（IO 线程保存，`settings` 流更新）。名称约束：非空、不超过 32 个字符、仅 `[A-Z0-9_]`；不合规时抛 `IllegalArgumentException`，与现有参数校验风格一致。
4. 主题色与壁纸、玻璃、封面取色互相独立（壁纸合同已写明），后端不需要联动。

验收：单测覆盖默认值、往返保存、旧数据缺键、未知名称原样保留、非法名称拒绝。

## 2 · 播放进度的取样时刻（P1，逐字歌词需要）

用户 11-11 确认逐字歌词做“柔和扫光”：当前行从左到右平滑填色。三平台字词数据已就绪（`LyricLine.words`、`LyricsWordTimeline.sample`），但界面拿到的 `PlaybackState.positionMs` 约每 500ms 才更新一次（`PlaybackConnection.kt:644` 的 `progressTick`），而且状态里没有“这个位置是在什么时刻读的”。没有取样时刻，前端只能以“收到状态的那一帧”为起点往前推算，误差最多约半秒，扫光会忽快忽慢，暂停、缓冲、拖动时还会回跳。

要做：

1. `PlaybackState` 增加 `positionSampledAtElapsedMs: Long`，值为读取 `player.currentPosition` 同一时刻的 `SystemClock.elapsedRealtime()`（`PlaybackConnection.update`，约第 587 行）。每次 `positionMs` 变化都同步更新。
2. 增加 `playbackSpeed: Float`，取 `player.playbackParameters.speed`，默认 1f。本地音频后续会有倍速，前端推算位置时要乘上它。
3. 说明清楚“可以向前推算”的条件，例如 `playing && !buffering`，前端只在这个条件成立时推算；其余情况直接用 `positionMs`。如果 Media3 有更准确的判断条件，以你的为准，写进交付说明。
4. 不要求提高 500ms 的刷新频率，不新增逐帧回调，避免增加耗电和重组。

前端做法（供核对语义）：`估计位置 = positionMs + (帧时钟 - positionSampledAtElapsedMs) × playbackSpeed`，再减去 `LyricsState.offsetMs` 后交给 `LyricsWordTimeline.sample`；每次收到新状态都重新对齐，拖动和切歌时直接跳到新位置。

验收：单测证明取样时刻与位置成对更新、暂停时不再推进、倍速字段正确。逐字听音对齐由前端接好后实机验收。

## 3 · 本地音频接线时发现的缺口（P1）

前端按 [本地音频合同](frontend-backend-contract-local-audio.md) 做正式页面时，梳理出下列问题。前端会先按现状绕开，但这些问题会让界面出错或文案不准确，建议后端补齐。

1. **整理用的版本号被播放进度频繁改动。** `saveProgress` 每次都会让 `revision` 加一（`SqliteLocalAudioStore.kt` 约 596 行）；播放本地音频时，播放服务每 15 秒存一次进度。于是播放中做批量整理、规则排序、拖动排序、来源检查、检索续页，很容易因为版本号过期被拒。建议把“整理版本号”（文件夹、归属、顺序、名称、来源）和“进度版本号”分开，带 `expectedRevision` 的命令只核对前者。如果判断不宜拆分，请说明理由，前端改为在用户确认时即时读取版本号并重试一次。
2. **`reorder` 版本过期时返回的错误类型不一致。** 它返回 `INVALID_ORDER`（`AppLocalAudioRepository.kt:248`），批量整理和 `sortFolder` 返回 `STALE_STATE`。建议统一为 `STALE_STATE`；`INVALID_ORDER` 只用于成员集合不对。
3. **关联目录时拿不到目录名。** `linkDirectory(name = ...)` 的名称必填，但后端不返回所选目录的显示名，前端也不应该自己解析 URI（合同要求 URI 只在后端处理）。建议 `name` 改为可空，为空时后端从 DocumentsContract 读取目录显示名；或者另提供一个只返回显示名的查询函数。
4. **目录刷新的错误字段混了两种含义。** 列举成功时，`link.error` 被设为第一个文件的导入错误（Sqlite 约 941–945 行）；列举失败时也写这个字段。前端分不清是“目录不可用，需要重新授权”还是“有个别文件没导入”。建议拆成 `link.error`（目录本身）和 `lastRefreshFailures: Int`（逐项失败数）。
5. **导入和来源检查的结果没有清除接口，也没有完成标记。** `imports` 和 `sourceChecks` 会一直保留上一轮的结果；`imports` 也没有 `error` 和 `completed` 字段，整批失败只出现在回调里。界面重进时会看到旧结果。建议加一个递增的 `runId` 和 `completed` 字段，另提供 `clearImportResult()` 和 `clearSourceCheckResult()`。
6. **重新关联需要单选的文件选择器。** `LocalAudioPickers` 只有固定多选的 `files()`，`relink` 只需要一个文件。建议加 `files(allowMultiple: Boolean = true, ...)`。
7. **复制模式下长时间导入是否会丢失临时读权限，需要实机确认。** 复制模式依赖选择器结果里的临时读权限。debug Activity 会刻意保活直到导入用完结果，正式版由 MainActivity 接收结果。用户在长时间复制过程中离开或关闭 Activity 时，后续文件是否会出现 READ_FAILED 或 PERMISSION_REQUIRED？这一点源码里无法判断，请在下一次设备专项中顺带验证，并写明结论。

## 4 · 不需要后端的（备忘，CC 负责）

- 歌词字号、“颜色跟随封面”、“显示翻译”目前只保存了设置，界面没有读取。这是前端漏接，CC 今天修。
- 应用内更新的后端已完整，CC 改为“检查到新版后弹出更新面板”的交互，不需要新接口。
- 默认音质设置后端已在使用（`AccountRepository.defaultAudioQuality`），只是“我的”页的选择入口还是“即将推出”，CC 补上。

## 交付说明（Codex 填写）

交付日期：2026-11-11。第 1、2 项已实现；第 3 项本轮完成 3.2 和 3.6，其余按下述顺序安排。本轮没有修改 `app/src/main/kotlin/tech/musxi/player/ui/`，没有提交、推送或覆盖安装手机主包。前端仍由 CC 接线。

### 主题色接口与保存语义

- `AppSettings.accent: String = "MIST"`；core 的 `AppSettings.DEFAULT_ACCENT` 同为 `"MIST"`。`AppSettingsPersistenceFormat.ACCENT = "settings.accent"` 已加入完整快照的 `keys/encode/decode`。
- core 与 accounts 的 `AppSettingsRepository` 均提供 `setAccent(value: String): AppSettings`；应用调用入口为 `app.settingsRepository.setAccent(value)`，读取仍用 `app.settings`。
- setter 只校验名称形状 `[A-Z0-9_]{1,32}`，不校验是否属于当前 UI 调色板。非法值抛 `IllegalArgumentException`，既不保存也不发布状态。旧数据缺键回默认；未知名称读取及回写均原样保留，不 trim、不改大小写，由 UI 回退显示雾蓝。
- 保存沿用现有同步事务：**调用方线程执行 `SharedPreferences.commit()`，成功后才更新 `settings` 流**；失败抛异常，流不提前变化。调用方应使用 `withContext(Dispatchers.IO) { app.settingsRepository.setAccent(value) }` 并处理失败。此接口没有隐式切换到 IO，也没有异步排队保存。
- 不联动主题明暗、壁纸、玻璃、歌词配色或封面取色；core 不依赖 UI 的 `Accent`。

### 播放取样接口与逐字时钟

`PlaybackState` 在既有字段末尾追加：

```kotlin
val positionSampledAtElapsedMs: Long = 0
val playbackSpeed: Float = 1f
```

另有只读计算属性 `canExtrapolatePosition: Boolean`。`PlaybackConnection.update` 在主线程读取 `player.currentPosition` 后立即读取 `SystemClock.elapsedRealtime()`，再读取 `player.playbackParameters.speed`；位置、时刻和倍速在同一个状态中发布。每次 Media3 事件和原有约 500ms tick 都重新取样；seek/切歌会重置样本，断线为默认时刻 0、speed 1f、不可推算。没有提高刷新频率、增加逐帧后端回调或新增倍速设置命令。

`canExtrapolatePosition=true` 必须同时满足：连接正常、`playing && playWhenReady && !buffering && !ended`、`suppression == NONE`、`error == null && localError == null`，且倍速有限并大于零。`playing` 来自 Media3 的 `isPlaying`，因此也受播放器就绪和播放抑制状态约束。前端直接使用该属性，暂停、缓冲、抑制、错误、结束或断线时只使用当前 `positionMs`。

可推算时：

```text
估计位置 = positionMs + max(0, SystemClock.elapsedRealtime() - positionSampledAtElapsedMs) × playbackSpeed
歌词位置 = 估计位置 - LyricsState.offsetMs
```

已知 duration 时可限制估计位置上界；每个新状态重新对齐，再将歌词位置交给 `LyricsWordTimeline.sample`。**不能把 Compose `withFrameNanos` 或 Choreographer 的帧时间直接除以百万用于相减**，其基准在熄屏后可能与 elapsedRealtime 不同；可由帧回调触发重绘，但每帧使用 `SystemClock.elapsedRealtime()` 计算。此取样接口同时适用于平台与本地播放，倍速取播放器真实值，不伪造为固定 1f。

### 本地音频七项状态与排期

| 条目 | 本轮结果／后续顺序 | 接口或实施边界 |
| --- | --- | --- |
| 3.1 整理／进度版本号 | 下一批第 1 项，完整拆分 | 本轮仍使用原单 revision，理由及过渡接法见下文 |
| 3.2 reorder 错误类型 | 已完成 | 过期版本返回 `STALE_STATE`；成员集合不对仍返回 `INVALID_ORDER`，失败不写入顺序 |
| 3.3 目录显示名 | 下一批，与 3.4 同批 | 在 Documents 层 IO 查询目录 document 显示名；关联接口支持省略名称，显式名称优先；不让 UI 解析 URI |
| 3.4 目录错误／逐项失败 | 下一批，与 3.3 同批 | 区分目录本身错误和 `lastRefreshFailures`，连同持久化兼容迁移及整批／逐项错误测试交付 |
| 3.5 结果生命周期 | 随后独立一批 | imports/sourceChecks 增加 runId、完成／整批错误与清除接口，需同时处理取消和迟到结果，不能只清空 Flow 后被旧任务写回 |
| 3.6 重新关联单选选择器 | 已完成 | `LocalAudioPickers.files(showAllFileTypes: Boolean = false, allowMultiple: Boolean = true)`；重新关联调用 `files(allowMultiple = false)` |
| 3.7 COPY 临时读权限 | 下一次正式页面设备专项 | 在前端接好后覆盖离开、关闭／重建 MainActivity、大批长复制及不同提供方；本轮没有新增此项实机结论 |

3.1 **建议拆分，但本轮不做仅取消 `saveProgress` 的 revision 递增这一局部修改**。当前同一个 revision 还保护搜索分页、恢复预览与来源检查；`LocalAudioSearch` 支持进度筛选、播放进度及最近播放时间排序，写入进度会改变查询成员、顺序和页边界。若简单不递增，旧分页会被错误视为有效，导致重复或漏项。完整方案需要独立的整理版本和进度版本：不依赖进度的查询只核对整理版本，依赖进度的查询必须携带两种版本，配套 SQLite 兼容迁移、公开 State/Page/Query token、过期检查和分页回归。此改动跨存储、查询与公开接口，安排为下一批首项，不在这次两个优先接口的交付中只拆一半。

过渡接法：用户确认整理动作时读取最新 revision，并重新核对稳定 ID／成员集合；仅遇 `STALE_STATE` 时，在确认目标和成员仍符合本次操作后最多重试一次，不循环重试或覆盖并发整理。拖动期间发生外部变化仍取消拖动；分页过期重查第一页，不能给旧续页换一个新 token 继续。当前每约 15 秒的进度检查点仍会使单 revision 变化，这个已知问题本轮未消除。

3.6 保留 `showAllFileTypes` 为第一个位置参数，避免改变现有 `files(true)` 的含义；它仍表示 `*/*`，默认 `files()` 仍为多选 `audio/*`。单选只改变 `EXTRA_ALLOW_MULTIPLE`，继续只请求读取／可持久权限，不增加写入授权。

### 验证范围与证据

定向检查覆盖 `AppSettingsPersistenceFormatTest`、`AppSettingsRepositoryTest`、`AppSettingsAccentTest`、`PlaybackPositionSampleTest`、`LocalOrganizationRepositoryTest` 和 `AndroidLocalAudioDocumentsTest`。主题色覆盖默认、完整往返、旧数据缺键、未知名、非法名称和合法边界，以及 Robolectric 下的 SharedPreferences 写盘／重建；播放覆盖位置与邻近 elapsedRealtime 成对、seek 倒退／切歌替换、真实 speed 读取、暂停不推算与缓冲／错误／断线等门禁；本地覆盖 reorder 两种错误及不改成员、单选／默认多选和原位置参数兼容。

上述定向测试共 **43 项全部通过**，无失败／错误／跳过；结果在 `.local/artifacts/backend-requests-20261111/targeted-summary.json` 与同目录日志／XML。交付前执行统一入口 `pwsh -NoProfile -File scripts/verify.ps1`，最终结果、对应源码清单、测试数和 Lint 范围统一收录到 `.local/artifacts/backend-requests-20261111/final-summary.json` 指向的本轮证据，不使用此前设备轮的数字代替。若 CC 并行修改导致冻结校验失败，以该次真实结果记录。

JVM/Robolectric 检查和 debug／设备测试包编译不证明真机逐字扫光、听音对齐、主题色正式页面重启效果或 3.7 长复制权限已通过；这些在 CC 接线后进行专项。此前手机旧主包的结果仍只属于[原设备轮](backend-local-device-20261111.md)，本次没有重复操作账号、平台数据或手机主包。
