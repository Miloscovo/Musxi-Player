# 给 CC：锁屏歌词 C 前端需求单

日期：2026-11-13。项目主目录：`C:\text\Musxi-Player安卓`。

本需求单供用户后续交给 CC 实施。后端已准备好，当前任务是补正式设置和锁屏页面，复用 Musxi 现有歌词、背景、图标和播放控件。后端详细约定见[锁屏歌词 C 合同](frontend-backend-contract-lockscreen-lyrics.md)，实机范围见[交付记录](backend-lockscreen-lyrics-c-20261113.md)。

## 一、用户要的效果

实现类似酷狗自有锁屏页的 Musxi 锁屏歌词：播放时，用户熄屏后再次点亮，在系统仍锁定的情况下显示 Musxi 歌词页面，包含歌名／歌手、歌词、上一首、播放／暂停和下一首。

背景沿用用户已经应用的 Musxi 歌词页背景，包括同一图片、取景位置、缩放和背景强度；没有启用图片背景时，复用当前歌词页的封面取色渐变。锁屏不另建背景设置。

在页面上向上、下、左、右任一方向滑动，均关闭 Musxi 锁屏页，回到系统锁屏。这里的退出只露出系统锁屏，手机仍保持锁定，后续解锁由系统负责。返回键也执行同样的退出。

独立锁屏歌词与现有“媒体卡片显示歌词”只能二选一，也允许两者都关闭。

## 二、正式设置入口

将相关设置统一为一个三态选择，名称可按现有设置页风格调整，语义如下：

| 展示选项 | 后端值 | 效果 |
| --- | --- | --- |
| 关闭 | `LockScreenLyricsMode.OFF` | 两种歌词展示均关闭 |
| 媒体卡片歌词 | `LockScreenLyricsMode.CARD` | 使用已有方案 B，在媒体标题中显示歌词 |
| 锁屏歌词 | `LockScreenLyricsMode.PAGE` | 使用本次独立锁屏页，关闭媒体卡片歌词并恢复原标题 |

读取 `app.settings.value.effectiveLockScreenLyricsMode`，订阅设置流更新；写入调用 `app.settingsRepository.setLockScreenLyricsMode(mode)`，在 IO 调度执行并处理保存失败。新安装默认 OFF，已有用户原设置由后端迁移，页面不能覆盖成默认值。

统一调整已有媒体卡片歌词开关及相关映射，避免留下两套能同时开启的设置。不要直接读取 nullable 的 `lockScreenLyricsMode` 作为选中值，也不要用 `copy(mediaCardLyrics=false)` 关闭 PAGE。

PAGE 关闭的是“媒体卡片中的歌词”，普通媒体通知及系统播放控制继续保留。

## 三、正式锁屏页与组件复用

| 页面部分 | 数据与接线 |
| --- | --- |
| 歌名、歌手、封面 | 收集 `app.lockScreenLyricsContent` 的公开展示数据 |
| 歌词、译文、逐字时间 | 使用内容中的 `lyrics`；复用现有歌词组件和策略 |
| 时间与歌词推进 | 使用 `position.positionMs / sampledAtElapsedMs / speed / canExtrapolate`，沿用 elapsedRealtime 播放头 |
| 图片背景 | 使用内容中的 `wallpaper`，来自 `app.wallpaper.state.saved` |
| 无图片背景 | 沿用现有歌词页的封面取色渐变 |
| 上一首／下一首 | `app.playback.previous()`／`next()`，按 `hasPrevious / hasNext` 显示可用性 |
| 播放／暂停 | `app.playback.toggle()`，按真实 playing／buffering 状态显示 |
| 四方向滑动／返回／无障碍关闭 | 调用宿主的 `dismissToSystemLockScreen()` |

具体要求：

- 沿用现有字体、图标、按钮反馈和背景材质；可按锁屏布局提取共享组件，避免复制整套播放器逻辑。
- 背景读取已应用值，包括 enabled、受管图片、取景中心、zoom 和 0～150 强度。不要读预览候选／未保存草稿，也不要读 `interfaceWallpaper`。
- 字号、颜色和翻译偏好从现有 `app.settings` 获取。当前单曲译文手动选择属于 MainShell 页面内状态，后端没有新增跨页面同步；需要复用页面策略，不假定该临时状态会自动传入锁屏。
- 后端已校验队列和实际音源身份；切歌、换源及歌词未就绪时，以内容流为准清空旧歌词，不用上首歌词填空。
- 无歌词、获取失败和本地音频仍显示歌名及可用播放控制。本地音频当前不提供平台歌词，不在前端另发歌词请求。
- 暂停、缓冲或不可推算时不能继续推进歌词。只在页面活跃时收集／驱动动画，离开后停止。
- 四方向退出必须均可操作；按钮点击不能被误识别为退出手势，按钮滑动与页面退出要处理好触摸竞争。补系统栏、安全区域、大字体、长歌名及无障碍说明。

## 四、宿主与 debug／release 接线

新增正式 Activity，继承 `tech.musxi.player.lockscreen.LockScreenLyricsHostActivity`，由它承载 Compose 页面。调用并保留基类生命周期处理；请求身份、窗口焦点确认、返回、关闭和 API26兼容已经在基类实现，不另写一套启动器。

在 `MusxiApplication.lockScreenLyricsHostClass()` 返回正式宿主 class，并在主 Manifest 声明：

```xml
<activity
    android:name="这里替换为正式宿主的完整类名"
    android:exported="false"
    android:launchMode="singleTask"
    android:taskAffinity="${applicationId}.lockscreen"
    android:excludeFromRecents="true"
    android:showWhenLocked="true" />
```

当前 release 的宿主返回 null，会处于 HOST_UNAVAILABLE；本次必须补齐。当前 `DebugMusxiApplication` 还覆盖宿主选择并返回 `LockScreenPageProbeActivity`，也要调整为正常 debug 使用正式页面，避免完成 UI 后仍弹黑底探针。原固定验证入口保持 debug 专用，不将探针、故障注入或 DUMP 入口带入 release；若调整验证路由，说明影响并保留既有测试能力。

不要主动亮屏、保持常亮、自动开始播放、调用 `requestDismissKeyguard` 或退出时打开 MainActivity；不要另建播放器、悬浮 View 或歌词获取链。

## 五、权限引导与状态反馈

ColorOS 应覆盖以下三个检查项，系统版本不同允许路径存在差异：

| 检查项 | 已知入口与证据 |
| --- | --- |
| 悬浮窗 | 后端提供 `overlayPermissionIntent()`，可检测 `overlayGranted` |
| 后台弹出界面 | 应用详情 → 权限管理 → 特殊应用权限 → 后台弹出界面；本机仅开悬浮窗时被拦，用户再开启此项后成功显示 |
| 锁屏显示 | 用户补充的路径：隐私 → 权限管理 → 权限 → 锁屏显示 → Musxi；可能也是必要条件，尚未做独立开关对照 |

后端入口位于 `app.lockScreenLyrics`：

- `overlayPermissionIntent()`：标准悬浮窗设置。
- `applicationSettingsIntent()`：标准应用详情；两项 OEM 开关通过说明引导用户手动查找，当前没有专用直达接口。
- `refreshPermissions()`：从设置返回后调用。
- `state`：观察机制状态。

现有 API **只检测悬浮窗，不检测后台弹出界面和锁屏显示**，刷新也不会增加这些检测结果。前端不能显示“三项全部已授权”。系统设置跳转失败应能返回并给出文字路径，不为厂商开关伪造授权结果。

NEEDS_PERMISSION 表示当前缺少可检测的悬浮窗权限，保留用户 PAGE 选择，不自动退回 CARD。UNCONFIRMED 表示未取得显示确认，可提示用户检查系统权限，不能断言一定缺哪一项。HOST_UNAVAILABLE 在正式接线后应解决；机制计数和内部枚举不需要直接展示给普通用户。

## 六、沿用后端触发行为

- 正在播放、有有效媒体、系统仍锁定时，在亮屏后由后端延迟约300ms尝试一次；请求已发出不等于窗口已显示。
- 显示后暂停保留页面，允许用户继续播放；首次显示前暂停会取消请求。
- 停止、播放结束、断连、错误、模式切换、可检测的悬浮窗撤权或系统解锁时关闭；息屏也关闭。
- 滑走、启动失败、未确认后，同一亮屏周期不再抢回；同屏切歌、切换模式也不绕过该限制，下一次实际熄屏／亮屏才有机会。

前端不另加定时拉起或重试循环。

## 七、验收与交付

后端已有2,784项统一测试通过，且 PJF110／Android16／ColorOS16 上最小探针实际显示、获得焦点，触摸退出先回仍锁定的系统锁屏。该结果不代替本次正式页面验收。

正式交付需验证：

1. 三态选择、重启持久化、旧CARD迁移和互斥；PAGE不会继续发布媒体卡片歌词。
2. 用户正常播放后手动熄屏／亮屏，弹出正式页面；四个方向分别退出，均回系统锁屏，普通返回和指纹／密码解锁路径正常。
3. 自定义图片与无图渐变均同歌词页一致；取景、zoom、强度及取消草稿不串入锁屏。
4. 上下曲、播放／暂停、seek后的歌词跟随、切歌／换源、无歌词、缓冲、本地音频、大字体与按钮触摸均正确。
5. 滑走后同屏不重现；停止／断连／模式切换关闭，权限引导可用。三项权限按设备实际能力分别记录，补验“锁屏显示”，不把提示当检测通过。
6. debug 和 release 都注册正式宿主；release无debug探针入口。按项目规范完成针对性检查及最终 `scripts/verify.ps1`，区分自动化结果与用户实机验收。

修改范围以 UI、必要的宿主、Application／Manifest最小接线及相关测试为主，不重写后端状态机或平台协议。保留其他未提交成果；构建使用共享锁入口。安装和手机操作按届时用户安排执行，不自动重放历史探针；无需平台歌单写入。交付列明文件、验证结果、未完成项和所需用户操作，不自动提交、推送或发布。
