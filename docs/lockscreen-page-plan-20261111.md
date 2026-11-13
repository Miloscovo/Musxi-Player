# 锁屏歌词页（方案 C）技术方案草案 · 2026-11-11

> **状态：草案，待用户拍板与上机实验。** 用户 11-11 确定：ColorOS 原生锁屏歌词页（方案 A）在 PJF110 上探针未显示；媒体卡片标题显示当前行（方案 B）交 Codex；Musxi 自有整页锁屏歌词页（方案 C）后续要做，排在交互重构之后。本文是 C 的基础调研与方案草案，尚未写任何正式代码。

读者：用户、CC（前端）、Codex（后端）。事实来源：CC 组织的三路只读调研（平台机制、开源实现、Musxi 可复用件）加复核，原始记录在 `.local/tmp/cc-lockpage-research.md`。AOSP 证据来自已归档的 GitHub 镜像 `aosp-mirror/platform_frameworks_base`（最新提交 2025-03-27），不等于 Android 16 正式版或 ColorOS 16；developer.android.com 与 Play 政策页本次无法访问，相关结论标为“未核实”。

## 1 · 关键结论

1. **前台媒体服务不能让我们在后台拉起页面。** 安卓的“后台启动 Activity”判定里，没有按前台服务放行的分支。（已核源码）
2. **悬浮窗权限（显示在其他应用上层，SYSTEM_ALERT_WINDOW）是普通应用唯一稳定的放行条件。** 持有它时后台 `startActivity` 直接放行，而且不要求当前有悬浮窗在显示。（已核源码）开源实现（md3Music 等）也依赖它。
3. **全屏通知（USE_FULL_SCREEN_INTENT）不适合作为主通道。** 安卓 14 起要用户单独授权，未授权会被系统降级；它只在“发出通知”那一刻生效，和息屏、亮屏时机对不上；Play 政策大概率不允许音乐类使用。（源码部分已核，政策未核实）
4. **被拦截时不会报错。** 系统只打一行日志“Abort background activity starts”，调用方拿不到异常。所以要靠锁屏页显示后（`onResume`）回报“已显示”，才能确认拉起成功。
5. **页面本身没有障碍。** `setShowWhenLocked(true)` 的页面可以盖在有密码的锁屏上，触摸、手势都由我们自己处理，设备保持锁定；`requestDismissKeyguard` 能弹出系统密码界面来解锁。（已核源码）
6. **ColorOS 是否另有“锁屏显示 / 后台弹出界面”私有开关、会不会在悬浮窗放行之外再拦一道，完全未知。** 只能上机用日志和 `dumpsys appops` 确认。酷狗能在 PJF110 上做到，说明存在可行路径。
7. **前端几乎都能复用。** `LyricsBlock` 已在设计稿 `LyricsSheet` 中脱离 FullPlayer 独立使用过；逐字扫光、播放头、翻译判断、封面取色、进度拆分都能直接用。

## 2 · 推荐方案（待实验确认）

- **权限：** 打开“锁屏歌词页”时，引导用户授予悬浮窗权限，这是硬性前提。在 ColorOS 上，再引导到应用权限页打开“锁屏显示 / 后台弹出界面”（如果存在）。跳转按开源库 XXPermissions 的做法依次尝试 `com.coloros.safecenter` / `com.oplus.safecenter` 的权限页，失败回退到应用详情页。
- **触发：** 播放期间动态注册熄屏、亮屏广播（`SCREEN_OFF` / `SCREEN_ON`）。主触发选 SCREEN_OFF 预加载（亮屏即见、不闪），还是 SCREEN_ON 后延迟约 400ms 再判断，由实验决定，可以两种都实现。拉起前检查四个条件：正在播放、开关已开、有悬浮窗权限、`isKeyguardLocked()`。用户解锁（`USER_PRESENT`）时关闭锁屏页。
- **页面配置：**
  - 独立任务栈、不进最近任务、单实例，`showWhenLocked`。
  - **不设** `turnScreenOn`，只叠在用户自己点亮的锁屏上。
  - `noHistory` 先不加：它可能和密码界面冲突，实验时验证。
- **解锁：**
  - 上滑或返回统一走一个只执行一次的 `unlock()`：先 `requestDismissKeyguard`，有密码时等回调再关闭页面，避免“闪回锁屏”。
  - 指纹或面部解锁后，由 `USER_PRESENT` 自动退出。
  - 需要进入 App 的操作（队列、打开播放页）先解锁再跳转。
- **防误触：** 空白区域只响应上滑；控件只放底部一小块；歌词默认只读，不能点按跳转（待你定）。
- **结束：** 暂停超过一段时间（待定）或播放停止时，由服务主动关闭锁屏页，露出系统锁屏。熄屏后停掉逐帧动画。
- **未授权时的回退：** 完全不尝试后台拉起，只靠系统媒体卡片，加上方案 B 的“卡片显示当前行”。

## 3 · 需要拍板的事项

| # | 事项 | CC 倾向 |
| --- | --- | --- |
| 1 | 拉起通道：只走悬浮窗，还是也做全屏通知 | 只走悬浮窗；全屏通知不做 |
| 2 | 主触发时机：SCREEN_OFF 预加载，还是 SCREEN_ON 延迟 | 两种都实现，按实验结果定主次 |
| 3 | 挂点归属：放播放服务里，还是 app 层 `LockScreenTrigger` 经 `PlaybackHost` 回调 | app 层负责决策，服务只回调熄屏、亮屏（与方案 B 同模式） |
| 4 | 前台标记：`ProcessLifecycleOwner` 的观察者现在注册在 MainActivity，锁屏页可见会把账号层标成“前台” | 交 Codex 评估，移到 Application |
| 5 | 设置形态：三选一（关闭 / 系统卡片 / Musxi 锁屏页），还是独立开关 | 「我的 → 歌词」下设“锁屏歌词”二级浮卡：关闭 / 卡片显示当前行（B） / Musxi 锁屏页（C） |
| 6 | 本地音频和无歌词时 | 仍拉起，显示大封面、标题和进度（本地音频适合听书） |
| 7 | 歌词能否点按跳转、能否手动滚动 | 锁屏下只读，防误触 |
| 8 | 常亮 | 默认跟随系统超时；可选“播放时保持亮屏”留第二步 |
| 9 | 字号 | 锁屏用独立的大一档版式，跟随歌词字号设置等比放大 |
| 10 | 单曲翻译覆盖 | 锁屏跟随全局翻译设置，不跟单曲覆盖 |

## 4 · 最小上机实验（下一步，debug 构建）

最小探针页面：只设 `showWhenLocked`，`onResume` 打日志；播放时由服务注册的熄屏、亮屏广播拉起（`FLAG_ACTIVITY_NEW_TASK`）。

**实验矩阵：** 4 种权限状态 × 2 种触发时机 × 2 种锁屏类型。
- 权限状态：全部不授权；只开悬浮窗；悬浮窗加 ColorOS 的“锁屏显示 / 后台弹出”（如果存在）；再加全屏通知。
- 触发时机：SCREEN_OFF；SCREEN_ON 后 400ms。
- 锁屏类型：有 PIN；无锁。

**每格记录：**
1. logcat 里出现的是“Abort background activity starts from”还是“allowed because SYSTEM_ALERT_WINDOW permission is granted”，以及 ColorOS 特有的拦截日志（如果有）。
2. `onResume` 有没有回报。
3. 亮屏瞬间先看到哪一层，有没有闪烁。
4. AOD 是否失效，屏幕有没有被意外点亮。
5. `requestDismissKeyguard` 能否正常弹出密码界面、回调是否到达。
6. 用 `dumpsys appops` 对比授权前后的差异，找出 ColorOS 的私有开关。

**对照：** 记录酷狗在同一台机器上实际授予了哪些权限（设置 → 应用 → 酷狗 → 权限，由用户查看）。

锁屏、解锁一律由用户本人操作，不自动输入密码；不改系统设置。

## 5 · 文件布局草案

- **app 前端（CC）**
  - `ui/theme/MusxiAppTheme.kt`：从 MainActivity 抽出主题接线，两个 Activity 共用。
  - `ui/player/LyricsPresentation.kt`：从 MainShell 抽出歌词状态组装（翻译、字号、封面色、播放头），MainShell 和锁屏页共用。
  - `ui/lockscreen/LockScreenLyricsPage.kt`（页面）、`LockScreenLyricsUi.kt`（纯映射，处理本地音频和无歌词）、`LockScreenPermissionGuide.kt`（授权引导）。
  - `ui/design/LockScreenLyricsSheet.kt`：设计稿截图，先给用户看。
- **app 宿主（Codex）**
  - `lockscreen/LockScreenLyricsActivity.kt`：页面宿主。
  - `lockscreen/LockScreenTrigger.kt`：判断何时拉起、监听解锁、回报是否显示。
  - manifest 声明这个 Activity 和悬浮窗权限。
- **playback（Codex）：** 尽量不改；需要时给 `PlaybackHost` 加熄屏、亮屏回调。
- **设置（Codex）：** `AppSettings` 加锁屏歌词模式（与方案 B 的 `mediaCardLyrics` 合并成一个枚举也可以，待定）。

## 6 · 设计方向（初稿，出设计稿时细化）

- **底色：** 封面取色的竖向渐变，复用播放页的 `playerPalette`，压暗一档；不用玻璃，玻璃只用在底部控件。
- **布局：**
  - 顶部是大号时间和日期。
  - 中部是居中歌词：当前行放大，逐字柔和扫光，翻译在下；当前行颜色跟随“歌词颜色跟随封面”设置。
  - 底部依次是曲名和歌手、细进度条、上一首 / 播放暂停 / 下一首、上滑解锁提示。
- **动效和触感：** 全部取自 `Motion.kt` / `Haptics.kt`；上滑解锁按 iOS 手感，橡皮筋加速度推算落点。
- **本地音频和无歌词：** 大封面加标题、进度。
