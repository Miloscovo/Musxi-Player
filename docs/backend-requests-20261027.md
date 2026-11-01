# 后端需求单（2026-10-27，前端 -> Codex）

读者：负责 accounts / playback / platform-* 的后端开发。行号以 2026-10-27 的 main 工作区为准，开工前请自行复核。通用约定见 `AGENTS.md` 与 `docs/frontend-contract.md`：队列操作带 expectedRevision，账号代次 generation 隔离迟到结果，UI 不持有 Session/Cookie/签名 URL，新增依赖先核对许可，release 不含调试入口。

## 本轮前端已完成，无需后端重复做

以下改动目前在 main 工作区未提交（`git status` 可见），不要重做，也不要回退：

- 酷狗取流 hash/std_hash 修复
- 酷狗歌单最新在前（`KugouLibraryApi.tracksNewestFirst`，`platform-kugou/.../KugouLibraryApi.java:75`）
- 收藏自动同步
- 下拉刷新与"更新时间"
- 播放顺序三选一
- 状态栏图标
- 账号管理对话框
- 网易云网页登录接入正式页面
- 图标全出血
- 点歌后该行立即显示"正在准备…"（纯前端反馈，不缩短后端等待；真正提速靠第 1 项）
- 酷狗队列与页面同序（`KugouLibraryRepository.queue()` 整表读完后 `reverse()`）

---

## 1. 播放队列"先播后补"（最高优先级）

### 目标
歌单点歌后 1 秒内开播，其余曲目在后台补全，不打断播放、不改变当前曲和进度。

### 现状（已核实）
- 点歌入口：各平台 `Repository.queue(playlist, startSong, cb)` 整表读完才返回 `PlaybackQueue`。
  - 酷狗 `accounts/.../KugouLibraryRepository.kt:91`：每页 100，循环到 `nextOffset<0`，上限 20,000 首、60 秒预算；第 121 行 `songs.reverse()`。注意它走的是原始 `library.tracks`（API 顺序：最旧在前），不是 `tracksNewestFirst`，所以整表读完再反转。
  - QQ `QqLibraryRepository.kt:75`：同样整表分页，不反转。
  - 网易云 `NeteaseLibraryRepository.kt:67`：只取 `trackIds`，生成标题为"待加载歌曲"的占位 SongSummary，一次返回。
- 酷狗"最新在前"的页面分页由 `tracksNewestFirst` 从 API 末尾往前取窗口（每页额外调一次 `tracks(...,0,1)` 取 total）。
- 邮箱 `playback/.../QueueCommandMailbox.kt:8-16`：Action 只有 Replace/Clear/Jump/Edit/Insert。`submit` 只有一个 `pending`（第 22-25 行），后提交覆盖前者，被覆盖的 token 在 `take` 时返回 null，服务端回 `ERROR_INVALID_STATE`。连发会丢。
- `Insert`（`MusxiPlaybackService.kt:466` `insertQueue`）一次一首，位置只有 `currentMediaItemIndex+1` 或队尾（第 478 行）；用 `QueueSources.append`（`QueueSources.kt:71`）单条追加。
- revision：`publishQueue(force=true)` 使 `revision+1`（第 59-64 行）。Replace（经 `applyQueue` 末尾，第 396 行）、Edit（458）、Insert（485）、Clear（396）都会 +1。Jump 不改 revision（第 404-423 行注释）。
- `editable(revision)`（第 400 行）要求 revision 相等、队列非空、`mediaItemCount==queueSongs.size`、账号代次与当前媒体一致；用户拖动/删除携带手势开始时的 revision，过期被拒。
- 前端调用入口：`PlaybackConnection.enqueue(song, expectedRevision, playNext)`（`PlaybackConnection.kt:273`），经 `dispatch`（309 行起）走 `COMMAND_APPLY_QUEUE`。
- 队列上限 `QueueSources.MAX_QUEUE_SIZE = 20_000`（`QueueSources.kt:138`）。

### 建议接口
1. `QueueCommandMailbox.Action.InsertBatch(expectedRevision: Long, songs: List<SongSummary>, at: InsertAt)`，`enum InsertAt { HEAD, TAIL }`。
2. `PlaybackConnection.enqueueBatch(songs, expectedRevision, at, callback)`。
3. `QueueSources.appendAll(mediaIds, generation): List<String>`（一次持锁、一次校验代次与数量上限）。
4. Repository 分段加载（三平台各自实现，签名可调整，但需覆盖以下语义）：
   - `queueHead(playlist, startSong, cb)`：返回首批 `PlaybackQueue`，必须包含所点歌曲；以及 `QueueRemainder` 句柄，用于取回剩余部分。
   - 剩余部分按"相对首批的位置"分成 `before`（排在首批之前）与 `after`（排在首批之后）两段，各自分页返回，前端串行提交：`before` 用 `HEAD`，`after` 用 `TAIL`。
   - 首批定义：包含所点歌曲的那一页，或列表页面已显示的曲目（前端已持有，可作为入参 `knownSongs` 传入，后端据此减少请求）。
5. 各平台的方向：
   - 酷狗（最新在前）：API 末尾为最新。首批取包含所点歌曲的窗口；比它更新的在 HEAD 侧，更旧的在 TAIL 侧，两侧都可能非空。务必沿用 `tracksNewestFirst` 的方向语义，补全结果拼接后须与现 `queue()` 的"整表读取再 reverse"逐项一致。
   - QQ：API 顺序即显示顺序，同样首批前后两侧都可能有歌。
   - 网易云：ID 已全量拿到，建议同样提供首批（所点歌附近）+ 余下占位项，沿用现有占位与按需补元数据机制。

### 语义与约束
- InsertBatch 原子：校验全部通过才修改；一次 `player.addMediaItems`，一次 `publishQueue(force=true)`，revision 只 +1。
- HEAD 插入：`player.addMediaItems(0, items)` 后当前索引随之偏移，当前曲、播放位置、`playWhenReady`、已选换源 ticket、重试预算全部保留（参照 `editQueue` 中"不动当前槽位"的做法）。检查 `queueIndex` 对外值、checkpoint（`MusxiPlaybackService.kt:106`）保存的 index 是否随之正确。
- 校验沿用 `insertQueue`（第 466-475 行）的逐条规则；同平台、同账号代次、`acceptsMediaId`、总数不超 `MAX_QUEUE_SIZE`；超限整批拒绝。
- 去重：同一首歌在歌单中可能出现多次，不要按 key 去重，保持与整表读取一致。
- 邮箱单 pending 的问题：补全是串行提交，前端等回调再发下一批；但用户的 Jump/Edit 可能与补全批互相覆盖。请评估后选择其一并在文档说明：(a) 邮箱改成小队列；(b) 保持单 pending，补全被覆盖时回调失败，前端重试同一批（需保证重试幂等，即批次带 `batchId`，已应用的批次重复提交返回成功而不重复插入）。推荐 (b) 加 `batchId`。
- 失效规则：补全携带"队列身份"（首批 Replace 后的 revision 基线 + `batchId` 序列）。以下情况必须拒绝并让前端丢弃剩余补全：账号代次变化；revision 不符（用户已拖动/删除/Replace 其他歌单）；队列平台不同；队列已清空。revision 不符时补全不能自行重试，由前端决定是否重新读取 revision 后继续。
- 注意补全期间用户拖动/删除会使 revision +1，导致后续补全批全部失败。需要一个不依赖手势 revision 的补全身份（如 Replace 时分配的 `queueEpoch`，用户编辑不改变它，换歌单/清空/换号才改变），InsertBatch 以 `queueEpoch` 校验，同时对 HEAD 插入处理好用户已移动/删除过的位置（HEAD 永远是队首，TAIL 永远是队尾，因此仍有定义）。但这会让补全结果与整表顺序在用户编辑后不再一致，这是可接受的；请在文档说明。
- 旧补全取消：`cancelQueueLoading()`（`KugouLibraryRepository.kt:30`）已有类似机制，三平台统一；换歌单、`stop()`、账号变化都要使在途分页立即作废，迟到结果不得回调。
- 不持久化签名 URL；journal（`FilePlaybackJournalStore`）在每批后 checkpoint 一次即可，避免 20,000 首时每批全量写盘过于频繁，可合并节流。
- 请求预算：首批请求不应受整表 60 秒预算影响；补全阶段每页仍受 RequestMetrics 与限频约束。

### 验收标准
- 755 首酷狗歌单点任意歌，1 秒内开始播放（真机计时，注明网络状况）。QQ 大歌单同理。
- 补全完成后队列顺序与整表读取结果逐项一致（单测用夹具：旧 `queue()` 结果 vs 首批 + before(HEAD) + after(TAIL)），当前曲 mediaId、queueIndex 对应的曲目、播放位置不变（位置允许自然前进，不得回 0）。
- 补全期间用户删除/拖动：该操作要么成功，要么被明确拒绝；队列无错位、无重复、无丢失，`queueSongs.size == mediaItemCount`。
- 补全期间切换歌单：旧补全全部丢弃，新队列不混入旧歌。
- 账号代次变化：InsertBatch 返回失败，队列行为与现有代次失效一致。
- 超过 `MAX_QUEUE_SIZE`、非法 mediaId、跨平台歌曲：整批拒绝，队列不变。
- 单测覆盖：`QueueCommandMailbox`、`InsertBatch` 的 HEAD/TAIL 索引偏移、`QueueSources.appendAll`、三平台分段与整表一致、取消/代次。运行 `build.ps1 -Test -Lint`。

---

## 2. 音质选择

契约已写好：`docs/frontend-backend-contract-quality.md`。以该文件为准，本文只概括：

- `qualities(mediaKey) -> QualityOptions(options: List<QualityOption(id, name)>, current)`；名称为后端映射好的中文，前端原样显示；平台未确认的档位不列出，无法确认返回空列表。
- `setQuality(id)`：当前曲用新档位重新取流，从当前位置续播；失败保持原档位并返回失败类别；偏好按平台持久化，歌曲不支持时降级到不高于偏好的最高可用档。
- `PlaybackState.qualityName: String?` 反映实际正在播放的档位。
- 约束：不改 ticket、账号代次、换源与有限重试规则；不重建队列；换源到其他平台按该平台偏好；试听/付费档位拿不到完整音频就不列出。
- 现状：三平台均固定标准档（酷狗 `quality=128`，网易云 `level=standard`，QQ `M500…mp3`）。
- 前端状态：全屏播放器已放占位按钮，点按只提示"即将支持"；后端交付前前端不显示档位列表。

---

## 3. 音频输出设备（播放页底部"音箱"按钮）

### 目标
前端能显示当前输出（手机扬声器 / 有线耳机 / 蓝牙设备含名称 / 其他如 USB、投屏），并在可用设备之间切换。按设备记住音量或音质本轮不做。

### 现状（已核实）
- 只有 `PlaybackSuppression { NONE, AUDIO_FOCUS, OUTPUT, OTHER }`（`PlaybackConnection.kt:22`），由 `player.playbackSuppressionReason` 映射（约 400-405 行），`OUTPUT` 对应 `UNSUITABLE_AUDIO_OUTPUT`。
- 服务端 `MusxiPlaybackService.kt:168-169`：`setAudioAttributes(DEFAULT, true)` 与 `setHandleAudioBecomingNoisy(true)`；第 205-210 行在 `AUDIO_BECOMING_NOISY` 导致暂停时清除 `playbackWanted` 并取消待处理选源。这是耳机拔出暂停的既有行为。
- `PlaybackState`（`PlaybackConnection.kt:53` 起）没有输出设备字段；没有设备列表 API。
- Manifest（`app/src/main/AndroidManifest.xml`）当前只有 INTERNET、FOREGROUND_SERVICE、FOREGROUND_SERVICE_MEDIA_PLAYBACK；没有蓝牙权限。minSdk 26，targetSdk 36。

### 建议接口
- `enum OutputType { SPEAKER, WIRED, BLUETOOTH, USB, REMOTE, OTHER, UNKNOWN }`
- `data class OutputDevice(val id: String, val type: OutputType, val name: String?, val selected: Boolean)`，`id` 为本进程内稳定的不透明字符串（不暴露 MAC 地址）。
- `PlaybackState.outputDevice: OutputDevice?`（当前路由）。
- `PlaybackConnection.availableOutputs(): List<OutputDevice>`，另提供 Flow/状态以便前端在面板打开期间刷新。
- `PlaybackConnection.selectOutput(id, callback: (Result<Unit>) -> Unit)`。

### 实现提示与降级
- 检测：`AudioManager.getDevices(GET_DEVICES_OUTPUTS)` + `registerAudioDeviceCallback`；当前实际路由在 API 33+ 可用 `AudioManager.getAudioDevicesForAttributes(AudioAttributes)` 的首项，低版本按优先级推断（蓝牙 A2DP/LE > 有线/USB > 扬声器）并在文档说明是推断。
- 设备名：`AudioDeviceInfo.productName`。蓝牙设备名在 Android 12+ 可能需要 `BLUETOOTH_CONNECT` 才能拿到真实名称；不要为此新增危险权限为默认方案。无权限时降级为类型 + 通用名（如"蓝牙设备"），请在文档写明并让 `name` 可为 null。若产品确需真实名称，单独说明理由，由前端先做权限说明再决定。
- 切换：优先 Android 14+（API 34）的 `MediaRouter2` / 系统输出切换器；API 33 及以下可考虑 `AudioManager.setCommunicationDevice` 仅限通话类，不适用于媒体，因此不建议使用。无法直接切换时提供降级：返回"需要系统切换器"类别并提供打开系统输出切换器的意图（如 `Settings.ACTION_BLUETOOTH_SETTINGS` / Android 11+ 的 `android.settings.panel.action.MEDIA_OUTPUT` 面板意图），由前端启动。请在文档写明各版本走哪条路径，以及哪些设备类型可直接切换。
- 不得读取其他应用信息；不获取网络、位置权限；不新增危险权限。
- 保留 `setHandleAudioBecomingNoisy(true)` 与既有暂停语义；设备监听只读取状态，不得触发 play/pause。切换输出不得清除 `playbackWanted`，且不得与 NOISY 暂停混淆（切换成功时不应当作"拔出"而暂停；若系统在切换时发出 NOISY，需验证并说明）。
- 监听器生命周期放服务内，随服务销毁反注册；回调去抖（合并短时间内的连续变化），状态变化才发布。
- release 包不含调试入口；若需要手动验证入口，只放 debug 源集并遵守现有固定步骤约定。

### 验收标准
- 插拔有线耳机、连接/断开蓝牙，前端在 1 秒内收到正确的类型与名称（无权限时名称为 null 或通用名，类型必须正确）。
- 耳机拔出仍按既有行为暂停；设备变化本身不引起播放状态变化。
- `selectOutput` 成功时播放不中断（位置连续）；失败时返回明确失败类别，原路由不变；降级路径可打开系统切换器。
- 单测覆盖类型映射、去抖、选择的纯逻辑（把 AudioManager 访问抽成可替换接口）；真机验证记录设备型号与 Android 版本。
- 清单 diff 无新增危险权限（或有明确说明）；release 构建中无调试入口。

---

## 4. 来自上游 PC 同步（bcee404，2026-10-27 补充）

上游 Miloscovo/Musxi-Player `e72fba9..bcee404` 中与安卓后端相关的两项。PC 本地 main 已快进到 bcee404，可直接对照 `C:\text\Musxi-Player\Musxi-Player`。

### 4.1 酷狗"收藏的他人歌单"曲目读取（aed9e90，`services/bridge.cjs` L105、L254-264）
- 上游：`playlist()` 读 `list_create_gid` 作为 `sourceGlobalId`；对**非自建且非默认收藏**（`is_edit!=1 && is_def!=2`）的歌单，曲目改用 `playlist_track_all`（vendor `KuGouMusicApi/module/playlist_track_all.js`：GET `/pubsongs/v2/get_other_list_file_nofilt`，`global_collection_id=<gid>`、`begin_idx`、`pagesize`、`mode=1`、`type=1`）读原歌单；自建、默认收藏、无 gid 的旧数据仍走 `get_list_all_file`。页数不足时报"不完整"，不覆盖已有缓存（上游有 133 首两页的测试）。
- 安卓现状：`KugouLibraryApi.parsePlaylists` 不读 `list_create_gid`；`tracks()`/`tracksNewestFirst()` 对所有歌单都用 `/v4/get_list_all_file` + `listid`。
- 用户账号里目前 3 个歌单（默认收藏、我喜欢、TOP500）均能读出，暂未遇到收藏他人歌单的样本；请补 `sourceGlobalId` 路径与夹具测试，并确认 `tracksNewestFirst` 的方向语义对原歌单是否适用（原歌单顺序可能不是"最旧在前"，需真机核对后再决定是否反转）。

### 4.2 登录校验暂时失败不应显示为"已退出"（aed9e90，`services/multi-platform.cjs`）
- 上游：启动校验时网络等暂时失败返回 `verificationFailed`，账号保留并带"登录状态验证失败，请稍后重试"，UI 只在确认失效时显示未登录。
- 安卓请核对 `AccountRepository.loadRestore/fail`：网络/超时是否进入 `RETRYABLE_ERROR` 并保留会话与缓存，只有平台明确判定过期才 `REAUTH_REQUIRED`；补测试。

### 4.3 无需同步
- WASAPI 排空宽限、FFmpeg FLAC 总采样数判定是 PC 自有解码链路问题；Media3 自行判定 `STATE_ENDED`。可选真机回归：酷狗 FLAC（带 footer）能否正常播完并从头重播。
