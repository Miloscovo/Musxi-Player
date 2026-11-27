# DSF 第四批：后台受限定位、整首与熄屏验收

2026-11-15（会话时区 Asia/Tokyo；原始主机／设备日志跨 2026-11-14 UTC+08）。用户授权在纯净手机窗口按顺序继续后端验收。本轮仅修改 debug 验收入口及文档，不改正式前端、解码算法或系统权限，不提交、推送或发布。

## 结论

同一真实 DSD256／双声道样本现已两次从头自然播放至结束；最终包的一轮 374 次采样全部处于熄屏、锁定状态，观察 387,497 ms、进度推进 386,500 ms，缓冲状态跃迁为 0。此前[热点优化](backend-dsf-optimization-20261114.md)的前台两分钟证据已补为整首及数分钟熄屏证据。

正式 `PlaybackHost.experimentalDsfDecoding` 仍默认 false，公开 `DsfPreviewInfo.playbackSupported=false`；正式 DSF 导入／播放未开放。结果只覆盖 PJF110／Android 16、本轮单个 DSD256 文件和应用 PCM 桥接，不代表真人听感、其他倍率、数小时后台或 USB／原生 DSD 已通过。

## starting 的复现与原因

旧包无页面冷触发后再次停在 starting。本次日志明确显示曲库和 MediaController 已初始化、点播已提交，随后系统抛出 `ForegroundServiceStartNotAllowedException`，原后端正确发布 `BACKGROUND_RESTRICTED`；服务 `startForegroundCount=0`。之后进程进入 cached 状态，厂商记录 `virtualFreeze=true`。

旧验收协程仅每 100 ms 等待 playing，既不及时消费失败状态，也可能因进程冻结而不能执行超时回调，导致报告长期停在 starting。本次复现不是解码吞吐下降，也不应通过乱开权限或关闭系统限制来“做通过”。旧轮缺少同级日志的其他停更现象仍不归为唯一同因。

修改仅在 `DsfDeviceValidationRunner` 与其 DUMP 保护的 `ValidationReceiver` 调度：

- readiness 和播放失败改为状态事件观察，记录 waiting_ready／loading_owned_item／submitting_play／waiting_playback／continuous 等阶段；后台受限直接终止并报告。
- `goAsync` 只覆盖最长 8 秒的启动等待，成功或失败后立即释放；不会为六分钟观察期长期占用广播接收器。
- 区分 timed_out、cancelled 与 background_restricted；取消等待时同时取消本次返回的准备 Job，忽略已失效回调。finally 只暂停仍属本轮 ID 的播放，并复位 debug 开关。
- 增加固定的整首、控制和只读快照步骤；不接受任意文件路径、曲目 ID 或平台写入参数。release 不包含这些入口。

最终包冷触发同样得到 background_restricted，未进入播放；重新打开现有 MainActivity 后按正式 facade 点播及控制正常。后台任意冷启动和已经开始播放后的后台运行分别验收，不混为同一通过条件。

## 实机结果

测试前确认旧 marker 与专用目录不存在，建立新 cycle，只准备唯一 1,092,855,071 字节样本的手机副本并核对 SHA-256。通过正式 facade 引用导入／预览；源 11,289,600 Hz／1bit，约 386,493 ms。桥接输入与应用 AudioTrack 为 176,400 Hz；物理 deviceOutput 仍未知。源 ID3 超过既有 1 MiB 上限，UNAVAILABLE 为预期，不计作真实标题／封面提取通过。

| 场景 | 实际结果 |
| --- | --- |
| 第一验收包整首 | startup 588 ms；初始 1 ms，366 次采样，wall 387,405 ms／advance 386,496 ms；缓冲跃迁 0；自然曲末通过 |
| 切后台与返回 | 第一轮约 26 秒按 Home，后台服务 isForeground=true、进程非 cached；约 322 秒返回 MainActivity 后仍连续播放 |
| 第一轮熄屏 | 只观察到约 18 秒后重新亮起；保留为短熄屏，不冒充整首熄屏 |
| 最终包整首熄屏 | startup 3,245 ms；初始 2 ms，374 次采样全部 screenInteractive=false／deviceLocked=true；wall 387,497 ms／advance 386,500 ms；缓冲跃迁 0；最终位置 386,502 ms，ended=true |
| 暂停／恢复／定位 | 最终包暂停后 1.5 秒位置变化不超过 100 ms；恢复正常；60→240→120→300→30→180 秒六次定位均读回并继续播放 |
| 快速取消 | 发出取消请求时报告阶段为 submitting_play；最终报告 cancelled，位置 15 ms、playing=false／playWhenReady=false、gate=false、jobActive=false。跨 ADB 时序不能证明取消必在准备 Job 完成前送达，不将该竞态全部判为已验 |
| 播放中取消 | 正常播放取样后取消；独立快照确认暂停、Job 结束且开关复位 |

两次整首之间有构建与回归间隔，不构成连续十二分钟或长期热稳定性测试。最终包六分钟运行后继续做控制复验，但更长热负载、来电／焦点、Doze／数小时后台仍需专门窗口。

最终熄屏轮 PSS 188,577～219,506 KiB（约 184～214 MiB），最后 208,916 KiB；Java heap 17,269,584～52,576,480 字节，采样有回落，未观察到 OOM。最大采样间隔 1,062 ms。进程 CPU 累计 390,934 ms 包含整个应用，不能当作内核独占 CPU 或耗电；手机通过 USB 连接且充电，没有做电池续航结论。前后系统 Thermal Status 均为 0，只是两个时点记录，不证明整个过程从未调频或长期无泄漏。

## 回归、包与保全

官方 `scripts/verify.ps1` 最终 2,954 项 JVM 测试通过，0 失败／错误／跳过；Lint 0 错误／150 警告，主包与测试包编译及源码冻结通过。证据 `.local/artifacts/verification/20261114T145939302Z-10392/`。后续只更新本文和状态文档，非 Markdown 源码与该次冻结逐项一致。

最终主包 SHA-256：`8d19d19bebcc84c5fbf5f6e85728061160947894ed9671ddb254af870817fc00`；测试包：`ea95269d9fb03b45564a580b0de806d7ff2a03f518b48e6576a421bc697f9edc`。两者均与手机安装读回一致。第一轮整首使用取消准备 Job 保护补丁之前的 `920cbc8504f6cc9c18eddbf26c1eac5bfaa8064430f541eb7bd6296d33393b60`；最终包已另做整首熄屏及控制／取消复验，不混用旧包结果。

只删除本 cycle marker 的条目与文件夹；手机 sample.dsf 删除前再核对原散列。原库 audio／folders／members／progress／cursors／bookmarks／directories 七表逐字段一致（仅忽略 progress 的正常 checkpoint 时间戳），integrity_check=ok；受管文件集合与持有的来源授权记录一致。清空本轮队列后只恢复原暂停 journal，逐字节一致，没有整份覆盖数据库或账号。marker 和手机副本已删除，Musxi 收尾时保持停止；手机窗口已经交还用户。

本轮私有证据 `.local/artifacts/dsf-background-20261114T2225/`，回退 `.local/checkpoints/dsf-background-20261114T2225/`。精确统计见 playback-summary.json、包与回归见 final-summary.json、保全见 preservation.json，控制脚本归档于 reproduce/；含本地库及文件来源信息，不提交或公开，不重放本轮 marker。

## 接下来

先取得真人听感确认，再补真实 DSD64／128 样本及不同文件提供方／不可 seek 来源；超限标签、封面、长时耗电与电话／蓝牙路由分别保留。准备阶段取消的精确竞态需要可控设备测试，不因快速取消通过而宣称所有时序均通过。之后再决定正式 DSF 入口开放条件。前三批已通过的 CUE／格式主链路不全量重跑，第五批自定义音源和第六批 USB 继续按[六批计划](audio-expansion-roadmap-20261114.md)安排。
