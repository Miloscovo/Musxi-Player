# 后端需求单 · 2026-11-12：手动封面自选裁切位置（前端 → Codex）

> **状态：当前需求单（2026-11-12）。** 用户上机后反馈：「更换封面」选图后，显示的位置是自动定的（实际是整图入库、显示时居中裁切），希望能自己选取图片的哪一块作为封面。前端将在选图后加一个**裁切页**（方形取景框，双指缩放、拖动定位），把用户框定的区域交给后端裁切入库。
>
> 完成后由 Codex 在末尾“交付说明”填写接口与验证边界。

读者：负责 core / accounts / app / playback 后端层的 Codex。约定同前几批：不改 `app/src/main/kotlin/tech/musxi/player/ui/`（CC 负责）。行号以 2026-11-12 工作区为准，开工前复核。

## 现状（CC 已核对）

- `AppLocalAudioRepository.setCustomArtwork(audioId, image, grantedFlags, result)` → `AndroidLocalAudioArtwork.prepareImage(uri)` → `LocalArtworkImages.decodeImage(...)`：先读尺寸、采样解码、`scaleToMaxEdge(…, 512)` 保持原始宽高比后编码 JPEG。**不裁切**，所以横图、竖图都整张存下，列表/播放页再用 `ContentScale.Crop` 居中显示。
- `decodeImage` 用 `BitmapFactory`，**没有应用 EXIF 方向**。手机竖拍的 JPEG 常以横向像素加 `Orientation=6/8` 保存，现在会被存成躺倒的封面。壁纸导入 `AndroidWallpaperImporter` 已有 EXIF 归一，可参照。（这一条单独也是缺陷。）

## 1 · 带裁切区域的手动封面（P1）

1. 新增可选参数（名称可调整，写进交付说明）：

   ```kotlin
   data class LocalArtworkCrop(val left: Float, val top: Float, val right: Float, val bottom: Float)
   localAudio.setCustomArtwork(audioId, imageUri, returnedIntent.flags, crop = LocalArtworkCrop(...)) { result -> }
   ```

   - 坐标是**按 EXIF 方向摆正之后**图片的归一化比例（0～1），左上为原点。前端用 Coil 显示预览，Coil 会摆正方向，所以前端给出的就是摆正后的坐标。
   - `crop = null` 维持现在的行为（整图入库），兼容已有调用。
2. 校验：四个值都有限，`0 ≤ left < right ≤ 1`、`0 ≤ top < bottom ≤ 1`；非法为 `INVALID_TARGET`。前端保证区域在摆正后的像素里是正方形，后端按给定区域裁，不强制正方形（允许取整误差），输出可以是区域原样的宽高比。区域太小（例如裁切后短边不足 64px）请定一个下限并说明错误码。
3. 内存：建议对裁切区用 `BitmapRegionDecoder`（或等效做法）按区域解码并配合 `inSampleSize`，让解码结果略大于 512 即可，不先整图解码再裁。保留现有的 24MiB 读取预算、3200 万像素 / 16384px 上限和两次开流限制；若区域解码需要多开一次流，请说明。
4. 输出同现在：最长边 ≤512 的 JPEG，内容散列去重、租约、fsync、原子发布；手动图优先级、OFF 下仍显示、`clearCustomArtwork` 语义都不变。
5. 只读取图片本身，不保存来源 URI、不持久授权，同现有约定。

## 2 · 选图路径应用 EXIF 方向（P1，与第 1 项同批）

`crop = null` 时也要先按 EXIF 摆正再缩放。旋转/镜像后再按第 1 项的坐标裁切。HEIC/WebP 等格式的方向信息能读到多少请说明；读不到时按原像素方向处理，不报错。

## 前端这边的配合（CC 负责，供参考）

- 选图返回后进入裁切页：方形取景框，初始为“短边铺满、居中”；双指缩放（最小为短边铺满，最大约 4 倍）、单指拖动，图片不能拖出取景框。点“使用”时把取景框换算成摆正后图片的归一化坐标传给第 1 项接口。
- 裁切页只在内存中持有这次的 URI（不写入可保存状态）；Activity 重建时直接关闭裁切页，不重放。
- 后端交付前，前端先把裁切页做好、`crop` 暂不传，交付后接上。

## 验证建议

横图、竖图、方图；EXIF 为 1/3/6/8 和镜像的 JPEG；裁在四角和正中；区域贴边（right=1、bottom=1）；非法区域；超大图在裁切区域解码时的内存；取消、权限撤回、恢复默认与更换封面的先后竞争。不需要安装手机，CC 会接好后用同一个新包上机。

## 交付说明

### 接口与兼容

新增公开值类型 `tech.musxi.player.localaudio.LocalArtworkCrop(left: Float, top: Float, right: Float, bottom: Float)`，通过以下入口传入：

```kotlin
localAudio.setCustomArtwork(audioId, imageUri, returnedIntent.flags,
    crop = LocalArtworkCrop(left, top, right, bottom)) { result -> /* 主线程 */ }
```

`crop` 是按 EXIF 摆正后的图片坐标，左上原点、范围0～1。`crop = null` 整图入库、保持宽高比，但修正 EXIF 方向；不会额外居中裁切。旧的三参数、尾随回调、第四参数位置回调均通过原签名重载保留，旧调用无需改动。类型构造不抛异常，后端命令校验后以 `Result.failure(LocalAudioFailure(...))` 回报；`Job` 取消沿原语义停止且不回调普通成功。

四值须有限，`0 ≤ left < right ≤ 1`、`0 ≤ top < bottom ≤ 1`。非法区域为 `INVALID_TARGET`，不读取来源、不替换旧封面。摆正后的像素边界按左/上floor、右/下ceil向外取整并限定在图内；取整后短边至少 **64px**，不足同样为 `INVALID_TARGET`。不强制正方形，不放大，输出保留所选区域宽高比。64px下限仅应用于非空crop；原来可用的小整图仍可在null模式导入。贴边right/bottom=1可用。

### 解码、方向与文件生命周期

- 选图源提供者只开 **一次流**，总读取仍受24MiB预算限制；使用16KiB缓冲复制到私有 `filesDir/local-artwork-v1/part-<UUID>.tmp`。本地临时文件分别用于尺寸、EXIF和像素读取，不增加提供者开流次数；不会将整份压缩图放进Java字节数组，也不保存URI或持久授权。临时源与输出共用受管暂存名/在用保护，完成、失败、取消均清理，异常退出后由既有prune回收。
- 解码前仍拒绝原图超过3200万像素或任一边超过16384px，即使所选区域很小也不绕过源图上限。裁切先把摆正坐标逆映射回原图区域，再以 `BitmapRegionDecoder` 及区域自身尺寸计算 `inSampleSize`，只解码所选区域，随后旋转/镜像和缩放；从视觉结果看等同先摆正后裁切。采样结果最长边约≤1024px，最后按既有管线编码为最长边≤512px JPEG。
- null使用原有整图采样语义，新增相同EXIF方向归一。八种标准方向（含水平/垂直镜像、transpose/transverse）均处理。使用系统 `android.media.ExifInterface`，没有新增依赖；HEIC/HEIF、WebP、PNG等的方向读取取决于系统版本及容器，能读到标准方向即应用，读不到/未知方向按原像素方向。图片是否可解码/可区域解码仍取决于系统；裁切格式不支持区域解码时返回 `READ_FAILED`，不退回整图解码再裁。
- 内容散列、共享去重、租约、fsync、原子发布沿原管线；最终JPEG不带原照片EXIF/位置元数据。手动图仍优先且OFF时显示，`clearCustomArtwork`只清手动选择，不启动自动提取。读取失败/来源预算或尺寸超限为 `READ_FAILED`，权限撤回为 `PERMISSION_REQUIRED`；失败保留旧封面。较新选择、恢复默认或删除使旧操作不能迟到提交。无需改数据库schema，自动音视频封面提取路径未新增裁切。

### 验证范围与交付状态

定向 **70项通过**（几何15、原生图片8、原封面22、仓库17、壁纸回归8），0失败/错误/跳过。包含八种EXIF方向及四角实际像素、非方/居中/贴边区域、非法值和63/64px边界、4096×3072图取约128px小区域、区域采样及512px输出、JPEG/PNG/WebP、元数据移除、一次开流/关闭、取消、恢复默认和较新选择竞争；也验证复制中的临时源受并发prune保护。图片测试使用Robolectric SDK35原生图形，不能替代手机解码或峰值内存测量。

主目录官方 `scripts/verify.ps1` **统一2,510项全过**（core578、accounts55、app1388、playback160、酷狗147、网易云70、QQ112），0失败/错误/跳过；Lint0错误/132警告，debug主包及设备测试包编译通过，全树源码冻结通过。本轮8份后端源码/测试与验证启动清单及交付时磁盘一致；没有新建隔离树。证据为 `.local/artifacts/verification/20261111T175423080Z-27080/`，索引 `.local/artifacts/artwork-crop-20261112/final-summary.json`。结果文档在验证后更新，另作链接/空白检查；没有继续改业务源码。

保留APK `.local/artifacts/artwork-crop-20261112/Musxi-debug-artwork-crop.apk`，28,814,624 bytes，SHA-256 `545fdad1d0f73df6f655c2436645997d48ca6b18a6ad73fa0c4f265bcb1eebeb`，未安装。修改前回退点为 `.local/checkpoints/artwork-crop-20261112/`；本轮失败尝试及修正另保留在验收目录：`LocalAudioFailure`继承`IOException`，需先重抛以保留区域错误码；读取路径改显式流后，临时源及测试输出的文件删除断言通过，没有弱化清理要求。

本轮不改 `ui/` 或CC测试，不安装手机、不提交、不推送。HEIC设备样本、Android版本/厂商差异、真实权限撤回、峰值内存、Coil预览到裁切坐标的一致性、真人手势及同包锁屏仍由CC接线后实机验收。取消以协程检查及旧选择守卫保证不提交迟到结果，不能强制中断已经进入的系统解码调用；其返回后释放结果。
