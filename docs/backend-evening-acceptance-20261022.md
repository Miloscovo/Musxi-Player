# 2026-10-22 晚间后端补验与分阶段译文

> 历史专项记录：以下只对应文中日期、包和测试范围；当前状态以 [0.1 验收](android-0.1-acceptance.md) 为准。本机证据路径仅用于本地复查，不随仓库分发。

更新于北京时间 2026-10-22。用户授权按“待补实测、酷狗实际来源双语、原文先显示及独立刷新”推进；另明确授权网易云/QQ/酷狗各一首临时收藏切换并恢复。前端由 DS 主导、cc 辅助，本轮只改后端及 debug 验收入口，不代替正式歌词页或前端完整验收。

## 产物与证据归属

设备为 OPPO PJF110 / Android 16，包名 `tech.musxi.player.dev`，覆盖安装保留三平台账号及缓存。QQ 启动重验曾要求重新登录，用户本人完成官方确认后恢复 CONNECTED；网易云、酷狗仍连接。未退出/换号/清数据，未提交、推送、更新 PR #24 或发布。

后端开发在 `<project-root>/.local/worktrees/Musxi-Player-android-backend-next`，主集成为 `<project-root>`。本轮最终 16 个源码/测试/debug 文件先检查主工程与晚间 checkpoint 一致，再同步并逐项核对 SHA-256；没有覆盖 DS/cc UI、MainActivity、manifest、Gradle 或 next-session-handoff。主工程 app 和单测源码编译通过。

最终 `./build.ps1 -Test -Lint` 成功：369 项 JUnit，0 failures/errors/skipped（core208/app11/playback45/酷狗54/QQ29/网易云22）；六 Android 模块 Lint 0 errors/12 warnings。最终 APK SHA-256：`4427fc4d015e977cd8995b0042f178c064fa3e61dbf4f6a717481fc2c4158d96`，v2 签名验证通过，已安装。它是隔离后端包，不含主工作区最新正式前端。

证据目录：`<project-root>\.local\artifacts\musxi-backend-evening-20261022`。`integrated-source-manifest.json` 为16文件散列，`build-verified-final.log` 为最终构建，`main-integration.log` 为主工程编译。安装前的主工程源码 checkpoint 在 `<project-root>\.local\checkpoints\Musxi-android-before-evening-20261022`。诊断只存状态、计数、固定错误和相对时长，不导出原始歌词/歌曲ID/账号/URL/票据；平台收藏恢复基线留在 app-private noBackup 内，未拉取。

阶段包不能混用：

| 包 | 用途 | SHA-256 |
| --- | --- | --- |
| R1 | B1 控制器预算/确认、B2 真实收藏、首次酷狗实际双语播放 | `1ae20b6880339c8c7a99cbfc7f0f94bdede61af320e5377063f4e9ab5ed70c28` |
| R2 | 分阶段译文首轮设备验证 | `21ab3e586132f901d432dc8583549ee4278ddb73bebeb0e215d78b8003cc5cf3` |
| R3 | 服务内部队列丢失注入及补充计时入口 | `7deaf93faeaaff2bc7360c916bf3772e4f1df2672aab8e3f34815fa3e9058a9a` |
| R4 | 修复上下文生命周期/worker可见性后的最终包 | `4427fc4d015e977cd8995b0042f178c064fa3e61dbf4f6a717481fc2c4158d96` |

## B1：连接与服务队列

以下连接断开、失败/超时及确认延迟均为明确的 debug 注入，实际执行现有恢复状态机并释放/重建真实 MediaController；不是自然 Binder 崩溃或系统回收。

- 暂停状态重连：服务由独立 keeper 控制器保持，重连后队列1项/位置5000ms/来源KUGOU/revision均与基线相等；queueSubmits=4、resolveCount=2 未增加，仍暂停。见 `b1-before.xml`、`b1-reconnected-final.xml`。
- 播放中重连：keeper 及重新建立的应用控制器都观察到服务继续播放；队列/来源/revision相同，queueSubmits=5、resolveCount=3不变，位置自然增长。见 `b1-playing-before.xml`、`b1-playing-reconnected.xml`。
- 超时预算：三次等待各约10000ms，第一次失败至第二次连接约1013ms，第二次失败至第三次约3018ms，之后 FAILED/canRetry=true。独立 keeper 在应用控制器不可用期间仍播放。失败注入另覆盖共享已使用预算的第2/3次；不是三个全新失败样本。事件见 `b1-timeout-exhausted.xml`。
- 快速断开继续使用1/2/3次预算，第3次后FAILED；稳定超过30秒再断开回到新预算；耗尽后连点10次重试只建立一个新控制器。见 `b1-fast-exhausted.xml`、`b1-retry-burst.xml` 及各 connectionEvents。
- 真实队列编辑确认延迟2s，pendingAcks=1时断开，回调恰好失败一次；迟到确认不再次回调/回写，不重提队列。另有断线编辑即时失败样本。见 `b1-ack-pending.xml`、`b1-ack-late.xml`、`b1-quick-disconnect.xml`。
- 服务丢队列：最初尝试 keeper.clearMediaItems 被正式会话的队列权限保护挡住（短时本地 masking 后仍是1项），**这次不计通过**。随后新增 debug-only bridge 进入服务内部清空真实队列，保留journal，`serviceQueueLossAccepted=true`。重连后 keeper/app队列均0、暂停、取流0、queueSubmits仍2；journal仍1项；显式恢复后队列1、仍暂停、取流0。见 `service-loss-accepted.xml`、`service-loss-reconnect-final.xml`、`service-loss-journal-retained.xml`、`service-loss-paused-restore-final.xml`。这仍是注入，非真实系统回收。

最终R4另补 HOME 后曲尾循环：automaticTransitions由0到1，随后第二轮到2，playing=true，实际source仍KUGOU，媒体前台服务isForeground=true。`background-end-before.xml`、`background-end-later.xml`、`background-loop-after.xml`和`background-service.txt`留证。取流计数1→2及KUGOU AUDIO成功数1→2发生于接近曲尾的seek重新打开来源；循环时没有进一步增加，因此**不记为后台跨曲重新取流通过**。

剩余：旧连接结果迟到、断线点播整个用例、自然Binder/真实系统回收、锁屏/通知用户控制、后台跨曲新取流及手选来源、长Doze、独立应用焦点、输出设备变化。HOME样本不扩充为这些场景通过。

## B2：真实收藏及列表联动

R1 对三平台各选一个已喜欢的条目，先完整读取原集合，再取消喜欢，完整读回核实“仅目标变化”，恢复喜欢并再次完整读回比较全集合。平台返回的数据均真实；不做乐观成功、不自动重试写入。每平台 invalidationRevision 0→1→2，其余平台不随它增加。

| 平台 | 原集合 | 切换后 | 完整恢复 | 证据 |
| --- | --- | --- | --- | --- |
| 网易云 | 5 | 4 | 5，全成员相等 | `b2-netease-prepared.xml`、`b2-netease-toggle-page-failure.xml` |
| QQ | 7 | 6 | 7，全成员相等 | `b2-qq-prepared.xml`、`b2-qq-toggled.xml` |
| 酷狗 | 746 | 745 | 746，全成员相等 | `b2-kugou-prepared.xml`、`b2-kugou-toggled.xml` |

最终 `b2-final-restored-verified.xml`：三平台 favoriteRestored=true、无收藏错误；后续没有再写平台收藏。恢复可能改变服务端顺序，不宣称原排序恢复。恢复后幂等设置当前状态，不增加失效revision。一次额外网易云完整读取真实返回network，保持原集合及revision；之后读取成功。写失败/读回结果不一致仍未专项覆盖。

网易云同时令 metadata fetch 返回固定 network（仅 debug 注入，不断手机网络）：真实收藏核实成功后，favorites=4/error=NONE；对应列表旧content保留、stale=true、libraryError=network。恢复 metadata 读取并刷新后 stale=false/error=NONE；没有反转收藏成功。其他平台列表失败、缓存写失败、真实换号仍待补。

## 酷狗实际来源双语

此前固定 Lemon/Hello/My Love 的音频曾真实换到网易云，今晚没有把那些记录改写成酷狗成功。新增固定查询、最多6候选的小范围只读探测：Amazing Grace 查询前4项中，2项音频permission，一项原词1行无译文，最终找到可播且有译文项；没有放宽取流权限、TLS或身份校验。

R1 `kg_play` 后实际 queue/source 均 KUGOU、playing=true、原文18行/译文17行、AVAILABLE/SUCCEEDED、lyricsSourceMatches及lyricIndexMatches=true、injectedFailures=0。30秒跳转在播放中 activeIndex=1且有对应译文；暂停后5秒 activeIndex=0，原文/译文及来源仍一致。见 `grace-progress.xml`、`kg-playing-early.xml`、`kugou-real-seek30-playing.xml`、`kugou-real-seek5-paused.xml`。两次seek采样间resolveCount从1到2，不能声称整个过程没有再次打开音频来源。

用户回复第一次没戴耳机并要求重播，已实际重播；最终R4再次播放后，用户明确回复“有声音，播放正常”。这构成本轮真实听音确认；录音语义与正式双语页面布局仍未验。

## 原文先显示及独立刷新

精确 API 见 [翻译契约](frontend-backend-contract-lyrics-translation.md)。三平台统一：原文成功先进入 TIMED，另用 translation.lookup=PENDING 表达可选译文，不占整页加载。网易云/QQ首轮使用原响应的译文并在worker上对齐；显式刷新重新请求同份响应，原始原文全等才接受。酷狗使用绑定API/session的 LyricsOriginal，后续KRC只用相同candidate，不重新搜索或下载LRC。异常不进入整份ERROR；同份原文的旧译文可AVAILABLE+FAILED，成功NONE才清除旧译文。

复审发现并修正：过期原文响应不得先注册仓库缓存、挤掉仍由Browser显示的有效文档；新增guard，并在整份刷新/淘汰/账号失效时释放对应上下文。双账号与revision门禁继续保留；worker读取的目标/revision使用可见性保护。新增14项Browser阶段/生命周期测试及5项酷狗同候选句柄测试；旧二参数/trailing-lambda构造器兼容。

R2 真机对当前实际KUGOU样本设置一次4500ms译文延迟（明确注入，非平台慢请求测量）：

- 原文约256ms发布18行，257ms为TIMED/PENDING、0译文，播放继续；约4958ms后18/17、SUCCEEDED。
- 独立刷新约3ms为TIMED/PENDING，原有17行译文仍可读；约4663ms后成功。
- 注入一次独立译文network失败：约4511ms后TIMED、AVAILABLE/FAILED/NETWORK，18/17不丢失；originalUnchanged=true。
- 随后真实刷新成功回SUCCEEDED。上述流程 sourceResolveCalls=1未增加，不提交播放队列。证据 `staged-original-pending.xml`、`staged-translation-arrived.xml`、`refresh-pending.xml`、`refresh-success.xml`、`refresh-failure-retains.xml`、`refresh-recovered.xml`。

最终R4已复现原文约278ms出现、279ms为TIMED/PENDING且0译文（`final-original-pending.xml`），译文随后补入18/17（`final-translation-arrived.xml`）。最终包失败保留/恢复见 `final-refresh-failure.xml`（约4506ms后AVAILABLE+FAILED/NETWORK、18/17及原文保留）和 `final-refresh-recovered.xml`（随后真实刷新SUCCEEDED）；正式前端接线/显示/滚动由DS/cc另验。

## B3：逻辑请求计时

`work` 为整项逻辑操作，可能含多个HTTP、音频探测或完整收藏分页；不是逐HTTP计数/耗时，也不是用户听到声音所需时间。完成均值只用completed作分母，不把在途当0ms。所有窗口只读，无新增收藏写入。

三平台 SEARCH/LYRICS/AUDIO/TRACKS 和酷狗offset30翻页均已采样，见 `final-b3-matrix.xml`；其中网易云页读取network、酷狗音频permission或无权限，QQ歌词/音频在首次截图时尚在途。不能把矩阵计数当所有操作成功。首轮三平台搜索work分别716/393/146ms，示例网易云音频queue184/work231ms；这是各一次观察。

第一次约30s基线窗口与竞争窗口：`b3-baseline-30s.xml` 30052ms，网易云AUDIO queue1/work359ms但结果失败；`b3-competition-30s.xml` 30124ms，完整酷狗喜欢读取746项work4407ms、TRACKS翻页work276ms、网易云SEARCH work567ms，随后网易云AUDIO queue4805/work279ms且成功。基线结果不同，不据此直接比较播放体验或均值；保留原样本并补一次同长度成功基线：`b3-repeat-baseline-30s.xml` 30035ms，网易云AUDIO 1次成功、queue0/work590ms。与竞争窗口同样本成功相比，单次排队由0增至4805ms，支持共享串行队列存在阻塞，仍不是稳定性能均值。

取消与clear：立即取消后loading=false，旧结果不替换原页面，最终包 `final-cancel.xml` 的 cancelPageUnchanged=true。在途clear时 count=2/completed=1，之后旧ticket不再产生网易云SEARCH桶；见 `final-clear-inflight.xml` 与 `final-clear-completed.xml`。早期窗口里clear后出现新的KUGOU_LYRICS，是clear后新发请求，应保留，不是旧ticket污染。未真实换号；取消后HTTP剩余量没有计量。

## 后续

优先为共享账号串行队列约4.8秒音频排队做多轮、同样本且成功结果一致的对照，随后再评估只读元数据与播放请求的调度隔离；不能直接挪账号变更/收藏写/会话校验到无序并发。继续B4歌手/时长/版本匹配、正式前端双语接线及剩余设备专项。不要把本轮注入恢复、单设备样本或隔离后端测试数扩展为完整产品验收。

## 收尾设备状态

最终R4已暂停，repeat恢复OFF；1项酷狗样本队列，18/17行、AVAILABLE/SUCCEEDED，gate=ALLOW，三平台CONNECTED，完整收藏读数5/7/746且error=NONE。见`final-paused.xml`、`final-backend-state.xml`。没有留下故障门禁、延迟配置或真实收藏变更；应用页是否仍后台以用户后续操作为准。
