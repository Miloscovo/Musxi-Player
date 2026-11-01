# 第二批后端交付（2026-11-01）

对应 [需求单](backend-requests-20261101-2.md)，基于 `9443160` 的本地增量。后端与 CC 前端共用主目录，本轮接续保留已有改动，不修改 `app/.../ui/`。源码仍为开发中的 `0.2.0-alpha01`；本记录不代表提交、发布或新一轮真机验收。

## 已完成与接口

| 需求 | 接口及语义 |
| --- | --- |
| 失败原因 | `PlaybackState.failureReason: PlaybackFailureReason?`，包含 `reason`、`platform`；与换源失败使用同一分类。未知权限不能推断为会员；明确的试听、会员、版权与网络分别保留。 |
| 原曲/音源 | `PlaybackState.original` 是队列原曲，`actualSource` 是实际录音的公开信息，`sourcePlatform/sourceKey` 提供来源。跨平台不改原队列身份或 ticket；通知栏/系统媒体会话沿用实际录音元数据，主界面可显示原曲并附实际音源说明。 |
| 严格匹配 | 未引入 Jay/周杰伦别名。补“提速版”等版本标记与取流元数据二次核验；《晴天》历史日志缺少身份和选择模式，无法重建当次 AUTO/MANUAL，不能据此宣称已复现或确定根因。 |
| 手动歌词 | `app.lyricsMatch.follow(original)`、`search(platform, keywords, page)`、`preview(candidateKey)`、`bind(original, candidateKey, offsetMs)`、`reset(original)`。预览不落盘，绑定按原队列 MediaKey 和 owner 存储；支持 ±600 秒偏移，自动歌词继续跟随实际音源。切歌/换号使旧请求失效。 |
| 随机顺序 | `PlaybackQueueState.upcoming` 从 Media3 Timeline 的真实顺序计算，槽位索引保持原队列语义；拖动/删除仍作用于原顺序，随后重算 upcoming。单曲循环没有其他自动下一首，返回空列表。 |
| 歌单类型 | `PlaylistSummary.kind = LIKED/CREATED/COLLECTED/OTHER`；三平台映射与文件缓存兼容旧数据。 |
| 最近播放 | `playbackJournal.removeRecent(platform, key)` 单条删除并持久化，owner/账号代次隔离；上限由 100 提为 300。完整列表页是前端待办。 |
| 歌词缓存 | `FileLyricsCacheStore`，歌词 30 天/无歌词 24 小时，最多 256 条/16 MiB；过期联网刷新失败时保留旧词。包含译文，手动绑定单独持久化；登出/换号的清理与缓存写入使用同一磁盘队列。 |
| 会员 | `AccountState.membership: Membership(level, name, expiresAtEpochMs)`；`accounts.refreshMembership(platform)` 可显式重查，连接/恢复成功后自动发起。三平台固定只读查询运行于独立串行线程，不阻塞 AUDIO。失败/缺字段为 UNKNOWN，绝不使已验证登录退出；完成时检查当前 generation，旧账号结果不能写入新账号。 |
| 默认音质 | `accounts.defaultAudioQuality()` / `setDefaultAudioQuality(STANDARD/HIGH/LOSSLESS/BEST)` / `clearPreferredQuality(platform)`；已有逐平台明确选择优先，清除后采用全局默认；影响下一次取流。仅明确的权限/会员/版权/试听/不可用允许逐档回退，网络和账号错误直接返回。显式选档不静默换档。 |
| Hi-Res/授权 | 网易新增 `netease:hires`；酷狗新增 `kugou:high`。默认 `KugouLibraryApi(api)` 已接入固定官方授权链。变体必须隶属原 hash 资源，同专辑、档位 hash 唯一；拒绝显式非 full tracker、试听/片段、回显 hash 冲突和不合要求的 FLAC 头/时长。字段缺失仍按保守既有回退处理；未知枚举不假定可播放。 |
| 更新 | `app.updates.check(includePrerelease, result)`、`ignore(version)`；返回无新版/有新版/限流/失败，30 秒节流、限流退避，URL 固定本安卓仓库 Releases；不在启动时请求，不下载 APK、不申请安装权限。 |
| 封面/重复读 | 酷狗复用固定 3 线程，仅补每页前 6 项缺失封面，失败保持占位且不改顺序。同页仅记录成功联网时间，30 秒内复用；显式刷新/重试、换号、invalidate 绕过。 |

会员只描述账号权益，不能作为某首歌具有版权或可完整播放的证明。UI 仍应以本次取流的失败原因决定提示；会员为 NO 也不能把所有权限失败改称“需要会员”。

## 接续修复

- 补齐 QQ 固定 `VipLogin.VipLoginInter/vip_login_base` 的只读查询，解析以项目固定 QQMusicApi 0.8.1 commit `3fc57f02e4eddb68a069bba4e0e12a7893f2a814` 的 `modules/user.py`、`models/user.py` 为准；到期按显式字段/东八区日期处理。三平台 driver 与账号状态已接线。
- 网易明确的 `expireTime=0` 可表示没有当前权益；字段缺失仍为 UNKNOWN。
- 恢复此前从标准测试目录移出的 `KugouAuthorizedAudioTest`，修复 Android `org.json` 的 checked exception 兼容；补异专辑与 tracker 类型测试。
- `build.ps1 -Test` 加入 `:accounts:testDebugUnitTest`，避免遗漏本批音质回退与封面并发的测试。

## 验证

2026-11-01 08:01，接续后的 `build.ps1 -Test -Lint` 完整通过：**988 项 JVM 测试、0 失败/跳过，六 Android 模块 Lint 0 错误/108 警告**。统计为 core 299、app 434、accounts 5、playback 76、酷狗 90、QQ 52、网易 32；其中恢复的酷狗授权链 12 项、账号会员代次 3 项、QQ 会员 5 项、网易会员 2 项全部通过。该数量包含当时共享工作区的前端测试，不应全算作本批新增。

本机证据位于 `.local/artifacts/backend-batch2-takeover-20261101/`：`build-final.log`、`validation-summary.json`、`source-sha256.json` 和冻结的 `app-debug.apk`；报告在构建锁释放前统计，避免其他对话的局部测试覆盖后误计。最终 APK 另补齐同版本协议来源声明，包校验值以该目录 summary 为准。修改前局部基线位于 `.local/checkpoints/backend-takeover-20261101/`，保留用于比较，不可整批恢复覆盖其他对话的新改动。

当前 ADB 列表为空，未覆盖安装、未修改账号、收藏或设备队列。第一批 769 项测试和收藏/Doze 验收只证明第一批当时的包；7:33 的构建没有覆盖被移出的授权链测试，不能作为本批最终门禁。

## 前端配合及待实机验收

- 会员徽标/到期显示从 `AccountState.membership` 读取；UNKNOWN 应显示暂不可用，不能显示为非会员。全局音质设置调用上述偏好接口；这两处 UI 本轮未改。
- 保留现有失败提示、实际音源说明、歌词匹配、随机队列与更新检查接线。手动歌词绑定、离线冷启动、随机编辑、真实三平台会员读取、酷狗授权/Hi-Res 和会员降级需使用新包验证。
- 第一批遗留的真实普通歌单写入、成功手动换源续播、收藏他人歌单样本、数小时自然待机、来电/进程回收仍按原验收记录补测。
