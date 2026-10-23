# B4 保守匹配与锁屏/通知/焦点专项

> 历史专项记录：以下只对应文中日期、包和测试范围；当前状态以 [0.1 验收](android-0.1-acceptance.md) 为准。本机证据路径仅用于本地复查，不随仓库分发。

2026-10-22 后续状态：本文件保留B4阶段原包结论。B1-08前台资格门禁已在后续实现；最终包两次真实换源遇NETWORK，成功闭环待补，受控超时/取消及酷狗播放已验。当前包、构建及恢复情况见[B1-08验收](backend-b108-acceptance-20261022.md)，不把本文待办或旧听音沿用为新包结论。

日期：2026-10-22（北京时间）。设备 OPPO PJF110 / Android 16。开发仍在 `<project-root>/.local/worktrees/Musxi-Player-android-backend-next` 隔离后端，具体文件同步到 `<project-root>`。前端继续由 DS 主导、cc 辅助。

## 本轮包与构建

最终安装 Musxi APK SHA-256：`a51868c0522ecb85967337f7162b41651f219538b40ce26ae3aaa54db025aa86`，v2签名通过。本记录设备专项均使用此包；旧包的听音与注入证据不搬成此包验收。

隔离 `build.ps1 -Test -Lint` 成功，384 项测试通过、0 failures/errors/skipped：core223/app11/playback45/酷狗54/QQ29/网易云22。Lint 0 errors/12 warnings。主工程 app/单测 Kotlin 编译通过。

本轮7个后端文件按开工散列核对后同步：SourceMatchPolicy及其测试、SourceSelection及其测试、两个debug专项runner、ValidationReceiver。正式页面、MainActivity、Manifest、Gradle、前端交接文和PR隔离副本未改；无提交、推送或发布。

证据目录：`<project-root>\.local\artifacts\musxi-backend-b4-20261022`。`build-verified.log`、`test-summary.json`、`main-integration.log`、`integrated-source-manifest.json`分别记录构建、测试计数、主工程编译及7文件散列。初次通知runner误用了不存在的Android播放/暂停semantic常量，编译即失败；已改成从本应用实际通知action里匹配固定播放/暂停标签，再构建上述最终包。

## B4 实现与实测

完整规则见[前端增量契约](frontend-backend-contract-b4.md)。自动资格要求归一标题一致、完整非未知歌手集合一致、时长差同时不超过3秒及短曲2%，并且两侧标题/专辑无已识别版本标记。只有首个合格候选启动5秒倒计时。缺资料、不同歌手、已识别版本都保留手选；原平台队列、候选顺序、20项边界及最多3个替代来源不变。

交叉审查补齐未知歌手名称变体、TV Size/DJ/Piano/日文现场翻唱等标记；保留既有20项边界（上游两个目标平台各最多10项）。同key原曲元数据补齐继续支持，异key结果不能替换当前搜索。新增12项测试覆盖阈值边界、合作歌手、元数据缺失、版本、首个合格项、手选保留、key绑定及补齐。

`b4_probe_aizo` 使用真实QQ搜索结果作为原曲，再调用正式跨平台候选搜索，在**独立 SourceSelection** 中评估，不触发实际换源播放或计时：

| 观察 | 结果 |
| --- | --- |
| 目标平台 | 网易云10项、酷狗10项，partial=false |
| 标题不同 | 13项 |
| 同标题但完整歌手集合不同 | 5项，只手选 |
| 合格 | 2项，首个自动索引0、5秒资格true |

见 `real-source-match.xml`。这证明生产搜索结果进入新评估规则；不能把自动资格当成实际取流、同录音或用户听音确认。metadata不足/版本的反例本轮由自动测试覆盖，不伪称为平台真实搜索样本。

### 实际自动选源与后台边界

随后在本包实际播放队列上补验，均未注入故障（injectedFailures=0）：

- 第一轮从较长锁屏后台暂停后新发QQ队列：真实原曲PERMISSION；20项候选CHOOSING，自动索引0，剩余3790/2558/1275ms后自动SELECTED网易云，取流完成、duration215954ms；但playing=false/wanted=false。系统日志在20:07:17及显式重试20:07:40两次记录 `AS.HardeningEnforcer: AudioHardening focus request ... ignored`，播放器ready=false reason=2。不能将这轮记为自动播放成功，也不把它归因于网络或B4筛选。
- 用户再次解锁，打开现有DeveloperActivity（隔离后端验收包）后显式resume，playing=true、位置2520ms。前台重发同一QQ队列，真实原曲拒绝后20项候选自动索引0，5秒倒计时后网易云自动起播，playing/wanted=true、buffering=false、取流计数由3到4；原队列仍QQ/7项/索引1、实际音频NETEASE。随后HOME继续播放。见 `b4-foreground-00..08.json`、`b4-foreground-final.xml`、`b4-after-home.xml`。
- 另两轮前台提交后在倒计时期HOME：网易云搜索实际NETWORK、酷狗10项候选；首个合格项到期尝试后实际PERMISSION，余9项保留CHOOSING，automaticIndex=-1、deadline=-1，无继续自动试播。第一轮倒计时中自有服务isForeground=true。见 `b4-home-timer-*`、`b4-home-retry-*`、`b4-home-trace.txt`。这两轮是平台失败与手选保留证据，不能当作后台替代音频成功。

`b4-live-*.json`、`b4-live-final.xml`、`b4-playback-trace.txt`、`b4-focus-denial.txt`保留第一轮失败。实际换源音频未新增人工听音/同录音确认；前述锁屏听音确认属于KUGOU样本。

**新登记 B1-08：后台新起播/换源申请焦点被系统拒绝。** 已定位到焦点资格边界，前台对照可播放；还没有定位或修复前台服务资格丢失的具体时序。下一步应单独检查错误→搜索→倒计时→替代prepare/play间的会话/前台服务状态，继续保留主动暂停和焦点丢失不自播语义，不用无限重试或强占焦点绕过。后续不要把本包所有后台起播宣称已修好。

后台后段少数 `ss_*` 广播读到旧快照，文件snapshotElapsedMs仍为20235953；对应 `b4-notification-retry.xml`、`b4-current-system.xml`、`b4-woken-system.xml`排除出新动作验收。解锁打开应用后固定入口恢复，新快照显示当时无通知动作（NO_ACTION）。未用旧playing=true来冒充该轮播放成功。

## 通知动作与安全锁屏

新增debug固定入口 `ss_notification_pause/play`：从 `NotificationManager.activeNotifications` 中取本包id1001，匹配通知上实际固定播放/暂停标签，核对 PendingIntent creatorPackage 是本包后调用 `send()`。它执行**实际通知PendingIntent**，不直接调用应用PlaybackConnection，也不使用坐标点按；不保证SystemUI布局/触摸本身正常。

- 解锁：通知1条/actions2，pause后 playing=false/wanted=false，play后 playing=true/wanted=true；队列1项、实际KUGOU来源、无失败。见 `notification-paused.xml`、`notification-playing.xml`。
- 安全锁屏：deviceSecure=true、deviceLocked=true、keyguardLocked=true；初始熄屏interactive=false仍播放，通知pause后停止，play后继续。play时屏幕曾亮起但仍锁定，不能说整个动作期间都熄屏。见 `locked-playing.xml`、`locked-notification-paused.xml`、`locked-notification-playing.xml`。
- 用户随后明确回复：**“控件正常，暂停/继续和声音均正常，已解锁”**。按提问范围，用户亲手核对锁屏控件和暂停/继续，并确认本包声音正常。这补足实际可见/可操作及听音确认；通知栏展开后的独立布局/点击未单独要求用户确认，不扩大证据范围。

## 独立应用焦点

原 `ValidationFocusActivity` 同包/同UID，仅能提供同包焦点预检。为本轮构建独立测试APK `tech.musxi.focusprobe`，UID10381；Musxi UID10477。独立前台Activity使用系统 AudioManager 请求焦点，不播放声音、不联网、无额外权限；30秒自动归还，固定release动作也归还。

测试APK SHA-256：`749549325bc3e7e606773815e7b810cd8302625ea25b1703d0dafdee5269d53e`。源码与离线构建脚本在证据目录 `focus-probe`，没有加入产品模块或修改产品Manifest。此场景是真实不同UID焦点竞争，但不是来电或真实第三方音频播放测试。

| 场景 | 请求/占有期 | 归还后 |
| --- | --- | --- |
| transient | request result=1、gain2；Musxi playing=false、wanted=true、suppression=AUDIO_FOCUS | playing=true、wanted=true、suppression=NONE，按原播放意图恢复 |
| transient期间主动暂停 | 占有时先确认焦点抑制，再显式pause | playing=false、wanted=false，未因归还自播 |
| permanent | request result=1、gain1；Musxi playing=false、wanted=false | 仍暂停、wanted=false；随后显式resume才继续 |

证据 `focus-before.xml`、`focus-transient-client.xml`、`focus-transient-held.xml`、`focus-transient-released.xml`、`focus-pause-held.xml`、`focus-user-pause-released.xml`、`focus-permanent-client.xml`、`focus-permanent-held.xml`、`focus-permanent-released.xml`。最终 `focus-probe-final.xml` active=false。

## 较长锁屏后台观察

实际KUGOU来源、原1项队列，临时repeat=ALL；用真实时间等待并每30秒采样，不seek或加速时钟。最终从基线到最后样本 **698.551秒（约11分39秒）**，24份 `long-00..23.xml` 汇总于 `long-summary.json`。

24次观测均playing/wanted=true、buffering=false、failure=NONE，来源与队列始终相同；完成3次自然循环。前段sample01/02实际亮屏解锁；sample03到23均interactive=false、deviceLocked/keyguardLocked=true，覆盖 **605.818秒（约10分6秒）的连续采样锁屏区间**。快照只证明各观测点状态，不能据此保证两次采样之间毫无中断，也不能代替全程听音。

末段自有MusxiPlaybackService仍isForeground=true、通知id1001；`long-service.txt`。USB供电、charging=true，power wakefulness=Dozing，但deviceidle的mState/mLightState均ACTIVE，所有快照deviceIdle=false；`long-power.txt`、`long-deviceidle.txt`。屏幕Dozing不等于深度设备Doze，未强制整机Doze或修改全局设置。本轮没有覆盖拔电待机、后台重新取流或跨不同歌曲，3次自然循环不算3次新取流。

## 未覆盖与收尾要求

仍需专项：自然深度Doze/拔电长待机、音频输出变化、真实来电、自然系统Binder回收、通知栏展开的具体布局操作、多设备与正式前端。保守自动规则的前台实际换源已补；同录音人工确认、B1-08后台新起播焦点资格仍待补/修复。

已通过bg_restore恢复测试前保存的1项原KUGOU队列及81715ms位置，保持暂停、循环OFF；独立焦点测试应用已释放并卸载。最终账号/收藏/门禁状态见下方收尾复核。本轮未写真实平台收藏，也没有清数据或操作其他应用。


## 收尾复核

`final-queue-restored.xml`：RESTORED_PAUSED/restored=true，恢复原1项KUGOU队列、索引0/位置81715ms，无prepare或重新取流（恢复后duration0属于暂停待显式播放状态）。`final-state.xml`：playing/wanted=false、gate=ALLOW、pendingAcks=0、ackArmed=false、keeper=false、无播放失败，循环已恢复OFF；fault_clear已执行。三平台CONNECTED，三平台只读完整收藏刷新后synced=true、错误NONE，数量网易云5/QQ7/酷狗746；本轮无收藏写，数量复核不冒充本轮切换恢复完整成员集合的证据。焦点测试APK卸载Success见 `focus-probe-uninstall.txt`。

从设备pm path再次拉回的 `installed-check.apk` 散列与本记录最终APK完全一致。较长后台之后读到旧ss快照的几次尝试均已排除；前台对照和最终状态按新快照留证。下一次开发优先处理B1-08；已有384测试及本包锁屏/焦点证据不替代下一包相应回归。
