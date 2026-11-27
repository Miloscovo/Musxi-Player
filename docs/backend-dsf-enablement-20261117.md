# DSF正常生产启用与Release路径收尾

日期：2026-11-17。仅后端；用户已授权连接手机验收，不提交、推送或发布。正式前端、第五批自定义音源和第六批USB继续暂缓。

## 当前结果与支持范围

正式应用 `MusxiApplication.dsfPlaybackEnabled=true`，本地DSF预览、复制／引用导入、目录候选、来源探测及服务Extractor共用该能力。新debug进程默认继承正式值，无需运行验收广播或临时setter。旧 `dsfDeviceAcceptance` 只作当前debug进程的显式覆盖；进程重启不保留覆盖。`PlaybackHost.experimentalDsfDecoding` 仅兼容既有测试host。

首版为DSF中DSD64／128／256、单／双声道解码到176.4kHz浮点PCM，再交给现有Media3／系统输出。真实双声道64／128本轮正常路径已验，256依据前轮专项；单声道仅有代码／自动化覆盖，未新增实机。物理输出采样率、原生DSD、DoP、USB独占、DFF／DST、DSD512与多声道不在本次开放范围。不会将平台网络音频交给DSF sniff。

`dsfPreview.preview` 仍为只读，不入库、不取得持久授权。`playbackSupported` 依据实际格式、应用能力与随机读取条件计算，不能把扩展名识别当成完整可播证明。REFERENCE的权限、随机访问及来源变化检查继续保留；不能随机读取时使用 `READ_FAILED + COPY_REQUIRED`，由用户选择COPY。复制完成后复核实际副本与可用标签；错误MIME可沿用 `files(showAllFileTypes=true)`。恢复提示的正式页面接线仍待前端。

## 本轮设备与安装包

设备为PJF110／Android16，保留数据覆盖安装开发包 `tech.musxi.player.dev`。只操作Musxi、固定验收入口及系统文件选择器，未修改账号、平台收藏／歌单、音量、蓝牙或电池策略。用户材料根 `C:\text\musxi-测试用各类音频文件` 原件不改；真实DSD64《星灯》和DSD128《The levee broke》两份手机临时副本均先匹配SHA-256。

- 最终统一验证：`.local/artifacts/verification/20261117T005526811Z-23676/`，2,971项全部通过，失败／错误／跳过均0；debug Lint0错误、152警告，主包／测试包编译与源码冻结通过。
- 手机主包SHA-256：`8afd0259fd2791d067e56d7d066f0a0963c24c8bae7c32bbbe691980d5ead454`；最终测试包：`d6d963cc3f14e4eb84432b91a6c8ce10847a0a1cfad471c39638b49da5b489c0`。初轮主包与最终主包逐字节相同，只有测试包更新。
- 本轮证据：`.local/artifacts/dsf-enablement-20261117/`；原数据快照与失败logcat：`.local/checkpoints/dsf-enablement-20261117/`，不提交。

## 正常启动与重启读回

`normal_setup` 未调用临时gate setter：正常能力为true、legacy flag为false，DSD64／128各COPY、REFERENCE共四条导入，通过176.4kHz应用输出观察、10秒定位、稳定暂停1.5秒／漂移不超过100ms、恢复推进至少500ms及跳近曲末自然结束。

初次 `normal_restart` 在等待曲末时失败，原JSON与日志完整保留。原因是测试用整个文件夹队列，而重启时四条已全部存在，第一条播完正常进入第二条，不能期待整个队列进入ENDED。修正仅在设备测试：传null文件夹建立单条队列，断言队列数1、循环OFF及最终曲目身份；没有放宽曲末100ms误差或修改正式播放器。

随后 `normal_restart_single` 曾在首条开始播放的25秒等待超时，与前次曲末断言失败不同；原报告保留，尚未确定这次孤立超时的原因，不能声称已修复。设备测试增加失败瞬间的结构化播放诊断字段，没有改等待阈值或正式代码。

带诊断的最终测试包下，`normal_restart_observed` 及同包再次force-stop后的独立confirm两轮新进程均保持正常能力true、legacy flag false，四条来源全部可解析并通过相同控制检查，启动超时未复现。保留偶发启动超时的后续观察项，不将其抹成全程一次通过。以下是confirm轮独立读回，单位毫秒：

| 来源 | 模式 | 预览时长 | 实际曲末位置 | 控制与身份检查 |
| --- | --- | ---: | ---: | --- |
| DSD64 | COPY | 253049 | 253054 | 通过 |
| DSD64 | REFERENCE | 253049 | 253051 | 通过 |
| DSD128 | COPY | 201853 | 201860 | 通过 |
| DSD128 | REFERENCE | 201853 | 201855 | 通过 |

本轮是正常能力与控制／重启验证，不声称重新完整听完两首、不新增听感或耗电结论。前轮真实音乐整首、管道、实际provider、切换、USB一小时和[断电半小时](backend-dsf-battery-device-20261116.md)按各日期证据保留。真实云端按需跟进，不再阻塞本地DSF开放。

## Release路径

新增 `pwsh -NoProfile -File build.ps1 -ReleaseCheck`，通过共享构建锁执行release Kotlin编译、Lint和合并清单，不生成、签名或安装APK。它与Release／Test／Lint／Verify／Install互斥。

本轮release编译通过，app release Lint0错误、55警告；清单使用正式 `MusxiApplication`、无debuggable／testOnly；检查的debug验收组件及类未进入release清单／编译产物。`packageRelease`、`packageReleaseBundle`、`bundleRelease` 的实际任务图均包含先行签名门禁，显式运行门禁在缺少密码时按预期失败；assemble／sign入口亦依赖门禁。无unsigned或debug签名回退。

**尚未生成本轮正式签名APK，也未在正式包上做设备验证。** 当前进程未取得签名密码；没有使用一次性密钥替代长期发布密钥。此处证明release源码／清单／编译隔离及签名任务约束，不能当成最终APK的DEX／证书／安装验证。既有 `release-0.3.0` 三份发布材料逐文件散列不变；源码仍0.3.0／code6，本轮未升版。实际下一次发布应先确定版本递增，再由所有者在本机安全输入密码，沿用原正式签名，详见[Release构建](release-build.md)。不在聊天或日志发送密码。

## 收尾保全与后续

仅删除marker记录且与基线无交集的四条测试音频／文件夹与对应临时队列；两份手机样本经散列核对后移除。原库七表逐行一致、SQLite integrity为ok，原受管文件和owned授权一致；原inactive本地journal逐字节恢复，原平台journals仅核对未写回。最终无播放、无验收任务，正常能力仍true、legacy flag false、marker不存在，Musxi恢复测试前无进程状态。

后续release剩正式签名包生成／包内隔离／证书与正式安装验收；发布需另有授权。其他Android版本、单声道设备、更多文件提供方、数小时后台／深度Doze和不同路由继续按兼容性场景补验。第五六批和正式新页面不自动展开。
