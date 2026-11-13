# 曲库歌单管理后端交付

2026-11-13。接续“Musxi 安卓开发261110”：用户确认排序只在 Musxi 记住，暂不连手机。范围为曲库页直接新建、改名、删除本人普通歌单及本地移动排序；CC 继续负责正式页面，不提交、推送或发布。

## 接口与接线

统一入口仍是 `app.playlists`。以下入口与回调在主线程调用，平台请求和磁盘存储由后端派发；页面不处理凭证、响应或存储文件。

| 操作 | 入口及语义 |
| --- | --- |
| 曲库列表 | `val catalog = app.playlists.catalog(platform)`；收集 `catalog.state`，显示 `items`，进入 `open()`、主动刷新 `refresh()`、分页 `loadMore()`、离页 `cancel()`。不要再把旧 browser 的页累积到这份列表 |
| 新建空歌单 | `createPlaylist(platform, name, callback)`；与“新建并加入”共用协议，返回已核实的 `EditablePlaylist`，不要求先选歌曲 |
| 行权限 | `managementInfo(key, callback)`；返回 `PlaylistManagementInfo(target, canRename, canDelete, canUnfollow, reason)`。这是当前账号新鲜列表的菜单提示；执行写入仍重新核实权限 |
| 改名 | `renamePlaylist(target, name, callback)`；原有平台限制和元数据保留语义继续有效，成功和不确定响应均失效曲库／详情缓存，不改变按 ID 保存的排序 |
| 删除整单 | `deletePlaylist(target, callback)`；必须检查返回值内的 `status/confirmedAbsent/error`，不能只检查外层 Result |
| 移到前面 | `catalog.moveToStart(key, expectedRevision, callback)`；“我喜欢”保持固定在最前，普通歌单移到普通区域最前 |
| 拖动／移到最后 | `catalog.move(key, before, expectedRevision, callback)`；放在指定已加载行之前；`before=null` 表示末尾，要求 `state.complete=true` |
| 恢复默认 | `catalog.resetOrder(expectedRevision, callback)`；删除自定义排序，按平台列表顺序显示 |
| 忙碌状态 | `app.playlists.busyPlatforms`；同一平台新建／改名／删单／归属写入／整单订阅互斥，忙时返回 `busy`。`catalog.state.savingOrder` 单独表示本地顺序保存 |

拖动开始固定 `state.revision`，外部刷新／切号／加载更多／保存结果改变 revision 后取消手势，提交过期返回 `conflict`。不要给旧手势替换新 revision 自动重试。`invalid_request` 为目标／锚点非法，`incomplete` 为未加载完整就移动到末尾，`storage` 为持久化不可用；失败不提前发布新顺序。账号变化返回 `account_changed`。

## 分页与持久化

后端拥有原始分页链和合并后的显示列表；显示排序不改变服务端 offset。校验页内／跨页重复 ID、总数、前缀变化及续页位置，部分分页不清理尚未加载 ID 的排序记录；刷新保留本地排序，服务器新增而未排过序的普通条目按返回顺序跟在已有排序之后。已确认的新建歌单写入普通区域最前，远端事件遇到本地保存会排队，不丢成 `busy`。已确认删除移除对应排序记录；其他保留记录不按不完整分页猜测删除。

`FilePlaylistOrderStore` 按平台独立目录、账号 owner 摘要独立文件保存在应用 files 下，独立于曲库／歌词／推荐缓存。重启或同账号重新登录后恢复，切换账号不复用上一账号结果，清普通缓存和退出平台账号不删排序。受损文件可观察为 `storageError`，不当作合法空设置覆写；原子写入失败保持旧文件。没有新增运行时依赖或业务服务器，也没有向官方客户端同步排序。

## 删除协议与确认

下表保留本轮首次交付状态；同日后续酷狗本人非空歌单[一次实机删除成功](backend-kugou-playlist-delete-probe-20261113.md)，随后按用户授权[接入正式后端](backend-kugou-playlist-delete-20261113.md)，生产已开放 `delete=true`。当前能力以[歌单合同](playlist-management-contract.md)为准。

| 平台 | 本轮能力 | 协议依据和边界 |
| --- | --- | --- |
| QQ | `delete=true`，待真实平台验收 | QQMusicApi 0.8.1 固定 `3fc57f02e4eddb68a069bba4e0e12a7893f2a814` 的 `modules/songlist.py`，`PlaylistBaseWrite/DelPlaylist`，只传一个 `dirId`；不使用删除歌曲的 `DelSonglist`。完整本人目录核实精确 `tid_dirId`，拒绝目录0、201～209、invalid、他人收藏及同dirId多tid歧义 |
| 网易云 | `delete=true`，待真实平台验收 | 固定 MIT 来源 `2aab9957dfd5231e5b192aecdb177f93ace4c92e` 的 `module/playlist_delete.js`，WEAPI `/weapi/playlist/remove`，`ids` 为单ID JSON数组字符串；该模块不要求订阅使用的SDK v2令牌。完整账号分页核实 creator 和 `specialType=0`，拒绝“我喜欢”及系统类型；缺少本人喜欢列表哨兵视为不完整 |
| 酷狗 | `delete=false` | 固定 `7ac64e1cdd333ad9bfff644ded82a0ffeef002d1` 的 `playlist_del.js` 注释是“取消收藏”，固定 `type=1/total_ver=0`，无法据此证实本人歌单删除。直接调用适配器也写前返回 `unsupported`，不猜参数。已有创建／改名仍可用 |

`PlaylistDeletionCoordinator` 先读完整新鲜列表，再由适配器重验权限并最多发送一次删除，随后统一再次完整读取。写响应丢失也读回，不自动重发。已不存在返回 `UNCHANGED, confirmedAbsent=true` 且不写；确认删除返回 `APPLIED, true`；仍在返回 `FAILED, false`；写后无法核实或切号返回 `UNKNOWN`。写前读失败为 `FAILED` 且 absence 未知。UI 对 UNKNOWN 提示刷新核对，不当作“尚未删除”。前置权限、能力、代次或互斥拒绝使用外层 `Result.failure`。

确认不存在后失效相关列表与详情、移除本地顺序记录，并取消该歌单尚未接受的播放／补队列工作；不暂停或替换已经接受的队列，不操作红心或其他歌单。用户离页只取消展示列表的等待，不撤销已发出的管理操作。

## 验证与保留材料

最终主目录官方 `scripts/verify.ps1` **2,736项通过，0失败／错误／跳过，Lint0错误／139警告**；debug主包／设备测试包编译与928条源码清单一致性检查通过。统一证据 `.local/artifacts/verification/20261113T053202355Z-23928/`，索引 `.local/artifacts/playlist-library-management-20261113/final-summary.json`。新增8个专项类共75项，覆盖完整分页与权限、单次写／丢响应、账号切换与旧账号可见页面守卫、并发、实际队列补载取消而已接受播放保留、本地排序／刷新／页链冲突、服务器事件排队及真实文件持久化／损坏。缓存清理测试另增排序文件保留断言。网易云改名同时收紧为仅普通specialType=0，其他系统类型及缺字段均在写前拒绝。

相对原会话本任务开始时保留的baseline，共34份后端／测试／NOTICE／文档变更；260份CC前端／UI测试／设计测试路径原样保留。交付APK在共享构建锁内重新核对HEAD、Git状态、928条清单和包散列后保留为 `.local/artifacts/playlist-library-management-20261113/Musxi-playlist-management-debug.apk`，SHA-256 `99d3fbf67a846a5b4c3c7ae18647017c90ac3f0f50142697ec77421a24d245e5`。最终只回填6份文档，并另行检查链接与源码散列，不把回填当作重新构建。

较早统一验证 `20261113T051824173Z-20832` 的2,733项属于收尾修正前源码；第二次 `20261113T053043289Z-20792` 因新增测试将缺字段的错误预期误写成incomplete而失败，实际后端在已有前置检查返回protocol并拒绝写入，修正测试后完成上述最终重跑。两次历史记录单独保留，不代替最终结果。此前2,661项及实机安装属于酷狗创建轮。

本轮沿用前一会话已保留的 `.local/checkpoints/playlist-library-management-20261113/` 修改前快照和验收目录下 `baseline.json`、`reference/`。这些记录原先开发现场与固定公开来源，后续最终源码以本轮验证清单为准，不重复新建工作树或复制工程。

没有连接手机、装包或对真实平台执行写入；真实删单、平台延迟／风控、OEM表现和正式菜单／拖动验收仍待用户以后安排设备窗口。QQ旧创建不确定marker与酷狗单次探针marker继续保留，不重跑。后续设备删除仅选用户明确指定的临时本人普通歌单，不能运行历史整套写入测试。
