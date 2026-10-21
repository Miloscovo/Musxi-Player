# P2 收藏同步与队列编辑（2026-10-20）

P0/P1接口继续可用。本增量由后端实现，正式Compose页面接线与视觉验收另行完成。所有命令、StateFlow订阅和回调使用主线程，平台网络由账号worker执行；页面不触碰会话、票据或音频地址。

## 收藏/喜欢

`app.favorites.state: StateFlow<Map<Platform,FavoritesState>>` 独立表示三个账号在各自平台的“我喜欢”。它不跨平台复制收藏，也不自动同步到其他账号。

- `refresh(platform)` 显式从该平台读取完整收藏ID集合，最多20,000首；仅已验证账号可联网。
- `setLiked(song: SongSummary, liked: Boolean)` 设置该歌曲在其原平台的喜欢状态，不能用实际替代音源的key覆盖原曲。播放页应明确使用原曲还是实际来源，并将选定歌曲原样提交。
- `keys` 按完整 `MediaKey` 判断，`synced` 表示成功取得完整快照，成功空集合是“无收藏”；错误不等于空集合。
- `loading/pending` 表示刷新/单曲变更及核实中；同平台忙碌时拒绝新写，刷新不会取消已提交的写入。不同平台分别处理。
- `canWrite` 表示协议可提交，不保证平台必然接受。写入前要求已同步、账号已验证；相同目标状态幂等跳过。没有乐观更新，也不自动重试写请求。
- 写后重新读取完整集合，核实目标包含关系后才发布成功；失败或未核实设 `synced=false`，保留旧集合供展示。`write_unverified` 提示重新同步确认，不能据此自动补写/反转。
- 同账号刷新失败保留旧集合与上次成功时间；owner/generation/verified变化立即清除旧状态、拒绝迟到结果。收藏集合仅保留进程内存；已有歌单分页缓存仍按原规则工作。

网易云使用固定vendor的likelist/like；QQ固定本人喜欢目录201，详情取得数值songId/type后单曲写入，写入单独使用上游Android公参（ct=11），读取继续使用WEB公参；酷狗仅识别is_def=2且is_edit=1的唯一喜欢歌单，取消使用该歌单实际fileid，添加接口使用网关路径自带的路由。未开放任意平台歌单创建、删除或编辑，不提供离线写队列。平台错误统一固定分类，不透传原始响应。

收藏写入后的完整集合核实会更新 `favorites.state`；已打开的歌单分页及本地歌单缓存仍需显式刷新，不将旧缓存视为本次写入结果。服务端添加可能改变平台收藏列表排序；本接口保证喜欢状态，不提供恢复原收藏排序的功能。

## 当前队列

`app.playback.queueState: StateFlow<PlaybackQueueState>` 提供播放服务拥有的原队列 `songs` 和 `revision`。当前索引、进度、模式、实际来源仍从 `app.playback.state` 读取；前端不维护第二份权威队列。

```kotlin
val snapshot = app.playback.queueState.value
app.playback.moveQueueItem(from, to, snapshot.revision) { result -> /* 提交结果 */ }
app.playback.removeQueueItem(index, snapshot.revision) { result -> /* 提交结果 */ }
app.playback.enqueue(song, snapshot.revision, playNext = true) { result -> /* 下一首 */ }
app.playback.enqueue(song, snapshot.revision) { result -> /* 追加到末尾 */ }
app.playback.clearQueue(snapshot.revision) { result -> /* 清空 */ }
```

操作携带本次渲染的revision，服务拒绝过时的队列、非法索引或已失效账号。回调成功代表服务接受操作；前端随后观察服务发布的状态。不能收到拒绝后用旧索引自动重试。大队列通过进程内mailbox传递，Binder只传token；外部系统controller仍不能修改媒体队列。

移动的to是最终索引，不是移除前插入缝隙；相同索引是无操作。重复歌曲按队列槽位区分。移动任何项、删除其他项或插入时，当前槽位的票据、位置、暂停/播放状态与已选音源保留。删当前项选择原下一项，末项则原上一项，并暂停、从0开始；删除唯一项或清空后播放器为空。

插入只支持当前非空队列的同平台歌曲，沿用当前账号generation，最多20,000项；不接受混平台原队列。跨平台替代来源仍可用。空队列起播继续调用 `queuePlayback.playSong(song)`。随机/循环不因编辑重置，随机排列由Media3维护，不承诺编辑前后的随机遍历顺序完全相同。

编辑后的顺序/索引/位置进入P1 journal；清空/删唯一项同时清除该平台保存队列并保留recent。普通stop沿用P1语义保留最后快照，两者不同。编辑不会修改平台歌单，也不因移动/插入而伪造最近播放；recent仍只在实际isPlaying后记录。

验收记录见 [p2-acceptance.md](p2-acceptance.md)。
