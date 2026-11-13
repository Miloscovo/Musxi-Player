# 锁屏歌词 C 后端合同

更新：2026-11-13。用户要求本轮只做后端；正式设置页、歌词、背景绘制、按钮及四方向手势由前端复用既有组件接入。旧[草案](lockscreen-page-plan-20261111.md)仅作历史依据，冲突以本文和当前源码为准。

## 模式与兼容

读取 `app.settings.value.effectiveLockScreenLyricsMode`，取值 `LockScreenLyricsMode.OFF / CARD / PAGE`。调用 `app.settingsRepository.setLockScreenLyricsMode(mode)`，磁盘同步保存成功才发布，调用方应在 IO 调度，保存失败保持原值。默认 OFF。

- OFF：不发布卡片歌词，不启动自有锁屏页。
- CARD：现有方案 B，平台标题显示当前原句，蓝牙／车机也可能读取该标题。
- PAGE：关闭 B 发布器并恢复原标题，仅允许自有锁屏页。普通媒体通知／卡片继续保留。

旧 `mediaCardLyrics` 构造参数、copy 和 setter 保留。`lockScreenLyricsMode` 是兼容用的 nullable 存储字段，前端必须读 effective 属性，不直接用 nullable 字段作选择值。OFF/CARD 在内存中规范化为旧布尔形状，PAGE 显式保存且布尔为 false。旧 setter 明确选择 CARD/OFF；通用 update 改变旧布尔时也兼容，显式新模式优先。PAGE 下 `copy(mediaCardLyrics=false)` 属于无变化，关闭请用模式 setter 或旧 setter。

新键 `settings.lockscreen-lyrics-mode` 优先；不存在时旧 true→CARD，其余→OFF；新键畸形或错误类型安全读 OFF，不回退旧 true。保存时新模式及旧布尔同一快照提交，旧键仅 CARD 为 true，降级不会让 PAGE 变成卡片歌词。

## 播放、歌词与背景

`app.lockScreenLyricsContent: Flow<LockScreenLyricsContent>` 组合现有播放／歌词／换源／播放页壁纸状态，不发起新请求、不创建第二播放器。

- 歌名、歌手、封面、playing/buffering、上下曲可用性、时长及位置采样。断连或无媒体身份时清空标题／封面。
- `position` 包含 `positionMs / sampledAtElapsedMs / speed / canExtrapolate`，沿用 elapsedRealtime；前端复用播放头，在停止可见时取消动画，不使用墙钟推进歌词。
- `lyrics: LyricsState` 包含原文、译文、逐字时间和 offset；严格校验 queueKey／实际 sourceKey。切歌、换源等待／失败、本地条目或错配时清空文档，防止串行。无歌词仍保留播放控制。
- `wallpaper: SavedWallpaper` 只来自 `app.wallpaper.state.saved`；预览候选和草稿绝不进入锁屏。复用同一受管图、取景中心、zoom、enabled 和 0～150 强度，不另建锁屏配置。启用且有图时按歌词页的绘制／遮罩规则；未启用或无图时沿用现有封面取色渐变。不是界面壁纸 `interfaceWallpaper`。
- 全局字号、颜色、翻译偏好仍从 `app.settings` 读；当前单曲译文覆盖目前是 MainShell 的页面内状态，未新增跨页面持久化。正式接线时按既有歌词策略处理，不在后端另造译文。
- 控制直接复用 `app.playback.toggle()/previous()/next()`。手势／返回只关闭自有页，不调用 `requestDismissKeyguard`，手机保持系统锁定。

## 触发、宿主和权限

`app.lockScreenLyrics.state: StateFlow<LockScreenLyricsState>` 提供 `phase / environment / cycle / launchAttempts / confirmedDisplays / requestId`。environment 仅公开布尔，不含账号与媒体 URL。请求发出不是已显示：窗口获得焦点且身份仍有效才记 SHOWN；3秒未确认记 UNCONFIRMED，同亮屏不重试。FAILED 表示本地启动异常，UNCONFIRMED 不断言一定被系统拦截。

仅 PAGE、存在合法非导出宿主、`Settings.canDrawOverlays` 为 true、手机亮屏且 Keyguard 锁定、已有有效媒体且正在播放时，在 SCREEN_ON 后单次延迟300ms尝试。不给全屏通知权限，不主动亮屏、不保持常亮、不用悬浮 View 绕过限制、不自动改系统设置。OEM能否弹出仍需设备验证。11-13在PJF110／Android16／ColorOS16上已确认还需独立允许“后台弹出界面”，路径为应用详情→权限管理→特殊应用权限→后台弹出界面。仅悬浮窗允许时实测2次BAL_BLOCK；用户再开启该独立权限后，同一APK、同一链路在17:25:33实际显示并获得焦点，用户确认看到。app-op只读值分别为 `SYSTEM_ALERT_WINDOW: allow`、`BACKGROUND_START_ACTIVITY: allow`。`overlayGranted`只报告悬浮窗权限，不代表OEM后台弹出开关，后者未加入公开API检测；界面不能仅据此承诺可弹出。当前只提供标准应用详情入口，由用户在系统设置中操作。

用户随后补充：还可能必须允许“隐私→权限管理→权限→锁屏显示”中Musxi的应用权限。ColorOS前端权限说明应同时覆盖悬浮窗、后台弹出界面及锁屏显示三个检查项；路径以系统实际版本为准。本轮未单独切换“锁屏显示”做对照，也未读回成功样本中该项的状态，因此将其记录为用户反馈的额外必要条件，独立因果待验，不改写既有阳性结论。后端没有“锁屏显示”或OEM后台弹出权限的独立检测字段，`refreshPermissions()`也不会补出这些状态；前端不得显示三项均已通过检测或将UNCONFIRMED断言为某一OEM权限未开。可提示用户检查这两项并提供现有应用详情入口，后续正式页面验收时补覆盖，不阻塞当前前端接线。

显示后暂停保留；首次显示前暂停取消请求。停止／播放结束／断连／错误、可检测的悬浮窗权限撤回、模式切换或系统解锁关闭。息屏关闭并重置下一屏幕周期。滑走、失败、未确认、同屏模式重开或切歌不再抢回；下一次真实熄屏／亮屏才有新机会。

`overlayPermissionIntent()` 返回标准悬浮窗设置 Intent；`applicationSettingsIntent()` 返回应用详情 Intent。前端按用户操作打开并处理无法跳转；返回时调用 `refreshPermissions()`（普通 Activity 开始时也刷新）。只在宿主活跃时以1秒频率复核可检测的悬浮窗权限／锁定状态，生命周期结束即停止。未获悬浮窗权限时保留 PAGE 意图，显示 NEEDS_PERMISSION，不自动切 CARD；该状态不覆盖前述两项OEM权限。

正式前端须继承 `LockScreenLyricsHostActivity`，在 Application 的 `lockScreenLyricsHostClass()` 提供具体 class，并在 Manifest 声明 `exported=false / singleTask / taskAffinity=本包独立后缀 / excludeFromRecents=true / showWhenLocked=true`。基类处理 API26兼容、单次请求身份、onNewIntent、窗口焦点确认、返回与销毁；四方向手势绑定 `dismissToSystemLockScreen()`。当前 release 默认返回 null，无具体页面，PAGE 状态为 HOST_UNAVAILABLE；不能宣称前端已可用。

账号前台标记统一由 Application 跟踪普通已启动 Activity，锁屏宿主不计入，避免锁屏显示触发账号前台轮询或会员刷新。MainActivity 不再重复注册进程观察者。

## Debug 最小探针

debug 仅注册 `LockScreenPageProbeActivity`（黑底文字、任意方向拖动／返回退出），不渲染歌词／壁纸／播放控件。复用既有 DUMP 保护的 ValidationReceiver，只接受固定步骤：

```powershell
adb shell am broadcast -n tech.musxi.player.dev/tech.musxi.player.ValidationReceiver --es step lock_page_arm
adb shell am broadcast -n tech.musxi.player.dev/tech.musxi.player.ValidationReceiver --es step lock_page_status
adb shell am broadcast -n tech.musxi.player.dev/tech.musxi.player.ValidationReceiver --es step lock_page_off
adb shell run-as tech.musxi.player.dev cat files/lockscreen-page-probe-report.json
```

arm 保存原模式后暂选 PAGE，不发起播放或授予权限；10分钟自动关闭，进程重启遇 armed marker 先恢复。off 仅在仍为 PAGE 时恢复原模式，避免覆盖用户后续选择。报告仅含时间、模式、机制状态／布尔与计数，不导出歌词、账号、媒体身份或 URL。实机锁屏、点亮、指纹／密码解锁及特殊权限授权由用户操作。release 不含探针、debug步骤或故障注入。

## 验证边界

本轮统一验证和手机实际结果见[交付记录](backend-lockscreen-lyrics-c-20261113.md)。单元测试验证模式迁移／失败、歌曲身份、背景参数及生命周期策略；同包debug探针已在上述单台设备完成后台显示、触摸退出并回到仍锁定的系统锁屏。未逐方向验收手势、正式歌词／背景／按钮或长后台；其他OEM需另验。探针进程重启后计数清零并恢复CARD，显示结论以用户观察和系统创建／绘制／焦点事件为依据。原生 A 探针不能代替 C 宿主验证。
