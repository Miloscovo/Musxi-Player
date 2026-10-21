# 安卓前端所需后端增量（2026-10-20）

本轮根据 cc 对话「Musxi-Player 安卓前端规划20261020」补充 P0。现有账号、歌单分页、完整队列、有限恢复和跨平台选源约定继续见 [frontend-contract.md](frontend-contract.md)。这里的图片 URL 是普通公开封面，音频 URL、Cookie、会话和 capability 仍不提供给页面。

## 1. 封面与当前播放元数据

- `SongSummary` 末尾新增 `coverUrl: String? = null`，歌曲分页、搜索、单曲详情及播放取流元数据统一映射。`PlaylistSummary.coverUrl` 保留原字段，QQ 补充返回已有封面。
- `PlaybackState` 末尾新增 `album: String = ""`、`coverUrl: String? = null`、`queuePlatform: Platform? = null`、`sourcePlatform: Platform? = null`。订阅 `app.playback.state` 即可显示，前端无需从候选面板推算实际来源。
- `queuePlatform` 属于原媒体 ID；`sourcePlatform` 是服务已提交的本次取流来源。选源提交后不等于已经发声，仍需观察 `playing`、`buffering`、`failure`、`suppression`。暂停保留所选来源，导航及恢复原来源时同步更新。没有当前项时平台为 null。
- 封面、专辑、标题随同一 MediaItem 元数据交付；迟到取流元数据受原有 ticket/账号代数校验。切换来源使用候选的图片，候选无图片时回退占位，避免误留上一版本的封面。
- `ArtworkPolicy.sanitize(platform, value)` 仅保留指定官方图片域的无查询、无 fragment、无凭证 HTTPS 地址；原图片域 HTTP 可升 HTTPS。缺失或不合要求的图片为 null，不使歌曲请求或播放失败。QQ 在专辑 MID 有效时可采用公开官方图片模板，酷狗将响应里的 `{size}` 替换为固定图片尺寸。
- 歌单/歌曲分页缓存写入 v2，保留合法公开封面地址；沿用原目录、账号归属、容量、CRC 和原子更新，读取兼容 v1。旧缓存没有图片时仍能显示，联网刷新后补充。这里只存图片地址，不下载或持久化图片字节。

前端可以自行接图片加载器及失败占位、封面取色；请求不附带任何音乐账号凭证，不将图片请求交给音频解析器。现有 cc 主题、截图及 UI 文件未在本轮改写。

## 2. 用户搜索

进程唯一入口：`app.searchBrowser`，页面按生命周期观察 `state: StateFlow<SearchBrowseState>`。命令和回调在主线程执行。

```kotlin
app.searchBrowser.search(Platform.NETEASE, keywords) // 新查询或切换平台，offset 默认 0
app.searchBrowser.next()                           // 采用当前页后端 nextOffset
app.searchBrowser.refresh()                        // 刷新当前页
app.searchBrowser.cancel()                         // 页面退出或撤销查询
```

| 状态字段 | 含义 |
| --- | --- |
| `query: SearchQuery?` | platform、trim 后的 keywords、本页 offset |
| `page: Page<SongSummary>?` | 当前单页，初始/首次失败为 null；成功零结果为 items 为空 |
| `loading: Boolean` | 本次有效请求是否正在进行 |
| `error: String?` | 固定错误分类，不能展示原始异常信息 |

首版明确选择一个平台搜索，三个平台均可选；不合并三家分页，不伪造统一排名/总数。要求所选音乐账号已验证 CONNECTED。每页最多 30 项，`nextOffset` 原样透传，null 表示该平台本次查询结束，`total` 可未知。关键词 trim 后 1～120 字符，不允许控制字符。offset 以 30 递增，允许 0～19,980；达到工作上限且仍有结果时返回 `limit`，不伪装为全量结束。请求过程中不要把搜索结果存在推断成有播放权限。

`search(platform, keywords, offset)` 也可显式请求后端返回的 offset。State 只持有当前页，列表若需累计展示由 ViewModel 聚合；修改关键词、切换平台、刷新首页后丢弃旧页聚合。刷新同一页失败保留其内容，改变查询清空旧内容。搜索历史和结果不写磁盘。

取消/替换立即使旧 revision 失效，已排队旧搜索在开始网络请求前检查有效性；已经开始的 HTTP 不保证立即断开，但结果无法覆盖新页面。账号断开、重新验证或代数/身份变化会清除旧搜索结果，不自动重搜。页面提供显式重试；`refresh()` 不承担账号重验。

固定错误包括 `request`（输入不合要求）、`account_unavailable`（未连接验证）、`account_changed`（账号状态已变化）、`network`、`timeout`、`rate`、`risk`、`permission`、`protocol`、`incomplete`、`limit` 等。错误和成功空结果分开渲染，播放权限错误不一概解释为需要重新登录。

底层三个 Repository 新增 `searchPage(keywords, offset, result, isCurrent = { true })` 返回 `Result<Page<SongSummary>>`；页面优先使用上面的状态入口。内部跨平台换源原有 `search(keywords)` 首屏 10 项接口保持兼容，不受用户搜索取消影响。

## 3. 搜索结果起播

```kotlin
app.queuePlayback.playSong(songSummary)
```

将所选歌曲作为一项完整队列，绑定所属平台当前已验证账号代数；替换既有队列，取消此前尚未完成的歌单加载，等待服务接受再结束 `QueueLoadState.loading`。停止、取消、旧确认隔离沿用 `queuePlayback` 现有规则，账号未验证报告 `account_unavailable`。

它不构造假的歌单 ID，也不将当前搜索页当作全量歌单；播完这一项按既有末尾规则停止。若原来源明确不可播，继续使用既有跨平台候选流程。搜索结果的存在或封面显示成功不代表平台已发放完整音频。

## 4. 后续范围与验收

P1 的随机/循环、持久队列及暂停恢复、实际来源标准歌词、最近播放已实现，见 [P1 后端接线](frontend-backend-contract-p1.md)；P2 收藏同步与当前队列编辑见 [P2 后端接线](frontend-backend-contract-p2.md)。各阶段实际真机覆盖以对应验收文档为准。

初次实现的构建和自动回归结果见 [安卓端适配规划第 30 节](../安卓端适配规划.md)。随后已使用保留数据的开发包完成本机 P0 后端真机检查，搜索分页、公开封面、单曲播放、换源、曲尾及缓存证据和限制见 [P0 真机验收](p0-device-acceptance.md) 与规划第 31 节。听音反馈按实际包和操作记录；不以状态增长替代听音。前端截图审定及视觉方向继续由 cc 和用户决定。
