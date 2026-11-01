# 第四批后端接口（2026-11-01）

本文描述本批新增合同。构建和真机结论以交付记录为准；接口存在不代表所有平台已完成在线验收。

- `app.settings: StateFlow<AppSettings>`；命令在 `app.settingsRepository`。持久化主题 `SYSTEM/LIGHT/DARK`、透明、自动换源、放宽时长、默认音质、歌词字号 `SMALL/MEDIUM/LARGE`、配色 `COVER/FIXED`、译文 `AUTO/SHOW/HIDE`。使用 `setThemeMode`、`setTransparencyEnabled`、`setAutoSwitchSource`、`setRelaxSourceDuration`、`setDefaultQuality`、`setLyricsFontSize`、`setLyricsColorMode`、`setLyricsTranslationPreference`，或 `update { copy(...) }`。命令保存失败会抛异常，状态不先行变化；调用方应在 IO 线程执行并处理失败。`MainActivity` 已跟随主题，透明和歌词视觉由前端接线。
- 默认音质使用 `core.playback.PreferredAudioQuality` 的 `STANDARD/HIGH/LOSSLESS/BEST`；旧 `AccountRepository.DefaultAudioQuality` 命令兼容并写到同一设置。旧 `playback-preferences-v1/quality.default` 可读，第一次修改任意设置后写入新快照。既有逐平台显式音质仍优先于全局默认，可用 `clearPreferredQuality(platform)` 清除。
- 自动换源默认开、放宽时长默认关。关闭自动换源后仍搜索，但状态停在 `CHOOSING` 等待选择。手动模式也不会自动选。标题和完整歌手集合一致且没有版本标记是必要条件。严格时长差同时不超过 3 秒与较短版本的 2%；放宽时同时不超过 15 秒与 8%。返回候选按允许自动匹配、时长差、同专辑排序。平台搜索保留固定超时与短暂并行等待，`partial` 表示并非所有平台都已返回。
- `SourceCandidate.relaxedMatch / durationDifferenceMs` 为候选标记；`SourceSelectionState.selectedPlatform / selectedRelaxedMatch` 表示选择，`playbackConfirmed` 为服务实际进入播放后的确认，成功提示应等待这个确认。候选选择仍使用 revision，不能凭旧索引调用。
- 自动播放前并行准备两个来源平台各自最接近的一首，仅在通过匹配的候选中准备；单曲权限/不可用/解析失败后可继续尝试同平台下一候选，整次恢复最多尝试三首。账号、限流、风控和网络失败不继续尝试该平台。首个成功后最多等 150 毫秒收集另一平台，慢网络下准备阶段最多等待 8 秒，并非固定等待。已准备的能力在账号/票据再次核对后提交，播放器直接复用，不二次取流。`SourceSelection.offer` 的可选 `automaticCandidates` 集合限定可自动选中的候选；未就绪的候选仍可手动尝试。常见场景从点播到实际开播以 5 秒为优化目标，尚待独占设备计时；8 秒只约束候选准备阶段，不能作为总开播上限。网络或平台无可用版本时不承诺五秒内开播，也不放松标题、歌手和版本核对。
- 下一首预取由服务后台处理，只准备实际队列顺序中的下一首，含随机顺序。先取原平台能力，遇单曲权限/不可用/解析失败才查替代；自动换源关闭时不提前绑定替代。两次预取启动至少间隔 15 秒；暂停、下一首变化、队列替换、偏好变化会取消，账号和票据在交付及打开音频时重新检查。只预取能力，不下载整首音频。
- 成功映射复用播放日志中真正播过的原曲/来源元数据，重启后仍可读取，限定原账号、7 天内、当前匹配规则和当前来源账号。URL 不落盘；重新取流仍校验权限。播放失败的映射在本次进程不再从日志复用。
- `SongSummary.artistIds` 与 `artists` 按索引对应，缺少完整 ID 时整体为空；`albumId` 缺失为 null，前端此时隐藏该实体跳转，不以名称猜 ID。队列/曲库磁盘格式兼容旧版本，新版本保存 ID。`PlaybackState.sourceArtistIds / sourceAlbumId` 对应实际音源，原曲 ID 从 `queueState` 取。
- `app.catalog.tracks(CatalogKey(platform, ARTIST|ALBUM, id), offset) { Result<Page<SongSummary>> }`；各库也有 `artistTracks/albumTracks(MediaKey, offset=0, result)`。每页 30，沿用账号请求通道，不取音频。实体 ID 和歌单 ID 是不同类型，不送进歌单缓存。网易云/酷狗用平台数字 ID，QQ 用实体 MID。
- `app.accounts.searchCachedPlaylist(playlistKey, query) { Result<CachedPlaylistSearchResult> }` 只查该账号已缓存的所有页，无网络请求；NFKC/大小写/空白规范化后匹配标题、歌手或专辑。返回 `items/loadedCount/total/complete/loadedOffsets/oldestFetchedAtEpochMs`。仅当从 offset 0 连续衔接、总数一致、最终页结束且去重数量等于总数时 complete。界面须在不完整时显示“仅搜索已加载的 N 首”，不得把空结果当作整歌单无结果。

基础取流已返回 permission 时，酷狗授权链的后续 IOException 不再覆盖分类。会员 NO 时普通取流不走授权重试，菜单也不探测授权高档。显式高档请求会直接返回 permission。采用单曲 PROTOCOL 换源：播放阶段的单曲身份/解析错误可以尝试同一首其他来源；账号过期、限流、风控仍停止，不触发跨平台请求。

会员状态按平台独立判断。YES/UNKNOWN 保留酷狗授权重试；NO 仍先做基础取流，只跳过授权重试。基础取流可用就留在原平台；已保存的跨平台映射仅在原平台实际失败后查询，不会因为以前换过源而跳过原平台。会员资格也不保证每首歌的版权或单独购买权限。

会员在登录验证后刷新，返回前台时也刷新；自动刷新按平台和账号代次节流 60 秒，并合并正在进行的请求。`accounts.refreshMembership(platform)` 可显式刷新，不受该时间节流限制，仍合并在途请求；请求失败将状态标为 UNKNOWN，登录保持有效。酷狗音质菜单现在还按会员状态隔离缓存，NO/YES/UNKNOWN 变化后不沿用上一状态的 60 秒结果。

## 缓存维护（追加）

入口为 `app.cache`：`state: StateFlow<CacheMaintenanceState>`，`refresh { CacheOperationResult }` 查询占用，`clear { CacheOperationResult }` 清理三个类别后重新统计。两命令异步串行执行，回调在主线程，文件操作在后台；页面用 `state.busy / clearing` 控制进度与按钮。

`CacheOperationResult.usage.bytes` 按 `CacheKind.COVERS / LYRICS / LIBRARY_PAGES` 返回字节数；`totalBytes` 是三类之和，任一类无法读取则为 null，不能显示为 0。`failed` 返回失败类别，`succeeded` 仅在全部成功时为 true。清理某类失败仍尝试其他类别并重新统计，前端应显示部分失败及剩余占用，不能统一提示“清理成功”。状态不带私有路径或原始异常。

统计为可回收的磁盘文件字节数，不包含内存、目录文件系统开销、更新 APK 或登录网页缓存。封面使用当前 Coil 单例的磁盘/内存清理 API；歌词限 `lyrics-cache-v1`，曲库限三平台 `*-library-pages-v1`。清理也释放相关进程内页面/歌词缓存并使先前请求失效，不主动重新抓取。仍在显示的图片或当前歌曲后续读取可以自然生成新缓存，占用不保证一直为零。

账号、Cookie/会话、设置、手动歌词绑定、收藏、最近播放、保存队列、位置和播放日志都保留。不得复用退出账号的 `clearLibraryCache`，也不得递归清空 `cacheDir`、`filesDir` 或 `noBackupFilesDir`。原统一设置入口继续使用，不另建存储。

折射已由产品负责人确定。当前主目录仍为 Kotlin 2.2.21；CC 会在第四批存档后升级到 2.3.21 并引入 Backdrop 2.0.0-alpha03。整合后须以实际新代码和新包重新跑后端全量与真机回归，不沿用升级前的冻结结论。
