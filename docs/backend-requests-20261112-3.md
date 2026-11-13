# 后端需求单 · 2026-11-12（三）：界面壁纸，与播放页壁纸分开（前端 → Codex）

> **状态：后端已交付，专项及官方统一验证通过（2026-11-12）。** 用户希望在播放页壁纸之外，再加一张常态的「界面壁纸」，铺在播放页和歌词页以外的所有页面：曲库、搜索、我的，以及二级页面。两者在「外观」里是两行，各有开关，互不影响。接口及验证边界见末尾交付说明。
>
> 完成后由 Codex 在末尾“交付说明”写清接口与验证边界。

读者：负责 core / app 后端层的 Codex。约定同前几批：不改 `app/src/main/kotlin/tech/musxi/player/ui/`（CC 负责）。行号以 2026-11-12 工作区为准，开工前复核。

## 用户已定的产品规则

- 名称为「界面壁纸」，在「外观」里排在「播放页壁纸」上方。
- **两份完全独立**：各自有图片、开关、强度、取景、预览草稿。播放页壁纸关闭时，播放页仍使用原来的封面取色渐变，**不会**沿用界面壁纸。
- 界面壁纸在曲库首页预览：选图、取景后跳到曲库首页，底部是强度条，加取消和应用。

## 现状（CC 已核对）

- `core/.../wallpaper/WallpaperRepository.kt`：只有一份 `SavedWallpaper`。`collect()` 用 `store.prune(saved + candidate)` 回收图片，**其他图片都会被删除**。
- `FileWallpaperStore`：目录 `filesDir/wallpapers-v1/`，清单 `current.wallpaper`（v2），图片以内容 SHA-256 命名。
- `WallpaperPreferences.scope` 为 `PLAYER/GLOBAL`。前端从没写过 `GLOBAL`，现有用户的清单里都是 `PLAYER`。
- `AppWallpaperRepository` 提供 `choose / preview / setPreferences / apply / cancel / reset / imageFile / imageFailed`，它们在 IO 上串行执行，回调回到主线程。前端已按这套顺序接好播放页壁纸。

## 1 · 两份壁纸（P1）

建议接口如下，名称可以调整，写进交付说明：

```kotlin
// MusxiApplication
val wallpaper: AppWallpaperRepository          // 保持不变 = 播放页壁纸，现有前端接线不动
val interfaceWallpaper: AppWallpaperRepository // 新增 = 界面壁纸，同一个类、同一套方法和状态
```

两个实例**一模一样**，前端会把现有 `WallpaperHost` 复用为两份：

1. 每份壁纸自己的 `saved / candidate / previewing / draft / busy / error` 互不影响。其中一份正在预览时，另一份的 `setPreferences / apply / reset` 照常工作；一份 `cancel / reset` 不会改动另一份的状态。
2. `choose`、`preview`、`apply` 等的语义与现有合同完全一致，包括：换候选时 frame 归零，没有预览时 `setPreferences` 直接保存，以及 `STALE_CANDIDATE` 和 `INVALID_ARGUMENT` 两个错误。
3. 两份可以同时预览（例如一份开着预览、另一份在后台调用 `setPreferences`），不能出现一份的预览草稿写进另一份。前端不会刻意制造这种情况，这条只是防御。

## 2 · 共用图片池（P1）

1. 图片继续放在 `wallpapers-v1/`，按内容散列命名。两份用同一张图时**只存一份文件**。
2. 回收必须看两份的全部引用：`prune` 要保留两份各自的 saved 和 candidate。界面壁纸 `reset`、`cancel` 或换图，都不能删掉播放页仍在用的图片，反过来也一样。启动时的孤儿回收同理。
3. 两份对同一目录的写入、回收要在同一个串行队列里执行，避免一份写清单的同时另一份删了文件。
4. `imageFailed(id)` 只影响调用它的那份。如果图片文件确实损坏，另一份会在自己加载时发现，并自行调用 `imageFailed`。前端两边各自加载、各自上报。

## 3 · 「用播放页那张」（P2）

界面壁纸的缩略图菜单里有「用播放页那张」，播放页壁纸的菜单里有对称的「用界面那张」，用户不需要再从相册选一次。

```kotlin
// 在目标那份上调用：把另一份已保存的图片设为本份候选，不重新导入、不复制文件
fun chooseSaved(from: AppWallpaperRepository, result: (WallpaperError?) -> Unit = {}): Job
```

接口形状可以调整，例如传对方的 asset id。语义等同于一次成功的 `choose`：成为本份 `candidate`，草稿里的 frame 归零，其余草稿保留；当前已应用的背景不变。之后前端走同样的 `preview → setPreferences(frame) → apply`。对方没有已保存图片时返回 `NO_IMAGE`，对方图片已经不可用时返回 `SAVED_IMAGE_UNAVAILABLE`。不能借这个接口读取任意 id 或路径。

## 4 · 持久化与迁移（P1）

1. 播放页沿用 `current.wallpaper`，**不迁移、不改写**。现有用户的图片、开关、强度、取景原样保留。
2. 界面壁纸使用新清单，例如 `interface.wallpaper`，格式、校验方式和原子替换都与现有清单相同。没有这个文件时，读成默认值（无图片、关闭），不能判为损坏。
3. `scope` 以后由所在的那份决定，前端不再读写它。旧清单里的值照常读出，不报错；新清单里 `scope` 写什么值、是否从结构里去掉，由 Codex 决定并写进交付说明。如果清单里遇到 `GLOBAL`，按播放页处理，不自动复制成界面壁纸。
4. 清除封面、歌词、曲库缓存，以及退出或切换账号，两份壁纸都不受影响（与现状一致）。

## 5 · 不需要改的

图片导入、格式、尺寸上限、EXIF 归一、`WallpaperFrame` 定义与校验、强度范围 0～100 及默认 35、错误码文案边界，这些都不变。界面壁纸的蒙层上限、对比度保底和偏暗提示由前端处理，后端不需要知道窗口比例或页面类型。

## 前端这边的配合（CC 负责，供参考）

- 「外观」里新增「界面壁纸」一行，样式与播放页壁纸相同：左侧缩略图，右侧开关。点缩略图打开菜单：调整位置 / 调整强度 / 换一张 / 用播放页那张 / 恢复默认。
- 图片铺在 `MainShell` 的背景层，不跟随列表滚动。顶栏和底栏的玻璃会把壁纸模糊透出来；透明效果关闭时，它们改为实色。蒙层比播放页更重，保证列表文字清晰。
- 后端交付前，前端先用现有单实例把界面做出来，交付后改接 `interfaceWallpaper`。

## 验证建议

- 两份各自完成选图、预览、应用、取消，互不影响。
- 同一张图被两份同时使用时，只存一份文件。一份 `reset` 或换图之后，另一份的图片仍在、仍能读取，冷启动也一样。
- 只有旧 `current.wallpaper` 时，冷启动播放页原样保留，界面壁纸为默认值。
- `chooseSaved` 的正常与失败分支都要覆盖。
- 两份交错调用时，孤儿回收不会误删正在用的图片。

不需要安装手机。

## 交付说明

### 接口与独立状态

P1 第 1/2/4 项和 P2 第 3 项均已实现。`MusxiApplication.wallpaper: AppWallpaperRepository` 保持播放页用途与原有调用，新增同类实例 `MusxiApplication.interfaceWallpaper`。两边的 state 与 choose/preview/setPreferences/apply/cancel/reset/imageFile/imageFailed 签名及回调方式一致，各自保存 enabled、strength、frame、saved、candidate、previewing、draft、busy、error；可同时预览，一份直接保存、应用、取消、重置或加载器失败不会修改另一份。

换候选成功时本份 frame 居中、zoom 1，其余草稿保留；未预览时 setPreferences 直接写本份已应用图片设置，预览中只改本份 draft。STALE_CANDIDATE/INVALID_ARGUMENT、保存失败保留草稿、关闭保留图片与位置、整图导入/EXIF/尺寸限制均沿用前轮。播放页关闭后的封面取色渐变、界面页排除播放器/歌词、曲库首页预览和界面蒙层由 CC 按所属实例接线；后端不会用另一份壁纸兜底。

两份共用应用级 `AppWallpaperPool`：同一 filesDir 的旧式 `AppWallpaperRepository(context)` 构造也加入该池，不会另起队列或回收器。初始化、导入暂存清理、图片准备、所有命令及图片读取/失败上报共用一个 Mutex，IO 执行、主线程回调，仍返回 Job。各自 busy 只表示对应命令已开始执行，等待共享队列的命令尚不置 busy；不能用另一份 busy 推断本份草稿状态。

### 共享图片、回收与清单兼容

core 新增 `WallpaperLibrary(directory: File)`，提供 player/interfaceWallpaper 与 load()；两份 WallpaperRepository 共用一个 monitor，覆盖整条命令的图片文件发布、状态发布和回收。启动先读两份清单，再启用孤儿回收；回收取双方 saved + candidate 的并集，包括禁用图和未预览候选。只有最后一个引用释放后才删除；一份候选被取消、重置、换图或加载失败都不会删掉另一份的引用。需要直接使用 core 时统一创建 WallpaperLibrary，不对同一目录自行创建两个独立 WallpaperRepository。

图片仍按内容 SHA-256 放在 `filesDir/wallpapers-v1/`，相同受管图片字节只保留一份文件。播放页继续用 `current.wallpaper`，新增界面清单 `interface.wallpaper`；FileWallpaperStore 增加默认 PLAYER 的 WallpaperSlot 参数以选择固定文件名，没有任意路径入口。清单仍读 v1/v2、写 v2，CRC、fsync、原子替换不变，未升级格式版本。缺少界面清单时读为无图/关闭默认值，读取本身不创建清单。

**不迁移、不改写、不复制旧播放页清单。** 原图片、开关、强度、取景及 scope 原样读出；scope 保留在结构及二进制中作为兼容字段，新默认值仍为 PLAYER，原值照常携带写回。用途由所属实例决定，前端不再读取或设置 scope；即使旧值是 GLOBAL 也只作为播放页壁纸，不自动生成界面壁纸。一份清单加载失败只发布自己的错误，仍读取并保护另一份合法图片。普通缓存清理、账号退出/切换仍不触及两份私有壁纸数据，未增加账号绑定或清理入口。

### “用另一张”的调用

在目标实例上调用：

```kotlin
app.interfaceWallpaper.chooseSaved(app.wallpaper) { error -> /* 成功后进入相同预览流程 */ }
app.wallpaper.chooseSaved(app.interfaceWallpaper) { error -> /* 反向同样支持 */ }
```

完整签名为 `chooseSaved(from: AppWallpaperRepository, result: (WallpaperError?) -> Unit = {}): Job`。只取来源已保存的 asset，来源禁用仍可；不取来源 candidate/draft，不继承来源开关/强度/取景，不重新导入、解码、复制或写清单。成功设为目标 candidate，目标旧 saved 不变，已有草稿仅 frame 重置。随后 preview(expectedId) → setPreferences(本份最新 effectivePreferences.copy(frame = …)) → apply(expectedId)，取消仍走 cancel。

来源无已保存图片返回 NO_IMAGE（仅有候选也不取）；来源清单加载失败或保存图片校验失败返回 SAVED_IMAGE_UNAVAILABLE；跨受管池对象返回 INVALID_ARGUMENT。失败更新目标错误，保留两份的 saved/candidate/draft；来源状态不变。没有按任意 ID 或路径取文件的接口。imageFailed(id) 仍只影响调用方，即使双方引用同一文件；对方由自己的加载器发现并上报。

### 验证范围

最终壁纸专项 **57 项通过，0 失败/错误/跳过**：双份真实文件集成 11 项、Android facade 5 项、原事务 15 项、文件存储 14 项、图片检查 4 项、Android 图片导入 8 项。覆盖旧 current（含 GLOBAL 和非默认 frame）字节不变、界面缺清单默认、双预览/独立应用取消、共享图去重、保存/候选全部引用与最后释放、禁用保留、冷启动、chooseSaved 正常/无图/损坏图/坏清单/跨池、取 saved 而非 candidate、局部 imageFailed、同目录旧构造加入共享池及主线程回调。另有双线程交错回归和受闩锁控制的 stage/reset 测试，验证回收不能插入图片落盘与候选状态发布之间。

首轮专项为 56 项通过，随后补充清单损坏分支及确定性串行用例，重跑最终 57 项；最终结果才对应交付源码。证据 `.local/artifacts/interface-wallpaper-20261112/targeted-final/`，9 份源码/测试散列在 `.local/artifacts/interface-wallpaper-20261112/delivery-source-files.json`。

主目录官方 `scripts/verify.ps1` **通过：2,575 项 JVM 测试，0 失败/错误/跳过；Lint 0 错误、135 警告**，debug 主包与设备测试包编译及全树源码一致性检查通过。证据 `.local/artifacts/verification/20261112T113204525Z-24500/`，阶段索引 `.local/artifacts/interface-wallpaper-20261112/final-summary.json`。9 份后端源码/测试与最终专项、官方验证清单及交付时磁盘一致；结果回填后另核对 6 份文档散列、15 份本轮文件的差异空白检查及 121 份 Markdown 链接。前轮取景任务的 MainShell Lint 失败保留为历史记录，本轮重新验证已通过，没有修改或绕过其 UI/Lint 配置。

保留本轮验证 APK `.local/artifacts/interface-wallpaper-20261112/app-debug.apk`，29,290,883 bytes，SHA-256 `70ff5f057a805b953207d6a0263ffd92f00ad629e883dec95ad37b819b88a5b0`；复制时持共享构建锁，并与官方摘要核对相同，未安装。修改前快照 `.local/checkpoints/interface-wallpaper-20261112/` 只用于差异和必要回退，保留既有未提交成果。没有新增运行时依赖或改动签名、包名及版本号。

没有改 `ui/` 或 CC 测试，没有安装手机、提交、推送或创建工作树。系统选图、两份正式页面范围/预览路由、界面壁纸不透进播放页、复杂背景对比度、手势与横竖屏/分屏及设备性能待 CC 接线后同包验收；JVM/Robolectric 或设备测试包编译不替代这些结论。[现行合同](frontend-backend-contract-wallpaper.md)已同步。
