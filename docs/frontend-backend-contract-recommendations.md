# 三平台推荐接入合同

更新：2026-11-12。仅歌曲元数据与现有播放链路；没有业务服务器、收听上报、不喜欢反馈或自研排序。平台能力与本轮验证见[交付记录](backend-recommendations-20261112.md)。

## 入口与类型

`MusxiApplication.recommendations: AppRecommendations`，在主线程调用，观察 `state: StateFlow<Map<Platform, RecommendationState>>`。

| 平台 | `state.kind` | 前端建议名称 | 当前协议 |
| --- | --- | --- | --- |
| 网易云 | `DAILY_SONGS` | 每日推荐 | 登录后的每日歌曲，最多200条 |
| 酷狗 | `DAILY_SONGS` | 每日推荐 | 登录后的每日歌曲，最多200条 |
| QQ | `PERSONAL_RADIO` | 猜你喜欢 | 登录凭证作用域的99号个性电台，每次请求30条，上限30条 |

QQ固定及当前上游的recommend模块没有可证实的每日专用方法。当前接口**不承诺QQ每日更新、不使用匿名热门结果替代**。前端按kind命名，不能统一写成“三平台每日推荐”；将来接到QQ日推应单独扩展能力并更新缓存版本。

```kotlin
val recommendations = app.recommendations
recommendations.open(Platform.NETEASE) // 页面进入/回到前台时主动调用
recommendations.refresh(Platform.NETEASE) // 用户主动刷新
recommendations.cancel(Platform.NETEASE) // 离页取消该平台的进行中工作

val shown = recommendations.state.value.getValue(Platform.NETEASE)
val songs = shown.snapshot?.songs.orEmpty()
recommendations.play(Platform.NETEASE, startIndex = clickedIndex,
    expectedRevision = shown.revision) { result -> /* 主线程；提交播放结果 */ }
```

`play`使用整份推荐快照与所选起点，播放全部传0。单曲点播也是从该位置播放推荐队列；若只需独立单曲，仍可使用已有`queuePlayback.playSong(song)`。无需伪造歌单ID，也不能把推荐列表当作某个歌单的连续分页前缀传给`playPlaylist`。

## 状态和错误

- `snapshot == null`表示还没有有效结果；非null且songs为空表示平台明确返回空列表，与失败分开。`snapshot.songs`是完整、有界、保持平台顺序的不可变列表，复用`SongSummary`/`MediaKey`；平台ID和录音版本不跨平台合并。
- `snapshot.fetchedAtEpochMs/retrievedDate`是成功取回时间及UTC+8日期，**不是平台声明的推荐生成日期或更新时间**。不伪造推荐理由、得分或平台批次。
- `source`为`CACHE/NETWORK`；`loading`覆盖读缓存及网络/落盘，`refreshing`表示已进入网络阶段。刷新期间保留已有结果，失败也不清空旧列表。
- `accountVerified`表示当前账号可用于联网/播放。仍保留身份的重新验证或需登录状态可以显示该账号的缓存，同时为stale；没有当前身份则不读私人缓存。换成另一账号立即撤下原账号结果，旧请求不能更新新账号。
- `stale`在打开/返回结果/账号变化时重算，表示取回缓存过期或账号未验证。没有后台计时刷新，页面重新进入时应调用open。
- `error`只发布固定类别：`account_unavailable/expired/network/timeout/risk/rate/protocol/identity/limit/permission/unavailable/endpoint/http/platform/request/account_changed/cancelled/unclassified`。`storageError`单独表达缓存读写失败；新结果落盘失败仍可显示和播放，不误报网络失败，不泄漏原始异常。
- 每平台两次网络发起至少间隔30秒，快速重复打开合并请求。手动刷新被本地限流时为`rate`并给`retryAfterMs`；该字段是当次剩余时长，不自动倒计时。取消的请求也占用原间隔，避免反复离页触发请求。平台风控/限流不会自动重试。
- `revision`是推荐快照的本地版本，不是播放器队列revision。读取列表后点播必须传当时版本，变化则失败`stale_revision`；账号未验证/换代、没有快照或索引非法也拒绝，不按旧序号播放新歌。成功回调表示现有播放入口接受了队列，不代表解码、完整版权或真人听音通过。

## 缓存、取消与生命周期

### 应用级总开关

`app.settings.value.recommendationsEnabled: Boolean` 默认 `true`，通过 `app.settingsRepository.setRecommendationsEnabled(value): AppSettings` 保存；`update { it.copy(recommendationsEnabled = value) }` 也走同一同步通知。持久化键为 `settings.recommendations-enabled`，缺键、非法值或旧设置回退为 `true`。沿用设置的同步提交语义：调用方在 IO dispatcher 执行，保存失败抛异常，原状态和推荐工作保持；只有保存成功才切换推荐行为。

关闭成功后，三个平台的内存快照立即清空、revision 递增、进行中工作失效；`open/refresh` 无操作，不读取推荐缓存、不发推荐请求，状态为空闲且 `error == null`。`play` 在校验账号/revision 之前返回 `LibraryPageFailure("recommendations_disabled")`，不提交新队列。点播校验与调用现有队列提交入口共用关闭的同步边界，关闭命令成功返回后不会再交付一次先前已校验的新队列。关闭前已提交给播放链路的队列和当前歌曲继续播放；这不是停止播放开关。

清理只包含三平台推荐列表元数据（`daily.bin/daily.tmp`）与内存快照，不清共享封面、普通曲库/歌词、本地文件、壁纸、设置或账号。磁盘删除异步排入现有串行缓存队列；设置命令返回表示已完成内存失效并排入清理，不表示磁盘删除已结束。已进入的磁盘操作先结束、清理随后执行，重新开启后的读取排在清理之后；旧响应及旧排队读写不能重新发布或回填。清理逐平台尝试，一处失败仍继续其余平台，失败只以 `storageError` 表示，不发布网络错误；下一次关闭或关闭状态的冷启动会再次清理。

后端实例由账号层持有，关闭和关闭状态的冷启动都不要求先访问 UI 的 `app.recommendations`。取消为逻辑取消，已发出的 HTTP 可能自然结束，但结果失效。重新开启只允许后续入口，不自动联网；CC 在显示当前平台卡片后主动调用 `open`。本进程原有每平台/账号的 30 秒节流记录保留，快速关闭再开启不能绕过限流。账号隔离、缓存 v1 格式和点播 revision 规则不变。

网易云/酷狗同一UTC+8取回日优先缓存，下次日期变化后第一次open才联网。QQ个性电台缓存最多1小时且不过UTC+8取回日；手动刷新可绕过新鲜度，不能绕过30秒限流。平台可能重复返回同一组，刷新成功不保证换一批。没有午夜闹钟、后台轮询或打开应用就自动请求。

缓存以平台及账号SHA-256摘要校验，独立于账号凭证，置于noBackup目录的`recommendations-<platform>-v1/`，每平台一份最多2MiB的完整快照。只含公开歌曲元数据、取回时间与账号摘要；封面沿用平台白名单，音频URL、Cookie、token和原始响应不落盘。写入有校验、fsync和原子替换；损坏快照作为缓存未命中，不影响账号数据。

推荐缓存计入现有`LIBRARY_PAGES`统计与清理；应用的`cache.clear()`先让浏览器作废进行中工作，再在同一磁盘队列清除，迟到结果不能把缓存补回来。退出对应平台清除该平台推荐；本地音频、壁纸、书签、设置和其他账号数据不受普通清理影响。

cancel为逻辑取消：尚未开始的请求跳过，已发出的HTTP可能自然结束；之后不更新状态、不提交新缓存。界面重新open可继续使用已有快照，30秒间隔仍有效。三平台状态、缓存、节流和取消独立，一家失败不阻断另外两家。

## 播放与扩展边界

推荐点播复用`queuePlayback`/同一Media3服务、账号代次和现有取流、音质、歌词、换源、队列恢复。实际可播放性在取流时判断，出现在推荐列表不等于能完整播放。用户的新播放意图会取消旧歌单补队列和本地恢复；推荐刷新本身不会修改正在播放的队列。

不上传Musxi中的播放/跳过/收藏历史以训练平台，不自动写平台收藏或不喜欢。平台是否因在Musxi收听而改善推荐尚未验证。混合列表、跨平台去重、每日历史、无限FM、推荐理由和自行排序留待后续，不把没有实现的行为隐藏在首版接口中。
