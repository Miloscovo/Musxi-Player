# 后端需求单 · 2026-11-12（二）：壁纸取景位置（前端 → Codex）

> **状态：后端已交付（2026-11-12），壁纸专项通过；统一验证受 CC 界面 Lint 错误阻挡。** 用户确认壁纸第一版就要能自己定显示哪一块。手机是竖屏，横图铺满时只能显示其中一段。前端会在选图后进入一个取景页（复用本地封面的取景交互，框改成屏幕比例），把用户定下的位置交给后端保存。接口和验证边界见末尾交付说明。
>
> 完成后由 Codex 在末尾“交付说明”填写接口与验证边界。

读者：负责 core / app 后端层的 Codex。约定同前几批：不改 `app/src/main/kotlin/tech/musxi/player/ui/`（CC 负责）。行号以 2026-11-12 工作区为准，开工前复核。

## 现状（CC 已核对）

- `core/.../wallpaper/WallpaperModels.kt`：`WallpaperPreferences(enabled, scope, strength)`；`WallpaperState` 有 `saved / candidate / previewing / draft`，`effectivePreferences = draft ?: saved.preferences`。
- 受管图片是整张图（摆正 EXIF、缩到单边 ≤2560、≤400 万像素）。前端用 `ContentScale.Crop` 居中铺满，没有位置信息。

## 1 · 取景位置（P1）

**存位置，不裁图。** 同一张壁纸要适配不同窗口比例：分屏、横屏、折叠屏，以及以后的全局背景。所以后端保留整图，只记一个取景位置，由前端按当前窗口比例算出显示区域。

1. 新增值类型（名称可调整，写进交付说明）：

   ```kotlin
   data class WallpaperFrame(
       val centerX: Float = 0.5f,   // 取景中心，受管图片（已摆正）的归一化坐标 0～1
       val centerY: Float = 0.5f,
       val zoom: Float = 1f,        // 1 = 按窗口比例刚好铺满；最大 4
   )
   ```

   前端的换算规则，供参考，后端不需要实现：窗口比例为 r（宽/高）时，zoom=1 的显示区域是图中能容纳的最大 r 比例矩形，zoom 越大区域越小，中心尽量靠近 (centerX, centerY)，并被限制在图内。所以同一个 Frame 在任何比例下都有合法结果，后端不需要知道屏幕比例。

2. 放进设置：`WallpaperPreferences.frame: WallpaperFrame = WallpaperFrame()`。也可以单独放，但必须和 `strength` 走同一套 draft、apply、cancel 语义：
   - 预览中 `setPreferences(draft.copy(frame = …))` 只改草稿；`apply(expectedId)` 时和候选图一起保存；`cancel()` 时丢弃。
   - 没有预览时，`setPreferences` 直接保存当前图片的 frame。前端会在“调整位置”入口用到它：已应用的壁纸重新取景，不需要重新选图。
3. **位置跟着图片走**：`choose()` 换了新候选图时，草稿里的 frame 重置为默认值（居中、zoom 1），不能把上一张图的位置套到新图上。`reset()` 时同样回到默认值。
4. 校验：三个值都必须是有限数，`centerX`、`centerY` 在 0～1 之间，`zoom` 在 1～4 之间。不合法时请定一个错误码（建议复用或新增一个“参数无效”类错误），并写明。前端不会发出越界值，这条只是防御。
5. 持久化：写进现有带校验的设置清单。旧清单没有这个字段时读成默认值，不能因此判成损坏或丢掉已存壁纸。

## 2 · 不需要改的

图片导入、尺寸上限、格式、EXIF 归一、存储目录、`imageFile / imageFailed`、与透明效果和主题色相互独立，这些都不变。

## 前端这边的配合（CC 负责，供参考）

- 流程：选图 → 取景页（屏幕比例的框，双指缩放、拖动，可以“换一张”）→ 进入真播放页预览，底部玻璃条上有强度滑条、“调整位置”、“取消 / 应用”。
- 外观页“播放页壁纸”这一行：缩略图 + 开关；长按缩略图或在预览条里可以“调整位置 / 换一张 / 恢复默认”，和本地封面的长按逻辑一致。
- 后端交付前，前端先按居中做好，frame 暂时不传，交付后接上。

## 验证建议

默认值与旧清单兼容；预览中改 frame 后取消，能回到已存值；apply 后冷启动能读回；换候选图时 frame 重置；越界值和非有限数被拒；`setPreferences` 在无预览时直接保存 frame；与 strength、enabled 同时修改不会互相覆盖。不需要安装手机。

## 交付说明

### 接口与取景语义

- 新增 `tech.musxi.core.wallpaper.WallpaperFrame(centerX: Float = 0.5f, centerY: Float = 0.5f, zoom: Float = 1f)`；`WallpaperPreferences` 末尾新增 `frame: WallpaperFrame = WallpaperFrame()`，原三参数调用保持可用。`MusxiApplication.wallpaper` 的 `setPreferences/preview/apply/cancel/reset` 签名不变，仍返回 Job、IO 串行执行、主线程回调。
- centerX/Y 指已摆正 EXIF 的整张受管图片归一化中心，范围包含 0 和 1；zoom 范围包含 1 和 4，1 表示按当前窗口比例刚好铺满。后端只存这三个值，不接收屏幕比例，不把中心按某个窗口提前夹紧。前端按当前窗口比例计算显示区域并限制在图内；图片仍沿用原受管整图，不裁剪、不重新编码、不改变内容散列、路径或尺寸。
- 三个值均须有限。`setPreferences` 对 NaN、正负无穷和越界值返回新增 `WallpaperError.INVALID_ARGUMENT`，更新 state.error，保留原 saved/draft，不写盘。值对象允许构造非法值，由命令边界校验并走正常回调；存储层也独立校验。磁盘里已存非法参数属于 `SAVED_IMAGE_UNAVAILABLE`，不会被静默改成默认。

### 草稿、应用与已有图片调整

- 预览中基于最新 `state.value.effectivePreferences.copy(frame = frame)` 调用 setPreferences，只改草稿；frame、strength、enabled、scope 一起随 apply(expectedId) 原子保存。cancel 丢弃本次草稿/候选，进程重启只读最后成功保存的值。保存失败保留旧设置及待应用草稿，过期 ID 仍返回 `STALE_CANDIDATE`。
- choose 成功换图时，现有草稿只重置 frame，保留强度/范围/开关；换图失败不重置。没有预览时 choose 不创建草稿、不改变已应用图片显示，之后第一次 preview 候选时从居中、1 倍开始。重复 preview 不再覆盖现有草稿，避免取景页返回真播放预览时丢失位置或强度。
- 选图后的取景页若先在前端记录 Frame，接线顺序为 `choose → preview(expectedId) 成功回调 → setPreferences(effectivePreferences.copy(frame = frame))`。没有预览的 setPreferences 始终直接保存当前已应用图片的设置，不用于保存候选草稿。
- 已应用图片无需重新选图：用 saved.asset.id 进入 preview，可从原位置继续调整，apply 保存、cancel 恢复。也支持无预览直接 setPreferences 保存当前图片位置。reset 恢复全部默认值，包括居中、1 倍；仅关闭壁纸仍保留整图和位置。

### 旧清单兼容与交付范围

私有 `filesDir/wallpapers-v1/current.wallpaper` 的目录、文件名、CRC 和原子替换方式不变。读取支持格式 v1/v2；v1 没有 frame，读取为默认值，原图片、enabled、scope、strength 全部保留，读取本身不改写清单；下一次保存写 v2，在 strength 后追加三个 Float。v2 缺字段、非法值、未知版本、尾随内容和校验失败仍拒绝。只保证新版读旧版；只认识 v1 的旧 App 不保证能读新版清单。

修改限定为 core 的模型、事务和文件清单及对应测试；Android facade 直接透传新增设置，不需加接口。图片导入/EXIF/格式与上限、imageFile/imageFailed、透明设置和主题色均沿用现有行为；没有修改 `app/src/main/kotlin/tech/musxi/player/ui/` 或 CC 测试，没有安装手机、提交或推送。CC 接线详见[壁纸合同](frontend-backend-contract-wallpaper.md#取景位置与调整已有图片)。

### 验证范围

壁纸专项 **41 项通过，0 失败/错误/跳过**：core 文件存储 14 项、事务 15 项、图片检查 4 项，Android 图片导入 8 项。覆盖旧 v1 有图/无图清单及首次升级保存、非默认 frame 冷读、已有图预览调整/取消/应用、换候选重置、重复预览保留草稿、无预览直接保存、重置、非法参数无副作用、保存失败、过期 ID 和整图字节不变。证据 `.local/artifacts/wallpaper-frame-20261112/targeted/`。

已运行官方 `scripts/verify.ps1`，**本次统一验证未通过**：全量 JVM 测试任务 `:verifyUnitTests`、debug 主包及设备测试包编译已完成，随后 `:app:lintDebug` 报错：`app/src/main/kotlin/tech/musxi/player/ui/MainShell.kt:1102` 的 `SuspiciousIndentation`（前一语句在 1093 行）；app Lint 为 1 错误、114 警告、2 提示。该 UI 文件未由本轮修改，也未加入忽略规则或基线绕过。CC 修复后需要重新运行统一验证，不把本次已完成任务拼成整包通过。

失败证据 `.local/artifacts/verification/20261112T074944556Z-21800/`；官方失败后的最终 XML 汇总、APK 散列和全树冻结检查没有执行，所以不报告本次全量测试数、全模块 Lint 汇总或成功冻结结论。尝试按共享构建锁保留全量报告时锁已被其它构建持有，等待 30 秒仍未释放，因此没有复制可能被替换的报告或 APK。5 份本轮后端源码/测试已另行核对，与专项结束时、官方验证开始时及交付时散列一致。阶段索引 `.local/artifacts/wallpaper-frame-20261112/final-summary.json`；修改前快照 `.local/checkpoints/wallpaper-frame-20261112/` 保留既有未提交成果，不是独立开发入口。

系统选图、正式取景页手势/显示坐标、横竖屏/分屏适配及同包设备性能由 CC 接线后验收；本轮不安装手机，自动化结果不替代视觉或实机结论。
