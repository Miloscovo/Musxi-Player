# 后端需求单（2026-11-01，前端 -> Codex）

> **状态：历史需求单（2026-11-01，第一批）。** 本文保留当次需求与开工前提，不构成当前排期或当前工作区指令；完成情况查对应交付、验收与 [当前开发接续](next-session-handoff.md)。

读者：负责 accounts / core / playback / platform-* 的后端开发。行号以 2026-11-01 的 main 工作区为准，开工前请自行复核。通用约定同 [10-27 需求单](backend-requests-20261027.md) 开头：revision / 账号代次、UI 不持有会话与签名 URL、新增依赖核对许可、release 无调试入口。

前端本轮正在并行改：搜索页、播放页浮动菜单与"更多"菜单、收藏红心、debug 玻璃预览。**请不要改 `app/src/main/kotlin/tech/musxi/player/ui/` 下的文件**；需要前端配合的接口变化写进本文件末尾的"交付说明"即可。

## 0. 上一轮遗留（[10-27 需求单 §4](backend-requests-20261027.md)）

- **4.1 酷狗"收藏的他人歌单"**：仍未实现（`list_create_gid` / `get_other_list_file_nofilt` 无引用）。
- **4.2 启动校验暂时失败不显示为"已退出"**：请确认是否已核对，若已符合请在交付说明里写一句。

## 1. 收藏写入后的核实太慢（高优先级）

**现状**：`FavoritesBrowser.setLiked` 写入成功后 `startWriteVerification` 重新拉取**整个**收藏集合核实（`core/.../FavoritesBrowser.kt` 约 157-200 行）。用户酷狗"我喜欢"758 首，一次核实要好几秒；期间同平台再点任何红心都返回 `busy`。写入失败或核实不符会把 `synced` 置 false，此后必须整表重读。

**前端已做（不依赖后端）**：红心改为乐观显示，意图排队；`busy / not_synced` 时排队等待、失败回滚并提示；`synced=false` 后有界自动补同步。

**希望后端做**：
1. 核实改为**只查这一首**（或只查集合的最新一页/首页），确认目标状态即可，不重读全表。各平台若有"单曲是否已收藏"的查询接口优先用；没有则说明原因与可替代方案。
2. 核实成功后在本地 `keys` 上增删这一首，`synced` 保持 true；核实不符再走整表刷新。
3. 允许不同歌曲的写入**串行排队**，不要直接返回 `busy`（或者提供 `enqueueLiked(song, liked)`，由后端按顺序执行）。
4. 验收：758 首账号连续切换三首歌各点一次红心，全部在 2 秒内确认；网络断开时失败并保留原状态；单测覆盖排队、单曲核实成功/不符/失败。

## 2. "更多"菜单需要的后端能力

前端"更多"菜单本轮先上：定时停止（前端进程内计时）、搜索该歌手/专辑、复制歌名、分享（纯文字）、歌曲信息。以下需要后端：

1. **加入歌单**：列出当前账号可编辑的歌单（含"我喜欢"），把当前歌曲加入其中；三平台各自实现，失败分类与收藏一致。PC 端已有酷狗 `playlist_tracks_add` 可参考（`services/bridge.cjs`）。
2. **手动换源**：现在只有原来源 PERMISSION/UNAVAILABLE 时才自动搜索候选。希望提供"当前歌曲主动打开候选列表"的入口（复用 `SourceSelection` 与 B4 匹配规则），用于用户嫌音质差或想换平台时手动切换；不改变原队列身份。
3. **定时停止迁入播放服务（可选）**：前端现在用进程内协程计时。若要在进程被回收后仍生效，或支持"播完当前这首再停"，需要服务端实现 `setSleepTimer(deadlineElapsedMs | AFTER_CURRENT)` 并通过 `PlaybackState` 暴露剩余时间。
4. **歌手 / 专辑页（后续）**：目前"搜索该歌手"只是跳到搜索页填关键词。若要做真正的歌手页、专辑页，需要三平台的歌手详情/热门歌曲、专辑曲目接口。先评估可行性即可，不必本轮实现。

## 3. 换源面板相关

前端会把"暂无音源 / 正在其他平台找 / 候选选择"这一套弹出页重做（与播放顺序、音质同一类改造）。请确认现有 `SourceSelectionState` 是否足够，或是否需要补：
- 每个候选的**音质档位**（标准/高品/无损）与是否为试听，便于用户手选时比较；
- 搜索进度（已完成的平台 / 仍在等待的平台），现在只有 `partial` 布尔值；
- 失败原因按平台区分（无版权 / 需要会员 / 网络），现在前端只能笼统显示"没找到能播的替代音源"。

## 4. 电源与后台（[10-31 后续](backend-followup-20261031.md) 已列，重申优先级）

播放器未设置 `setWakeMode`、Manifest 无 `WAKE_LOCK`。熄屏长时间播放、后台换歌取流可能被深睡眠打断。请评估 `setWakeMode(C.WAKE_MODE_NETWORK)` 并做深睡眠/长后台验收。

## 4b. 歌单类型字段（曲库页需要）

曲库页要把"我喜欢"单独放大显示，目前前端只能按歌单名猜（"我喜欢""我喜欢的音乐""xxx喜欢的音乐"）。请给 `PlaylistSummary` 补 `kind`：`LIKED`（平台的"我喜欢/红心"歌单）、`CREATED`（本人创建、可编辑）、`COLLECTED`（收藏的他人歌单）、`OTHER`。各平台依据：酷狗 `is_def==2` / `is_edit`（PC `services/bridge.cjs` L105 已在用），网易云 `specialType==5` 或 `userId==本人` + 创建/收藏，QQ 以接口字段为准。缓存格式若需变化请注明迁移方式。前端拿到后会删掉按名称猜测的临时规则。

## 5. 工具链（暂不做，仅记录）

真正的"液态玻璃"折射效果需要 Kyant Backdrop，它要求 Kotlin 2.3+ / Compose 1.11+。本项目目前 Kotlin 2.2.21。产品负责人看完 debug 玻璃预览后再决定；若决定做，需要统一升级 Kotlin / Compose 编译器插件并回归全部模块，届时另开需求。

## 交付说明（Codex 填写）

2026-11-01：已在当前主工作区增量实现，未修改 `app/src/main/kotlin/tech/musxi/player/ui/`。保留原有未提交改动，未提交或推送。构建和验证结果见 [本轮交付记录](backend-delivery-20261101.md)。后续按用户授权完成 [真机增补验收](backend-live-acceptance-20261101.md)：真实酷狗三首排队确认用时 441/860/1394 ms，收藏集合和顺序已恢复；熄屏跨曲、强制深度 Doze、暂停/恢复电源锁检查通过。普通歌单未执行真实写入。

### 收藏

- 现有 `app.favorites.setLiked(song, liked)` 现在会串行排队；新增 `enqueueLiked(song, liked, result: (Result<Unit>) -> Unit = {})`，建议前端使用回调确认每个意图。`FavoritesState.pending` 表示执行中的歌曲，`queuedKeys` 表示等待项，同平台最多 128 个等待意图。不同平台独立维护状态，底层普通网络请求仍共享既有请求通道。
- 确认后只增删这一首的本地 key，保持 `synced=true`。不确定的核实会整表校准；只结束当前意图，保留其他等待意图至校准成功后继续。离线导致校准也失败时，等待项收到失败回调，原有 keys 保留；不会自动重放已发出的写请求。账号变化拒绝整个旧队列和迟到回调。
- 酷狗：首次完整同步同时保存平台指定收藏歌单及 fileId/位置；随后取消收藏无需先重读 758 首。写后仅查询变更所在的至多 100 首窗口，并核对预期顺序、总数及服务器写入应答；新增查询尾部窗口。若顺序或总数不同，回退完整同步。缓存绑定实际 Session，完整快照最长复用 5 分钟。
- 酷狗固定协议没有已核对的单曲收藏查询或全表版本号。因此局部确认不等于证明窗口外集合完全一致；其他客户端的并发修改仍需用户刷新同步。这是本轮替代方案的边界。
- 网易云：固定协议只有一次返回纯 ID 的 `likelist` 请求，使用它核实，避免歌曲详情分页。QQ：先查收藏首页 100 首，找到目标可确认存在，完整单页可确认不存在；多页中未找到不能判为取消成功，回退完整同步。**QQ 大列表的取消收藏仍可能较慢**，未宣称三平台都有单曲查询端点。
- 收藏成功只将曲库缓存标为 stale，下一次常规打开页面刷新；不立即发起歌单封面和整页请求，避免抢占后续收藏写入。前端保留乐观红心，失败依对应回调回滚；对 `not_synced` 先同步，对 `account_changed/account_unverified` 终止旧账号意图。

### 加入歌单

- `app.playlists.listWritable(platform, result: (Result<List<EditablePlaylist>>) -> Unit)`：仅返回当前账号可写歌单，包含我喜欢。
- `EditablePlaylist(playlist: PlaylistSummary, accountGeneration: Long, favorite: Boolean)` 仅含公开元数据和账号代次，无会话或写权限凭据。
- `app.playlists.addToPlaylist(song, target, result: (Result<Unit>) -> Unit)`：限定同平台、原账号代次；普通歌单写前再次向服务器核对本人可写目标，成功后失效曲库缓存。我喜欢走同一收藏队列，调用前需要收藏已同步；不会并行另写一条收藏通路。
- QQ 使用 `GetPlaylistByUin` + `PlaylistDetailWrite/AddSonglist`；网易云使用歌单 creator 校验 + 固定 `playlist/manipulate/tracks`；酷狗使用 `is_edit` 校验 + `playlist_tracks_add` 对应的结构化请求。普通歌单成功以平台明确应答为准，未加入写后全表读取。收藏他人歌单不可写。

### 主动换源与面板数据

- `app.playback.findAlternativeSources(callback: (Result<Unit>) -> Unit = {})` 通过播放服务，为当前队列槽位开启搜索；回调只表示入口是否受理。结果继续订阅 `app.sourceSelection.state`，选择/取消沿用 `select(revision, index)` / `cancel(revision)`。
- 新增 `SourceSelectionMode.MANUAL`：主动搜索等所有目标平台返回或各自超时，不自动选择；当前播放继续，取消不暂停，选中时保留队列身份和当前播放位置。候选与账号代次、槽位票据、revision 绑定。`AUTO` 保留原有快速严格匹配和显式确认行为。
- `SourceSelectionState.platformProgress` 逐平台提供 `SEARCHING/COMPLETE/FAILURE`、完成计数、候选数、安全错误类别。未连接的平台在手动模式中明确报告 `account_unavailable`。`playbackFailures` 另提供各平台实际取流失败的 `PlaybackFailure`，可区分网络、权限、账号和不可用等情况。
- `SourceCandidate.audio` 提供音质、试听、可播放性及加载/错误字段，未查询时为 `UNKNOWN`。前端可以按需调用 `app.inspectAlternativeSource(revision, index, result)`，取得当前候选实际授权的 `QualityOptions` 并更新该候选的 `audio.qualities` 和最高已确认档位；不向 UI 返回音频 URL。检索元数据本身不证明音质或完整播放权限。
- 固定平台协议目前会把部分无版权、会员和试听拒绝合并为 `PERMISSION`。前端应显示“权限受限，可能需会员或无版权”，不能把它断言为单一原因。试听状态缺少明确证据时仍为未知；本轮不虚构会员/版权/试听结论。

### 遗留、电源与可选项

- 酷狗收藏的他人歌单已读取 `list_create_gid`，详情走 `get_other_list_file_nofilt`，本人云歌单维持旧接口；来源歌单保持原发布顺序。从磁盘缓存直接进入详情时会补查来源标识。公共列表与写入菜单分开，写入菜单不会包含只读目标。
- 已核对启动校验：网络/HTTP/超时进入 `RETRYABLE_ERROR`，保留已保存账号身份与 Session；明确过期才进入 `REAUTH_REQUIRED`。前端已有“网络异常/重试”映射，未更改 UI。
- 播放器已设置 `C.WAKE_MODE_NETWORK`，Manifest 已添加 `WAKE_LOCK`；锁由 Media3 按播放状态管理，不另持永久锁。后续新包已完成约 10 分 19 秒熄屏、其中约 5 分半强制深度 Doze 和两次跨曲的专项；数小时自然长后台/自然 Doze 仍需继续验证，不能扩大本轮证据范围。
- 可选的定时停止本轮未迁移：现有前端 `SleepTimer` 仍是进程内计时，`AFTER_CURRENT` 和进程回收后恢复未实现。工具链升级未做。
- 歌手/专辑页技术上可行，但需先补平台稳定歌手/专辑标识和分页模型。现有 `SongSummary` 仅公开显示名，不能用名称充当唯一 ID；酷狗固定来源已有 `artist_detail/artist_audios/album_detail/album_songs` 模块，其他平台还需逐端点核对并扩展固定白名单。这轮仅评估，不新增页面或端点。
