# 本地音频库：数据与导入合同

状态：2026-11-11，阶段接口。用户确认保留复制/引用两种导入，倾向默认复制，要求播放器小而精。数据/导入后端已接同一播放服务的本地队列、独立进度检查点与文件夹续播，正式前端和实机听音待接。产品目标及固定 GitHub 参考见 [方案](local-music-design-20261108.md)，导入阶段见 [交付](backend-local-audio-delivery-20261108.md)，服务阶段见 [播放接续](backend-local-playback-delivery-20261108.md)。11-11 补缺及剩余排期见[本轮需求交付](backend-requests-20261111.md#交付说明codex-填写)。

## 入口与线程

`MusxiApplication.localAudio` 为延迟初始化的 `AppLocalAudioRepository`。命令在主线程调用，返回可取消的 `Job`；短数据库事务串行在 IO dispatcher 执行，批量复制和文档读取不长时间占数据库锁，普通结果 callback 返回主线程。`state` 为 `StateFlow<LocalAudioLibraryState>`；`imports` 为 `StateFlow<LocalAudioImportState>`。等待 `ready=true` 再操作；初始化失败保留错误，不重建空库覆盖原数据。

状态只含 ID、名称、元数据、归属、进度、书签和枚举错误。`LocalAudioSource`、`LocalAudioDocument` 和原始 URI 只用于导入/后端授权，不保存到界面状态、日志或媒体控制器。

## 文件选择与导入

- `LocalAudioPickers.files(showAllFileTypes: Boolean = false, allowMultiple: Boolean = true)` 使用 `ACTION_OPEN_DOCUMENT`、默认多选；`type="*/*"` 配 `EXTRA_MIME_TYPES=["audio/*", "video/*"]`。重新关联用 `files(allowMultiple=false)` 单选；`files(showAllFileTypes=true)` 不加 MIME 筛选，仍由后端检查容器/音轨。保留首个位置参数语义，既有 `files(true)` 仍表示显示全部类型。
- `LocalAudioPickers.directory()` 使用 `ACTION_OPEN_DOCUMENT_TREE`。两者只请求读取/持久权限，不申请全盘或写入权限。
- 用户取消系统选择器时不调用导入；成功后从 `Intent.data/clipData` 收集 URI，把系统真实返回的 `Intent.flags` 传给 `grantedFlags`，不能自行拼一个“已授权”值。
- `importFiles(folderId, uris, mode=COPY, grantedFlags, callback)`；`importDirectory(folderId, tree, mode=COPY, grantedFlags, includeSubdirectories=false, callback)`。
- 首版每批最多 500 个文件，本地库最多 20,000 条音频/500 个应用文件夹/100,000 份归属/50,000 个书签；选取或整理范围过大返回 `LIMIT_REACHED`，不默默截断。
- 导入逐项返回 `fileName/audioId/error`。某个文件失败不撤回已经导入的其他文件；复制进度包含当前文件字节数与可选总字节数，更新限频。`cancelImport()` 保留已完成条目并清当前半成品，`cancelled=true`；取消不会通过普通成功 callback 回报。
- 一次只运行一批导入，短整理/检查点事务可在复制过程中执行。重复提交导入在开始前返回错误，不叠加后台任务；导入期间删除目标文件夹时，未提交条目会逐项失败，不把文件写入已删除分组。

两种选项建议文案：

| 选项 | 说明 |
| --- | --- |
| 复制到 Musxi（默认） | 保存在 Musxi 中，导入成功后可自行删除原文件；卸载 Musxi 会删除这份应用副本 |
| 使用原文件 | 不复制音频；原文件需保留，移动、删除或撤回权限后需要重新关联 |

复制不转码，成功后才提交库记录，相同内容复用本地副本。流式复制同时计算内容标识，常驻缓冲区 64 KiB；不为常规播放重新全量哈希。引用模式要求持久读取权限，提供方不支持时返回 `PERMISSION_REQUIRED`，可改选复制。Musxi 不自动删除来源文档。

`importDirectory` 仍为一次性批量导入，重复选择同目录可发现新增/刷新已知条目；COPY 单次导入只保留文件，不保留目录读取授权。需要下次主动刷新时使用下节的持久关联入口。

## 视频声音与自动封面

11-11 第四份需求的完整语义、兼容边界与验证在[交付说明](backend-requests-20261111-4.md#交付说明)。文件及目录/关联刷新接受视频 MIME 和 `mp4/m4v/mov/mkv/webm/3gp`；视频候选必须探测到音轨，无音轨为 `UNSUPPORTED_AUDIO`，无法读取轨道元数据为 `READ_FAILED`。`LocalAudioMetadata.kind` 默认为 `LocalMediaKind.AUDIO`，实际存在视频轨才为 `VIDEO`；`LocalAudioItem.kind` 转发该值。旧库加法迁移为 AUDIO，不重建数据。实际音轨解码支持仍由 Media3 与设备决定。

单文件上限仍为 64 GiB，COPY 保留完整原容器，REFERENCE 保留授权；没有转码/抽取丢弃画面。播放服务使用原 ExoPlayer 与默认渲染器，轨道选择显式禁用视频；进度、书签、定时、续播和来源检查共用原机制。未来观看时需补服务观看模式/视频轨选择、Controller/Surface 生命周期和 CC 的 VIDEO 页面，具体改点见需求单。

`LocalAudioItem.artworkKey` 为可空64位小写散列，选择 `customArtworkKey ?: metadata.artworkKey`，后者只存自动图；`artworkCustom` 标记手动选择。自动图：音频取内嵌图，视频优先内嵌图再取时长10%或约第1秒关键帧，缩放为最长边≤512px JPEG，保存在私有 `filesDir/local-artwork-v1`。**Coil展示使用 `localAudio.displayArtworkFile(item): File?`**，同时收集设置/`artworkMode` 与库状态，确保OFF只隐藏自动图，手动图仍显示。`artworkFile(key)` 仍可解析文件但不判断模式。不能用key拼来源URI；派生图片可用于展示，原音视频路径仍只在后端。封面不随普通缓存清理或平台退出删除，自动/手动图均参与引用保留，最后一个条目删除后回收。

旧条目使用独立 `fillArtwork(audioIds, expectedRevision, callback): Job`，1～500个唯一 ID，分批时每批取最新 revision；`artworkFill: StateFlow<LocalAudioArtworkState>` 提供 `running/total/processed/currentAudioId/results/completed/cancelled/error`，`cancelArtworkFill()` 或取消 Job 停止。每项结果 `audioId/artworkKey/error`；两个可空值均为空表示无可提取图。有效旧图复用，丢失图片重取；手动图直接保留，不打开音视频来源。OFF时拒绝补取为 `INVALID_TARGET`，执行途中切OFF不再提交新自动图。已完成项取消后保留，取消不回报普通成功callback。补取在IO执行，正常callback回主线程，来源代次和较新观察守卫迟到结果。封面变更只提升普通revision，不改变sourceVersion/进度/书签；轻量 `checkSources` 不提取图片。服务已订阅偏好与封面模式，队列封面/系统媒体信息实时更新，不需重新点播。

本地 MediaItem 带派生图 `artworkUri`，`PlaybackState.coverUrl` 可返回该图片 URI。提取失败降级无封面；API26 超大/未知尺寸视频帧会跳过，最终系统媒体卡片与锁屏显示仍需实机。整理备份保持 v1，不含封面、音视频本体及新增 kind；恢复只合并整理，保留目标库当前 kind/封面。视频原始导出保留扩展名并传对应视频 MIME。

## 条目倍速、封面模式、手动封面与书签改名

本轮完整接口、callback语义与验证见[第五份需求交付](backend-requests-20261111-5.md#交付说明)。

| 接口/状态 | 语义 |
| --- | --- |
| `PlaybackConnection.setPlaybackSpeed(speed, callback)` | 仅当前本地条目，七档0.5/0.75/1/1.25/1.5/1.75/2；保存后发标准Controller参数，pitch固定1。callback为持久化/命令发送结果，切歌/重连使迟到操作失效 |
| `LocalAudioItem.playbackSpeed` / `localAudio.setPlaybackSpeed(audioId,speed,callback)` | 每条目持久化，默认1；切条目/暂停恢复各取本条速度，重关联保留，删除随条目删除。平台播放始终恢复1倍 |
| `AppSettings.localArtworkMode` / `setLocalArtworkMode("AUTO"/"OFF")` | 默认AUTO，按名称持久化。OFF不提取新自动图，保留旧图只隐藏；OFF→AUTO不自动补取，只恢复已有图，补取由用户按钮发起 |
| `displayArtworkFile(item)` / `displayArtworkKey(item)` | 执行手动优先和OFF可见性；前端收集设置与库状态后调用，文件交给Coil |
| `LocalAudioPickers.image()` | 系统单选图片、临时只读；取消不调用命令，成功传回URI和原flags |
| `setCustomArtwork(audioId,image,grantedFlags,crop,callback)` | `crop: LocalArtworkCrop?`按EXIF摆正后的0～1坐标，仅区域采样解码；null整图入库并摆正方向。原无crop签名重载保留，兼容第四参数回调。与自动图分别存，手动始终优先，OFF也显示 |
| `clearCustomArtwork(audioId,callback)` | 仅清手动选择，自动图存在则回落，否则无图；OFF下自动图仍隐藏，不启动提取 |
| `renameBookmark(id,name,callback)` | trim后按条目名规则校验，保留音频ID/时间位置/建立顺序，更新revision；未知ID为NOT_FOUND，非法名INVALID_NAME |

条目偏好和书签写入在IO执行、正常callback回主线程；设置setter同步commit，沿既有设置调用约定放IO。图片拒绝超过3200万像素、最长边16384px或总读取24MiB预算的输入；选图提供者只开一次流，复制到受管暂存文件后读取尺寸/EXIF/像素，结束/失败/取消清理，不保存来源URI或持久授权。提取失败不替换原图，取消/删除/较新选择守卫未提交图。速度非法值（含NaN/Inf）拒绝；进度/书签/seek始终媒体时间，定时分钟仍是实际时间，曲末以真实播放器事件为准。快退/快进沿用seekTo，由前端限制到0和已知时长。整理备份v1不保存新倍速/手动图，恢复保留目标库偏好；本轮未另行做画面播放。

手动裁切值类型位于 `tech.musxi.player.localaudio.LocalArtworkCrop`。四值必须有限，满足 `0≤left<right≤1`、`0≤top<bottom≤1`；按摆正后尺寸向外取整，裁切区短边至少64px，否则 `INVALID_TARGET`。不强制正方形，null不套64px下限。原图区域解码后处理八种EXIF旋转/镜像，最长边≤512px JPEG，不保留原照片元数据；没有方向/系统无法读方向时按原像素，格式不支持区域解码为 `READ_FAILED`，不退回整图裁切。HEIC/WebP方向支持由系统版本/容器决定，尚无设备样本结论。重扫/重关联/补图不覆盖手动选择；恢复默认仍回落自动图，OFF隐藏自动图。接线示例、错误与验证见[11-12裁切交付](backend-requests-20261112.md#交付说明)。

## 持久目录关联与手动刷新

| 命令 | 行为 |
| --- | --- |
| `linkDirectory(folderId, tree, name, mode=COPY, grantedFlags, includeSubdirectories=false, callback)` | 保存系统目录与应用文件夹的关联，不立即导入。COPY 与 REFERENCE 都要求持久读取授权，供后续刷新使用 |
| `refreshDirectory(id, callback)` | 手动完整列举该关联范围，按自然序导入新增文件；复用 `imports`、单批互斥与 `cancelImport()` |
| `reauthorizeDirectory(id, tree, grantedFlags, callback)` | 重新取得同一目录授权并清关联错误；不同 tree 返回 `SOURCE_CHANGED`，应创建新关联 |
| `unlinkDirectory(id, callback)` | 仅移除关联与来源记录，保留音频、文件夹成员、名称、进度和书签；回收无人使用的授权 |

`state.directories` 为 `List<LocalAudioDirectoryLink>`，公开 `id/folderId/name/mode/includeSubdirectories/lastRefreshedAtMs/missingCount/error`，不含系统 URI。刷新 callback 成功值为 `LocalAudioDirectoryRefreshResult(directoryId, results, missingCount)`；成功完成列举仍可能含逐项导入错误，界面需检查 `results`。相同文件夹和 tree 重复关联返回原记录，保留名称；模式或递归偏好冲突时拒绝隐式切换。最多 500 份关联，每次最多 500 个音频、检查最多 20,000 个目录条目，超限明确失败。

关联保存后由用户明确刷新，不启动目录监听、定时任务或全盘扫描。普通文件夹删除会同时取消其关联，音频本体仍留在本地库。取消关联后，REFERENCE 音频仍需要原读取授权，因此只在没有剩余关联或音频引用时释放；进行中的导入也会暂时保留所需授权。

刷新遵循以下数据保留规则：

- 已有成员不重排，新文件追加到末尾；用户手动移出的已知来源不会被重新加入。原有别名、进度、书签和其他文件夹归属保留。
- 完整列举成功后才比较缺失。失败、取消或云提供方仍在加载时不当成空目录，不更新缺失差集；已完成导入保留。逐项读取失败不阻止对其他已知来源计算缺失。
- COPY 来源消失只计入 `missingCount`，Musxi 副本保持可播；REFERENCE 来源消失标记 `MISSING`，保留条目和整理数据。已显式重新关联到其他来源的条目不会被旧目录扫描标坏。
- COPY 只有来源大小和修改时间均已知且一致、受管副本存在且长度吻合时才直接复用；提示未知则重新流式读取并按内容去重。发现新内容创建独立音频，保留旧副本和旧数据，避免套用旧进度。
- REFERENCE 已知大小或修改时间变化返回 `SOURCE_CHANGED`，等待用户显式 `relink`。提供方不提供可靠提示时，相同大小的内容替换仍可能无法发现；不为常规播放全量哈希引用文件。
- 扫描中取消关联、删除文件夹或重新授权后，旧扫描的迟到结果不得提交到新状态；当前半成品会被清理。重新授权不会自动触发刷新。

目录关联于schema v2增加，v3追加kind/artwork_key，当前v4再追加playback_speed/custom_artwork_key；兼容v1/v2/v3逐级迁移，保留原音频、用户整理、进度及封面；不以重建空库处理迁移失败。目录实现见[目录交付](backend-local-directory-delivery-20261108.md)，新字段见[视频封面](backend-requests-20261111-4.md#交付说明)与[条目偏好交付](backend-requests-20261111-5.md#交付说明)。

## 主动检查来源

`checkSources(audioIds, expectedRevision, callback): Job` 由用户手动发起，检查选中的 1～500 条本地音频；`cancelSourceCheck()` 或取消返回的 Job 可停止。`sourceChecks: StateFlow<LocalAudioSourceCheckState>` 含 `running/total/processed/currentAudioId/results/completed/cancelled/error`，不含 URI、受管路径或提供方异常文本。命令仍在主线程调用、IO 执行、结果回调回主线程。前端从当前库／搜索结果取得稳定 ID 和 revision，不按名称猜来源，不自动全库检查。

开始时冻结 ID 列表并核验 revision、全部条目；空批／超过 500 返回 `LIMIT_REACHED`，重复／非法 ID 为 `INVALID_ORDER`，库中条目消失为 `NOT_FOUND`，revision 过期为 `STALE_STATE`。这些错误在打开来源前整批拒绝。一次只运行一批检查，重复触发返回 `LIMIT_REACHED`，保留正在执行的状态。导入、整理、进度保存、播放及传输仍可执行；检查只短时持有数据库锁，不在提供方读取期间占用。

| 来源 | 检查内容 |
| --- | --- |
| COPY | 解析受管副本、核对已知长度，尝试读取首字节后关闭；不再访问原文档，原文件已删除不影响副本结果 |
| REFERENCE | 读取当前文档的大小／修改时间提示，与已保存且双方已知的值比较；再打开来源读取首字节后关闭，沿用现有授权，不新申请权限或提取媒体标签 |

逐项结果 `LocalAudioSourceCheckResult(audioId, availability?, error?, applied)`：正常为 `AVAILABLE`／error=null，来源缺失为 `MISSING`／`NOT_FOUND`，授权失败为 `PERMISSION_REQUIRED`，其他读取问题为 `UNREADABLE`／`READ_FAILED` 等。已知大小或修改时间变化为 `UNREADABLE`／`SOURCE_CHANGED`，保留原元数据和来源，需显式重新关联，不自动用新内容覆盖旧进度。文档查询返回 null、加载中或提供方报错均为 `READ_FAILED`；明确空响应或文件不存在为 `NOT_FOUND`。

可用状态逐项保存，状态没有变化时不增加 revision；不改名称、文件夹、顺序、目录来源、进度、书签或文件，也不更改播放队列。成功结束的 callback 表示所选条目均已处理，仍可能包含逐项错误；前端检查 `results`，不要仅凭 callback 成功提示“全部可用”。再次检查成功可将已恢复的来源标回 `AVAILABLE`。

检查期间重新关联、重新导入、删除条目、同一目录重新授权或新的播放探测，都会使受影响的旧结果失效：`applied=false`，availability=null，error 为 `SOURCE_CHANGED/NOT_FOUND/STALE_STATE`，不回写旧来源结果。改名、分组和进度变化不会单独取消来源检查。取消保留已保存的结果，当前未提交观察不落库，不通过成功 callback 回报；提供方阻塞调用需等返回后关闭，不能承诺立即打断其内部 I/O。

这是轻量可读性检查，不全量计算哈希、不解码播放；同大小且修改时间未知／不可靠的内容替换、文件后段损坏、编码是否可播仍需实际播放确认。SAF 可能指向云文档，由提供方处理本次手动读取；应用不额外发平台请求，不复制云音频、不启动后台轮询。此状态为本次检查观察，重新授权／整理后可重查，实际播放仍会重验来源。实现与当轮验收见[来源检查交付](backend-local-source-check-delivery-20261109.md)。

## 音频导出与整理备份／恢复

全部入口仍在 `MusxiApplication.localAudio`，返回可取消的 `Job`。`transfers: StateFlow<LocalAudioTransferState>` 公开 `running/kind/completedBytes/expectedBytes/completed/cancelled/error/cleanupFailed`，不包含来源或目标 URI。一次只运行一项传输，导入／目录刷新与传输互斥，冲突返回 `LIMIT_REACHED`；普通整理及服务进度保存仍可执行。`cancelTransfer()` 取消当前任务，不取消普通导入。

| 入口 | 语义 |
| --- | --- |
| `LocalAudioPickers.exportAudio(fileName, mimeType)` | `ACTION_CREATE_DOCUMENT` 新建导出文件；建议保留原扩展名和有效音频 MIME，不将显示别名当作已修改的源标签 |
| `exportAudio(audioId, target, grantedFlags, callback)` | 原始字节导出，COPY／REFERENCE 均可；返回 `LocalAudioExportResult(audioId, bytes)`，不转码、不改标签或删除音频 |
| `LocalAudioPickers.exportOrganization(fileName)` | 新建 `.musxiorg` 整理备份文件 |
| `backupOrganization(target, grantedFlags, callback)` | 保存全库整理快照，返回 `LocalAudioBackupResult(backupId, items, folders)` |
| `LocalAudioPickers.restoreOrganization()` | `ACTION_OPEN_DOCUMENT` 选择备份；取消选择不调用预览 |
| `previewOrganization(source, callback)` | 读取、完整校验并暂存一份备份；返回 `LocalAudioRestorePreview`，含 backupId、expectedRevision、匹配／未匹配音频数、文件夹数和可匹配书签数；预览不写库 |
| `restoreOrganization(backupId, expectedRevision, callback)` | 使用已校验的暂存内容事务合并，不再次打开来源文件；返回 `LocalAudioRestoreResult` 的匹配／未匹配及新增文件夹／成员／书签数 |

导出只接受系统创建的空 `content` 文档，必须传系统实际返回的写授权 flags。目标已非空、无法确认空、是目录或与库内引用源身份相同时返回 `INVALID_TARGET`／`PERMISSION_REQUIRED`，不打开或删除该目标；不要求持久写权限。部分提供方不能报告新文件大小，首版需要改选能报告大小的提供方。传入普通已有文件不能被当作默认覆盖入口。

复制采用 64 KiB 缓冲并检查取消和字节上限。COPY 导出核对保存的内容摘要，REFERENCE 导出前检查已知大小／修改时间，读完核对已知长度；提示不可靠的同大小替换限制仍在。期间用户删除／重新关联音频时，旧来源的迟到导出不能报告成功，活跃副本／授权保留到任务清理完成。导出失败或取消会尝试删除本次未完成目标；若提供方拒绝清理，`cleanupFailed=true`，界面应提示用户到所选位置检查残留。提供方写入与应用数据库之间没有跨系统原子事务，不能保证断电或进程终止后目标一定自动消失。

**整理备份不包含音频本体。** v1 格式包含条目公开元数据及来源匹配摘要、文件夹／继续偏好、成员顺序、用户别名、进度、游标和书签；不包含原始 URI、读取权限、目录关联、账号、平台数据或活动队列。要迁移副本，需要先导出音频、在目标库导入后再恢复整理。引用源与目录授权必须由用户在目标设备重新选择。备份包含私人名称和收听进度，应由用户自行选择保存位置；格式校验摘要用于发现损坏，不提供加密。

备份上限 32 MiB，流式读写、严格 UTF-8、字段／数量边界和 SHA-256 尾部校验。未知格式、截断、校验不符、重复身份、无效关联或尾随数据拒绝；未知版本返回 `UNSUPPORTED_BACKUP`，损坏返回 `INVALID_BACKUP`，范围超限返回 `LIMIT_REACHED`。不使用 Java 对象反序列化或解压来源提供的路径。

恢复策略固定为保留当前数据的合并：

- 只按导入模式及精确来源摘要匹配现有音频，COPY 为内容标识，REFERENCE 为文档身份／大小／修改时间组合摘要；不按标题、文件名或大小猜测。无法匹配的音频及其进度／成员／书签跳过并计数，不导入不可读的伪音频；文件夹可先恢复为空。
- 已存在同 ID 文件夹保留本机名称和继续偏好；缺失文件夹新建。已有成员顺序保留，新成员按备份顺序追加。当前已有别名、进度和游标优先，只有缺失字段才从备份补充。
- 书签按 ID 以及同音频／时间／名称去重。重复合并不产生重复记录；没有实际变化时不增加 revision。不改来源、目录、音频本体或授权。
- 使用预览返回的 revision 确认，库已变化则 `STALE_STATE` 并保持原样，需重新预览。容量超限或数据库写入失败时整个事务回滚；不可把逐行写入误作整批成功。
- 同时只暂存一个备份；新预览会清除旧暂存，校验失败也不会回退使用旧备份。成功恢复后清暂存；进程退出不保留预览，需重新选择。

前端需同时展示“音频另行保存”和未匹配数量，不应将整理备份表达成包含音频的完整迁移包。实现和当次证据见[导出与备份交付](backend-local-transfer-delivery-20261108.md)。

## 整理与持久化

- 文件夹：`createFolder / renameFolder / deleteFolder / setContinuePlayback`。首版为单层逻辑分组，独立于系统目录。
- 条目显示名：`renameAudio(id, name)`；传 `null` 恢复标签/文件名回退。不修改原文件名或标签。
- 归属：`addToFolder / removeFromFolder`；同一音频可在多个文件夹中共享。删文件夹或移除成员不删音频本体。
- 排序：导入按自然数字序 1、2、10；`reorder(folderId, audioIds, expectedRevision)` 必须包含当前全部成员且不重复。版本过期返回 `STALE_STATE`，成员集合错误返回 `INVALID_ORDER`，两者都不写入顺序。
- 全库删除：`deleteAudio` 移除音频及其所有归属、进度、书签，再收集无人引用的应用副本/读取权限。此动作不能借“移出文件夹”隐式触发。
- `relink(audioId, uri, grantedFlags)` 仅用于引用条目。稳定 ID、用户别名、书签、进度保留；目标已属于另一条音频时拒绝合并。副本不接受重新关联来源。
- 名称去首尾空格，空白/控制字符/超 1024 字符拒绝。存储使用 ID 关联，不用名称作为文件路径或主键。

## 检索、筛选与分页

`search(LocalAudioQuery, callback): Job` 只检索库内公开元数据，不打开音频、不扫描来源、不联网。`searchState: StateFlow<LocalAudioSearchState>` 含 `loading/page/error`；页面为 `revision/total/offset/items/nextOffset`。新查询取消旧查询，迟到结果不能覆盖新查询；`clearSearch()` 取消并清空，调用方直接取消返回的 Job 也不会回调成功。

`LocalAudioQuery` 的字段：

| 字段 | 约定 |
| --- | --- |
| `text` | 默认空，最多 256 字符；空白分词后全部词都需命中。逐词匹配用户别名、内嵌标题、原文件名、作者中的任一字段；NFKC 归一、与系统地区无关的忽略大小写；`%`、`_`、`[]` 等按普通字符处理 |
| `folderId` / `unfiledOnly` | 默认全库；指定文件夹或仅看没有任何文件夹归属的音频，两者互斥 |
| `mode` / `availability` | 可选复制／引用与已记录的可用状态；筛选不会主动重验来源可读性 |
| `progress` | `ANY` 默认；`UNPLAYED` 为无进度或未完成且位置为零；`IN_PROGRESS` 为未完成且位置大于零；`COMPLETED` 为已完成 |
| `bookmarkedOnly` | 默认 false；true 时只保留至少有一个时间书签的音频 |
| `sort` / `descending` | 默认 `MANUAL`、false；也可选 `TITLE/FILE_NAME/ADDED_AT/DURATION/LAST_PROGRESS` |
| `offset` / `limit` | 默认 0／100；offset 为 0～20,000，limit 为 1～200，不默默截断非法参数 |
| `expectedRevision` | 第一页可省略；后续页必须带第一页的 revision，任何库变化均要求重新查询 |

非法参数在构造 Query 时抛出 `IllegalArgumentException`，前端应约束长度与范围。未就绪返回 `STORAGE_FAILED`，文件夹消失为 `NOT_FOUND`，revision 过期为 `STALE_STATE`。查询过程中库已变化也拒绝返回成功。已显示页面之后发生库变化时保留原 page 并标记 `error=STALE_STATE`，前端停止续页、重查第一页；不把旧条目继续当作最新可编辑状态。服务进度保存也会改变 revision，不自动为每个检查点反复执行查询。整理/进度 revision 本轮尚未拆分；进度筛选及进度/最近播放排序需要相应分页 token，不能只取消进度写入时的递增。拆分范围与临时接线方式见[需求交付](backend-requests-20261111.md#交付说明codex-填写)。

标题按当前显示标题（别名优先），文件名保留原文件名，自然数字序为 1、2、10；添加时间与时长按数值。`LAST_PROGRESS` 按进度的 `updatedAtMs`（最近保存时间），不按听到的位置排序；查最近在前用 descending=true。未知时长／未保存进度在两个方向都放末尾。同值条目保留当前文件夹人工顺序，全库则保留库顺序；倒序只反转主键。`MANUAL` 在文件夹内读取成员顺序，可只读反向显示；全库使用库顺序，忽略 descending。查询排序仅影响返回结果，不写回文件夹。

## 批量整理与文件夹规则排序

| 命令 | 行为 |
| --- | --- |
| `addToFolderBatch(folderId, audioIds, expectedRevision, callback)` | 按选择顺序追加尚未存在的归属；目标已有成员保持原位 |
| `removeFromFolderBatch(folderId, audioIds, expectedRevision, callback)` | 整批从该文件夹移出；要求全部条目当前都是成员 |
| `moveToFolderBatch(sourceFolderId, destinationFolderId, audioIds, expectedRevision, callback)` | 从源文件夹移出并加入不同的目标文件夹；目标已有归属复用且不重排 |
| `sortFolder(folderId, sort, descending=false, expectedRevision, callback)` | 对整个文件夹按上述规则写回顺序，包含筛选／分页外成员；与只读查询排序不同 |

每批 1～500 个有效且不重复的稳定音频 ID，不能按名称匹配。空批／超限为 `LIMIT_REACHED`，重复／非法 ID 或相同源目标为 `INVALID_ORDER`，缺失音频／文件夹／源成员为 `NOT_FOUND`。revision 在事务内校验，过期返回 `STALE_STATE`。冻结输入列表后执行整批事务，任何校验、容量错误、写入失败或提交前取消均回滚；不部分成功。MOVE 按最终归属数计算容量，满库也可进行不增加总归属数的移动。

批量返回 `LocalAudioBatchResult(action, selected, addedMemberships, removedMemberships)`，selected 为输入条目数；ADD 全部已存在时为成功且不增加 revision。REMOVE／MOVE 清理指向移出成员的源文件夹游标，保留音频本体、共享进度、书签、别名、其他归属、目录来源记录与授权；不会删除源文件或 Musxi 副本。目录再次刷新也保留用户移出决定。

规则排序返回 `LocalAudioSortResult(folderId, members, changed)`，members 为全文件夹成员数；序列未变化时 changed=false，不增加 revision。排序保留文件夹游标及所有音频数据，不改已经运行的临时播放队列。`MANUAL` 作为规则写入返回 `INVALID_ORDER`，人工拖动继续使用包含全部成员的 `reorder`。当次实现与验证见[检索与批量整理交付](backend-local-organization-delivery-20261109.md)。

## 续播与书签的数据接口

`saveProgress(folderId?, LocalAudioProgress)` 保存每条音频的位置/播完状态，并在条目仍是该文件夹成员时更新文件夹最后条目。`addBookmark / removeBookmark` 保存命名时间书签。位置使用 Long，本地数据允许超过 24 小时，上限 366 天；平台歌曲旧校验未改变。

本地服务现已在切换、暂停/音频焦点变化、自然结束与周期检查点自动调用。已完成条目保存 `completed=true`，下次继续从零重听；曲末定时暂停也标记完成。每音频共享一份进度，每文件夹单独保存最后条目；删除文件夹后播放中的该音频仍可保存进度，不再写文件夹游标。UI 不自行启动第二个播放器。

## 本地播放入口与状态

`MusxiApplication.localAudioPlayback.play(folderId, audioId=null, fromStart=false, playWhenReady=true, callback)` 返回 `Job`：

- 文件夹“继续播放”：传 `folderId`，不指定 `audioId`。按文件夹游标选条目，再根据该音频进度继续；未听过则第一条。
- 点击某音频：传文件夹 ID 和音频 ID，根据文件夹的继续偏好选择位置。
- “从头播放”：传 `fromStart=true`；不指定条目时从文件夹第一条开始，这次队列所有条目均从零起播，不删除历史书签。
- 单条音频：`folderId=null`，必须提供 `audioId`。
- 暂停恢复队列：`playWhenReady=false`，只设置 timeline，不 prepare、不自动请求音频焦点；后续用户点击播放再启动。
- 调用前先等待 `playback.state.connected`；先等待服务把当前位置存妥，再生成队列。并发准备以最新请求为准，较早结果不能回头覆盖新意图；平台接管和停止会取消未派发的本地准备。

`PlaybackConnection.localQueueState` 公开 `revision/folderId/items/currentIndex/upcoming`；平台旧 `queueState.songs` 在本地播放时为空。`PlaybackState.localAudio` 为当前本地条目，`localError` 为可选 `LocalAudioError`。本地 `queuePlatform/sourcePlatform/sourceKey/original/actualSource` 均不伪造平台身份；平台收藏、换源、音质操作据此隐藏。标题使用本地用户显示名，作者可空。媒体控制器和通知只含公开元数据与不透明队列槽位，不含文档 URI 或受管路径。

队列专用命令：`jumpToLocalQueueIndex(index, expectedRevision, fromStart=false)`、`removeLocalQueueItem`、`moveLocalQueueItem`、`clearLocalQueue`。跳转保持槽位/队列、不用 Replace 伪造跳转；过期 revision 拒绝。队列移除不删除库条目、文件夹归属或原文件。通用暂停/恢复/seek/上下条/循环/随机/输出、既有三种定时策略沿用同一服务。通知“下一条”按继续偏好恢复该音频进度，显式指定位置 seek 尊重请求位置。

本地文件读取失败暂停，不自动换成平台同名歌、不默默跳过。旧票据在替换/删除后立即撤销，迟到的 loader 结果也拒绝；每次打开重验文件版本和读取能力。退出平台账号不清本地队列。网络来源仍经既有严格白名单，本地只接受受管 file 或已授权 content 来源。

播放服务冷启动时，若最后活动来源是本地且没有更新用户意图，会自动重建最后本地队列并保持暂停/未 prepare，不请求焦点、不打开音频。恢复包含临时队列编辑顺序、位置、循环模式和实际随机顺序；原票据不持久化。旧定时器不恢复。也可以再次调用文件夹 `play` 按当前成员重建，这与恢复上次临时列表是两个入口。

`MusxiApplication.localAudioPlayback.restoreLastQueue(callback)` 返回可取消 `Job`，显式恢复最后保存的本地列表，始终暂停；等待 `playback.state.connected` 后调用。`restoreState` 是 `StateFlow<LocalAudioRestoreState>`，字段为 `ready/available/restoring/error`；`available` 只说明有已保存列表，来源可读性仍需实际播放检查。切换平台或普通 STOP 后保留该显式入口，但不再自动恢复本地；清空本地队列则删除保存列表。新播放/停止意图会拒绝较早的恢复结果，线上歌单开始加载时即失效旧本地请求，已准备队列在服务实际应用时再次核验。前端使用本节 App 入口，不自行直接构造/派发本地队列或再装一次启动队列。

恢复使用数据库最新显示名；已从库删除的条目会跳出保存列表，不自动加入后来新增的文件夹成员。当前条目被删时选原顺序中下一条，否则最后一条；全删返回 `NOT_FOUND`。已删文件夹降为独立队列；来源版本改变返回 `SOURCE_CHANGED`，需从文件夹/音频重新选择，不能套用旧来源快照。只安装未播放的 fromStart/idle 队列不会覆盖历史进度或完成标记。实现与剩余后端工作见 [恢复交付](backend-local-resume-delivery-20261108.md)。

## 错误与体验

`LocalAudioError` 为稳定枚举：`INVALID_NAME / NOT_FOUND / INVALID_ORDER / LIMIT_REACHED / READ_FAILED / STORAGE_FAILED / PERMISSION_REQUIRED / UNSUPPORTED_AUDIO / TOO_LARGE / NOT_ENOUGH_SPACE / SOURCE_CHANGED / INVALID_BACKUP / UNSUPPORTED_BACKUP / STALE_STATE / INVALID_TARGET`。界面映射友好中文，不显示异常原文或 URI。

已知引用文件大小或修改时间变化返回 `SOURCE_CHANGED`，避免把旧进度盲目套到另一份文件；同大小且提供方修改时间不可靠的替换仍不能可靠识别。后端解析文件时保留不可用条目，权限失败/缺失/不可读取分开表达，不删除用户名称和书签；重新关联后恢复。

音频格式根据 MIME/常见扩展名接受，元数据可缺失；播放复用 Media3，实际格式/seek/系统后台效果待设备样本。此阶段不提取大型封面、不做内嵌章节或批量标签改写，不引入额外解码依赖。常用操作维持简洁，复制/引用在导入时选择，递归子目录放在按需选项中。
