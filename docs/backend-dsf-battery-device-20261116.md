# DSD64实际断电半小时耗电与持续播放观察

2026-11-16，Asia/Shanghai。PJF110／Android16。用户提供约一小时手机窗口并授权独立不充电观察，之后明确后台游戏已由本人关闭。只操作Musxi与固定SAF样本，不改其他应用、音量、蓝牙或系统电池策略；第五六批暂缓，不改正式UI／生产gate，不提交推送发布。

## 结果

手机自主记录确认22:33:06.603检测到所有外部电源断开，23:03:12.027完成；与用户估计22:33拔线一致。结束后用户重新接线，再读取原始文件。没有以adb模拟断电，未把等待拔线或接回后的充电算入测量。

| 指标 | 本轮实际结果 |
| --- | --- |
| 样本 | 真实音乐DSD64双声道Suara《星灯》，253,049ms，单曲循环 |
| 断电播放墙钟／位置累计推进 | 1,805,362ms／1,805,076ms，差−286ms |
| 采样／循环／最大间隔 | 120次／8次曲末回绕／15,071ms |
| 播放检查 | 全部采样playing／connected，无failure／localError，buffering=false |
| 缓冲状态跃迁 | 0次 |
| 电源／屏幕 | 起止及全部采样plugged=0、status=DISCHARGING；全部采样熄屏且锁定 |
| 电量 | 79%→77%，下降2个百分点 |
| 电池charge counter | 2,124,000→2,072,000µAh，报告减少52mAh |
| 按charge counter估算整机平均电流 | 约103.7mA，仅本窗口粗估 |
| 电池温度 | 起24.5°C、末21.6°C，范围21.6～24.5°C |
| 系统热状态 | 全部0（正常） |
| PSS | 220,872～257,560KiB，末222,454；前／后半中位数237,772／236,340.5KiB |
| Java heap | 33,980,384～67,282,368字节，末33,980,384；前／后半中位数50,397,736／50,219,632 |

设备原始报告passed，主机另按原始样本独立复核。结束finally已提交暂停并resetgate；立即finalState可能仍playing，接回后的新鲜快照确认!playing／gate=false／jobActive=false，不把异步提交当作已完成暂停读回。

这是一份实际不充电的整机观察：没有空闲或普通PCM对照、校准功率仪或多次重复，不能把52mAh归因于解码器，也不能据此线性估算整机续航、宣称所有DSD倍率耗电可接受。电量百分比和charge counter各有量化／估算误差；CURRENT_NOW在本机返回数值仅保留原始诊断，不按该字段解释物理电流或功率。应用CPU累计865,867ms也不是独占解码器CPU统计。本轮没有新听感反馈，不能从状态采样排除所有输出层短促异常。

## 准备、用户操作与自动结束

debug固定步骤audio_expansion_dsf_battery30仅接受本轮cycle marker及owned sample.dsf，从前台资格下实际播放。等待系统ACTION_BATTERY_CHANGED的plugged=0才开始30分钟计时；等待拔线最长15分钟，观察中重新接电则明确标power_reconnected，不记完整passed。每15秒保存状态／PSS／heap／电量／charge counter／电压／温度／thermal，另外监听缓冲跃迁，到时自动暂停并关闭临时gate，无instrumentation驻留，不要求USB或无线调试保持连接。

用户关闭游戏后才开始测量。原始report中backgroundLoad沿用泛化的“user_game_untouched”字符串，表示代理未操作游戏，不准确表达其运行状态；原始报告不改写，user-conditions.json及独立汇总明确修正为“用户已关闭游戏”。不把其他后台活动均清零作为已验证事实。

首次battery_setup因手机自动锁屏，文件选择器不可交互而超时，尚未导入，marker中音频ID为空。该失败日志保留；清理本轮空文件夹通过。用户解锁后，同一包重新固定setup通过，没有更换样本或掩盖失败。22:29确认waiting_unplug／playing／gate=true／jobActive=true后请求熄屏；22:33物理拔线。首次准备失败不算播放／耗电测试失败，也未重放已开始的断电窗口。

## 构建与数据保全

本轮只修改DsfDeviceValidationRunner和DsfProductionDeviceTest，增加有界电池观察及单样本setup，正式main／解码器未修改。统一验证`.local/artifacts/verification/20261116T141603559Z-24968/`passed：2,971项全过，Lint0错误152警告，主／测试包编译及源码冻结通过；实机结束792份src／构建／scripts文件与该manifest匹配。

- 主APK SHA-256：`04b9e20a2a06ac9fff89601f7b6e5d336b45340a4b541475694444bcba25bd98`。
- 测试APK SHA-256：`2906a134d5700f1031ff98f80fde13309da081aae2f9bb270d4169604038958c`。

保留数据覆盖安装并核实实装hash。断电窗口使用先前验过的用户材料DSD64《星灯》，原件只读，手机拷贝完整散列匹配。结束仅清理本轮owned音频／文件夹／队列、手机sample.dsf及marker；七表逐字段一致（progress仅比较时忽略updated_at_ms），integrity_check=ok，受管文件／授权一致。平台journal原始字节未变，未写回；原inactive本地journal在owned队列清理后通过完整中转及逐字节核对恢复，Musxi恢复开始时无进程状态，无整库覆盖。

证据`.local/artifacts/dsf-battery-device-20261116/`包含原始报告、独立复核、等待拔线、用户时间、首次锁屏失败、setup／cleanup、包和保全。私有基线`.local/checkpoints/dsf-battery-device-20261116/`只属于本轮，不能恢复到后续用户状态。真实云端样本经用户讨论改为按需兼容性验证，不再作为第四批正式开放前置；正常生产gate与release路径仍待验，正式DSF入口继续关闭。
