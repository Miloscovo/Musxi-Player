# 后端需求单 · 2026-11-11（三）：歌单内搜索补全（前端 → Codex）

> **状态：当前需求单（2026-11-11）。** 用户 11-11 要求把“歌单内搜索歌曲”提上日程。前端已用现有 `app.accounts.searchCachedPlaylist` 接上搜索框（只搜已缓存的页）。本单只补“把歌单剩下的页一次拉完”，让大歌单也能搜全。完成后由 Codex 在末尾“交付说明”填写接口与验证边界。

读者：负责 accounts / core / app 后端层的 Codex。约定同前几批：不改 `app/src/main/kotlin/tech/musxi/player/ui/`（CC 负责）。行号以 2026-11-11 工作区为准。

## 现状（已有，前端已在用）

`app.accounts.searchCachedPlaylist(playlistKey, query) { Result<CachedPlaylistSearchResult> }`：只查该账号已缓存的所有页，不联网；返回 `items / loadedCount / total / complete / loadedOffsets / oldestFetchedAtEpochMs`。界面在 `complete == false` 时显示“只在已载入的 N 首里搜索（共 M 首）”。

问题：用户一般只滑过歌单的前几页。在一个 2000 首的歌单里，搜索实际只覆盖前 100 来首，体验上像“搜不到”。

## 1 · 补全歌单缓存（P1）

需要一个“把这个歌单剩下的页按顺序全部拉进缓存”的操作，前端在用户点“载入全部再搜”时调用：

1. 接口建议：`app.library`（或现有 `LibraryBrowser` 所在处）提供
   `fillPlaylist(playlist: MediaKey): StateFlow<PlaylistFillState>` 和 `cancelFill()`。
   `PlaylistFillState(running, loadedCount, total, error, complete)`。命名可自定，交付说明写清楚。
2. 行为：
   - 从已缓存的最后一个连续页之后，按平台原有的分页顺序继续拉，写进现有缓存，和手动“加载更多”是同一套缓存，`searchCachedPlaylist` 随后能直接搜到。
   - 拉取期间与正常翻页共用节流和账号校验；**不要并发猛拉**，按平台现有的限速和退避来。账号切换、断网、平台报错时停下并给出 `error`，已拉到的页保留。
   - 可取消；离开歌单详情页时前端会调用取消。
   - 上限：单个歌单最多补到 5000 首（或平台本身的上限），超出时 `complete = false` 并在状态里说明。
3. 前端会在拉取时显示“正在载入 1200 / 2000”，每拉完一页就重新搜一次。
4. 不改变歌单详情页现有的分页、刷新、缓存过期语义。

## 2 · 搜索结果的播放（确认即可，可能不需要改动）

前端打算：在搜索结果里点一首歌时，仍以**整份歌单**作为队列，从这首开始播（调用现有 `queuePlayback.playPlaylist(playlistKey, songKey, items)`，`items` 传已载入的全部曲目，不是筛选结果）。请确认这样调用不会打乱现有的队列分页续取逻辑；如果 `items` 需要是从 offset 0 开始的连续前缀，请在交付说明写明。

## 交付说明

### 第 1 项：接口与接线

已实现，未修改 `app/src/main/kotlin/tech/musxi/player/ui/`，不提交、不推送。本轮没有安装或操作手机，手机安装窗口继续由 CC 使用。

使用歌单所属平台的现有 browser（不是 `app.library` 协议 facade）：

```kotlin
val browser = when (playlistKey.platform) {
    Platform.NETEASE -> app.libraryBrowser
    Platform.QQ -> app.qqLibraryBrowser
    Platform.KUGOU -> app.kugouLibraryBrowser
}
val progress: StateFlow<PlaylistFillState> = browser.fillPlaylist(playlistKey)
// browser.fillState 与返回值是同一个进程内 StateFlow。
browser.cancelFill() // 离开页面或用户取消；browser.cancel() 也会取消补全。
```

命令在主线程调用。每个平台同一时间补一份歌单；重复调用同一份正在补全的歌单不重启，调用另一份会替换旧任务。完成、取消或失败后再次调用，会重新读取实际缓存并续补，不从头清空。状态字段：

| 字段 | 语义 |
| --- | --- |
| `playlist` | 当前/最后操作的歌单；初始为 null |
| `running` | 正在读缓存、等限速、等网络或落盘 |
| `loadedCount` | 当前歌单所有保留页的去重曲目数，与缓存搜索一致；存在空洞时不等于连续前缀长度 |
| `total` | 缓存各页总数一致时的总数；尚无缓存或总数未知/不一致时为 null |
| `complete` | 与缓存搜索同一严格标准：offset 0 连续页链、总数一致、最后一页结束且去重数量等于总数 |
| `error` | 安全固定错误码，不含账号、路径、会话或平台原始异常；正常完成/主动取消为 null |
| `cancelled` | 主动取消或手动打开页面/翻页抢占补全时为 true；失败为 false |
| `pagesRevision` | 每次新页成功提交并读回后递增；不在重启任务时归零。变化后调用 `searchCachedPlaylist` 重搜，并核对 `playlist` 和前端自己的 query generation |
| `limit` | 5000；补全目标上限，不删除手动翻页已保留的额外曲目 |

`StateFlow` 是最新状态，慢订阅者可以合并中间快照；前端以最新 `pagesRevision` 重新搜索即可。起始读缓存会更新数量，但不递增 `pagesRevision`。`fillState` 独立于普通 `browser.state`，补全不会把详情页可见内容逐页替换成后端正在拉的尾页。

### 第 1 项：分页、取消和限制

- 从 offset 0 沿每页真实 `nextOffset` 找到首个缺页，按网易云 50 条、QQ/酷狗 30 条的原接口续取。已有空洞后的页保留；补上缺页后若能接上，会直接复用。缓存完整时不发网络请求，也不刷新时间戳。
- 所有结果写原账号、原歌单的 `LibraryPageStore`，搜索仍使用原 `accounts.searchCachedPlaylist`；没有第二套搜索数据库，也不下载音频。账号身份、代次、验证状态和任务代次在读缓存、派发请求、接收及落盘时检查。
- 本轮核实现有普通请求通道只有串行调度，没有时间间隔/退避策略。因此新增 `LibraryPageRequestGate`，在现有账号请求通道之前让同一平台 browser 的手动翻页与补全共用：上一页完成后，网易云至少 500ms、QQ 至少 750ms、酷狗至少 1000ms 再发下一页；单 browser 同时最多一个在途页。三个 browser 独立计时，底层仍共用原串行账号通道，音频通道不被延时占住。这是本地保守策略，不声称是平台官方配额。
- `rate` 立即停止当前补全，并将该 browser 后续翻页推迟至少 30 秒；`risk` 为 60 秒。没有自动重试，用户再次补全/手动翻页仍要等退避。断网/超时、账号变化和其他平台错误同样停止，保留此前落盘页。
- 取消后不再派发后续页；等待节流的请求在派发前再次检查任务。已派发的一次底层请求未强行中断连接，其迟到回调/尚未执行的落盘任务被拒绝；正在执行的原子落盘可能完成。取消不清缓存，也不退出账号。
- 开始补全会阻止原手动请求的迟到结果再落盘；新的普通 `playlists/tracks/refresh/retry` 操作取消补全并正常执行原分页。显式清缓存、收藏/歌单变更的 `invalidate` 则停止为 `cache_changed`；账号变更为 `account_changed`。不修改普通分页的 30 秒成功去重、显式刷新、旧页保留、时间戳或原失效语义。
- 不拆/截短正常页来凑整数：网易云可补到 5000；QQ/酷狗歌单总数正好 5000 时可收最后 20 条完成，总数超过 5000 时通常在 4980 的完整页边界停止。此时 `complete=false / error=fill_limit`。平台更早返回 `limit` 则按平台错误停止，不猜测为完整。
- 曲目缓存页容量由 128 扩到 167 页，足以容纳正常 30 条分页的 5000 首；歌单首页集合的 128 页限制不变。单集合 2 MiB、平台总量 16 MiB 和 128 集合的原磁盘预算保留。补全使用不淘汰本歌单旧页的追加方式；新页放不下时原集合不变，返回 `cache_limit`，不会一边拉新页一边丢已拉页。普通手动写入仍沿用原淘汰方式。

新增/特殊错误码：`account_unavailable`（无保存身份）、`account_unverified`（只能查缓存，不能联网）、`account_changed`、`storage`、`cache_changed`、`cache_limit`、`fill_limit`、`incomplete`（分页/总数矛盾）。其他固定平台码沿用原分类；未知异常使用 `unclassified`。歌曲和分页读取是逐页观察，不是服务器端事务快照；总数变化会保守停止，同数量的服务端并发重排仍需要用户刷新，不能承诺检测所有远端改动。

### 第 2 项：已确认，未修改队列

`queuePlayback.playPlaylist(playlistKey, songKey, items)` 的既有整单首批播放/后台续取可以复用。**统一约定 `items` 必须是从 offset 0 起、按歌单显示顺序、不去重的连续前缀**，不能传搜索筛选结果，也不能直接用 `searchCachedPlaylist(key, "").items` 的跨空洞/去重集合替代。

- QQ/酷狗将 `items.size` 用作后续服务端 offset，先播选中曲所在的 30 条窗口，再依次补已知前后部分和后续网络页；错误的前缀可能重复/漏歌。原 `tracks.items` 若确实来自连续翻页，可照常传。
- 网易云重新读取全量 ID 定位选中曲，50 条首批；`items` 只用于通过逐项 ID 前缀校验后的元数据，校验失败降级为待加载占位，整单身份仍以服务端 ID 为准。
- `items` 为空时走原整单枚举；QQ/酷狗选中曲不在非空前缀时也回退原整单枚举，随后从选中曲播放。跨空洞命中不应为了包含它而拼接假前缀，可以继续传真实前缀让原回退生效，或传空列表；代价是首播等待整单读取。
- 后台续页依旧保留账号、取消、`queueEpoch` 和总数/分页校验；本轮没有更改它的节流、超时和失败后保留已入队歌曲的逻辑。

依据：`NeteasePlaybackUseCase.playPlaylist/startPlaylistHead`、三平台 `queueHead` 与 `core/library/PlaylistQueueHead.kt`。本次为静态核对并回归既有队列策略测试，没有执行真实平台播放。

### 验证范围与证据

新增 JVM 回归覆盖缺页续补/复用后续缓存、逐页搜索可见性、完整/空缓存、重复启动、取消与重启的迟到回调、账号变化和初始账号通知、手动翻页/刷新/失效/清理、总数改变及不前进分页、三平台 5000 边界、容量不足保留旧页，以及共享限速/退避/单在途。真实 `FileLibraryPageStore` 检查 167 页共 5000 首 QQ 元数据的冷读/搜索完整性，并检查 2 MiB 拒写不淘汰旧页。没有新增运行时依赖。

执行入口为共享锁下的后端 core/accounts 测试与 accounts Lint，以及项目要求的 `scripts/verify.ps1`。本轮最终实际计数、通过/失败、源文件散列和统一验证目录在 `.local/artifacts/playlist-fill-20261111/final-summary.json`；原始失败尝试也独立保留，旧报告不计本轮通过。首次整包编译在 CC 正在修改的 `QueuePanel`/`scrollBy` 处失败，未代为修改前端。实机三平台大歌单、真实限流/断网、页面按钮进度与取消、搜索点播及听音均待 CC 接线后验收；本轮不占用其手机安装窗口。
