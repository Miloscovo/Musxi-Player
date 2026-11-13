# 后端需求单 · 2026-11-12（五）：壁纸强度放宽到 150（前端 → Codex）

> **状态：后端已交付，CC可接滑条上限常量。** 用户实际用了两份壁纸后，希望强度在 100 以上还能再放开一些，最高到 150。两份壁纸（播放页和界面）都适用。后端只需要放宽范围，强度怎么换算成画面由前端负责。
>
> 完成后由 Codex 在末尾“交付说明”写清接口与验证边界。

读者：负责 core / app 后端层的 Codex。约定同前几批：不改 `app/src/main/kotlin/tech/musxi/player/ui/`（CC 负责）。行号以 2026-11-12 工作区为准，开工前复核。

## 现状（CC 已核对）

- `core/.../wallpaper/WallpaperModels.kt:56`：`WallpaperPreferences` 的 `init { require(strength in 0..100) }`。前端如果构造 101，会在 `copy()` 时直接抛异常，所以前端在后端交付前把滑条最大值保持在 100。
- 清单中的 strength 以 `Int` 写入（`FileWallpaperStore` 第 57 和 133 行），存储类型本身不需要改。

## 1 · 范围（P1）

1. 合法范围改为 **0～150**，默认值仍为 35。建议加一个常量 `WallpaperPreferences.MAX_STRENGTH = 150`，前端直接读它，不再自己写死数字。
2. 越界值的处理与 frame 一致：在 `setPreferences` 等命令边界返回 `INVALID_ARGUMENT`，不写盘，也不钳制。值对象最好也和 `WallpaperFrame` 一样允许构造、由命令边界校验，这样前端不会因为 `copy()` 崩溃。如果要保留 `require`，请写明。
3. 清单：大于 100 的值照常写入、读出。旧清单里的值都在 0～100 之内，不受影响。是否需要升级清单版本由 Codex 判断。需要注意，只认识 0～100 的旧版应用读到 120 会判成损坏；如果不处理降级，请写进交付说明。
4. 两份壁纸（`wallpaper` 和 `interfaceWallpaper`）同时生效。

## 2 · 不需要改的

强度的语义仍是「图片可见程度的意图」，后端不需要知道 100 以上在画面上是什么效果。草稿、应用、取消、换图的规则都不变。

## 前端这边的配合（CC 负责，供参考）

- 0～100 的效果不变：100 仍是「在保证文字清晰的前提下最多能透出的程度」。
- 100～150 是「超出推荐」的一段：图片上限和文字对比度保底都会放宽，但不会取消。滑条在 100 处有刻度，超过后数字变色，并提示「超过 100 后，部分文字可能不够清楚」。
- 后端交付前，滑条最大值保持 100；交付后改读 `MAX_STRENGTH`。

## 验证建议

0、100、150 都能通过；-1、151 返回 INVALID_ARGUMENT，且不写盘；120 在预览、应用、冷启动之后都能读回；旧清单兼容；两份壁纸都覆盖到。不需要安装手机。

## 交付说明

### 接口与语义

- `tech.musxi.core.wallpaper.WallpaperPreferences.MAX_STRENGTH = 150`；两份入口 `app.wallpaper`、`app.interfaceWallpaper` 的合法强度均为0～150，含端点，默认仍为35。字段名/类型及facade调用形状不变。
- 移除构造器的强度 `require`；构造和 `copy(strength = ...)` 可以带越界Int。`setPreferences` 和 `apply` 的命令边界校验完整 preferences（强度及frame），非法值按原回调返回 `WallpaperError.INVALID_ARGUMENT`、更新state.error；saved/draft/candidate/图片和已存清单保留，不钳制、不写盘。文件存储直接write也在创建/替换文件之前校验。
- 120/150的预览仍只改draft；应用才写盘，取消丢弃，冷启动读取最后已应用值。两槽独立、共用图池、换图规则均保留；后端只保存可见强度意图，不定义100以上的画面映射。
- **清单不升级**：整数类型和布局未变，继续读v1/v2、新写v2；0～150原值写回，旧0～100清单读取不迁移、不改写。移除构造断言后在磁盘读取边界显式校验；非法strength仍作为 `SAVED_IMAGE_UNAVAILABLE`，不误收损坏数据。
- **未处理应用降级**：只认识0～100的旧版应用读到120会把清单判为不可用；如需降级应先在新版将两份强度设回0～100并应用，且只认识v1的更旧应用仍不能读取v2。本轮不增加降级副本或静默钳制。
- CC交付前滑条继续保持100；后端交付后，前端把最大值改读上述常量即可。100处刻度、超出推荐的提示/颜色及对比度映射由CC实现。完整接口见[壁纸合同](frontend-backend-contract-wallpaper.md)。

### 验证范围

专项 **56项通过，0失败/错误/跳过**（core壁纸全套与app壁纸facade），证据 `.local/artifacts/wallpaper-strength-20261112/targeted-final/`。覆盖两份入口0/100/150、120预览/取消/应用/冷启动、-1/151及Int极值拒绝、保存清单/图片字节不变、旧v1/v2加载不改写、CRC正确但strength非法的清单拒绝及主线程回调。初轮专项因新增测试把preferences误作asset位置参数而编译失败，修正命名参数后本轮56项重跑通过；失败日志保留在 `targeted/`，不计作验证成功。

最终主目录官方 `scripts/verify.ps1` **2,622项通过，0失败/错误/跳过，Lint0错误/135警告**，debug主包/设备测试包编译及全树源码一致性检查通过。5份本轮后端源码/测试与专项、官方清单及交付时磁盘散列一致，回填后另查5份文档。证据 `.local/artifacts/verification/20261112T135826818Z-21828/`，索引 `.local/artifacts/wallpaper-strength-20261112/final-summary.json`。

对应debug APK保留于 `.local/artifacts/wallpaper-strength-20261112/app-debug.apk`，SHA-256 `f91aa9f710a80f6ee7e4391a00e95573b1ec04849c9970bad9a9ebbf71b74045`；精确修改前快照 `.local/checkpoints/wallpaper-strength-20261112/`。本轮没有创建工作树、修改 `ui/` 或CC测试、安装手机、提交或推送。后端交付已完成，CC可把滑条上限改读 `WallpaperPreferences.MAX_STRENGTH`；100以上实际画面与可读性留给CC接线后验收。
