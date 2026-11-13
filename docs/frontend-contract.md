# 前端接入约定

> **状态：现行接入总览，2026-11-13 更新。** 本文只维护应用入口、接口边界和专门合同；后端/界面/实机完成度统一见[能力状态](feature-status.md)，当前任务和基线见[开发接续](next-session-handoff.md)。原总览的早期阶段描述保存在[历史总览](history/frontend-contract-through-20261028.md)。

## 接口边界

UI 观察公开 StateFlow，通过命令和结果回调发起操作；不持有 Session、Cookie、原始平台响应或带签名的音频 URL。`MediaKey(platform,id)` 表示身份，不按歌名或数字 ID 跨平台合并。账号代次、请求修订和队列 revision 在交付前再次校验，迟到结果不能覆盖用户的新选择。

`MusxiApplication` 提供以下入口，装配源码为 `app/src/main/kotlin/tech/musxi/player/MusxiApplication.kt`：

| 能力 | 入口 | 详细约定 |
| --- | --- | --- |
| 账号、头像、会员与登录 | `accounts` | 账号状态与错误；资料刷新不改变同一账号代次；会员不代表逐曲版权 |
| 曲库、分页与缓存 | `libraryBrowser` / `qqLibraryBrowser` / `kugouLibraryBrowser` | [数据增量](frontend-backend-contract-p1.md)，刷新失败保留已有内容；[主动补全歌单缓存](backend-requests-20261111-3.md#交付说明)的 `fillPlaylist/fillState/cancelFill` |
| 搜索与实体歌曲 | `searchBrowser` / `catalog` | [第四批合同](backend-batch4-contract.md)，实体 ID 缺失不猜测 |
| 歌曲推荐 | `recommendations` | [推荐合同](frontend-backend-contract-recommendations.md)，网易云/酷狗每日歌曲，QQ猜你喜欢；按账号缓存、取消与revision点播 |
| 红心与歌单管理 | `favorites` / `playlists`，`playlists.catalog(platform)` | [P2 合同](frontend-backend-contract-p2.md)、[歌单管理](playlist-management-contract.md)、[曲库管理](backend-playlist-library-management-20261113.md)，统一列表／本地排序、新建／改名／删单及逐项确认，平台能力分别读取 |
| 播放、队列和恢复日志 | `playback` / `queuePlayback` / `playbackJournal` | [P1](frontend-backend-contract-p1.md)、[P2](frontend-backend-contract-p2.md)，恢复保持暂停 |
| 换源 | `sourceSelection` | [匹配合同](frontend-backend-contract-b4.md)、[第四批增量](backend-batch4-contract.md) |
| 歌词与手动匹配 | `lyrics` / `lyricsMatch` | [双语合同](frontend-backend-contract-lyrics-translation.md)，歌词跟随实际音源 |
| 锁屏歌词 C | `lockScreenLyrics` / `lockScreenLyricsContent` | [模式、权限与宿主合同](frontend-backend-contract-lockscreen-lyrics.md)，正式前端待接 |
| KRC/QRC/YRC 字词 | `lyrics` 的统一字词时间轴 | [歌词合同](frontend-backend-contract-lyrics-translation.md)，后端三平台已接，逐字渲染与听音对齐待前端 |
| 本地音频库与播放 | `localAudio` / `localAudioPlayback`、`playback.localQueueState` | [本地音频合同](frontend-backend-contract-local-audio.md)，导入/检索筛选/批量整理/目录刷新/主动来源检查、原始音频导出与整理备份恢复、同一服务和暂停恢复；正式页面待接 |
| 播放页/界面壁纸 | `wallpaper` / `interfaceWallpaper` | [壁纸合同](frontend-backend-contract-wallpaper.md)，独立设置/草稿，共用图片池，chooseSaved复用；正式背景层及页面范围由CC接线 |
| 定时停止 | `playback.sleepTimer` 与服务命令 | [定时合同](frontend-backend-contract-sleep-timer.md)，分钟/当前曲末/分钟后曲末；新增模式菜单待接 |
| 音质 | 各平台 `qualities` 与播放音质命令 | [音质合同](frontend-backend-contract-quality.md) |
| 设置、缓存和更新 | `settings` / `settingsRepository` / `cache` / `updates` | [第四批](backend-batch4-contract.md)、[更新合同](android-app-updates.md) |

## 必须保持的行为

- 队列点击使用 `jumpToQueueIndex` 和 `expectedRevision`，不重建整个队列。拖动固定开始 revision，外部变化取消手势。
- 自动换源先尝试原平台；仅在合适的单曲失败分类下搜索替代。标题、完整歌手、版本、时长保守匹配，严格合格后立即准备，不恢复历史五秒倒计时。网络/账号/风控失败不能泛化成无版权。
- 原曲与实际音源分开呈现；选中候选与播放器实际进入播放是不同事件，成功提示等待 `playbackConfirmed`。平台签名地址不落盘，本地文档 URI 仅留后端授权存储，不进入 UI/控制器。
- 大歌单支持首批先播、后台补队列；音质和系统输出已有接口，不能沿用“整表等待、只标准档、输出暂缓”的旧描述。
- 缓存清理只触及封面、歌词和曲库元数据（含推荐快照），不清账号、设置、手动歌词绑定、播放日志、本地音频/书签或受管壁纸。缓存歌单搜索必须明确已加载范围。
- 本地与平台身份、队列状态和专属操作分开；本地不伪造平台账号，不自动联网匹配同名音频；恢复先保持暂停，不自动请求焦点或播放。
- QQ 和酷狗网页实验没有接入正式登录；网易网页登录已有正式入口，但完整真实登录验收仍待补。

## 合同优先级与验证

同一行为有多份历史描述时，先读本总览，再读对应能力的最新专门合同；第四批换源/设置/会员/缓存增量、更新合同及第五批交付覆盖其早期阶段备注。仍有冲突时核对当前实现并补齐合同，不按旧段落或旧包测试数猜测。

接口存在不代表 UI 已接完或所有账号已验收。完整自动化入口为 [验证工作流](validation-workflow.md)；真实设备与听音范围按对应包记录，不用夹具或旧证据替代。
