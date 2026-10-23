# AUDIO 调度隔离与晚间第二阶段补验

> 历史专项记录：以下只对应文中日期、包和测试范围；当前状态以 [0.1 验收](android-0.1-acceptance.md) 为准。本机证据路径仅用于本地复查，不随仓库分发。

日期：2026-10-22，北京时间。设备：OPPO PJF110 / Android 16。主工程 `<project-root>`，隔离后端 `<project-root>/.local/worktrees/Musxi-Player-android-backend-next`。前阶段结果见[晚间验收](backend-evening-acceptance-20261022.md)，总览与后续优先级见[后端现状](backend-status-20261022.md)。

## 包、源码与验证

本阶段最终安装 APK SHA-256：`897d44aac9ab91159d07abce9f608b2bc13dbbb911e272a0e2baada804a84811`。v2 签名校验通过。下述 `final-*`、`after-*` 和后台补验均属此包；第一次缓存脚本和断线复连初探属于上一候选包，不混入最终通过证据。

`build.ps1 -Test -Lint` 成功：372 项测试，0 failures/errors/skipped；core 211、app 11、playback 45、酷狗 54、QQ 29、网易云 22。六 Android 模块 Lint 0 errors/12 warnings。主工程 app 与单测 Kotlin 源码编译通过。

8 个后端源码文件按主工程开工散列检查后同步，散列再次一致：AccountRepository、RequestLanes 及其三项并发测试、ValidationReceiver、四个 debug 专项 runner。未修改正式前端、MainActivity、Manifest、Gradle、前端交接文或 PR 隔离副本；未提交、推送或发布。

证据目录：`<project-root>\.local\artifacts\musxi-backend-next-20261022`。关键文件：`build-verified.log`、`test-summary.json`、`main-integration.log`、`integrated-source-manifest.json`、`measurement-summary.json`。上一阶段已集成源码保持原样，本次清单只列新增/变更的8个文件。

## 调度修改及边界

原先三平台账号网络工作、曲库查询、完整收藏读取与音频取流共用单线程。完整酷狗收藏746首的多次HTTP读取，会阻塞随后提交的网易云取流；前阶段有 queue4805ms 的成功样本，本阶段旧包复查又观察到 queue2849ms。

新增 `RequestLanes`：只有 `RequestOperation.AUDIO` 使用独立单线程，其他操作和账号网络工作保留原队列。账号 Session 的所有访问持有同一 gate；音频在执行时取得已连接且同 generation 的快照，不在HTTP期间持锁。执行前、完成后、主线程交付及 AudioCapability 使用时的代次门禁保留。网易云/QQ的 expired 处理保留原语义，不另行并发收藏写、不自动重试。

并行只读审查三平台 API：签名参数、请求与连接均按调用创建；Session读取不写回凭据，未发现共享可变签名器或缓存。该结论不保证平台不会限流，也不覆盖所有未来 API 改动；本次不增加请求并行池或无界自动重试。音频之间仍会串行等待，歌词与其他库读取仍可能互相排队；正在执行的 HTTP 不强制中断。

三项新增并发测试使用真实串行 executor 与 latch：完整收藏读取仍阻塞时音频可完成；其他操作保持顺序；音频自身串行且不阻塞库读取。

## B3：约30秒等长窗口

每个窗口一项网易云 AUDIO；竞争窗口额外包含网易云 SEARCH、酷狗下一页 TRACKS、酷狗完整 FAVORITES_READ。各窗口分别清除聚合，等待异步快照并检查新窗口。曲目固定为网易云当前首项；酷狗翻页位置随窗口推进，不把这些网络样本当严格实验室均值。

`queue` 为进入 worker 前等待，`work` 为整项逻辑操作（可含多次HTTP及探测）；以下不是可闻起播时间。

| 包与窗口 | 窗口ms | AUDIO结果 | queue ms | work ms | 酷狗完整收藏work ms |
| --- | ---: | --- | ---: | ---: | ---: |
| 旧包：单独 | 30028 | 成功 | 1 | 435 | — |
| 旧包：竞争 | 30044 | 失败 | 2849 | 303 | 2785 |
| 新包：第1轮单独 | 30001 | 失败 | 1 | 285 | — |
| 新包：第1轮竞争 | 30033 | 成功 | 0 | 269 | 3359 |
| 新包：第2轮单独 | 30023 | 成功 | 1 | 506 | — |
| 新包：第2轮竞争 | 30007 | 成功 | 0 | 290 | 3886 |
| 新包：第3轮单独 | 30043 | 成功 | 1 | 473 | — |
| 新包：第3轮竞争 | 29974 | 成功 | 1 | 259 | 4011 |

新包三轮竞争中的 SEARCH、TRACKS、完整收藏及 AUDIO 全部完成成功。观察支持批量读取不再挡住音频进入 worker；旧包竞争失败、新包首轮基线失败保留，不能直接比较所有窗口的播放体验或承诺起播毫秒数。

有效证据为 `before-baseline-30s.xml`、`before-competition-30s.xml`、`after-{1,2,3}-{baseline,competition}-30s.xml`。本阶段最初复制脚本生成的 `b3-baseline-30s.xml` 与 `b3-competition-30s.xml` 实际是相同旧窗口快照，明确排除；不能因 adb broadcast 返回0就认定已收到新状态。

## B2：独立合成故障

以下在手机进程运行真实 core 状态机，但依赖独立内存 store/手动回调。没有写真实收藏、删除缓存或更改真实账号。不是自然平台故障或磁盘损坏。

- **B2-05 缓存写失败**：5阶段全通过。缓存旧页 → invalidate后新网络页写IOException → NETWORK/FRESH/storageError=true → 切页重开旧缓存仍STALE → 恢复写入后刷新为NETWORK/FRESH/storageError=false。写入4次中1次注入失败；包含初始种子2次，最终成功共3次。证据 `final-cache-failure.xml`。
- 初次脚本恢复阶段过早释放了首次请求的旧回调，未实际释放新刷新回调，因此恢复阶段失败；修正为先推进IO/交付队列再释放回调。没有据此修改生产 LibraryBrowser。首轮 `cache-failure.xml` 保留为失败记录。
- **B2-02/04 收藏故障与隔离**：三平台各4阶段，共12阶段通过。覆盖write失败、readback失败、readback不一致、账号owner/generation变化后迟到write/readback。精确计数write15/fetch12、verified事件0；无乐观改变、不自动重试，loading/pending收束，其余平台状态不变。证据 `final-favorites-faults.xml`。

真实换号、自然token过期、正式前端累计分页处理仍待补。

## B1-05：断线点播完整用例

使用独立 keeper 保留真实暂停服务/1项队列，debug TIMEOUT gate 释放应用 MediaController，然后调用正式 `queuePlayback.playSong(song)`。

`final-disconnected-play.xml`：立即 loading=false/error=player，serviceQueueUnchanged=true，newQueueSubmits=0、newResolves=0、pendingAcks=0，服务不播放。随后解除gate，等待原有10秒连接超时/预算推进，必要时显式retry，再主动拉取新快照。

`final-disconnected-settled.xml`：约14973ms时 CONNECTED，仍是相同服务队列、暂停；没有队列补发或额外取流，pass=true。最后释放keeper。`final-disconnected-reconnected.xml` 是3秒延迟回调保存的 CONNECTING 中间态，即使晚读仍是旧状态，不作为复连失败或最终状态。

本项为真实控制器上的故障注入，不能推广为自然Binder故障。旧连接结果迟到的手机专项仍待补。

## B1-06：后台取流与跨曲

固定 Amazing Grace 查询、最多6候选，找到与原队列歌曲 key 不同且实际可取流的另一首酷狗结果；实际只探测1个候选。临时2项KUGOU队列测试后恢复原来的1项队列及暂停位置。

第一轮：前台启动、接近曲尾后HOME；实际跨曲至第二首并播放、source=KUGOU、服务isForeground=true。但 `background-before-home.xml` 至 `background-after-home.xml` 新取流0，说明Media3已预取，不记为后台新取流。严格probe pass=false保留。

第二轮：在应用已离开前台时，先暂停恢复原队列，清除独立计时窗口并记录resolveCount=2，再通过固定debug入口提交2项队列。后台启动播放并seek近曲尾，随后第二首继续播放。证据：

- `background-home-baseline.xml` → `background-home-after.xml`：resolveCount 2→4，queueIndex=1、queueSize=2、playing=true、source=KUGOU。
- `background-home-metrics.xml`：约13456ms窗口，KUGOU AUDIO 2次且均成功，queue合计3ms/work合计941ms。
- `background-home-second.xml`：两首key不同，actualSecondPlaying=true、实际source为第二首KUGOU、position4177ms。
- HOME后未启动Activity，活动观察及 `background-home-service.txt` 确认播放器不在前台且媒体服务isForeground=true。未操作其他应用。

第二轮可计为**后台实际取流与跨曲接续子项通过**，仍含Media3预取，不能说“曲尾那一刻才请求第二首”。第二轮使用重新清空的B3窗口，bg严格probe仍持有第一轮mark，其相对AUDIO字段及pass不适用于第二轮；以独立be/bm窗口为准。后台未预取来源的曲尾现场取流、锁屏、通知控件、独立焦点、输出变化、长Doze及后台手选来源仍待补。未收到本包新的听音确认。

## 收尾

`background-final-restored.xml`：原1项队列及27137ms位置已恢复，暂停、不prepare。随后在本最终包重新播放原样本检查歌词，`final-lyrics-playing.xml`：实际KUGOU来源、17行译文、AVAILABLE/SUCCEEDED，lyricsSourceMatches及lyricIndexMatches=true；没有用此状态代替用户听音。

最后已暂停，故障gate=ALLOW、pendingAcks=0，keeper已释放；三平台CONNECTED，完整只读刷新显示收藏5/7/746、各favoriteError=NONE，见 `final-paused.xml`。本阶段无真实平台收藏写入；完整成员集合恢复的原始证据仍归前阶段。本轮没有留下断网/取流失败注入或改变循环模式。

后续按[待补清单](backend-device-followup.md)继续系统专项及B4保守换源匹配，不把单设备样本或合成状态机通过扩充为正式前端验收。
