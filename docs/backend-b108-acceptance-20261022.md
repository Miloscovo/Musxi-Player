# B1-08 前台资格门禁与设备回归（2026-10-22）

> 历史专项记录：以下只对应文中日期、包和测试范围；当前状态以 [0.1 验收](android-0.1-acceptance.md) 为准。本机证据路径仅用于本地复查，不随仓库分发。

2026-10-23集成候选补验：当前0.1.0-alpha01已补真实后台自动换源成功样本与用户声音/曲目确认，见[新包补验](backend-b108-alpha01-acceptance-20261023.md)。下文“最终包仍待补”仅指10-22旧后端包；不改写其两次NETWORK事实，也不把新样本扩大为深Doze等全项通过。

前台服务先于音频焦点的时序修复已实现，7 个后端文件已同步主工程；**最终包真实自动换源的成功播放闭环仍待补测**。最终包的两次 QQ→网易云换源均建立前台资格并提交播放，随后因 NETWORK 停止，不能算作完整通过。受控等待超时、主动暂停取消、显式重试和实际酷狗播放已通过。

本轮同时校准 README、AGENTS、前后端交接、P1/初版契约、歌词翻译契约/路线及规划，旧阶段验收保留包和日期。前端 F1/F2 已接，F3 原文首版已接，双语/开关/独立刷新入口以及 F4 收藏/队列管理、恢复/最近播放入口未完成；歌词由用户与 DS、cc 讨论。本轮未编辑前端实现。

## 修复依据与行为

Media3 1.8.0 的默认通知控制器根据会话的 READY/BUFFERING 与 playWhenReady 决定前台服务，而 raw ExoPlayer 的 prepare/play 可以先请求焦点。Android 16 / targetSdk 36 下，后台缺少前台资格会被拒绝。依赖源码已核对，下载材料仅保存在本轮证据目录。

- 启动、恢复和替代来源起播统一经过 `ForegroundPlaybackGate`。raw player 先 pause/prepare；会话暂时呈现有界的 BUFFERING/播放意图，允许 Media3 按正常通知流程升前台；观察到 `isPlaybackOngoing()` 后只调用一次 raw play。
- 等待预算 3 秒，25ms 检查一次，只有一个待执行定时器。没有重复取焦点、无限 play 或延长生产前台保留时间。保留 Media3 原有焦点处理和默认 10 分钟前台保留策略。
- 原票据、recoveryEpoch、原账号及已选来源账号代次共同拒绝旧回调；暂停、停止、换队列、授权变化和销毁取消等待。完成时先在 pending 保留期间提交 raw play，再发布等待结束，避免完成通知自己撤销前台资格。
- 系统拒绝前台启动或等待超时，报告 `PlaybackFailure.BACKGROUND_RESTRICTED` 并停止播放意图；异步取流错误不能覆盖这个受限状态。后续只有明确播放才重试，不因限制解除或账号重验自播。
- 新失败枚举供前端后续细化提示；现有通用失败分支可继续显示错误。本轮不改 UI。冷启动、journal 恢复仍不 prepare、不取流、不自播。

独立复审指出过“先清 pending 再 play 会失去前台资格”的窗口，主代理已修正并补第 8 项门禁回归；复审确认该窗口和 terminal 重入取消/替换处理已解决。

## 构建与包归属

均运行项目 `build.ps1 -Test -Lint`，测试结果无失败、错误或跳过；未新增生产依赖或改 Manifest/Gradle。

| 范围 | 测试 | Lint | APK SHA-256 / 用途 |
| --- | --- | --- | --- |
| 原手机 B4 包 | 本轮未重建 | 本轮未重跑 | `a51868c0522ecb85967337f7162b41651f219538b40ce26ae3aaa54db025aa86`，仅保存原包与对照 |
| 第一候选后端包 | 392 项 | 0 errors / 9 warnings | `6a22667cc41bdad756a9e25272a3c50c17a4198cb24ac3ef3501fade666539e9`，后台换源成功及用户听音属于此包 |
| 最终隔离后端包 | 392 项：core223/app11/playback53/酷狗54/QQ29/网易云22 | 0 errors / 9 warnings | `6c5107857cf9f05e0bb5701aa92fc2f89d10cd5cba23d4cce23254262114bd1c`，已安装、设备文件散列一致、v2签名通过 |
| 主工程集成包 | 515 项：app134，其余与后端一致 | 0 errors / 76 warnings | `71d7c99e727cbbc361cd80c21f07563f5d70d976a56d72c241f2d2f66333a20e`，仅构建验证，未安装，不当作前端整体验收 |

前后端测试范围不同，不累加或互相替代。主工程警告包括依赖版本建议及现有 debug 工具的 UseKtx 等，未为此变更依赖或前端。release 主清单另行生成，使用正式 MusxiApplication，未声明 ValidationReceiver、DebugMusxiApplication 或 ValidationFocusActivity；未构建或发布正式包。

第一次整包构建遇到迁移前的 D8 绝对路径缓存，已用 Gradle clean 清理可再生成产物后重建。一次编译期间补入完成时序测试，旧编译产物触发失败；最终源码冻结后重跑全套通过。早期失败日志保留，不算最终验证。

## 设备验证

设备 OPPO PJF110，Android 16 / API36，保留三平台账号、平台收藏与缓存。固定 debug 入口受 DUMP 权限限制，无坐标点按、账号切换或真实收藏写入。

| 包 / 场景 | 实际观察 | 结论与证据 |
| --- | --- | --- |
| 第一候选 / 首次恢复播放 | foreground=false → 通知升前台 → foreground_gate_ready(true) → raw ready=true；实际酷狗进度增长 | 起播顺序通过，`final-resume-events.log` |
| 第一候选 / 听音 | 用户第一次未戴耳机；重新播放原酷狗样本后明确回复“声音正常，可以继续测试” | 仅归属第一候选包，不迁移为最终包再次听音 |
| 第一候选 / 后台实际自动换源 | QQ真实PERMISSION，另外两家各10候选，5秒选网易云索引0；7首队列索引1保留，后台 playing=true、约49秒进度，无焦点拒绝 | 此包闭环通过，`final-auto-background-r2.log`、`final-auto-background-r2-settled.xml`及对应diagnostics |
| 第一候选 / 通知动作 | HOME后调用实际通知PendingIntent暂停/继续，wanted与playing相应变化 | 此包通知动作通过，`final-notification-paused.xml`、`final-notification-playing.xml` |
| 最终包 / 等待超时注入 | 强制前台判定保持false，实际服务可升前台，但门禁3秒后停止，BACKGROUND_RESTRICTED，pending=false | 有界失败通过；这是判定注入，`verified-hold-timeout.xml/.log` |
| 最终包 / 暂停取消 | hold期间发play，约350ms后主动pause；解除hold后仍暂停，之后显式play才实际起播 | 不自播与显式重试通过，`verified-cancelled-no-autoplay.xml`、`verified-explicit-retry-system.xml` |
| 最终包 / 已降后台后起播 | debug把前台保留时间设0，暂停后foreground=false；近期前台操作后HOME提交新队列，21:48:44.948前台建立、44.968才raw play；真实QQ拒绝后5秒再次通过门禁 | 资格先于焦点通过；这是缩短保留期的注入，不是自然等十分钟 |
| 最终包 / 两次实际自动换源 | 两家各10候选、选网易云、foreground_gate_ready(true)后raw play；两轮最终NETWORK停止，未出现HardeningEnforcer焦点拒绝 | 完整播放闭环未通过，不自动重试；`verified-auto-background.log`、`verified-auto-background-r4.log`，回前台确认NETWORK见`verified-auto-r4-after-return.xml` |
| 最终包 / 酷狗实际播放 | queue/source KUGOU，playing=true、failure=NONE，约206秒位置 | 最终包实际播放状态通过，`verified-kugou-playing.xml`，无本包新增听音确认 |
| 最终包 / 冷启动与收尾恢复 | 冷启动queue0/resolve0不自播；恢复原1项酷狗队列于81715ms、OFF、暂停，resolve0，foreground=false、pending=false | 暂停恢复通过，`final-accounts-cold.xml`、`final-restored.xml`、`final-accounts.xml` |

部分 HOME 后广播没有刷新 preferences：例如多个文件仍为相同 snapshotElapsedMs。对旧快照不推断当前播放/失败，改用固定事件时间线及回前台后的新快照核实。`final-before-background-start.xml` 等早期不足1秒的动作采样不作动作后验收；`verified-auto-background*.xml` 若仍停留旧时间或只显示等待态，不作播放成功证据。

最后一次尝试在较长后台无前台服务时提交队列，广播直到应用回前台才实际处理，未形成“最终包后台真实拒绝”的独立证据，`verified-system-denied*` 不作该结论。第一候选包曾实测系统拒绝前台启动，但当时迟到PERMISSION覆盖了失败，已促成最终包的状态保留修正；最终包受控超时状态已验证。

## 保留边界与收尾

- 最终包再补一次真实后台替代音源播放成功，优先核对此次网易云NETWORK；不把这类失败写成焦点拒绝，也不以重复自动播放绕过。
- 自然长暂停、深度Doze、真实来电/输出变化/系统Binder回收不在本轮新增证明范围。前端新枚举提示、歌词与整体验收另行推进。
- 三个平台的播放 journal 在手机本地备份；收尾强停后逐文件恢复并cmp一致，再重开显式恢复原酷狗队列，保留原recent和模式。没有导出账号存储或平台私有响应。最终三家CONNECTED，gate=ALLOW、pendingAcks0、keeper=false，无故障开关或待播放事务。
- 7文件同步前检查主工程原散列与新增路径归属，同步后双树逐项一致；前端源码未改，PR #24工作树仍干净。无提交、推送、PR更新或发布。

本轮材料统一在 `<project-root>\.local\artifacts\musxi-b108-20261022\`：原包/源码基线、最终后端APK、构建日志、脱敏设备快照、`validation-summary.json`及`synced-backend.json`。源码和临时材料均未散放到工作区根目录。
