# DSF 第四批：有界听音窗口与音频焦点观察

2026-11-15（会话时区 Asia/Tokyo；原始主机／设备日志为 2026-11-14 UTC+08）。用户重新连接手机，授权继续后端实机验收。本轮复用已经验证的包，只更新验收材料与文档，没有修改应用源码、构建、安装、改系统权限或操作其他应用；不提交、推送或发布。

## 结果与边界

同一真实 DSD256 样本已运行两分钟连续播放和暂停／恢复／六次定位的有界听音窗口，技术报告通过，结束后自动暂停，临时解码开关与观察 Job 均关闭。**用户随后确认使用蓝牙耳机，主观听起来“非常流畅”**，未报告杂音、爆音、断续或声道问题。本次窗口的蓝牙主观听感已取得确认；不扩大为单独左右隔离测试、其他耳机或 USB 听感通过。

正式 `PlaybackHost.experimentalDsfDecoding=false`、`DsfPreviewInfo.playbackSupported=false`，正式 DSF 导入／播放继续关闭。仍为应用内 DSD→PCM 桥接；本轮应用输入及 AudioTrack 为 176,400 Hz，公开 `deviceOutput` 未知，不代表 USB／原生 DSD 或物理输出采样率通过。

## 本轮包与准备

PJF110／Android 16，设备连接正常，开始时 Musxi 无进程，屏幕亮起且未锁定。重新建立 `dsf-listening-20261115` cycle，不重放上一轮 marker 或旧队列快照。原有本地队列为 inactive、保留一条历史条目的状态；其中 `continuePlayback` 是续接已记忆进度的偏好，不是自动播放标志。原始字节单独保全。

手机主包 SHA-256：`8d19d19bebcc84c5fbf5f6e85728061160947894ed9671ddb254af870817fc00`；测试包：`ea95269d9fb03b45564a580b0de806d7ff2a03f518b48e6576a421bc697f9edc`。两者均重新在手机读回，与[上一轮最终统一验证](backend-dsf-background-20261115.md)一致。852 个非 Markdown 源码／配置文件逐项无漂移，也没有新增非 Markdown 源码。本轮没有重新运行统一 2,954 项测试或 Lint，沿用既有通过基线，不把它们记为新跑结果。

只复制朋友材料中唯一 1,092,855,071 字节的 DSF，核对手机与电脑 SHA-256 后，通过现有系统 SAF 及正式 facade 预览／引用导入。源为 11,289,600 Hz／1bit／双声道 DSD256，时长约 386,493 ms。标签超过既有 1 MiB 上限，`UNAVAILABLE` 仍为预期，不算标签或封面验收通过。

## 首次准备被其他应用音频焦点打断

准备阶段的自动播放先正常推进到约 16,234 ms，之后 `playing=false`、`playWhenReady=false`，没有报告播放或本地解码错误。该次设备方法按其连续播放断言返回失败，原始失败报告保留，没有改成通过。

系统音频日志在电脑本地时间 2026-11-14 23:50:19.582（UTC+08）记录另一应用申请 `AUDIOFOCUS_GAIN`，23:50:19.583 记录 Musxi 失去焦点；Musxi 自身日志在 23:50:19.588 收到 `onAudioFocusChange(-1)`，随后暂停。这次中断有明确的永久失焦证据，不能计作不间断播放通过，也不将它归为 DSF 吞吐或解码失败。未关闭、强停或修改该应用；提示用户暂不播放其他应用声音后，将 Musxi 切回前台，用同一已导入条目开启独立窗口。

这只是一次真实永久失焦后的暂停观察，不代表来电、短暂失焦、duck、自动恢复或所有音频路由已通过。

## 有界窗口

| 场景 | 结果 |
| --- | --- |
| 从头连续播放 | startup 2,440 ms；初始位置 1 ms，120 次采样，wall 123,655 ms／advance 123,187 ms；缓冲跃迁 0，最大采样间隔 1,070 ms；报告 passed |
| 屏幕状态 | 本轮 120 次采样均为亮屏，不将其新增为熄屏证据；整首熄屏仍使用上一轮专项 |
| 暂停／恢复 | 暂停后稳定观察 1.5 秒，位置变化不超过 100 ms；恢复后继续播放 |
| 定位 | 60→240→120→300→30→180 秒，六次均读回并恢复播放；报告 passed |
| 自动收尾 | 两个窗口结束后独立快照均为 playing=false、gate=false、jobActive=false |
| 真人听感 | 用户后续明确为蓝牙耳机，主观听感“非常流畅”，未报告上述异常；结论来自用户反馈，非自动状态推断 |

连续窗口 PSS 213,137～271,858 KiB，末次 270,820 KiB；是亮屏现有页面及整应用采样，不与上轮熄屏窗口混算，不作长期无泄漏、内核独占内存或耗电结论。本轮没有新增整首或长时间热稳定性测试。

## 样本缺口与下一步

只读复核朋友提供的 12 个 DSF 文件头，全部是 DSD256，没有原生录音 DSD64／128。用户后续授权自行准备，现已生成[两份 45 秒双声道合成工程样本](backend-dsf-multirate-samples-20261115.md)，FFmpeg 与现有 Musxi 内核完整解码、信号检查通过。它们可支持后续倍率／声道工程验收，仍不替代原生音乐文件兼容性验收；本轮没有在手机播放它们。

本次 DSD256 蓝牙听感已确认；接下来可用合成样本补 DSD64／128 的手机工程验收，并继续保留原生音乐样本缺口。不同／不可 seek 来源、可控准备取消竞态及数小时后台／耗电分别安排。超限标签／封面与 USB 仍独立保留，正式入口开放条件不变；第五、六批不在本轮自动启动。

## 清理与证据

仅删除本轮 marker 记录的一个测试条目及文件夹；删除手机副本前再次核对 SHA-256，并确认目录只有本轮 `sample.dsf`。marker／手机专用目录均已清理，应用恢复为开始时的停止状态。

原库 audio／folders／members／progress／cursors／bookmarks／directories 七表逐字段一致（仅忽略 progress 正常 checkpoint 的 `updated_at_ms`），`integrity_check=ok`；受管文件集合、持有来源授权记录一致。只恢复本轮开始时的原始队列 journal，逐字节一致，没有整份覆盖数据库或账号。

私有证据：`.local/artifacts/dsf-listening-20261115/`；新回退：`.local/checkpoints/dsf-listening-20261115/`，包含个人曲库，不提交或公开。`phase-dsf.json`／`preparation-focus-attribution.json` 保留准备中断；`opening-summary.json`、`listening-opening.json`、`listening-controls.json` 与独立 state 记录两个窗口；`human-feedback.json` 已补用户蓝牙听感确认，补记前的 pending 字节保留于 `.local/checkpoints/dsf-multirate-samples-20261115/`；`preservation.json` 记录清理核对。控制脚本归档于证据的 `reproduce/`，绑定本轮已清理 ownership，不可原样重跑。
