# 歌词翻译第二阶段与设备补验

> 历史专项记录：以下只对应文中日期、包和测试范围；当前状态以 [0.1 验收](android-0.1-acceptance.md) 为准。本机证据路径仅用于本地复查，不随仓库分发。

2026-10-22 文档校准：本文件保留对应阶段的包、测试、听音及未覆盖范围，正文“下一步/待实现”为当时安排。当前三平台翻译、原文先发/译文后补及独立刷新均已实现，B4保守匹配已实现，B1-08正在接续；以[当前后端现状](backend-status-20261022.md)和[歌词契约](frontend-backend-contract-lyrics-translation.md)判断当前状态，不把本阶段证据迁移为新包通过。

后续QQ和酷狗KRC翻译已经接入，最新350项测试及手机最终安装包见[酷狗阶段记录](lyrics-translation-kugou-acceptance-20261022.md)，QQ历史证据见[QQ阶段](lyrics-translation-qq-acceptance-20261022.md)。本文保留网易云阶段的历史APK与证据，不能用“QQ/酷狗尚未实现”覆盖后续现状。

日期：2026-10-22（北京时间）。用户连接手机并授权继续后端开发和设备检查。源码在 `<project-root>/.local/worktrees/Musxi-Player-android-backend-next` / `android/backend-reliability`、基于 `ac3ac80` 隔离完成，逐文件核对后同步到主工作区 `<project-root>`。前端仍由 DS 主导、cc 辅助，本轮未编辑正式 UI、MainActivity、manifest 或 Gradle。

## 交付范围

- core 新增 LRC 原译文对齐；网易云同一次官方歌词响应读取 `tlyric.lyric`，不额外取流或增加服务。保留原文播放时钟、同一 activeIndex、来源与双账号代数隔离。
- 译文缺失、坏格式、超限、无法可靠配对分别处理；译文失败保留原文。精确优先、±100ms 的容差匹配拒绝歧义，不以行号拼接。详细规则见[接线契约](frontend-backend-contract-lyrics-translation.md)。
- QQ/酷狗仍原文，翻译为 UNKNOWN + UNSUPPORTED/NOT_IMPLEMENTED；没有独立 `refreshTranslation()`。`lyrics.refresh()` 仍刷新整份原文，不能承诺网络失败时保留旧文档。
- 只新增 debug 固定探测入口及计数诊断；无歌曲身份、歌词文本、账号、Cookie、私有响应或音频地址进入留证文件。

## 设备与产物归属

OPPO PJF110、Android 16，ADB 序列 `<device-serial>`，包名 `tech.musxi.player.dev`。覆盖安装保留数据；三家账号均自动重验为 CONNECTED。手机原包为 2026-10-20 后端验收包，覆盖前已备份到 `<project-root>\.local\artifacts\musxi-translation-20261022\device-before.apk`，SHA-256 为 `9ab1bf7493320f1739186300731f9b274ed597935aa98fb4b915303c6b920bc9`。

首次设备检查 APK SHA-256：`b2a51300651699c27a5bda3a30be5833a065bd5ac396eb1a1f1b334f16ea0e81`，v2 签名验证通过。下表的详细 seek、换源、冷启动及竞争窗口证据来自此包。交叉审查随后修复密集时间戳容差争用，并为最终包另存复测记录；两包均为隔离后端验收工具，不包含主工作区进行中的正式前端。

最终包与复测结果见本文末尾。不能用后端验收页替代双语页面布局、滚动、视觉或真实听音验收。

## 真实平台与播放器检查

固定关键词搜索后，取该次网易云搜索结果中首个标题精确匹配项；不是已核定歌手/录音版本的全量曲目测试。“晴天”结果不能推广为所有同名录音均无译文。

| 检查 | 实际观察 | 证据 XML |
| --- | --- | --- |
| `Lemon 米津玄師` → `Lemon` | 46 行原文、42 行译文，AVAILABLE/SUCCEEDED，时间有序 | probe-lemon.xml |
| `Hello Adele` → `Hello` | 53 行原文、51 行译文，AVAILABLE/SUCCEEDED，时间有序 | probe-hello.xml |
| `周杰伦 晴天` → `晴天` | 16 行原文、0 行译文，NONE/SUCCEEDED/NOT_PROVIDED，原文成功 | probe-qing.xml |
| Lemon 实际起播 | playing=true，NETEASE 原队列/来源，46/42，歌词 source 匹配、activeIndex 匹配、无播放器错误，取流 1 次 | play-lemon.xml |
| 暂停后 seek 30s / 5s | 位置 30000/5000ms、activeIndex 8/4、当前行有译文；同来源、同索引，保持暂停，取流仍 1 次 | seek-30.xml、seek-5.xml |
| 整份歌词刷新 | 仍为 TIMED、46/42、AVAILABLE，位置 5000ms、暂停，取流仍 1 次 | refresh-lemon.xml |
| 原曲注入一次 PERMISSION 后重新点播 | 实际 QQ 替代来源起播，原 NETEASE 队列仍 1 项；57 行 QQ 原文、0 译文，UNKNOWN/UNSUPPORTED/NOT_IMPLEMENTED，source/index 匹配，无网易译文残留 | alternative-source.xml |
| 暂停、强停进程再打开 | 早期 CONNECTING，稍后 CONNECTED；队列 0，playing/wanted=false，取流 0 次，三家账号 CONNECTED | cold-start-early.xml、cold-start-ready.xml |
| 显式恢复网易云 journal | 队列 1、位置 10110ms、暂停、取流 0 次；恢复原 NETEASE 来源及其翻译，不保存上次 QQ 音频票据 | explicit-restore.xml、explicit-restore-journal.xml |

上述源切换的原权限错误是 **debug 注入**，QQ 搜索/取流/歌词是当次真实请求；没有声称网易云真实拒绝该曲，也未证明两平台音频为同录音。playing/position/错误状态是程序观测，本轮没有新增用户听音确认。

B1-03 的“进程重开及显式暂停恢复”子项已通过；服务单独丢失队列/真实 Binder 断连仍未覆盖。其余 B1 的连接故障预算、迟到确认、长后台/Doze、焦点及输出设备专项不由此自动通过。

## B3 逻辑请求样本

正常手机网络条件，未人为切断网络或改变网络设置；不记录具体 Wi-Fi/运营商。第一窗口清空计时后依次做三组固定搜索+歌词读取，所有请求均成功、无 rejected/stale：

| 操作 | count/completed/success | queue total/max ms | work total/max ms | delivery total/max ms |
| --- | --- | --- | --- | --- |
| NETEASE_SEARCH | 3/3/3 | 2/1 | 1769/660 | 1/1 |
| NETEASE_LYRICS | 3/3/3 | 0/0 | 513/212 | 2/1 |

第二窗口清空计时后顺序快速提交 `p0_netease_playlists`、`p0_netease_search`、`lt_play_sample`，随后保存 `read-play-window.xml`；三项都成功：

| 操作 | count/completed/success | queue total/max ms | work total/max ms | delivery total/max ms |
| --- | --- | --- | --- | --- |
| NETEASE_PLAYLISTS | 1/1/1 | 1/1 | 220/220 | 1/1 |
| NETEASE_SEARCH | 1/1/1 | 69/69 | 700/700 | 1/1 |
| NETEASE_AUDIO | 1/1/1 | 559/559 | 178/178 | 1/1 |

这证明该次共享串行队列下 AUDIO 等待了 559ms，比本次逻辑执行的 178ms 更长，值得后续评估取流优先级。仅各一次且无等长独立起播基线，不能估计平均/P95/普遍收益，不能据此立即改并发和账号队列安全语义。歌词命中 Browser 内存缓存时没有新增 LYRICS 任务，不能把缺少桶误判为没显示歌词。

work 包含整个逻辑请求及其内部步骤，不等于单个 HTTP RTT；count 是逻辑提交数。B3-01 仅覆盖网易云搜索/歌词及歌单首页/取流，尚缺三平台逐操作、多页；B3-02 仅完成一次读请求+起播竞争，尚缺完整收藏核实和对照窗口。B3-03 的取消残余 HTTP 测量仍无专门能力。

## 自动验证、留证与后续

初版 `./build.ps1 -Test -Lint`：309 项 JUnit 全通过、无失败/错误/跳过，六模块 Lint 0 errors/12 warnings。主工作区初次源码及前端单测源码编译通过。最终容差歧义补丁另增两项测试，最终验证见末尾。

全部 XML 证据位于 `<project-root>\.local\artifacts\musxi-translation-20261022`，只含固定状态/计数/耗时；不作为 Git 生产资源提交。安装前的状态与旧包也在此处。源码同步仅 8 个后端/测试/debug 文件，执行前核对主工作区原 5 文件 SHA-256 未变，新 3 文件不存在；随后逐文件核对一致。

保留本人三平台登录、缓存及平台收藏；本轮没有收藏写入、退出、换号或清数据。播放测试更新了网易云本机队列快照/位置与最近播放，QQ/酷狗平台歌单未改变。PR #24、提交、推送及发布均未操作。

下一步先让 DS 依据契约接双语行，补正式页面设备验收；后端接 QQ 翻译字段/编码，酷狗 KRC 单独移植。继续按[实测清单](backend-device-followup.md)补未覆盖项，B4 歌手/时长/版本匹配与独立译文刷新尚待实现。

## 最终构建与复测

修复歧义容差争用后的最终 APK SHA-256：`749984794abe6337fdc307ee36ae163fb59b522c0bcd73d3f443e50ccc6afefb`，v2 签名通过，已覆盖安装。最终 `./build.ps1 -Test -Lint` 成功，311 项 JUnit，0 failures/errors/skipped（core183/app11/playback45/网易云22/QQ22/酷狗28）；六模块 Lint 0 errors/12 warnings，与先前数量一致。另执行 `:app:processReleaseMainManifest` 并检查合并清单，无 ValidationReceiver、调试焦点页或 DebugMusxiApplication；未发布或验证完整 release APK。

最终包重新冷启动时队列 0、取流 0、暂停，三家账号 CONNECTED。重新请求三个真实平台样本，Lemon 46/42、Hello 53/51、“晴天”搜索命中 16/0，状态与初版一致，原译时间有序。证据在 `final/cold-start-ready.xml` 和 `final/probe-*.xml`。详细初版换源/竞争证据保留独立归属，不冒充最终包重新完整执行。

8 个源码/测试/debug 文件的最终散列与主工作区逐一相同，清单为 `final/source-parity.json`；主工作区最终 `:app:compileDebugKotlin :app:compileDebugUnitTestKotlin` 成功，验证当前前端源码兼容，没有运行前端布局/交互测试。单测汇总为 `final/junit-summary.json`。手机收尾的暂停/来源/行索引快照为 `final/device-final.xml`，最终安装的是本文隔离后端验收包。

交叉审查的三条反馈分别处置：密集容差争用已修复并回归；“80%”明确是译文时间配对率，非原文译文覆盖率，补测试锁定部分译文语义；10000 源行包含尾换行后的空行是既有解析上限，本轮保持兼容并在契约写清，没有扩大原文解析范围。
