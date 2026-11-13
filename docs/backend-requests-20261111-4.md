# 后端需求单 · 2026-11-11（四）：本地音频支持导入视频（只听声音）与封面（前端 → Codex）

> **状态：当前需求单（2026-11-11）。** 用户 11-11 要求：本地音频也能导入 MP4 这类视频文件，**只播放其中的声音**；封面最好能自动从视频里截一帧。后续会考虑“观看视频”，本单要为它留好口子但**不实现画面播放**。完成后由 Codex 在末尾“交付说明”填写接口与验证边界。

读者：负责 core / app 后端层（localaudio、playback）的 Codex。约定同前几批：不改 `app/src/main/kotlin/tech/musxi/player/ui/`（CC 负责）。行号以 2026-11-11 工作区为准，开工前复核。

## 现状（已核对代码）

- `AndroidLocalAudioDocuments.metadata()`（约 62-85 行）**主动拒绝视频**：MIME 以 `video/` 开头，或 `METADATA_KEY_HAS_VIDEO == "yes"`，都抛 `UNSUPPORTED_AUDIO`。扩展名白名单 `AUDIO_EXTENSIONS`（约 176 行）只有音频格式。
- 系统选择器 `LocalAudioPickers.files()` 只请求 `audio/*`，用户在选择器里根本看不到视频。
- `LocalAudioItem` / `LocalAudioMetadata` 没有封面字段；本地音频在迷你播放器、播放页、列表里都没有封面（界面用占位渐变）。
- 播放走 Media3 1.8.0 的 ExoPlayer，它本身能解 MP4 / MKV / WebM / MOV / 3GP 的音轨。

## 1 · 接受视频文件，只播声音（P1）

1. 导入：
   - 放开对 `video/*` 的拒绝；扩展名白名单加入 `mp4 m4v mov mkv webm 3gp`（以 Media3 实际能解的为准）。
   - 要求文件**至少有一条音轨**：没有音轨（`METADATA_KEY_HAS_AUDIO != "yes"`）时，报一个新错误 `NO_AUDIO_TRACK`，界面写“这个视频没有声音”。如果不想新增枚举值，复用 `UNSUPPORTED_AUDIO` 也行，交付说明里写清楚。
   - 大小上限 `MAX_FILE_BYTES` 对视频是否要调整，请评估（视频动辄几个 GB）；“复制到 Musxi”模式下大视频很占空间，前端会在复制模式下提示。
2. 选择器：`LocalAudioPickers.files()` 的 MIME 改为同时请求 `audio/*` 和 `video/*`（`EXTRA_MIME_TYPES`）；目录导入、关联目录的扫描同样要认这些扩展名。
3. 播放：
   - 只出声音，不渲染画面。请确认 ExoPlayer 没有 Surface 时只解音轨、不解视频轨，避免后台白白耗电。如果需要，用 `TrackSelectionParameters` 禁用视频轨。
   - 进度、书签、续播、定时停止、来源检查、重新关联，都和音频一样工作。
4. 模型：`LocalAudioItem`（或 `LocalAudioMetadata`）增加 `kind: LocalMediaKind { AUDIO, VIDEO }`，导入时按是否有视频轨判定，旧数据迁移为 `AUDIO`。这是为以后“观看视频”留的标记：前端会据此显示“视频”标签，以后决定是否显示“观看”入口。

## 2 · 封面（P1，音频和视频都要）

1. 导入时取一张封面：
   - 音频：先取内嵌封面（`getEmbeddedPicture()`）。
   - 视频：先取内嵌封面，没有就截一帧（`getFrameAtTime`，取时长 10% 处或第 1 秒的关键帧，避开片头黑屏）。
2. 存储：缩放到最长边 512px 左右，存成 JPEG 或 WebP，放进应用私有目录的本地封面缓存。数据库只记相对路径或内容散列，不记 URI。删除条目时一并清理。
3. 暴露给前端：`LocalAudioItem.artworkKey: String?`（或 `artworkPath`），前端通过 Coil 加载（Coil 能直接读文件路径）。请在交付说明写清楚前端拿什么、怎么加载。
4. 已导入的旧条目：在“检查来源”或后台空闲时补取，或者给一个一次性的补取操作，二选一，写在交付说明里。
5. 播放时把封面带进 `MediaItem` 的 `artworkUri` / `artworkData`，这样系统媒体卡片和锁屏也有封面。

## 3 · 为“观看视频”预留（P2，本单只留接口，不做画面）

以后若做观看，需要的是：播放服务暴露一个能挂 Surface 的入口（Media3 的 `Player.setVideoSurface` 经 MediaController 已可用），以及 `kind == VIDEO` 的条目“横屏 / 全屏观看”页面。本单只要求：

- 不要做任何会妨碍以后挂 Surface 的决定。比如不要用一个只解音频的专用渲染器替换掉 ExoPlayer 的默认渲染器；按上面第 1.3 条，用轨道选择禁用视频轨即可，以后挂 Surface 时再启用。
- 交付说明里写一句：以后开启画面时需要改哪几处。

## 前端（CC）会做的

- 空状态和导入菜单的文案改成“音频或视频文件（只播放声音）”。
- 列表行给 `VIDEO` 条目加一个小“视频”标签；有封面时显示真实封面，没有时用占位。
- 复制模式下导入大视频时提示占用空间。
- 播放页、迷你播放器、锁屏页在本地条目有封面时显示封面。
- 以后的“观看”入口等 `kind` 字段交付后再定。

## 交付说明

2026-11-11：第 1、2 项后端已实现；第 3 项仅保留扩展路径。不改 `ui/`，不安装手机、不提交、不推送，手机安装窗口继续由 CC 持有。

### 视频导入与类型

- `LocalAudioPickers.files()` 现在为 `type="*/*"` 加 `EXTRA_MIME_TYPES = ["audio/*", "video/*"]`；`files(true)` 仍显示全部类型，`allowMultiple=false` 仍为单选。目录导入、持久关联目录刷新使用同一候选规则，新增 `mp4/m4v/mov/mkv/webm/3gp`。
- `LocalAudioMetadata.kind: LocalMediaKind` 默认为 `AUDIO`，`LocalAudioItem.kind` 为只读转发；按实际 `HAS_VIDEO == "yes"` 判定 `VIDEO`。视频 MIME、视频扩展或实际视频轨任一命中，都必须有 `HAS_AUDIO == "yes"`；无音轨复用 **`UNSUPPORTED_AUDIO`**，未增加枚举。视频无法读取轨道元数据时拒绝为 `READ_FAILED`，不走纯音频的可选标签容错。
- SQLite schema **v3** 加法迁移：v2→v3，v1→v2→v3；旧条目 `kind=AUDIO/artworkKey=null`，保留稳定 ID、别名、整理、进度、书签和 revision，不重建空库。
- 上限保留 **64 GiB/文件**。COPY 已按 64 KiB 流式复制、检查取消及可用空间，不需要为视频继续放宽；会保存完整原容器，没有转码或丢弃画面。REFERENCE 仍只持久授权。进度、续播、书签、定时、来源检查、重关联和原始字节导出继续复用原机制；导出视频需保留原扩展名并传入对应视频 MIME。
- ExoPlayer 保留默认渲染器，创建时通过 `trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)` 禁用视频轨，避免无 Surface 时仍选中视频解码器。文件扩展名受支持不代表其中任意编码均可解码，最终音轨支持取决于 Media3 和设备 codec。

### 封面与 CC 接法

- `LocalAudioMetadata.artworkKey: String?` 与 `LocalAudioItem.artworkKey` 暴露 64 位小写 SHA-256 或 null，数据库只记 key。图片位于私有 `filesDir/local-artwork-v1/<key>.jpg`，最长边 ≤512px、JPEG；不是普通缓存，不随平台退出或缓存清理删除。
- 前端使用 `app.localAudio.artworkFile(key): File?`，例如 `item.artworkKey?.let(app.localAudio::artworkFile)` 作为 Coil 的 `model`。请调用 resolver，不拼来源 URI；null 表示无封面或文件已不可用。该方法只返回派生图片，不暴露音视频来源。
- 音频先取内嵌图片；视频也先取内嵌图片，再在 `max(1秒, 时长10%)` 附近取关键帧，短视频限制在结尾前。嵌图先读取尺寸并采样；API27+ 直接请求有界缩放帧。API26 超过 400 万像素、尺寸未知或 codec 不支持时允许无封面，避免为缩略图完整分配超大帧。超大嵌图、提取/存储失败或内存不足均降级无封面，不阻止导入；取消仍终止本项。
- COPY 从受管副本提取；REFERENCE 从已授权文档提取。写临时文件、fsync 后原子发布；按内容去重。租约保护未提交的图，删除最后一个库引用、重关联换图/清图、失败/取消后再回收，启动时清理无主图片和遗留临时文件。仅从文件夹移出不删图，共享图片保留到最后一个引用移除。
- 本地 `MediaItem.mediaMetadata.artworkUri` 指向派生图片；应用 `PlaybackState.coverUrl` 同步提供本地图片 URI。Media3 的图片加载链路与音频的 opaque ticket resolver 分开，音视频原始 URI 不进入控制器。系统媒体卡片/锁屏能否实际显示仍需用本轮包在手机观察。

### 旧条目补取

选择独立的手动补取接口，**不把原有“检查来源”变成解码操作**：

```kotlin
localAudio.fillArtwork(audioIds, expectedRevision) { result -> /* Result<List<LocalAudioArtworkResult>> */ }
localAudio.artworkFill // StateFlow<LocalAudioArtworkState>
localAudio.cancelArtworkFill()
```

命令在主线程发起，IO 执行，正常结果 callback 回主线程；一次 1～500 个唯一稳定 ID，校验起始 revision，同一时刻只允许一批补取。全库需要前端分批、每批读取最新 revision。状态含 `running/total/processed/currentAudioId/results/completed/cancelled/error`；逐项结果含 `audioId/artworkKey/error`，key 与 error 均为空表示没有可提取图。已有可用封面直接复用，丢失图可重取。取消保留已完成项，不通过普通成功 callback 回报；来源变化、重新授权、删除或较新来源观察使迟到结果失效。封面更新提升普通库 revision，**不改变音视频 sourceVersion** 或进度/书签；已经安装的播放队列是快照，补图后重新点播/安装队列才带入新封面。

整理备份仍为 v1，**不包含音视频本体、封面资源或新增 kind**；恢复仅将整理信息合并到已导入的匹配条目，不覆盖当前条目的真实 kind/封面。没有因此升级备份格式。

### 以后开启画面要改的位置

1. `MusxiPlaybackService`：在明确进入观看模式并挂好 Surface 后，为当前 `VIDEO` 条目重新启用视频轨；退出观看时禁用，处理后台/锁屏与输出生命周期，继续共用当前 ExoPlayer。
2. `PlaybackConnection` / MediaController 与服务的 Session 包装层：补观看模式命令、可用命令及 Surface 挂载/解绑、页面重建与服务重连；核对 Media3 版本和进程布局下的实际 Surface 行为，不能仅启用视频轨就视为完成。
3. CC 的 `kind == VIDEO` 观看入口与全屏/横屏页面：绑定同一 Controller、Surface 和已有进度/队列，不另建播放器。现有 COPY 保留完整原文件，REFERENCE 保留原文档，存储层无需重新导入或抽取丢失的画面。

### 验证范围与证据

本轮结果如下，不沿用前轮测试数：

- 定向本地库/封面/播放服务 **205 项通过**（app 182 + playback 23），0失败/错误/跳过。覆盖视频候选/实际轨道类型、无音轨拒绝、音频标签容错、选择器MIME与目录候选；真实SQLite v1/v2→v3与重开库保全；图片采样/缩放/有界取帧请求、内容去重与租约/回收；COPY完整字节、REFERENCE重关联、补图取消/删除/过期守卫；MediaItem封面与禁视频/保留音轨选择参数。轨道元数据和截帧边界使用 Robolectric fixture，不替代设备 codec。
- `scripts/verify.ps1` **通过**：七模块 **2,394 项 JVM 测试**，0失败/错误/跳过；Lint 0错误/130警告；debug主包和设备测试包编译通过，全树源码冻结检查通过。17份本轮生产源码/测试另有最终散列，交付后核对未变。
- 统一证据：`.local/artifacts/verification/20261111T125236604Z-13992/`（summary、各模块测试/Lint报告与冻结源码清单）。本轮索引 `.local/artifacts/local-media-20261111/final-summary.json`；定向报告在同目录 `targeted/reports/`，两次测试编译修正的日志也保留。最终 APK SHA-256：`7c06d7363e390d0c8ec605720d896f238fa3ddb7f4db9581bf054097d998c626`。
- 修改前必要快照 `.local/checkpoints/local-media-20261111/`，只供逐文件比较回退，不能覆盖其他未提交成果。交付后仅更新结果文档并重新检查链接，没有修改已验证源码。

尚未安装或执行手机测试，因此真实视频音轨解码/听音、OEM 选择器展示、真实嵌入封面/视频截帧、长视频/后台耗电及锁屏显示仍需同一新包实机验收。API26特殊保护已做自动化规则验证，未做该系统版本实机验证。没有新运行时依赖，release本轮未另行编译。
