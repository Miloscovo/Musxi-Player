# 后端需求单 · 2026-11-11（五）：本地条目的倍速、书签、封面设置与手动封面（前端 → Codex）

> **状态：当前需求单（2026-11-11）。** 用户 11-11 确认本地条目（音频和“只听声音的视频”）的播放页改版：
> - 播放键右侧原“音质”位置换成**倍速**；
> - 底部工具行原“歌词”位置换成**书签**；
> - 右上角原红心位置换成**快退 15 秒 / 快进 30 秒**；
> - 「我的 → 外观」加“**本地封面**”设置：自动读取 / 不读取（用色彩封面）；
> - 任意本地条目可**手动换封面**，也可恢复默认。
>
> 完成后由 Codex 在末尾“交付说明”填写接口与验证边界。

读者：负责 core / accounts / app / playback 后端层的 Codex。约定同前几批：不改 `app/src/main/kotlin/tech/musxi/player/ui/`（CC 负责）。行号以 2026-11-11 工作区为准，开工前复核。

## 已有（不需要改，确认即可）

- 书签：`AppLocalAudioRepository.addBookmark(audioId, positionMs, name)` / `removeBookmark(id)`，库状态里有 `bookmarks`。前端直接用。若缺“重命名书签”，第 4 项补上。
- 跳转：`PlaybackConnection.seekTo(positionMs)`。快退 / 快进由前端按当前位置计算后调用 `seekTo`，不需要新接口。如果你认为服务端提供 `seekBy(deltaMs)` 更稳（比如避免取样延迟导致跳偏），可以加上并写进交付说明。
- `PlaybackState.playbackSpeed` 已在读。

## 1 · 倍速（P1）

1. `PlaybackConnection.setPlaybackSpeed(speed: Float, callback)`：可选值 0.5 / 0.75 / 1 / 1.25 / 1.5 / 1.75 / 2，非法值拒绝。经 MediaController 的 `setPlaybackParameters`，**不变调**（pitch 保持 1）。
2. **按本地条目记住**：同一条下次播放仍用上次的倍速；切到另一条时用那一条自己的倍速（没设过就是 1）。存在本地库里，和进度、书签同级，随条目删除。
3. **平台歌曲不受影响**：播平台歌曲时一律 1 倍，前端也不会对平台歌曲显示倍速。从本地切到平台时要恢复 1 倍。
4. 定时停止（“播完当前…后停止”）、进度保存、续播都按实际倍速正确工作；歌词播放头已经读 `playbackSpeed`，不用改。

## 2 · 本地封面设置（P1）

1. `AppSettings` 加 `localArtworkMode: String`，取值 `"AUTO"`（默认）/ `"OFF"`，用名称字符串存储，与 `accent` 的做法相同；配上 `setLocalArtworkMode(...)`。
2. `OFF` 时：
   - 新导入的条目不再提取封面（省时间、省空间）；
   - **已有的封面文件保留不删**，只是界面不显示，切回 `AUTO` 时不用重新截取；
   - 系统媒体卡片 / 锁屏也不带封面，与界面一致。
3. `AUTO` 时与现在一致。从 `OFF` 切回 `AUTO` 后，对没有封面的条目是否自动补取，请评估：建议不自动补，前端在设置下方给一个“为已导入的条目补取封面”的入口，调用现有 `fillArtwork` 分批执行。

## 3 · 手动封面（P1）

1. `AppLocalAudioRepository.setCustomArtwork(audioId, image: Uri, grantedFlags, result)`：从系统图片选择器拿到的图片，按现有封面的规格缩放、去重、存储，替换这条的封面。
   - **手动封面优先于自动提取**，“检查来源”、重新关联、补取封面都**不覆盖**它；
   - `OFF` 模式下手动封面**仍然显示**：用户亲手选的图，不受“不读取”影响。
2. `clearCustomArtwork(audioId, result)`：恢复默认，回到自动提取的图，没有就无封面。
3. 模型：`LocalAudioItem` 增加 `artworkCustom: Boolean`，前端用它决定菜单显示“更换封面”还是“恢复默认封面”。
4. 图片 URI 只在调用期间使用，不持久化，不暴露给界面。图片选择器的 Intent 请像 `LocalAudioPickers` 一样提供（例如 `LocalAudioPickers.image()`），由前端用 `StartActivityForResult` 启动，原样回传 `data.flags`。

## 4 · 书签补全（P2，按需）

前端会做：
- 播放页工具行“书签”：点一下在当前位置加书签（名称默认为“书签 1”“书签 2”…）；长按或再点打开书签列表，可点按跳转、左滑删除、改名。

如果现在没有改名接口，补 `renameBookmark(id, name, result)`；名称规则与条目名相同。书签变化需要更新库的 revision。

## 交付说明

2026-11-11：第1、2、3项及第4项书签改名后端已实现。采纳用户补充：**OFF→AUTO 不自动补取旧条目**，仅恢复已有自动图的显示；用户手动点补取按钮才调用 `fillArtwork`。本轮不改 `ui/`，不安装手机、不提交、不推送。

### 倍速接口与持久化

```kotlin
playback.setPlaybackSpeed(1.5f) { result: Result<Unit> -> /* 主线程 */ }
localAudio.setPlaybackSpeed(audioId, 1.5f) { result -> /* 也可编辑未播放条目 */ }
```

- 仅接受 `0.5/0.75/1/1.25/1.5/1.75/2`，包括 NaN/Infinity 在内的其他值拒绝。`PlaybackConnection` 只允许当前本地条目、连接与速度命令可用时调用；平台歌曲拒绝。发送标准 `MediaController.setPlaybackParameters(PlaybackParameters(speed, 1f))`，pitch 始终为1，由既有 Media3 音频处理链路完成变速不变调。
- `LocalAudioItem.playbackSpeed: Float = 1f` 存在本地库，按稳定条目 ID 记忆；重启/暂停恢复/切条目/重复播放/队列编辑恢复该条自己的速度，未设置为1。同源重扫、重新关联、进度和书签变化不重置。删除条目时一起删除；不是全局偏好或文件夹速度。
- facade 先异步保存，再发送 Controller 命令；callback 成功表示**偏好已持久化且命令已发出**，不代表音频已解码/听音通过。保存失败不发送；连接/队列/条目在期间变化则返回失败，不把旧操作施加给新条目。已成功保存的旧条目偏好仍保留。直接从 Controller 发来的标准速度命令也经过服务端合法性、pitch、持久化与迟到守卫。
- 从本地切回平台及清队列都恢复 `PlaybackParameters.DEFAULT`（1倍、pitch1），平台速度修改被拒绝。`PlaybackState.playbackSpeed` 继续报告实际速度，UI 不应单凭保存值推算正在播放速度。
- 进度/续播/书签/seek 始终使用**媒体时间毫秒**，不手工乘除倍速；定时的分钟期限继续用 elapsedRealtime 实际时间，“播完当前”继续以播放器真实曲末事件为准，因而随实际倍速到达结尾。没有新建播放器或定时器。

### 自动/不读取与显示接口

- `AppSettings.localArtworkMode: String` 默认 `"AUTO"`，另一值为 `"OFF"`；`settingsRepository.setLocalArtworkMode(value)` 同步持久化，前端沿现有设置约定在 IO 调用。磁盘键为 `settings.local-artwork-mode`，按名称存；未知/损坏旧值回落AUTO，非法setter抛 `IllegalArgumentException`，写盘失败不推进StateFlow。
- OFF 时新导入/目录刷新/重新关联不提取自动图；模式切换本身不写库、不删已有图、不启动任何补取。所有仍在库中引用的自动图和手动图都参与资源保留；正常删除条目、明确换图/重关联时不再引用的旧图仍按资源生命周期清理。
- **前端展示请改用 `localAudio.displayArtworkFile(item): File?` 作为 Coil model**，或 `displayArtworkKey(item)` 后调用 `artworkFile(key)`。同时收集 `app.settings`（或 `localAudio.artworkMode`）与库状态，以在模式切换时重算。OFF 只隐藏自动图，手动图仍显示。原 `artworkFile(key)` 保持底层文件解析语义，不判断模式，不能单独用它落实“不读取”的显示规则。
- 服务持续订阅条目偏好与封面模式；当前及队列的 `MediaItem.artworkUri`、`PlaybackState.coverUrl` 实时更新，无需重新点播，不 seek、不重新 prepare、不改变播放位置。普通进度更新不会反复刷新封面；没有增加后台解码任务。系统最终卡片/锁屏显示仍需同一新包实机观察。
- OFF→AUTO 只恢复已有图，没有图的条目保持无图。按钮调用既有 `fillArtwork(audioIds, expectedRevision)`，每批1～500个唯一ID、每批取最新revision，使用 `artworkFill/cancelArtworkFill`。OFF时补取明确拒绝 `INVALID_TARGET`；执行途中变OFF也停止提交新自动图。已有手动图的条目直接保留，不读取其音视频来源。正常“检查来源”仍不解码封面。

### 手动封面与恢复默认

```kotlin
LocalAudioPickers.image() // ACTION_OPEN_DOCUMENT, image/*, 单选、临时只读
localAudio.setCustomArtwork(audioId, imageUri, returnedIntent.flags) { result -> }
localAudio.clearCustomArtwork(audioId) { result -> }
```

- `LocalAudioItem.artworkCustom: Boolean` 表示是否设置手动图；`customArtworkKey: String?` 单独存手动图，`metadata.artworkKey` 始终为自动图。`item.artworkKey = customArtworkKey ?: metadata.artworkKey` 是优先级选择后的key；它不代表模式可见性，展示请用上述 helper。
- 手动图与自动图分别保留，手动优先，OFF也照样显示；导入重扫、检查来源、重新关联和自动补图不覆盖手动图。`clearCustomArtwork` 只清手动选择，有自动图则回落，否则无图；OFF下回落的自动图继续隐藏。清除不启动自动提取。
- 图片 URI 仅在调用期间从ContentResolver读取，不持久授权、不保存来源URI、不向界面状态/日志/控制器发布。系统选择器取消时不调用接口。原样传回 flags；读取是否被授权由 resolver 实际开流决定，不伪造权限，也不为选择图像添加持久grant。
- 图片先读取尺寸再采样，不整图读入内存；最多两次开流，每次有24MiB读取预算，拒绝超过3200万像素或最长边16384px的图片。最终最长边≤512px、JPEG，复用内容散列去重/租约/fsync/原子发布。只读取图片，不经过音视频MMR。临时权限撤回为 `PERMISSION_REQUIRED`，无效/过大/不可读图片或存储失败为 `READ_FAILED`，原图和库记录保留。
- 读取图片时不长期持库锁；失败、取消、删除条目或较新的“恢复默认/更换封面”使旧读取失效，不重新创建条目，未提交图片回收。取消返回Job不执行普通结果callback。即使音视频来源缺失，仍可设置手动图。

### 书签、跳转与迁移

- 补充 `renameBookmark(id, name, result): Job`：去首尾空白后按条目名规则校验（非空、≤1024字符、无控制字符），不改audioId/位置/建立顺序；未知ID为 `NOT_FOUND`，非法名称为 `INVALID_NAME`。成功更新普通库revision。既有 `addBookmark/removeBookmark/state.bookmarks` 保留。
- 快退15秒/快进30秒继续用既有 `seekTo`，前端以最新媒体位置计算并限制到0和已知时长，不新增 `seekBy`；书签跳转同样直接用媒体时间。
- SQLite **schema v4** 加法追加 `playback_speed REAL NOT NULL DEFAULT 1.0`、`custom_artwork_key TEXT`；兼容v1/v2/v3逐级升级，旧项1倍/无手动图，自动图、用户数据与revision保留，不损坏重建。
- 倍速、自动/手动图、书签改名更新普通revision，但不改音视频sourceVersion；库搜索/批量整理继续按现revision规则防过期。整理备份保持v1，**不包含新增倍速/手动图、本体或封面资源**；恢复只合并整理，保留目标库当前条目偏好。

### 验证范围与证据

首轮定向271项通过（app204、playback37、core26、accounts4）；最终通过官方 `scripts/verify.ps1` / `:verifyAndroid -Pmusxi.verify.fresh=true`，七模块共 **2,436项**（app1357、core553、accounts54、playback160、酷狗140、网易云65、QQ107），两轮均0失败/错误/跳过。Lint为0错误/130警告，debug主包与设备测试包均编译成功。本轮21份生产源码/测试与统一验证冻结清单、最终磁盘散列一致；交付结果文档在验证结束后补全。

- 统一验证、XML与Lint原始报告：`.local/artifacts/verification/20261111T141915790Z-26780/`；本轮索引：`.local/artifacts/local-preferences-20261111/final-summary.json`。
- 保留APK：`.local/artifacts/local-preferences-20261111/Musxi-debug-local-preferences.apk`，29,057,170 bytes；SHA-256 `1e664e1ade1e609394acd3005fddd19700273d5836d35a4fbde9c6aa14c442d7`，与统一验证一致，**未安装**。
- 两轮日志尾部均保留一条无异常正文/堆栈的 `Exception in thread "SDK 35 Main Thread"` 提示；Gradle及全部XML结果通过，未据此推断生产设备行为，原日志可供后续定位。

本轮不安装或运行设备测试，不将 JVM fixture 当作真实听音不变调、OEM图片选择器、系统卡片/锁屏或后台定时实机通过；这些需CC接线后用同一新包验收。没有修改 `ui/`、新增运行时依赖、提交或推送。
