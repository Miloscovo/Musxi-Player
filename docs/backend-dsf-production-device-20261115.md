# DSF 正式导入与来源收尾：新包实机

2026-11-15，Asia/Shanghai。用户连接手机授权验收，设备PJF110／Android 16。只做后端及固定测试入口，第五、六批暂不接，没有修改正式UI、正式Application gate、解码器或生产导入实现，没有提交推送发布。

## 本轮结论

本地系统SAF下的DSF复制／引用、标签与目录识别、撤权后重启、重新关联保留用户数据、队列跳转／单曲循环、曲末定时及关闭开关拒绝现有来源，均已取得新包实机断言。取消选择在两次自动步骤失败后，经主机仅针对主屏DocumentsUI返回取得真实取消回调，确认库不变。**不能把失败的整段import改记为通过**；结果为业务断言及独立阶段通过，取消由单独辅助复验补齐。正式DSF导入／播放仍关闭。

| 场景 | 实机结果与范围 |
| --- | --- |
| gate=false | DSF预览playbackSupported=false；COPY／REFERENCE均拒绝且不入库 |
| gate临时开启 | 系统文件选择，COPY与REFERENCE生成不同条目；标题、歌手、45秒时长及无自动封面读回正确 |
| 重复引用 | 原ID不变；用户命名、7秒进度、8秒书签保留 |
| 目录导入 | 实际tree选择；DSF候选被识别，复制内容去重；DSD512及坏头拒绝，首轮另验20字节截断拒绝 |
| 撤权与重启 | 仅撤本轮单文件持久授权，停止Musxi后重启，引用来源检查PERMISSION_REQUIRED；独立COPY仍播放 |
| 重关联失败 | 坏头返回UNSUPPORTED_AUDIO；旧ID、命名、进度及书签保留 |
| 重关联成功 | 新文件名读回，ID及用户数据保留，可播放 |
| 队列与循环 | 同文件夹多条目，按当前revision跳转引用条目；定位43秒后单曲循环回到起点 |
| 曲末定时 | repeat-one下设置曲末停止，再定位43秒，读回!playing／!playWhenReady且定时关闭 |
| 再关闭gate | COPY及REFERENCE现有来源检查均UNSUPPORTED_AUDIO |
| 取消选择 | 首两次自动取消失败；独立真实SAF取消回调通过，条目／文件夹无变化 |

素材为此前已保留的原创45秒双声道DSD64合成信号，两份不同文件名内容相同；DSD512为一块结构有效的识别／拒绝样本，未播放。不是原生DSD音乐录音。COPY可播放由撤权后独立阶段证明；初次import在播放前取消超时，不能声称该阶段copyPlays断言通过。先验用户数据保留后才播放；播放后的进度属于正常更新，不再错误要求始终7秒。

## 失败与复验

首轮`.local/artifacts/dsf-production-device-20261115/`主包d0826be6…，import已通过导入等业务断言，单次Back后等不到取消回调，失败报告保留。先清理owned条目及目录，核对原库／文件／授权／队列后，才建立v2新基线。测试改为有界等SAF焦点并按显示ID返回，但v2仍在等待焦点处超时。旧AudioExpansionDeviceTest单次Back探针也失败，三份日志均未改写为成功。

检查到设备同时存在display0及另一个虚拟显示窗口；DocumentsUI在主屏持焦。保持同一实装主／测试包，主机以dumpsys确认主屏DocumentsUI持焦时才执行`input -d 0 keyevent KEYCODE_BACK`，固定旧取消测试接到accepted=false真实回调，断言原条目／文件夹不变并通过。主机辅助复验记录在`cancel-display0-recheck.json/log`；这表明本次取消链路可用，不能宣称自动多显示焦点问题已在通用脚本中修好，也不代替在途大文件复制取消。最终源码保留失败的保守超时条件，避免在不确定焦点时操作其他应用。

两次后续主机构建中断残留子进程，确认本次wrapper／worker／daemon后停止并重跑单一验证；中断目录不计通过。独立DeepSeek只读审查因路由403模型不可用没有交付，主代理审查，不计独立复核通过。

## 包与源码身份

最终统一验证`.local/artifacts/verification/20261115T112003107Z-25996/`：2,971项JVM全过，0失败／错误／跳过，Lint0错误152警告；主包、设备测试包编译和构建期间源码冻结通过。验证脚本本身没有执行设备测试，实机结果另存上述阶段日志。首次统一验证`20261115T110215342Z-3672`也通过，但不能替代修正测试后的最终验证。

- 实装debug主APK SHA-256：`d0826be64edd8e968fd80c683fbaa4b8177b72020d08561b8535e3af5c7dbc26`。
- 实装测试APK SHA-256：`972ec565a27689251da3c92bc9ab01ec3b4af2a921c50c924b1bd78522588c55`。

ADB覆盖安装均使用-r，没有清数据；装机后核对散列与七表。主包相较[正式开放准备](backend-dsf-production-prep-20261115.md)仅增加debug系统tree选择入口；另新增DsfProductionDeviceTest。正式模块main源码362份在验前与上轮一致，最终789份模块src／Gradle／scripts文件与最终验证清单一致，设备阶段没有修改应用源码或包。

证据`.local/artifacts/dsf-production-device-20261115-v2/`，含baseline、实装记录、phase日志、辅助取消、final-results、final-source-check、preservation。测试包及主包保留。主机脚本复用历史只读ADB／比较工具，但cycle、marker所有权及回退数据均为本次新建，不运行历史主流程或恢复旧数据。

## 数据保全与剩余条件

清理前检查owned ID与原库无交集，只删除本轮条目／文件夹及测试文件。最终audio／folders／members／progress／cursors／bookmarks／directories七表逐字段一致（比较时仅忽略progress正常更新时间），integrity_check=ok；受管文件集合和owned授权记录一致。原inactive队列journal逐字节恢复，marker与本轮手机样本目录移除，最后新鲜快照!playing／gate=false／jobActive=false，再恢复最初Musxi无进程状态。没有整库覆盖、账号退出、平台写入、调音量或操作其他应用。私人快照在`.local/checkpoints/dsf-production-device-20261115-v2/`，不是可提交材料。

本轮客观验收主要补上新导入与权限链路。仍需实际非随机／云端提供方、在途大文件复制取消、原生DSD64／128音乐兼容性、平台切换及新包60～120分钟熄屏热／内存；耗电需独立不充电窗口，本次USB充电不计。没有取得本包新听感反馈，历史蓝牙反馈不转记为本包结果。正常生产gate及release入口仍待开放前验证，当前不开放正式开关。
