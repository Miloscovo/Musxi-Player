# PC 能力对齐与安卓后续推进

状态：2026-11-03 的初始讨论与建议，2026-11-08 已接续静态壁纸本地后端，前端待接。当前已实现能力和待验范围以 [开发接续](next-session-handoff.md) 为准。对齐用户能完成的操作和失败行为，再按 Android 的存储、权限、生命周期及性能约束实现。

## 11-03 初始建议（历史背景）

以下保留最初的讨论顺序，不作为当前排期；当前能力与下一步分别使用[能力状态](feature-status.md)和[开发接续](next-session-handoff.md)。

1. GitHub Actions [远端首跑联调](actions-first-run-20261103.md) 已完成：构建、测试、Lint 与证据上传通过；后续推送持续使用同一入口。
2. 已在 2026-11-03 建立 [v0.2 后端实机基线](backend-v02-device-acceptance-20261103.md)，后续优先补普通歌单移除及写后核实、长后台等未验项目。
3. 用户要求当前阶段以前端暂缓、后端为主。已有歌手/专辑、缓存歌单搜索和最近播放产品入口，以及自定义静态背景，均留待后续阶段按范围独立安排。
4. 每项能力独立交付：行为契约、Android 适配、必要自动化检查、对应实机回归和接续文档一起完成。

## 壁纸的 PC 依据

本次读取本地 `upstream/main` 的 `87c473f93e13a4628dacfc8feb5586fe423469d7`，未将该快照等同于未来的 PC 最新状态：

| 代码 | 已核实行为 |
| --- | --- |
| [wallpaper.inc](https://github.com/Miloscovo/Musxi-Player/blob/87c473f93e13a4628dacfc8feb5586fe423469d7/src/cef/wallpaper.inc) | 从本地选择 JPG/JPEG、PNG、WebP，进入候选流程 |
| [wallpaper_store.hpp](https://github.com/Miloscovo/Musxi-Player/blob/87c473f93e13a4628dacfc8feb5586fe423469d7/src/cef/wallpaper_store.hpp) | 检查真实格式、大小及像素数，拒绝动画；确认后以临时文件替换受管副本，支持恢复默认 |
| [wallpaper_image.cpp](https://github.com/Miloscovo/Musxi-Player/blob/87c473f93e13a4628dacfc8feb5586fe423469d7/src/cef/wallpaper_image.cpp) | 修正 EXIF 方向、限制解码后输出尺寸，按透明通道选择 JPEG 或 PNG |
| [useWallpaper.ts](https://github.com/Miloscovo/Musxi-Player/blob/87c473f93e13a4628dacfc8feb5586fe423469d7/frontend/src/composables/useWallpaper.ts) | 候选、预览、确认、取消和恢复默认；读取或显示失败回退默认背景；显示受透明效果及壁纸开关控制 |
| [WallpaperSettings.tsx](https://github.com/Miloscovo/Musxi-Player/blob/87c473f93e13a4628dacfc8feb5586fe423469d7/frontend/src/components/WallpaperSettings.tsx) | 播放器背景设置入口、缩略图及操作状态 |

## 安卓静态背景首版建议

2026-11-08 用户确认此方向，已开始独立本地后端实现；正式播放器/歌词背景仍待 CC。现状与验证见 [壁纸交付](backend-wallpaper-delivery-20261108.md)，下面保留最初的设计依据。

建议先用于全屏播放器。首版完成“选择图片 → 预览 → 应用 → 重启仍有效 → 更换/恢复默认”的闭环；不扩展动画或轮换范围。

Android 图片选择器或 SAF 提供输入，不依赖广泛存储权限。将选中内容校验、采样解码后保存为应用私有受管副本，避免原相册文件移动或 URI 授权失效导致背景丢失。处理旋转、大图、透明通道、取消及损坏；临时文件写入完成后再替换正式副本，设置与文件不一致时可恢复默认并清理遗留候选。尺寸与内存上限按 Android 设备验证确定，不照搬 PC 的 3840 像素输出限制。

背景、蒙层和玻璃采样应统一接入现有渲染层。文字、按钮和进度条在极亮/极暗图片上均须清晰；深浅主题、透明效果关闭、省电及旧系统回退的行为需要先约定。图片存储、设置和恢复属于本地数据层，无需业务服务器；UI 负责选择、预览和状态表达。

必要验证包括旋转图、大图、静态 WebP、透明 PNG、动画拒绝、失败保留原图、取消不落盘、恢复默认、进程重启及损坏回退。真机补测低内存设备和不同系统上的解码、滚动、拖动玻璃层及内存占用。

## v0.2 实机验收定位

建议在新功能前做一次集中验收，将账号连接、曲库/搜索、播放与通知、队列编辑、歌词/换源、音质、设置和更新等按具体包与场景登记。重点补网易云网页登录全流程、普通歌单真实写入、成功手动换源续播及长后台/来电/回收。

需要平台写入的用例沿用当次授权；登录确认和听音由设备使用者配合。跨系统矩阵、数小时后台和自然来电无法由一次短时单机测试代替。当前只安排后续工作，本轮没有安装、操作设备或生成新的实机通过结论。
