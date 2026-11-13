# 锁屏歌词 C 后端交付 · 2026-11-13

用户确认后端方案，并随后明确“还是做后端，不急着做前端相关”。本轮保持正式 `ui/` 及其测试不变，不接正式设置页、歌词页、按钮或背景绘制。现行接口见[后端合同](frontend-backend-contract-lockscreen-lyrics.md)。

**最终结果**：后端统一验证通过；在 PJF110／Android16／ColorOS16 上，用户允许悬浮窗及独立的“后台弹出界面”后，同一 APK 的后台锁屏探针已实际显示，用户确认看到。系统事件同时记录创建、绘制、获得焦点，以及触摸退出后先露出仍锁定的系统锁屏。本轮探针已关闭并恢复原 CARD 模式；正式前端仍按用户安排暂不接入。

## 实现范围

- 三态 OFF／CARD／PAGE、旧布尔快照与调用兼容、保存失败不发布；实际 B 发布端只认 CARD。
- 共享只读内容流，原队列／实际音源双重围栏；背景只用已应用播放页壁纸，沿用0～150强度、取景和缩放，不带草稿。无壁纸由前端复用封面取色渐变。
- 锁屏触发策略、非导出宿主检测、标准权限 Intent、单亮屏单次尝试、焦点确认、3秒未确认、暂停／停止／解锁／权限撤回管理。
- 抽象 Activity 宿主、API26兼容、singleTask新Intent处理；正式release没有具体页面，不能自动弹出。
- debug最小机制探针及固定DUMP入口，保存／恢复原模式，不改变播放，不授予权限，不解锁设备。
- 账号前台标记移至 Application，锁屏页不计普通前台；新增 PlaybackState.stopped 区别暂停与保留媒体的STOP。

## 验证进展

首轮编译发现 Android facade 未转发新模式setter；旧“缺键快照”测试只移除旧键导致仍读取新键。已修正适配和fixture，保留失败日志。第二轮 `build.ps1 -Test` 成功；交叉审查后再修快速亮灭屏／singleTask请求ID／暂停先于焦点等竞态，新增对应测试，最终统一 **2,784项全过（0失败／错误／跳过），Lint0错误149警告**，主包与设备测试包编译／947条源码一致性检查通过。相较前轮净增30项（core7、accounts4、app19）；其中内容8项、会话11项。统一证据 `.local/artifacts/verification/20261113T085230464Z-31876/`。

证据目录 `.local/artifacts/lockscreen-lyrics-backend-20261113/`，修改前全树散列为 `baseline.json`，8个入口文件副本位于 `.local/checkpoints/lockscreen-lyrics-backend-20261113/`。没有创建工作树、提交、推送或平台写入。

## 设备边界

准备阶段只读确认手机 PJF110／Android16 仍连接，Musxi debug 的悬浮窗 app-op 为 default，未授予。未通过 shell 改权限或输入密码。17:00前后已完成保留数据安装：主APK `5558360aa0ed8d97b478ade24f35114201bb110aafe1ba6c3edfc23db37da5e8`，安装前持共享构建锁复核源码／HEAD／状态／APK，手机base.apk散列一致，首次安装仍10-19。进程启动后过滤AndroidRuntime日志未见FATAL。

最初广播因ColorOS冻结策略被跳过，没有立即生成报告；进程恢复执行后，tag日志补证：暂选PAGE为 `NEEDS_PERMISSION`、hostAvailable=true、overlayGranted=false、launchAttempts=0、confirmedDisplays=0；off恢复原CARD、armed=false，最终设置新键CARD／旧键true，悬浮窗app-op仍default。报告和marker已读回保留。没有实际启动锁屏Activity，也未改变播放；不将命令result=0或负向检查当作自动弹出通过。

当时待人工配合：用户在Musxi前台授予悬浮窗权限、正常播放，再由用户锁屏／点亮、滑走及指纹解锁观察；不自动重放arm，不用shell改权限。后续两轮结果如下。前端视觉、背景一致性和真人听音不在本轮验收范围。


## 授权后的后台弹出验收 · 17:09–17:18

用户继续验收并授予悬浮窗权限，开始正常播放后手动熄屏／亮屏，反馈没有出现探针。固定已装包不变，未重新构建。报告记录2次启动尝试、0次焦点确认；events日志在17:09:03和17:09:30明确记录 `Background activity launch blocked / BAL_BLOCK`，调用进程处于FOREGROUND_SERVICE，未创建宿主窗口。不能把权限已开或发送请求当作显示通过。

进一步在系统设置只读导航确认：应用详情→权限管理→特殊应用权限中，“悬浮窗”为允许，独立的“后台弹出界面”为不允许。截图和事件证据位于 `.local/artifacts/lockscreen-lyrics-backend-20261113/acceptance-20261113T090608Z/`，摘要 `acceptance-summary.json`。这证明 `overlayGranted=true` 不代表ColorOS额外开关已允许；该开关作为此次拦截的候选原因，待用户开启后的同链路复测确认。

该轮结束时已关闭探针并读回 `armed=false / mode=CARD`，播放保持用户当前状态；未由代理修改系统权限。当时等待用户在已定位的设置页手动允许“后台弹出界面”后复测；该轮未通过真正后台显示。

## 双权限开启后的同包复测 · 17:24–17:26

用户手动允许 ColorOS“后台弹出界面”后，17:24:02 开始复测；主 APK 与前两轮相同，未改源码或重新构建。只读 app-op 为 `SYSTEM_ALERT_WINDOW: allow`、`BACKGROUND_START_ACTIVITY: allow`。arm 后报告 PAGE／armed=true／playing=true／WAITING_FOR_LOCK；计数继承前轮2次尝试，不作为本轮新增次数。17:24:40 首个窗口创建后被再次息屏打断，未将该次记为完整显示通过。

17:25:33 的阳性样本与用户“看到Musxi 锁屏宿主探针了”一致：

| 本地时间（Asia/Shanghai） | 系统证据及含义 |
| --- | --- |
| 17:25:32.100 | `wm_set_keyguard_shown`：系统仍处锁定状态 |
| 17:25:33.154–33.245 | `LockScreenPageProbeActivity` 创建、resume、报告绘制完成 |
| 17:25:33.345–33.433 | 覆盖系统锁屏并获得输入焦点，后台窗口实际可交互 |
| 17:25:37.812–38.384 | 探针收到触摸、finish、销毁，取消覆盖锁屏 |
| 17:25:40.433–40.927 | 约2秒后系统才进入独立解锁流程并解除锁定 |

因此，本机后台弹出和本次触摸退出路径已通过；退出先回到仍锁定的系统锁屏。日志未记录手势方向，不能扩成上下左右四方向逐项验收，也不能扩成完整歌词／壁纸／播放按钮、撤权或长时间后台均通过。该退出至解锁的短区间未见探针再次创建。

17:25:59 系统记录原进程因 `with swipe up` 被结束；17:26:09 播放服务进程重启。debug 恢复逻辑按 marker 自动撤销临时 PAGE 并恢复 CARD。采样17:26:10时为 `DISABLED / CARD / armed=false`、无媒体且未播放；计数随进程重置为0。没有持久化的最终 SHOWN 计数可读回，阳性结论依据用户观察及系统创建／绘制／焦点事件，不以重启后的零计数反推本轮结果。没有自动恢复播放或重新arm。

最终证据 `.local/artifacts/lockscreen-lyrics-backend-20261113/acceptance-20261113T092402Z/`：`acceptance-summary.json` 为本轮摘要，`display-and-keyguard-events.txt` 为显示与退出时间线，`after-user-seen-lifecycle-events.txt` 为进程重启证据，报告／marker／权限读回一并保留。根目录 `final-summary.json` 保留最初负向样本并另记最新验收结果；较早 BAL_BLOCK 记录仍保留。权限由用户操作，代理未通过 shell 授权、解锁或进行平台写入。

用户在验收后补充，似乎还必须允许“隐私→权限管理→权限→锁屏显示”中的Musxi应用权限。已纳入前端权限引导及后续正式页面验收项；本轮没有单独切换该项进行对照，也没有成功样本中该权限的独立读回，不宣称已证明其必要性。现有公开状态仅能检测悬浮窗，不能检测该项或OEM后台弹出权限；详见合同。这项设备权限补充不阻塞前端接线，历史显示证据保持原范围。

后端已具备前端接线条件；后续按合同接正式宿主、模式选择、共享背景／歌词／播放控制与四方向退出，再做同包视觉和交互验收。本轮设备复测后只更新文档与证据，没有重新运行或冒称新增业务测试。
