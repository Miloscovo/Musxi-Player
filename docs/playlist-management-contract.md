# 歌单管理后端合同

更新：2026-11-13。用户已确认歌曲红心、普通歌单内歌曲增删、整张歌单收藏和曲库页新建／删除／改名／移动排序都属于目标；操作只在所属平台内执行，排序只在 Musxi 保存。CC负责正式页面；本轮暂不连接手机。此合同描述已实现接口和明确缺口，不代表新能力已经通过实机验收。

曲库管理新增 `app.playlists.catalog(platform)`、`managementInfo`、`deletePlaylist`、`busyPlatforms` 以及目录的 `move/moveToStart/resetOrder`。完整状态、revision、分页合并、持久化与删除结果语义见[曲库管理交付](backend-playlist-library-management-20261113.md)。三平台生产 `delete=true`；酷狗在[单次删除实样](backend-kugou-playlist-delete-probe-20261113.md)后已接入[正式后端](backend-kugou-playlist-delete-20261113.md)，只允许新鲜本人列表明确 `is_edit=1,is_def=0` 的普通自建歌单，空／非空均可且不要求改名元数据。喜欢／系统／收藏不作为删单目标。`Result.success` 不代表删单成功，须检查 `PlaylistDeletionResult.status/confirmedAbsent`。删除写后统一完整回读，丢响应不重试。

## 语义与权限

- 红心是歌曲在原平台“我喜欢”中的归属；普通歌单中的同一首歌独立管理。取消普通歌单中的歌曲，不自动取消红心。
- 收藏整张他人歌单是订阅关系，不授予增删歌曲或改名权限。自建歌单、我喜欢、收藏歌单必须分别处理。
- 所有目标携带当前账号的 `accountGeneration`；每次实际写入重新检查当前身份和服务端权限。没有固定测试账号或普通歌单 ID。QQ 的 `0_201` 是协议定义的本人红心别名。
- 写入始终使用调用者传入的原曲 `SongSummary.key`，不会因为播放换源而自动改变平台。跨平台目标整批拒绝。
- 三平台整张普通自建歌单删除已接入；菜单权限读 `managementInfo.canDelete`，不能用改名权限代替。酷狗普通行的缺失／未知系统标志按无删除权限处理。封面、简介、标签、协作管理不在已实现范围内。

## 入口

均通过 `app.playlists` 调用，命令和回调在主线程，网络由账号 worker 执行；不向页面提供 Cookie、Session 或原始响应。

| 接口 | 行为 |
| --- | --- |
| `listWritable(platform, callback)` | 从服务端读取当前账号可写歌单，包含“我喜欢”；返回 `EditablePlaylist`。 |
| `playlistSongIds(target, callback)` | 返回该歌单完整有序 `MediaKey`；不以缓存页或单页缺失证明未收录。最多 20,000 首，截断、重复或分页总数漂移报错。 |
| `membershipFor(songKey, callback)` | 读取歌曲在全部可写歌单中的归属，返回 `SongPlaylistMembership`；任一读取失败不返回半份快照。最多 500 个目标。 |
| `updateMembership(song, snapshot, desired, callback)` | `desired: Map<MediaKey, Boolean>` 只指定需要设置或保留的歌单；未出现的歌单绝不移除。返回逐目标结果。 |
| `addToPlaylist(song, target, callback)` | 单目标添加；已存在时幂等跳过。写后核对才返回成功。 |
| `removeFromPlaylist(song, target, callback)` | 单目标移除；已不存在时跳过。红心通过现有 FavoritesBrowser 同步，普通歌单经各平台所有权检查。 |
| `setSongsPresent(target, songs, present, callback)` | 同平台单歌单批量增删，最多 100 首；拒绝重复歌曲，逐曲确认并返回结果。 |
| `capabilities(platform)` | 当前协议实现的创建、改名、整单收藏能力；不代表所有账号具有相同权限或已获实机验证。 |
| `createPlaylist(platform, name, callback)` | 已接入平台创建空的普通歌单；根据服务器返回 ID 读回，不按重名标题猜测。 |
| `renamePlaylist(target, name, callback)` | 仅已接入平台的本人普通歌单；拒绝我喜欢和非本人目标，写后读回标题。 |
| `createPlaylistAndAdd(song, name, callback)` | 返回新歌单和独立的歌曲添加结果；添加失败不会隐藏已经创建的歌单。重试添加时沿用返回目标，不能重新创建。 |
| `playlistSubscription(key, callback)` | 新鲜读取整张他人普通歌单的订阅关系，返回 `PlaylistSubscription`；包含当前账号代次，读取失败不视为未收藏。 |
| `setPlaylistFollowed(snapshot, followed, callback)` | 明确设置或保留整单订阅状态；写前读取、最多一次写入、写后回读，返回 `PlaylistFollowResult`。 |

名称为非空、无控制字符、最多 80 个 Unicode code point 的本地请求边界；服务器可能拒绝或调整名称。QQ 重名可能由服务端追加时间戳，使用返回歌单实际标题。

QQ 普通改名另按官方编辑页限制为去首尾空格后最多 20 个 UTF-16 code unit（不是 20 个 Unicode code point）。写后标题与非标题字段严格相等才确认成功；服务端归一化或改写也可能返回 `not_confirmed`，需刷新后核对，不能据此自动重试。

```kotlin
app.playlists.membershipFor(song.key) { loaded ->
    loaded.onSuccess { snapshot ->
        // 两个 key 均来自 snapshot.entries；其余歌单不改变。
        app.playlists.updateMembership(song, snapshot,
            mapOf(commuteKey to false, sleepKey to true)) { response ->
            response.onSuccess { batch ->
                // 必须检查 batch.successful 与每个 results[].status。
                // Result.success 只表示整个协调过程完成，并非每个歌单都成功。
            }
        }
    }
}
```

## 写入与失败

每个目标先回读当前归属，仅在需要改变时提交一次，再回读确认。未改变的复选框表示保留意图：如果它已被其他客户端修改，返回 `CONFLICT`，不会把服务器状态强行恢复为旧快照。已改变的复选框表示明确设置目标状态。读取不是跨歌单的服务器事务，允许其他客户端同时编辑。

| `PlaylistChangeStatus` | 含义与后续 |
| --- | --- |
| `APPLIED` | 写后回读确认目标状态；请求报错但回读已实现目标时也算确认成功。 |
| `UNCHANGED` | 提交时服务器已是目标状态，未再次写入。 |
| `CONFLICT` | 保留意图与其他客户端的新状态冲突；刷新后重新决定。 |
| `FAILED` | 写前读取或权限失败，或写后回读确认未达到目标；检查固定 `error` 分类。 |
| `UNKNOWN` | 已尝试写入，但断网、账号变化等导致无法核实结果；先重新读取，不能直接当作未写入，也不能自动反向补偿。 |

`confirmedPresent` 仅在实际读回后有值。多目标不承诺事务或自动回滚；保留成功项，只对失败/未知项重新读取后提交。协调器同平台同一时刻只处理一个歌曲归属提交，重叠提交返回 `busy`；批量歌曲按序处理，各曲间不是事务。没有跨进程离线写队列。

创建、改名同样属于远端写入：网络错误可能发生在服务器已接受之后。创建结果不确定时先刷新歌单列表人工核对，不能按名称自动重试创建。账号变化时拒绝旧结果；成功写入或结果不确定都会让当前账号的曲库缓存失效。

整单订阅与单曲归属使用独立协调器。`PlaylistSubscription` 只接受平台已核实为非本人、非系统的普通他人歌单；`COLLECTED` 表示此关系的类型，`followed` 才表示当前是否已收藏，未收藏的目标同样可以读取。`setPlaylistFollowed` 的状态与上表相同，但字段使用 `requestedFollowed` / `confirmedFollowed`。写后即使请求报错也回读，账号中途变化或无法核实返回 `UNKNOWN`；保留意图与远端变化冲突返回 `CONFLICT`。前置拒绝仍通过 `Result.failure` 返回。不能仅检查外层 `Result.success`，也不能自动重复订阅。

## 协议与剩余工作

普通歌单增删已接入酷狗、QQ、网易云。移除依据分别为固定 KuGouMusicApi `playlist_tracks_del.js` 的真实 `fileid`、QQMusicApi 0.8.1 `DelSonglist` 的数值 `songId/songType`、网易云 `playlist_tracks.js` 的 `op=del`。不能把三平台歌曲标识混用。

创建、改名、整单收藏以 `capabilities` 和下列边界为准。11-13已完成[QQ／网易云生产创建与删除实机闭环](backend-playlist-lifecycle-device-20261113.md)：各1次创建／删除，按精确返回ID核实空歌单，完整列表分别3→4→3、2→3→2，原行指纹一致。前述一次QQ旧创建仍不确定，历史marker原样保留，不与本轮新样本混为对账成功。

- QQ：创建和整单收藏/取消收藏已接入；订阅依据官方公开网页的 `PlaylistFavWrite.FavPlaylist/CancelFavPlaylist`，独立核实当前账号、public tid 和完整收藏列表。普通目录使用服务端完整 `tid_dirId`，不能固定为 `_0`；201..209 不作为普通歌曲写目标。公开他人歌单数值作者为 0 时允许经过边界检查的 encrypted 身份，双回显必须一致且与本人不同。整单订阅与双歌单归属已有实机往返；11-13当前生产创建及删单实样均成功，旧创建请求仍不确定，不能重试或按名称关联。普通改名已接入官方完整编辑形状 `mask=95`，要求本人目录和详情身份一致，简介、两张封面、标签、可见性全部明确读到后原样回填并写后核对；不推导单字段掩码、不补空值，目录 0 及系统目录拒绝改名。能力提示 `partial_support`；当前账号空/4 首普通歌单改名恢复已通过，复杂非空元数据与其他账号仍待验，并发编辑可能覆盖写前元数据，详见 [协议接续](backend-qq-playlist-rename-20261106.md) 与 [实机记录](backend-qq-rename-device-20261106.md)。
- 网易云：普通创建、改名及整单订阅状态读取已接入。此前匿名 v3 令牌替代被真实平台以 `risk` 拒绝；固定上游强制 SDK v2，本轮已移除默认 v3 获取器。当前 `follow=false, reason=verification_required`，缺少兼容 v2 提供者时订阅/取消订阅在写前拒绝；读取与不变意图仍可正常完成，失败不切换旧 WEAPI、不重试写入。内部测试接入点保留 EAPI 正文 `checkToken` 与 header/Cookie 的新鲜 `X-antiCheatToken` 分离，不代表默认生产已有令牌能力。普通歌单增删与改名恢复已有前轮实机证据，11-13创建及整单删除实样亦已通过；订阅缺口仍见 [验证路径接续](backend-playlist-verification-20261106.md)。
- 酷狗：普通创建已接入；11-13经用户授权的一次空创建确认`data.info.listid`对应新鲜本人可写列表中的新普通空歌单，生产只按此响应ID读回核对，不按同名或前后差集推断，不重发创建。`create=true, follow=false, reason=partial_support`；最终生产“新建并加入”仍待用户实测，见[本轮交付](backend-kugou-playlist-create-20261113.md)。本人普通歌单改名已接入较新固定来源的 `/v1/modify_list` 专用加密；拒绝 `is_def=1` 系统默认歌单、我喜欢与非本人目标，仅当服务端完整提供 `total_ver/sort/tags/intro` 时允许写入，保留非目标元数据并回读核对，缺失则拒绝。普通自建歌单 500 首改名恢复已通过；默认歌单可增删歌曲不代表可改名。整单收藏/取消收藏仍缺来源身份及订阅往返核实；普通新建的实样不代替订阅能力。

固定来源与本次新增酷狗提交、QQ 官方脚本依据见 [THIRD_PARTY_NOTICES](../THIRD_PARTY_NOTICES.md)。`capabilities` 表示存在可调用的实现，`reason` 保留未验证或部分缺口提示，不等同于真实平台已接受；已确认缺少默认验证提供者的网易云整单订阅为 `follow=false`。PC 的能力不能视为三平台 Android 协议已经可用。

## 验证

核心测试覆盖跨平台/代次拒绝、保留与远端冲突、重复提交、部分失败、写后未达目标、响应丢失回读、核实失败未知状态、同平台并发和账号中途变化。平台 fixture 检查端点、权限、载荷及完整分页；这些不证明真实平台接受写入。

`PlaylistMembershipDeviceTest` 必须明确选择类并带 `allowPlatformWrites=true`。它仅向现有可写普通歌单临时加入一首原先不存在的歌，再移除；写前保存新的恢复记录，收尾核对完整顺序和红心列表未改变。中断后在同账号、明确开启写入的后续运行中先恢复；不会使用 11-03 的历史基线覆盖现有数据。没有可写普通歌单时用例跳过，不算通过。不要执行整个历史 androidTest 套件。

手机准备完成后的新增证据见 [追加实机验收](backend-playlist-device-20261106.md)，覆盖前一轮网易云跳过、QQ 登录超时及改名/订阅全部待验的描述。新增 rename/subscription/multi/create 专项均需额外授权参数，写前保存实时基线，创建不确定不得重复请求。最终无歌曲/名称/订阅待恢复记录，另有 QQ 创建不确定 marker。此前三平台红心验收属于 [11-03 开发包](backend-v02-device-acceptance-20261103.md)。最新本地 JVM/Lint/包编译结果以统一验证生成的独立 `summary.json` 为准；本轮不会推送或发布。
