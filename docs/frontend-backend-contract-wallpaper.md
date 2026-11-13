# 自定义静态壁纸：后端接线合同

状态：2026-11-12 增加独立界面壁纸，与播放页壁纸共用图片池；两份强度上限放宽至150。正式页面、背景及取景绘制由 CC 接线。没有业务服务器，不改系统桌面或锁屏壁纸。

## 入口与状态

两份入口均为 `AppWallpaperRepository`，独立于账号与 `AppSettingsRepository`：

| 入口 | 用途 | 清单 |
| --- | --- | --- |
| `MusxiApplication.wallpaper` | 播放页壁纸，覆盖全屏播放器与歌词；现有调用保持不变 | `current.wallpaper` |
| `MusxiApplication.interfaceWallpaper` | 界面壁纸，覆盖曲库、搜索、我的及二级页面，排除播放页和歌词页 | `interface.wallpaper` |

首次访问任一入口都会异步读取两份清单，全部读取结束后才允许回收图片；只打开界面壁纸也不会回收尚未读取的播放页图片，反向同理。界面分别收集各自的 `state: StateFlow<WallpaperState>`，等待对应 `loaded` 后开放操作。所有导入、存储、检查在 IO 线程，两份共用一个 Mutex 串行队列，回调回到主线程。操作返回 `Job`，回调参数为可空 `WallpaperError`：空表示成功；取消协程不转换成业务错误。

每份都有独立的 saved/candidate/previewing/draft/busy/error，可以同时预览；一份取消、重置、失败或应用不会改另一份状态。`saved` 是最后成功应用的图片和设置；`candidate` 是已处理但尚未应用的图片；`previewing` 与 `draft` 只在内存存在。`effectiveAsset`、`effectivePreferences` 给当前渲染使用，缩略图使用 `candidate ?: saved.asset`。`busy` 时禁用对应实例的重复操作，仍允许用户取消协程。图片 ID 为内容 SHA-256，持久化中没有外部 URI、相册路径或原始图片元数据。

`WallpaperPreferences`：

| 字段 | 默认 | 语义 |
| --- | --- | --- |
| enabled | false | 关闭时保留已存图片，重新打开无需再次选图；无图不能开启 |
| scope | PLAYER | 只保留为旧清单兼容字段；PLAYER/GLOBAL 都照常读写，用途由所属实例决定，前端不再读写它 |
| strength | 35 | 0～150 的图片可见强度意图，上限读取 `WallpaperPreferences.MAX_STRENGTH`；100以上的画面映射与提示由前端负责，后端不把它换算成透明度或裁切 |
| frame | `WallpaperFrame()` | 整张受管图片的取景意图，与强度共用草稿和保存流程；定义见下文 |

两份壁纸与 `transparencyEnabled` 独立。关闭透明、旧系统或省电实色材质回退仍保留壁纸；前景材质沿用现有能力分档。开关壁纸不改变主题、玻璃、封面取色或歌词设置。播放页壁纸关闭时恢复原封面取色渐变，不使用界面壁纸兜底；后端不会自动在两份间继承、复制或启用图片。

强度值对象的构造和 `copy(strength = ...)` 允许越界值；`setPreferences` 在命令边界检查0～150（含端点），越界通过原有回调返回 `WallpaperError.INVALID_ARGUMENT`，不写盘、不替换 saved/draft、不钳制。`apply`及文件存储写入也做相同校验。120与150沿用原有预览/应用/取消规则，默认仍为35；两份入口共用同一规则。前端交付前滑条保持100，交付后改读 `tech.musxi.core.wallpaper.WallpaperPreferences.MAX_STRENGTH`。

## 选择、预览与应用

1. 前端注册 AndroidX `ActivityResultContracts.PickVisualMedia`，选择单张图片。AndroidX 在不支持系统 Photo Picker 时提供系统选择器兼容路径，无需申请广泛的相册读取权限。
2. 返回 `content:` URI 后调用 `choose(uri, callback)`；用户取消选择时传空或不调用均可。URI 仅用于当前导入，不持久化外部授权，不修改源文件。
3. 导入成功更新 `candidate`，当前应用背景不变。点击预览时调用 `preview(expectedId)`，ID 来自当前缩略图；首次进入预览自动将壁纸开启，但不改变已保存的设置。同一次预览重复调用保留草稿，包括 enabled、strength 和 frame。
4. 在预览期间调用 `setPreferences(value)` 只改 draft；`apply(expectedId)` 将候选和 draft 一起保存，成功后清候选。按钮必须携带当前 ID，过期 ID 返回 `STALE_CANDIDATE`。
5. `cancel()` 丢弃候选与草稿，恢复已保存背景；退出预览/设置流程时主动调用，防止返回页面后保留未确认预览。进程死亡也回到已应用状态。
6. 没有预览时，`setPreferences` 直接保存本份当前已应用图片的启用、强度和取景位置（scope 字段原样携带）。`reset()` 保存本份默认状态（包括居中、1 倍取景），只回收两份都不再引用的图片，与暂时关闭不同。

预览期间成功更换图片会替换本份候选、保留草稿中的开关/强度/旧scope，但 frame 重置为居中、1 倍；即使选中了内容相同的图片也重置。更换失败保留原候选、原草稿及已保存图片。预览只影响对应页面范围，不能触发系统壁纸设置。界面壁纸选图/取景后在曲库首页预览，播放页壁纸仍在播放页预览，由 CC 路由。

## 复用另一份已保存的图片

在目标实例调用 `chooseSaved(from: AppWallpaperRepository, result: (WallpaperError?) -> Unit = {}): Job`，例如 `app.interfaceWallpaper.chooseSaved(app.wallpaper)`，反方向同样支持。只取来源的 saved.asset，忽略其候选、草稿、开关、强度和取景；来源已关闭但有有效图片也能复用。不重新导入、解码或复制文件，校验通过后把相同 asset 设为目标候选。目标当前已保存图不变，已有草稿仅 frame 回到默认，其余保留，随后照常 preview → setPreferences → apply，或 cancel。

来源无已保存图返回 `NO_IMAGE`（即使有候选也不取候选）；来源清单加载失败或已存图片不能校验时返回 `SAVED_IMAGE_UNAVAILABLE`。失败只更新目标 error/busy，不改变目标 saved/candidate/draft 和来源状态。两份必须来自同一个应用受管池，跨池对象返回 `INVALID_ARGUMENT`，没有任意 ID、文件名或路径入口。优先使用应用属性；保留的 `AppWallpaperRepository(context)` 构造方式也会加入同一私有目录的共用池，避免另起回收器。

## 取景位置与调整已有图片

`tech.musxi.core.wallpaper.WallpaperFrame(centerX: Float = 0.5f, centerY: Float = 0.5f, zoom: Float = 1f)` 保存的是已摆正 EXIF 的整张受管图片坐标。centerX/Y 为 0～1 的归一化中心，zoom 为 1～4；三个值必须有限。zoom=1 表示按当前窗口比例刚好铺满，放大后显示区域相应变小。后端不接收屏幕尺寸、不裁图、不再编码；横竖屏、分屏或未来全局背景由前端按窗口比例计算矩形，尽量靠近指定中心并限制在图片内。贴边中心本身合法，不按某一屏幕比例提前夹紧。

进入新候选预览时 frame 使用默认值；choose 成功但尚未 preview 时没有新草稿，`effectivePreferences` 仍是已应用图片设置。选图后的取景页可以先保留本地临时 Frame，随后按 `choose → preview(expectedId) 成功回调 → setPreferences(state.value.effectivePreferences.copy(frame = frame))` 的顺序交给后端。不要在没有预览时用 setPreferences 写候选草稿，因为那会直接保存已应用图片的设置。

已应用图片直接调整时，不必 choose：使用 `saved.asset.id` 调用 preview，保留原 frame、范围和强度，再用 setPreferences 改草稿；apply(id) 保存，cancel() 恢复原值。若产品入口不需要草稿确认，也可在没有预览时直接 setPreferences 保存位置。更新单一字段时基于最新 `effectivePreferences.copy(...)`，避免新建默认设置覆盖其他字段。

非法 Frame 在 setPreferences 的命令边界返回 `WallpaperError.INVALID_ARGUMENT`，同时更新 state.error；不会写盘、替换 saved 或 draft，也不会钳制或静默改成默认值。值对象构造允许非法值，方便 facade 通过原有回调返回这个错误；不要依赖构造器抛异常。磁盘清单中的非法值仍属于 `SAVED_IMAGE_UNAVAILABLE`。

## 图片显示与失败

ID 变化后调用 `imageFile(id, callback)`，拿到受管 `File` 交给既有 Coil 单例，不能在逐帧/重组中重复做哈希检查与读取。候选和当前应用图片都能读取，任意外部 ID 无法解析。文件路径仅供应用本地图片加载，不上传、不写诊断日志。

图片在独立背景层按比例铺满，并按对应实例的 `effectivePreferences.frame` 计算显示区域，前景蒙层与文字保持清晰。连续播放器由 `PlayerSurface` 统一拥有播放页背景，避免只修改 `FullPlayer` 导致展开/收起闪回旧背景。歌词视图使用播放页图片，并加强歌词区域的可读性；界面壁纸从 `MainShell` 背景层接入，排除播放页/歌词页，不能放进滚动列表前景。透明 PNG 使用主题底色承接。

加载器失败后在对应实例调用 `imageFailed(expectedId)`；本份候选失败退出预览回到已应用背景，当前保存图失败回退默认并提供重新选择/恢复默认入口。即使两份引用相同图片也只影响调用方，另一份由自己的加载器发现并上报。延迟到达的旧 ID 失败不能覆盖本份新候选预览。该回退不把损坏数据重新标成有效，冷启动仍会校验文件。

`READ_FAILED` 为输入不可读，`UNSUPPORTED_FORMAT` 为格式不支持，`ANIMATED_IMAGE` 为动态图，`INVALID_IMAGE` 为损坏或解码失败，`IMAGE_TOO_LARGE` 为容量/尺寸超限，`STORAGE_FAILED` 为保存失败，`SAVED_IMAGE_UNAVAILABLE` 为已存图不可用，`NO_IMAGE` 为缺少可操作图片，`INVALID_ARGUMENT` 为强度/取景参数无效或 chooseSaved 来源不属于同一受管池。提示用明确中文；不能直接显示异常、路径或 URI。

## 存储与验证边界

输入支持静态 JPEG、PNG、WebP，按实际内容检查，APNG 与动画 WebP 拒绝。源文件最多 32 MiB、单边 16384、总像素 4000 万；Android 实际解码后还要确认像素可用。先采样再解码、归一 EXIF 方向，生成单边不超过 2560、总像素不超过 400 万的副本。输出透明 PNG 或不透明 JPEG，最多 16 MiB；不保留原相册 EXIF/位置元数据。

受管数据位于私有 `filesDir/wallpapers-v1/`，中间导入文件位于 `cacheDir/wallpaper-imports/`。两份清单分别原子更新，图片按内容散列共用，双方使用相同图片时只有一份受管文件。回收取两份 saved 与 candidate 的并集，包括禁用图片和未进入预览的候选；只删除两份均未引用的已知命名文件。导入暂存清理、初始化、导入/选用/应用及回收均在同一 App 队列内；core 的 `WallpaperLibrary` 另用共用 monitor 保证每条命令的文件发布、状态发布及回收不会被另一份打断。需要直接使用 core 时统一创建 WallpaperLibrary，不能对同一目录创建两个独立 WallpaperRepository。

不可变图片先落盘并同步，再原子更新本份带校验的设置清单；更新失败保留旧清单，启动读完两份后才回收孤儿文件。一份加载失败会发布自己的错误，仍加载另一份并保留其引用。清封面/歌词/曲库缓存不会清两份壁纸，账号退出与切换也不影响这些应用级设置。

播放页仍用原 current.wallpaper，新增界面 interface.wallpaper，两者格式和 CRC/原子替换完全相同。缺少界面清单表示默认关闭、无图，读取不生成清单。没有迁移、复制或启动改写原清单；原scope=GLOBAL也只留在播放页，不复制成界面壁纸。新清单默认scope=PLAYER，保留字段和原值以兼容格式，用途始终由实例决定。

两份清单均支持版本 1/2，新写版本 2，在 strength 后有三个 Float；旧版本 1 不含 frame，读取时补默认居中、1 倍，其余设置及图片保留，下次保存才写版本 2。目录名不变。版本 2 的非法数值、字段缺失、未知版本、尾随内容或校验失败会被拒绝，不当成旧数据兼容；未提供降级到只认识版本 1 的旧应用读取新清单的能力。

强度放宽不升级清单：strength仍占一个Int，字段布局和CRC不变，0～150原值读写；两种已支持版本里的非法strength仍报 `SAVED_IMAGE_UNAVAILABLE`。旧0～100清单不迁移、不在加载时改写。**未处理降级**：只认识0～100的旧应用会把120等清单判为不可用；有降级需要时，应先在新版把两份已应用强度都设回0～100并应用，且仍需遵守上述v1/v2兼容边界。不能承诺旧版读取新值或静默钳制。

正式界面、两份页面范围与预览路由、系统 Photo Picker 往返、拖动/缩放、横竖屏/分屏适配、连续播放器动效、亮暗复杂照片、逐字歌词可读性与真机内存/性能需要前端接好后联合验收。JVM/Robolectric 检查和设备测试包编译不能替代这些验收；强度放宽验证见[需求单交付](backend-requests-20261112-5.md#交付说明)，双份壁纸见[前轮交付](backend-requests-20261112-3.md#交付说明)，取景交付见[记录](backend-requests-20261112-2.md#交付说明)，最初图片导入交付保留于[原记录](backend-wallpaper-delivery-20261108.md)。
