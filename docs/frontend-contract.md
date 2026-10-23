# 前端接入约定（接口基线与历史增量）

2026-10-23：当前前后端接线、产品缺口及实机证据统一见 [0.1验收](android-0.1-acceptance.md) 与 [最新交接](next-session-handoff.md)。双语歌词/翻译开关/独立刷新、红心、队列行点击/长按拖动/删除清空均已接。恢复/最近播放入口、真实封面、主题/更多仍有缺口，输出选择继续暂缓。

本轮已补队列开始revision/变化取消/忙碌门禁/失败反馈和曲库重新读取；队列点击遵守 [P2契约](frontend-backend-contract-p2.md)。B1-08门禁已实现，[集成包真实后台自动换源样本](backend-b108-alpha01-acceptance-20261023.md)已补；自然长后台等系统边界仍待。下面保留原始接口说明，能力变化以v2/P1/P2、翻译/B4契约及最新验收入口为准。

## 初版可对接范围（2026-10-19 历史）

2026-10-19 正式开发包已在 Android 16 验证网易云手机号网页登录、保存与强停恢复、本人歌单/歌曲读取、官方标准音质单曲播放；用户确认声音正常。暂停、继续、进度跳转和退到桌面播放已有真机状态证据。只覆盖当前账号与设备，锁屏按钮和长时间后台仍需专项验收。

初版已增加完整歌单 ID 队列、指定歌曲起播、前后切歌、后台自动接续与末尾停止；网易云真机歌单为 5 首不同歌曲。三平台有账号归属的持久分页缓存，真实断网冷启动下可浏览本人已缓存歌单和歌曲，标注登录待验证。初版供 cc 接账号管理、三平台缓存列表和顺序播放器，并分别展示加载、空列表、失败与重试状态。持久队列及显式暂停恢复已在 P1 实现，正式页面入口仍待接；网易云相册备用尚未重新验收。

## 账号

应用进程持有一个 `AccountRepository`。页面订阅 `states: StateFlow<Map<Platform, AccountState>>`，通过生命周期收集，后台停止页面观察但不销毁 Repository。

| 动作 | API / 说明 |
| --- | --- |
| 酷狗 / QQ 连接 | `connectOfficial(platform) { intent -> Activity.startActivity(intent) }`；回调仅用于当次跳转，不保存 Intent/URL |
| 网易云网页登录 | `NeteaseWebLogin.open(activity, accounts)`，Activity 销毁时 `close()`；Cookie 不进入前端回调 |
| 网易云相册备用 | `connectNeteaseScanner { intent -> startActivity(intent) }`，提示到网易云扫一扫从相册选本次图 |
| 前后台 | `setForeground(true/false)`；正式多 Activity 架构应统一计数或使用进程生命周期，不能由多个页面各自覆盖布尔值 |
| 手动查询 | `checkNow(platform)`，只查询本次有效请求 |
| 取消 | `cancel(platform)`，关闭网页组件；取消不会接受旧请求的迟到结果 |
| 重验保存记录 | `restore(platform)`，网络失败保留记录和待确认身份 |
| 断开 | `disconnect(platform)`，仅本机；清理成功后才发布 DISCONNECTED |

`AccountPhase` 区分 PREPARING、WAITING_CONFIRMATION、VERIFYING、CONNECTED、REVALIDATING、RETRYABLE_ERROR 和 REAUTH_REQUIRED。CONNECTED 只由音乐会话验证并保存后产生；REVALIDATING 的 profile 只能显示为待验证身份。网络失败不能显示“已退出”；EXPIRED 表示明确的会话失效。错误文案按固定 AccountError 映射，不展示异常堆栈和原始服务端消息。

一方从官方 App 回来时自动检查仍有效请求，自动回跳还不能承诺所有版本。3 分钟从请求创建后计算；重复打开不能延长。正式 UI 应避免重复发起连接，并提供一个主要动作和清楚的取消/重试路径。账号切换先采用断开后重连，替换式切换另行验收。

## 音乐库

酷狗使用 `MusxiApplication.kugouLibrary` / `KugouLibraryRepository`，同样实现 `LibraryPageFetch` 的 `playlists(offset, callback)` 和 `tracks(MediaKey, offset, callback)`。这是底层直接联网接口；页面使用进程持有的 `MusxiApplication.kugouLibraryBrowser`，通过同一 `LibraryBrowseState` 接入缓存优先、后台刷新、失败保留及重试。网易云继续使用 `libraryBrowser`，两个入口不能混传平台 key。`nextOffset` 是后端提供的分页续接值，必须原样传回，不按本页项目数自行计算；总数未知且短页不表示结束，需要继续读至空页。失败保留已展示内容并提示重试，不把失败当作成功空列表。

酷狗歌曲 key 为 `KUGOU` 加 `hash_albumId_albumAudioId`（32 位小写 hash，两个非负十进制标识），不是文件 ID、歌名或网易云数字 ID。UI 只透传 `MediaKey`，不自行拼接或拆解。当前歌单封面未接入，使用占位图；不承诺完整歌手/专辑字段或高音质权限。

`NeteaseLibraryRepository.playlists(offset, callback)` 与 `tracks(MediaKey, offset, callback)` 返回 `Result<Page<...>>`。通过 `nextOffset` 继续分页；null 才表示结束。`total` 可未知。失败为 `LibraryFailure(category)`，只含固定分类。

歌单和歌曲的身份由 `MediaKey(platform,id)` 表示；使用 stableId 作列表 key。不得仅以数字 ID 或歌名做跨平台合并。缺失歌曲会保留原位置并显示暂不可用，当前基础模型尚未给出完整可播放能力，不用歌曲存在推断播放权限。

账号连接与歌单同步状态独立。`MusxiApplication.libraryBrowser`（网易云）、`kugouLibraryBrowser`（酷狗）和 `qqLibraryBrowser`（QQ）是分别由进程持有的缓存优先入口，页面通过 ViewModel 观察 `state: StateFlow<LibraryBrowseState>`；使用 `playlists(offset)`、`tracks(key, offset)` 和 `refresh()`。它会先读取该账号的本机分页，再尝试联网刷新；无需页面先读缓存再拼接网络回调。底层 `app.library` / `app.kugouLibrary` / `app.qqLibrary` 仍是各平台直接网络接口，播放用例继续从网络建立完整 ID 队列。

`LibraryBrowseState` 的 `query` 标明列表/歌单和 offset，`content` 是 `LibraryContent.Playlists` 或 `Tracks`；`source` 为 CACHE / NETWORK，`fetchedAtEpochMs` 是这页上次成功联网读取的时间。`refreshing` 和 `error` 与已有内容独立：刷新失败保留缓存并提示重试，不能把失败替换成空列表或“已退出”。空列表只有 content 内 items 为空才算成功的空页；content=null 表示尚无可用数据。

`accountVerified=false` 时，已保存账号的缓存可浏览，但不能声称登录成功或保证可播放。登录恢复期间和验证暂时失败时不为缓存浏览发新请求；待 CONNECTED 后可刷新。`storageError` 单独表示缓存读写未完成，已有联网结果仍可显示。正式页面应显示“本机缓存”、上次读取时间和必要的登录待验证提示。

分页按 `nextOffset` 继续，只有 offset=0 且 nextOffset=null 才可标明整份列表已读完。单页缓存不等于完整曲库，也不用于播放队列恢复。刷新首页成功后会淘汰该列表的旧后续页，避免拼接旧页；UI 刷新首页时同样应重置自己的分页聚合，不能继续沿用旧的后页数据。

缓存归属平台与保存/验证过的账号身份，不按易变 generation 建磁盘目录；迟到请求仍以 generation 隔离。元数据保存在应用 noBackup 私有目录，不包含 Cookie、会话、封面 URL、短期音频地址或音频文件。断开账号必须清理缓存后才报告断开成功，换号不能读出旧账号页。页面生命周期停止收集即可，不销毁进程持有的浏览器；调用 `cancel()` 或新选择会拒绝旧结果更新页面，已排队的旧任务在执行前检查有效性，进行中的磁盘写入不能越过随后排队的断开清理恢复旧账号数据。

每平台单集合最多 2 MiB / 128 页、最多 128 个集合，各平台缓存（含临时写入预算）不超过 16 MiB，三平台合计最多 48 MiB。超出后按旧集合淘汰；容量不足、无首页/前序页或总数变化导致不能安全缓存时，仍可展示本次联网结果并设置 storageError。缓存页不保证跨页属于同一时刻的服务端快照，不用多页缓存推断全量同步完成；当前也未缓存封面图片，离线图片可使用占位。

## 播放

页面通过进程持有的 `NeteasePlaybackUseCase.playPlaylist(playlist.key, song.key)` 发起播放，通过 `state` 展示队列加载与固定错误。停止或取消加载使用用例层 `stop()`，这样尚未完成的歌单请求不会迟到后重新开始播放；不要在队列加载中只调用底层播放器 stop。

`MusxiApplication.queuePlayback` 保留现有类型名，已按 key.platform 路由网易云、酷狗和 QQ，三平台共用一个用例与 MediaSession；切换平台同样遵循最后一次选择生效。酷狗先分页读完歌单 metadata，再提交最多 20,000 项队列；读取有取消和 60 秒工作预算，超限、已知总数变化或不完整会失败。分页不提供服务端原子快照保证；当前不支持混合平台队列。仅当前队列所属平台的账号失效或代数变化会停止清空，重验其他平台不会打断播放。

酷狗播放限定官方标准音质，拒绝试听与不匹配歌曲标识。音频地址只存在于取流/播放器内存，短期 capability 以本机单调时钟保守复用 60 秒，不能把该值当官方有效期。优先探测同次官方响应的 HTTPS 主/备用地址；最多 3 个 HTTPS 主机、2 个原始 HTTP 主机，受时间预算限制，关闭重定向，不发送账号 token/Cookie 作为 CDN 请求头。

当前实机两个官方 CDN 的原始 HTTP 地址升级 HTTPS 后证书主机名不匹配。为播放该响应，仅对 `fs.youthandroid2.kugou.com`、`fs.youthandroid.kugou.com` 两个精确域名保留原始 HTTP 音频兼容；系统网络策略与播放器均限制这两个域，不能扩为任意 HTTP，不能把 HTTPS-only 响应降级。HTTP 音频流与临时媒体查询参数不具备 TLS 的传输保密性；账号登录、歌单、歌曲和取流 API 仍使用 HTTPS，证书校验未关闭。该限制属于传输实现与交付边界，前端不接收或缓存地址，也不要求用户操作“关闭安全校验”。

`loading` 持续到服务接受队列，服务拒绝也会产生固定 `player` 错误；接受队列之后的缓冲和取流错误观察 `PlaybackConnection.state`。连续点击时以最后一次选择为准，不在页面串联 setMediaItems/prepare/play 或自行清空服务。普通系统 STOP 同样取消尚未完成的加载。

`PlaybackConnection.state` 来自真正的 MediaController，页面不自行维护另一份播放状态。包含服务连接、playing、playWhenReady、buffering、ended、mediaId、标题/歌手、固定失败类别、positionMs、durationMs、队列索引/长度及 hasNext/hasPrevious；使用 toggle、pause、resume、seekTo、next、previous、select(index) 操作同一个服务。播放结束时显示“已播放完”，不能仅因 playWhenReady=true 显示仍在播放。前后按钮按 hasNext/hasPrevious 启用。

末尾停止后首次点播放会从当前末曲开头重播；暂停中仍保留当前进度。播放失败时重试是显式动作，不在页面轮询或无限自动重试。

`NeteaseLibraryRepository.queue(playlist, startSong, callback)` 返回完整、不可变、绑定账号 generation 的 `PlaybackQueue`。它先读取全部 trackIds，超限或不完整即失败，不把当前 50 首详情分页当作完整歌单；歌曲详情和取流由服务按需执行。尚未解析的队列标题是“待加载歌曲”，可以显示占位，不能因此禁用该歌曲。

前端不调用 resolveAudio，不持有 AudioCapability 或地址。服务用无凭证的 opaque ticket 构建 Media3 timeline，仅在数据加载线程将其解析为官方 HTTPS。短期 capability 最多缓存 3 个，每次使用检查到期与账号归属；账号代数变化会停止并清空队列，已加载缓冲不能继续作为旧账号播放。

自动恢复规则：CDN 地址失效最多重取一次；明确不可播放时进入下述跨平台候选流程，等待选择期间不顺序跳过；网络、超时、限流、账号失效或协议问题停止自动请求，显示重试或换歌动作。`lastSkipped` 为历史兼容字段，`failure` 表示当前终态错误，两者不能混用。播放错误不一概要求重新登录，只有官方音乐账号接口明确判定失效时账号模块才标记重新登录。

当前支持顺序/单曲循环/列表循环与随机遍历，仍为整队列替换模式，不支持追加/删除/拖动排序、持久队列恢复或音频文件离线缓存。2026-10-19 用户本人已确认耳机断开停止、重连不自动播放，锁屏/通知媒体卡片可见、暂停/继续/下一首有效且切歌后有声音；更多耳机型号和设备仍需专项验收，详见 playback-acceptance.md。

## 产品体验基线

建议参考外层规划第 17 节：三个纵向账号卡片、每个平台一个默认入口、网页登录全屏、方式选择按需展开、成功后返回原任务。调试按钮、协议步骤、无效凭证对照和原始错误不进入正式页面。兼顾深浅色、系统字体放大、软键盘、安全区、读屏和至少 48dp 触控目标。

## 酷狗缓存与刷新交接补充

- 两个平台的缓存目录、平台账号摘要、浏览 state 和请求计数各自独立。断开只清理该平台缓存；换身份时回到新账号歌单首页，旧响应不能写回或覆盖当前页面。同账号重新验证后可继续当前页。
- 酷狗每次浏览请求 30 项，续接值按后端 `nextOffset` 原样传回；未知总数的短歌单页仍可能带 next=30，空末页才是结束。不得用 items.size、trackCount 或当前页为空之外的推测替代后端续接规则。缓存保存相同分页约定。
- 首页刷新成功会淘汰该集合之前的后续页。后续页须有已缓存首页及可衔接的前序页；刷新失败保留当前页及其原读取时间。单页缓存不是整份 746 首曲库，也不是持久播放队列。
- 建议列表页把数据、刷新状态和错误分别渲染：首次没有数据时显示加载/空白或错误；已有内容刷新时保持滚动位置并显示轻量刷新状态；失败用就地提示和重试，保留内容、来源及时间。不要把 `refreshing` 用作清空列表的条件。`storageError` 与网络错误分别展示。
- 未验证时可打开已缓存歌单及歌曲；播放动作依据 `accountVerified` 禁用并说明需联网验证。恢复网络后由账号重验入口 `restore(platform)` 恢复验证，CONNECTED 后浏览入口刷新；单独 `refresh()` 不负责重验账号，不承诺自动无限重试。
- 当前只持久化已浏览的分页元数据，去除封面 URL；不落盘音频 URL、会话或音频内容。每平台上限 16 MiB / 128 集合，每集合 2 MiB / 128 页，三平台预算合计最多 48 MiB；与播放的短期内存地址缓存分开。

2026-10-19 最终包缓存验收：酷狗 3 歌单、未知总数的空末页和两个歌曲页共 60 条已浏览元数据，在真实断网强停重开后都可从磁盘恢复；网易云 1 歌单/5 歌曲同时可读，两个平台曲库请求计数为 0，账号均标为待验证。恢复原网络并显式重验后，仍保留当前歌曲页并自动刷新；登录记录保留。此结论覆盖当前设备和已浏览分页，不代表离线音频、整单缓存或实际换号已验收。


## QQ 正式包接口补充

QQ 仍由 `connectOfficial(Platform.QQ)` 创建同机请求，经 QQ 确认和音乐凭证兑换后验证保存。`musxiplayer://login-return` 只恢复检查当前有效请求，不接收回跳凭证或成功声明；回到前台自动检查，保留手动 `checkNow(QQ)`、取消和失败重试。三家账号各自验证，QQ 曲库失败不把其他平台退出。

`MusxiApplication.qqLibrary` / `QqLibraryRepository` 提供直接联网的 `playlists(offset, callback)`、`tracks(key, offset, callback)` 和完整队列加载。歌单合并本人自建及收藏，先有界读取两部分再本地分页，`total` 为合并去重后的数目；自建未完成、收藏隐藏、分页总数变化或明显截断均返回固定失败，不伪装成功空列表。当前最多 20,000 项、每次响应 4 MiB、单次工作有 60 秒预算；不保证服务端多页原子快照。

QQ 歌单 `MediaKey(QQ, "tid_dirid")` 保留歌单 ID 和目录 ID；喜欢目录可用 `0_201` 并在协议模块内部绑定本人加密身份。QQ 歌曲 `MediaKey(QQ, songMid)` 使用 14 位 ASCII 字母/数字 MID。UI 只透传 key，不与数字歌曲 ID、media_mid、文件名或其他平台 ID 混用。封面暂用占位；原始身份、凭证、取流文件名和地址不进入前端 DTO。

统一 `app.queuePlayback.playPlaylist(playlistKey, songKey)` 按 QQ 路由完整有序队列，不能用首个 30 项页面当全量；停止/替换会取消未完成的 QQ 加载。按队列所属平台 generation 校验，重验其他账号不干扰当前播放，本平台重验/断开会停止旧代数队列。当前仍只支持单平台队列。

QQ 按需获取官方标准 MP3_128（M500），核对歌曲 MID、文件名和返回相对路径，拒绝加密、替代音质或可识别试听结果；QQ 本来源无权限时保留真实拒绝，跨平台临时来源按末节增量独立搜索和校验。只探测官方响应的允许 HTTPS CDN，最多 3 个主机并设探测预算；HTTP 根地址仅尝试升级 HTTPS，不新增明文例外，不关闭证书校验。音频 capability 只在内存保守复用 60 秒，每次校验账号和到期；不是官方承诺的 URL 有效期。

QQ 页面使用进程持有的 `app.qqLibraryBrowser`，订阅统一 `LibraryBrowseState`，调用 playlists/tracks/refresh；直接网络 facade 保留用于完整队列和底层操作。先显示缓存再刷新，失败保留当前内容、来源和读取时间，提供就地错误与重试；storageError 与网络/账号错误分开。账号已连接、列表同步成功和歌曲可播是三个不同状态，不用任何一个推断其他状态。QQ 独立目录、magic 和账号归属，不修改原两平台磁盘格式。每平台 16 MiB，三平台合计 48 MiB。


QQ 当前真机已验证：本人 QQ 官方确认、音乐会话保存、覆盖更新保留及强停重开恢复；1 个本人歌单补充至 6 首，指定第 2 首建立完整队列并播放，用户确认有声音；前后切歌、HOME 后曲尾自动接续、重验其他平台不影响当前队列，以及重验 QQ 自身清除旧代数队列。首曲曾有明确 104003 权限拒绝，不保证全部 6 首或付费音质可播。接口层/来源/账号隔离测试和静态交叉检查通过，实际换号、长期后台仍需专项验收；QQ 持久分页缓存增量见下文。


QQ 缓存前端增量：每页最多 30 项，nextOffset 按实际返回项数计算，UI 必须原样传回，不能套用酷狗步长。已知 total、首页 offset=0 且 nextOffset=null 时才能声称本列表读完；没有浏览过的页面不保证离线可用。同账号重验继续当前歌曲页，换号回本人歌单；离线/待验证只浏览元数据，歌曲播放按钮按 accountVerified 禁用。断开后清除此平台缓存，原平台不受影响。

2026-10-19 真机通过 QQ 1 歌单/6 歌曲联网落盘、模拟刷新失败保留与恢复、真实断网强停重开恢复（CACHE、曲库请求 0、待验证）、恢复网络后同页刷新。原网易云/酷狗缓存同时可读。完整对照记录见外层规划第 24 节。会员/版权/请求兼容等播放原因应独立表达，不用缓存状态推断；两端对照建议见 [qq-playback-parity.md](qq-playback-parity.md)。


## 基础验收与后续换源方向（2026-10-19）

`PlaybackState.suppression` 增加 NONE / AUDIO_FOCUS / OUTPUT / OTHER，区分系统暂时抑制和用户暂停。`playing` 表示实际输出，`playWhenReady` 表示播放意图；AUDIO_FOCUS 时可能 playing=false、playWhenReady=true，前端应显示“播放被其他音频暂时打断”，不能误报账号退出、网络错误或用户暂停。暂停动作仍通过原 pause()；恢复由系统焦点或用户显式 resume() 驱动。此字段在构造末尾提供默认值，原调用兼容。

实际使用验收持续记录在 [playback-acceptance.md](playback-acceptance.md)。2026-10-19 增量已接入跨平台候选、手选及 5 秒自动尝试首个同名项，详见 [cross-platform-sources.md](cross-platform-sources.md)。原队列保持单平台，实际取流来源可以临时来自其他平台；正式界面接线以本节末增量为准。


本轮基础验收完成：真实焦点请求的短暂中断/归还、主动暂停保持、永久失焦后的显式继续；页面重建、HOME、短时锁屏持续播放；真实断网时保留原队列/位置并报告 NETWORK，恢复网络后显式 resume 成功。播放失败提示应提供可发现的“重试播放”动作，调用 resume()，不靠列表刷新或重新登录代替。真实耳机和媒体卡片操作已获本人确认。长期后台、真实通话和跨设备不在本轮通过范围；下一阶段继续换源最小闭环，候选接口尚未冻结。


需保留的验收边界：设备锁屏且播放器已暂停时，经debug入口重新发起/恢复队列曾被系统以焦点原因停止，未显示取流错误；解锁前台后同队列resume成功。它不同于已通过的锁屏媒体卡片控制已有播放。后台新建播放、系统焦点资格与清楚的状态提示仍需后续定位，不宣称此路径已完成。

## 跨平台候选接口增量（2026-10-19）

页面观察 `app.sourceSelection.state: StateFlow<SourceSelectionState>`，由服务维护单一状态。`phase` 为 NONE / SEARCHING / CHOOSING / SELECTED / FAILED；`revision` 为当前操作令牌；`original` 为原队列歌曲，`candidates` 为最多 20 项 `SourceCandidate(song, accountGeneration)`。候选只有普通歌曲元数据和授权代次，不含凭证、URL 或可播保证。

CHOOSING 时使用 `select(revision,index)` 手选，`cancel(revision)` 取消；必须传展示时的 revision，不能把旧对话框操作套到新结果。倒计时由服务执行，页面只以 `deadlineElapsedMs - SystemClock.elapsedRealtime()` 显示剩余时间，不能自行启动播放或重置截止时间。`automaticIndex` 指首个满足B4标题/完整歌手/时长/版本保守资格的候选，可能不是第0项；无合格项时两字段为null，候选仍可手选。`partial=true` 表示部分平台搜索未完成，应提示仍可选择已返回结果。

SELECTED 只表示已经提交某个来源，实际是否播放仍看 `app.playback.state`。显示原歌单平台与实际来源，保留标题、歌手、专辑、时长和版本信息；不要写“已确认同录音”或预先承诺可播。标题匹配只做 Unicode、大小写与空白归一，保留 Live 等版本后缀。标准音质取流失败仍可能重新候选或停止。

原平台明确 PERMISSION/UNAVAILABLE 时启动搜索；网络、超时、限流、协议及账号问题维持原错误处理。候选中有符合B4保守资格的项时，准备后5秒自动尝试首个合格项；某候选同样不可播时移除已尝试项重新展示，本曲最多尝试 3 个来源。搜索失败/无候选/耗尽后停止并保留原队列，提供重试或下一首；显式重试原曲会恢复 ticket 原始来源，而网络重试保留当前实际来源。没有强制跳过原队列项。

播放结束、上一首/下一首、选队列项都遵循原队列，不进入搜索结果顺序。原 mediaId/queueIndex 不变，媒体标题/歌手可反映实际来源。原账号代次失效清空；替代账号失效停止但保留原队列；重验无关平台不应打断。取消/暂停/停止/导航使旧搜索、计时和恢复回调失效；关闭页面不等于用户取消，服务倒计时继续。搜索编排可取消，但已经执行的平台 HTTP 请求不保证即时断开，其迟到结果不能恢复播放。

当前开发页只是临时选择对话框；cc 正式前端可做底部候选面板，提供清楚的“取消自动选择”、倒计时、待尝试和部分结果提示；不要要求离开歌单。正式接线应复用此状态与原 PlaybackConnection 命令。

选源等待期的媒体会话以 `buffering=true / playWhenReady=true / playing=false` 表达仍在等待播放，避免把一次候选取流拒绝提前当作整次播放的终态。页面应优先根据 SEARCHING/CHOOSING 显示“搜索其他来源”或候选面板，不用通用“正在缓冲”盖过选源信息；此时无声音和进度不表示正在播放音频。取消、暂停、系统停止立即撤销意图；候选耗尽或搜索失败恢复明确失败。`SELECTED` 后如遇 NETWORK，应保留所选来源，显式 `resume()` 重试；不能因网络失败自动改播另一个版本。`play()` 不带 prepare 的系统控制器也可重试，页面仍统一用 `resume()`。

页面重建和 HOME 不取消服务拥有的截止时间。任务移除时服务按播放意图决定是否停止，不能仅凭底层播放器在等待候选时暂停判断；划掉任务、多设备及长时间 Doze 未专项真机验收。2026-10-19 单设备已验证后台截止时间提交同名来源；一次随后取流 NETWORK，前台显式重试成功，已开始的替代音频在页面重建/HOME 后短时继续。候选就绪、已提交来源和真正发声是三个状态，正式 UI 必须分开。

2026-10-20 P1 新接口见 [frontend-backend-contract-p1.md](frontend-backend-contract-p1.md)：随机/循环、本人队列与位置的显式暂停恢复、最近播放、实际来源歌词和 PlaybackState.sourceKey。真机证据及酷狗歌词空响应的遗留见 [p1-data-acceptance.md](p1-data-acceptance.md)。恢复快照不代表正在播放，仍须等用户点击播放再重新取流；cc接线复用这些状态和命令。
