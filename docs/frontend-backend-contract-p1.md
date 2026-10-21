# P1 后端接线（2026-10-20）

P1 可以独立于正式前端推进。现提供随机/循环、持久队列显式恢复、最近播放、实际来源歌词。P0 继续使用 [v2 接口](frontend-backend-contract-v2.md)，未修改 cc 的 Compose 页面。所有入口在主线程调用、订阅 StateFlow；平台网络与磁盘工作在后台执行。

## 接口与状态

```kotlin
import tech.musxi.playback.PlaybackRepeatMode

app.playback.setRepeatMode(PlaybackRepeatMode.OFF) // 顺序到末尾停止
app.playback.setRepeatMode(PlaybackRepeatMode.ONE) // 当前项曲尾重复
app.playback.setRepeatMode(PlaybackRepeatMode.ALL) // 全列表循环
app.playback.setShuffleEnabled(true)             // 随机遍历
app.playback.setShuffleEnabled(false)
```

均在主线程调用。页面订阅原 `app.playback.state`，新增字段：

| 字段 | 含义 |
| --- | --- |
| `repeatMode: PlaybackRepeatMode` | OFF / ONE / ALL，默认 OFF |
| `shuffleEnabled: Boolean` | 默认 false；与 repeatMode 分开设置 |
| `canSetPlaybackModes: Boolean` | 当前 MediaController 同时具备两种设置命令；未连接时为 false |

服务和 MediaSession 是状态来源，UI 不另建本地“已选模式”作为真实状态。调用前依赖 `connected && canSetPlaybackModes` 启用按钮；未连接/命令不可用时设置为无操作，不排队，不触发播放或偷偷创建队列。命令异步送到服务，等待 StateFlow 确认结果。模式同样对可信系统控制器开放；仍禁止外部任意设置或修改队列。

## 行为

- 随机与循环使用 Media3 原生行为；不重新排列原 timeline，不修改 queueSongs、mediaId、索引或缓存内容。`queueIndex` 仍是原列表索引，随机时切歌可能跳到任一原索引。队列页面继续按原顺序展示，用 current index 高亮，不自行另做随机算法。
- OFF+随机表示按随机顺序遍历至末尾停止；ALL+随机表示按同一随机遍历顺序循环；ONE 时曲尾仍重复当前项。随机播放是排列遍历，不能要求每次 next 都独立随机抽一首；本轮没有“重新洗牌”动作。
- ONE 只控制曲尾重复，显式 next/previous 仍能切换队列项；按钮按 hasNext/hasPrevious 启用。previous 沿用 Media3 的“超过回退阈值先回当前曲开头”行为。
- 改模式本身不启动、暂停、跳转或重搜换源。暂停中改模式仍保持暂停；下一次继续使用所选模式。
- 同一队列项重复时保留已选跨平台来源、候选显示元数据和受限恢复预算，不重新恢复不可播原源。真正离开该槽位时按既有规则恢复原曲资料并复位来源；以后回到此项从原源开始检查。
- `automaticTransitions` 现在也计入单项重复，与曲尾自动切到另一项一起计数；seek/手动 next 不计入。重复不会清空 3 次替代来源尝试的限制。
- 模式在同一服务生命周期中保留，普通替换队列不会强制改回顺序。冷启动空服务默认为 OFF、shuffle=false；显式恢复已保存队列时恢复其模式开关，不保证恢复上一次随机排列。

## 验证入口

debug 包增加固定 `p1_repeat_off/one/all`、`p1_shuffle_on/off`、`p1_watch`、`p1_snapshot`；仍由 DUMP 权限保护的 ValidationReceiver 驱动。内存中比较完整原队列 key 顺序、当前 key/来源/显示字段，诊断只输出布尔值、计数、枚举及索引，不输出账号、歌曲标识或媒体地址。正式页面不展示这些调试步骤。

自动回归与本机真机结果见 [播放模式验收](p1-playback-modes-acceptance.md) 和规划第 32 节；后端验证不替代正式页面的交互、视觉或听音验收。

## 保存与恢复

```kotlin
val journals = app.playbackJournal.state // StateFlow<Map<Platform, PlaybackJournalState>>
app.playbackJournal.refresh(Platform.QQ)
app.playbackJournal.restore(Platform.QQ) // 显式载入，保持暂停
app.playback.resume()                   // 用户随后点播放，重新授权并取流
app.playbackJournal.clearRecent(Platform.QQ)
```

每平台状态包含 `queue: SavedPlaybackQueue?`、`recent: List<RecentPlay>`、`accountVerified`、`loading`、`restoring`、`error`。`queue=null` 表示没有可用快照，与存储错误不同；加载完成才能判断空状态。队列字段为 `songs`（原顺序与原平台资料）、`index`（原索引）、`positionMs`、`repeatMode`（OFF/ONE/ALL 字符串）、`shuffleEnabled`、`savedAtEpochMs`。恢复按钮需要 `accountVerified && queue != null && !loading && !restoring`，播放还需 controller connected；失败显示固定错误，不自动循环重试。

冷启动仅展示本人保存的快照，实际播放器仍为空，不自动建队列或播放。`restore` 将原队列、位置、模式载入暂停/idle 的播放器；不 prepare，不恢复任何替代来源票据或音频地址。点击播放时从原平台重新检查，再按既有换源流程处理。原曲不可播后换源仍沿用现有从头准备行为，不承诺替代录音在同一时间点无缝续播。待验证身份可看缓存，不能恢复播放。

服务在事件后约 200ms 保存，在播放中每约 15s 保存位置，暂停/停止/替换/服务销毁会保存；强杀最多丢失最近一次成功保存以后的进度。系统停止和停止并清空当前播放器保留上次快照。播放结束保存位置 0，下一次显式恢复从该项开头开始。快照只是可恢复数据，UI 不把它当作实际播放状态。

每平台只保留当前账号的最后一份队列与最多 100 条最近播放，单文件总预算 8MiB、最多 20,000 首、时长/位置不超过 24h；文字/图片字段有界且图片重新过滤。超界或写入失败保留原文件并显示 `storage`，损坏/未知格式作为无可用快照处理。平台账号切换/断开清除其 journal，复用原有账号清理串行执行器，旧 owner/generation/验证状态的回调不能覆盖新状态。本轮不提供历史跨设备同步、离线音频或任意编辑队列。

## 最近播放

`RecentPlay(original, source, playedAtEpochMs)` 同时保留原队列歌曲与实际来源歌曲，归属于原队列平台的本人账号。只在 ExoPlayer 实际进入 `isPlaying=true` 后记录，搜索、取流、缓冲、仅载入恢复队列不算播放。按 `original.key` 去重，最新在前、最多 100 条；同一歌曲再次播放更新记录，同一槽位换到另一实际来源后更新 source。跨平台来源不改原队列身份。播放记录不证明用户听到了声音。

列表播放沿用 `app.queuePlayback.playSong(record.original)`，会重新授权取流。清空最近播放保留保存队列及平台歌单；如清空时已有歌曲正在播放，该次既有记录不会在每次进度保存时重新加入，之后新的播放事件仍可记录。错误字段限 `storage/account_changed/account_unavailable/player` 等固定类别，不透传平台响应。

## 歌词

```kotlin
val lyrics = app.lyrics.state // StateFlow<LyricsState>，自动跟随当前播放
app.lyrics.refresh()         // 用户显式重试
```

`LyricsState` 提供 `sourceKey`、`status`（IDLE/LOADING/TIMED/PLAIN/EMPTY/ERROR）、`lines: List<LyricLine(timeMs,text)>`、`plainText`、`activeIndex`（无当前行时 -1）、`error`。`PlaybackState.sourceKey` 是实际播放来源的公开歌曲 key；原 `mediaId` 和 `queuePlatform` 仍指原队列。歌词按 sourceKey 请求，换源候选搜索/选择期间清掉旧歌词；源/原队列/任一相关账号代数或验证状态变化均拒绝迟到请求。歌词失败不影响音频，也不会每 500ms 自动重试。

三平台仅支持标准逐行 LRC/纯文本，不包含翻译、逐字、卡拉 OK、QRC/KRC 解密。QQ 使用固定 CGI 的 `crypt=0,qrc=0` 标准路线；酷狗按实际 hash+album_audio_id 搜索，再以 `fmt=lrc` 下载并严格 Base64/UTF-8 解码；网易云使用固定 song/lyric EAPI。具体平台成功证据与兼容性限制见本轮验收文档。

三平台已实际返回标准歌词。酷狗空响应已通过改用同一官方 HTTPS 主机的 `/search` 修复，保留实际来源hash+album_audio_id查询及 `fmt=lrc` 下载；三首真机63/46/53行及seek同步通过。空响应仍显示ERROR/protocol，不将它当作EMPTY/无歌词，也不阻塞音频。证据与范围见 [P1数据验收](p1-data-acceptance.md)。

解析限制 512KiB UTF-8、10,000 输入行、20,000 时间行、每行 4,096 字符、时间不超过 24h；支持多时间戳、百分/十分/毫秒、最后合法全局 offset、稳定排序。`activeIndex` 为最后一条 `timeMs <= positionMs` 的行，随播放器约 500ms 进度、seek 和切歌更新，暂停保留当前位置。UI 用该字段滚动/高亮，不另设计时器推演音频。只在进程内缓存最多 8 份有界解析结果，账号失效淘汰，不写歌词文本到磁盘或诊断。

## P1 数据调试入口

debug 固定 `p1data_snapshot/refresh`、`p1data_restore_netease/qq/kugou`、`p1data_lyrics_refresh`、`p1data_seek_30/5`、`p1data_probe_lyrics`（仅读当前搜索首项歌词，不启动音频）。诊断文件 `p1-data-validation.xml` 仅含计数、时间/位置、枚举及内存比较布尔值，不保存歌曲 key、歌词、账号或媒体地址。与既有 P0/模式入口一样受 DUMP 权限保护，只存在 debug 源集。
