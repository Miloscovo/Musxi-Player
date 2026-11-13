# 给 CC：歌单管理前端首版

2026-11-06 用户已允许前后端共同推进，覆盖此前“前端暂缓”的排期。主开发目录 `C:\text\Musxi-Player安卓`。Codex 负责后端协议、核心模型、accounts、专项设备测试与本轮后端证据；CC 负责下列正式 UI。不要恢复或覆盖其他未提交改动。

## 首版交付

围绕“管理这首歌所在的歌单”完成一个可使用的闭环，沿用现有视觉与交互规范：

1. 把现有“加入歌单”的单选即加升级为普通歌单多选归属面板。进入读取真实归属，已收录项打勾，允许勾选添加、取消勾选移除，底部保存。红心继续独立操作，“我喜欢”不混入普通歌单复选列表。
2. 搜索歌曲、播放器当前歌曲、歌单内曲目复用同一套面板。歌单内曲目目前缺少歌曲更多菜单，需要补入口；能取消当前普通歌单的勾选并保存。
3. 做好加载、空列表、读取失败、提交中防双击、逐目标结果与刷新核对；部分成功时保留成功结果，不把整批都显示成功或失败。离开页面后忽略迟到的加载回调；账号变化后废弃旧快照。

首版不扩展新建、改名、整张歌单收藏或多首歌曲批量管理 UI。前述能力后续按后端平台边界逐步接入；不为首版设计不可用的按钮。

## 接口与提交方式

从 `app.playlists` 调用，详细合同为 [歌单管理后端合同](playlist-management-contract.md)。页面不持有 Session、Cookie、私有响应或音频地址。

```kotlin
app.playlists.membershipFor(song.key) { loaded ->
    loaded.onSuccess { snapshot ->
        // 展示 snapshot.entries.filterNot { it.target.favorite }。
        // 保存原 snapshot；desired 只包含用户实际改变的普通歌单。
        val desired: Map<MediaKey, Boolean> = /* changed checkboxes */
        app.playlists.updateMembership(song, snapshot, desired) { response ->
            // 外层 Result.success 只表示协调完成；检查每个 results[].status。
        }
    }
}
```

以上是流程示意，`desired` 的占位需由 UI 实现，不直接复制为可编译源码。

- 使用原曲 `song.key` 的平台；播放器换源后不能改用实际音源的歌曲身份。只列原曲同平台目标，不构造跨平台写入。
- 原 `SongPlaylistMembership`、目标 `EditablePlaylist` 及 `accountGeneration` 必须保留；账号改变则刷新重读，不用旧快照重试。
- `desired` 未出现的歌单不会改变。首版只提交用户实际改变的普通歌单；保存后若还有操作应重新读取，不能把旧快照继续当作当前状态。不要循环 `addToPlaylist` 代替多选协调器。
- `membershipFor` 已含完整目标列表读取，不先重复调用 `listWritable` 拼出另一份列表。`desired.keys` 必须是同平台 `snapshot.entries` 的子集；`results` 只对应本次提交目标，没提交的行不因缺少结果而显示失败。
- `APPLIED` / `UNCHANGED` 表示该项已确认；`CONFLICT` 刷新后让用户决定；`FAILED` 展示可理解原因；`UNKNOWN` 提示“结果未确认，请刷新核对”，先重读后决定，不自动重放或反向补偿。
- `busy` 防重叠提交；`account_changed` 清掉旧状态；读取失败不视为全部未收录。确认前不要用乐观勾选宣称远端成功。
- `busy` / `account_changed` 属于外层 `Result.failure` 的错误分类，不是 `PlaylistChangeStatus` 枚举。
- 保存后刷新当前歌单内容和计数，避免删除成功后仍显示旧行；不能重建播放队列或改变播放状态来实现刷新。
- 普通歌单移除不自动取消红心；现有 FavoritesBrowser 与红心交互继续使用。

## 已定位文件

| 文件（`app/src/main/kotlin/tech/musxi/player/`） | 当前作用与首版改造 |
| --- | --- |
| `ui/components/SongActionsMenu.kt` | `SongActionsMenu` / `PlaylistPickerPage` 当前单选即加；抽出共享多选面板与状态，保留 favorite 过滤。面板可沿用现有 Sheet，避免长列表与逐项结果挤在小浮卡中。 |
| `ui/MainShell.kt` | 持有 `app.playlists`，目前 `addSongToPlaylist` 接 `addToPlaylist`；在这里接入 membership 加载/提交与刷新回调。 |
| `ui/search/SearchScreen.kt` | 已有歌曲更多菜单，接入共享管理面板。 |
| `ui/library/TrackListContent.kt` | 曲目行缺少更多菜单，新增与搜索一致的操作入口。 |
| `ui/player/PlayerMenus.kt` | 播放器 `MoreMenu` 也复用旧 `PlaylistPickerPage`，同步更新，避免保留另一套单选即加行为。 |
| `ui/library/LibraryViewModel.kt` | 列表刷新与当前页面状态参考；membership 状态可独立持有，避免把账号写入流程塞入列表网格状态。 |

可以在 UI 目录新增共享状态/面板和必要的 UI 单元测试；不用新增生产依赖来完成此首版。需要后端改接口时在交付说明中列出，交 Codex 处理。

同步维护受影响的既有测试：`app/src/test/kotlin/tech/musxi/player/ui/components/SongActionsMenuTest.kt`、`ui/player/MoreMenuTest.kt`、`ui/design/TrackListSheetTest.kt`（后二者同属 `app/src/test/kotlin/tech/musxi/player/`）。

## 当前平台范围

三平台普通歌单歌曲归属增删均有设备样本。QQ 多歌单分别删除/保留已有样本；酷狗/网易云其他多目标及其他账号仍需增补。

- QQ：创建、改名、整单收藏已有协议实现。创建历史请求结果不确定，不能因补验自动创建；改名已有当前账号空/4 首普通歌单往返证据，能力提示 `partial_support`，其他账号及复杂元数据仍待验，见 [实机记录](backend-qq-rename-device-20261106.md)。首版 UI 不接这些入口。
- 网易云：普通创建/改名有实现；整单订阅 `follow=false, reason=verification_required`，状态读取仍可用，不能做可点击的订阅写入口。
- 酷狗：普通创建/改名有实现；11-13一次空创建探针已确认响应ID，`create=true`，现有能力接线即可启用；最终“新建并加入”仍待验，见[新交付](backend-kugou-playlist-create-20261113.md)。整单订阅不开放。缺省歌单可增删歌曲不代表可改名。

上述范围随主线程交付更新，产品不展示内部 reason 字符串。完整能力看接口合同，日期证据只覆盖当次账号/包/场景。

## 并行边界与验收

CC 只修改正式 UI 与其必要测试，不修改 `core/`、`accounts/`、`platform-*/`、`playback/`、`MusxiApplication.kt`、`app/src/androidTest/` 及本轮后端合同/证据。不要清除数据、退出账号、创建测试歌单，或自行安装/操作主线程正在验收的手机。

所有构建走 `build.ps1` 的共享锁；不要直接并发 Gradle。最终 `scripts/verify.ps1` 要在双方停止编辑后执行，核对同一源码清单，不在对方冻结验证期间编辑文件。完成前可先用已有单包/测试包检查反馈，设备联调由主线程集中执行。

首版验收：已有收录状态显示正确；一个目标移除、另一个保留/添加各自确认；红心不随普通移除变化；部分失败/未知有清晰反馈；重复点击不会重复提交；账号变化后的旧面板不能写入；保存后当前歌单列表与计数更新；搜索、播放器、歌单曲目入口行为一致。
