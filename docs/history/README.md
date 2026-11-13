# 历史与日期记录索引

记录只证明其日期、源码、包和场景。继续开发先回到[当前接续](../next-session-handoff.md)和[能力状态](../feature-status.md)，接口看[现行合同导航](../README.md)。下列文件保留原路径，不根据旧待办自动操作账号或队列。

## 2026-11-13 Android v0.3

- [v0.3发布说明](../android-v0.3-release.md)：汇总v0.2之后的前后端进展，版本0.3.0／code6，README参考LoveACE结构并采用自有素材。重新统一2,784项全过、Lint0错误149警告；正式包沿用v0.2签名，Release Lint0错误55警告。包与发布证据位于项目 `.local/artifacts/release-0.3.0/`、`release-v0.3-preparation/`。没有重新安装手机、真实平台写入或将旧设备结果冒充新正式包验收。
- 2026-11-13 23:02正式发布完成，上游[PR #30](https://github.com/Miloscovo/Musxi-Player/pull/30)已创建，三附件回下载散列匹配。首轮云端分别暴露自动换源真实延时顺序、本地音频测试准备／收尾问题；测试稳定性补丁重新本地统一2,784项全过，Lint0错误150警告（新增测试依赖版本提示），证据 `.local/artifacts/verification/20261113T151805094Z-24544/`。不改发布包、标签或生产行为，首轮失败与后续CI分别留存。

## 2026-11-13 锁屏歌词 C 后端

- [锁屏歌词 C 后端交付](../backend-lockscreen-lyrics-c-20261113.md)：用户明确只做后端，三态互斥与旧设置迁移、共享内容／已应用壁纸、触发状态和宿主生命周期、debug最小探针；统一2,784项通过。PJF110仅悬浮窗允许时BAL_BLOCK，用户再允许ColorOS“后台弹出界面”后同包实际显示并获得焦点，用户确认看到；触摸退出先回仍锁定的系统锁屏。已恢复CARD／armed=false，进程重启后计数清零另有事件说明；正式前端与四方向逐项验收未做。

## 2026-11-13 CC任务2／9验收与酷狗创建协议接续

- [QQ／网易云歌单创建及删除实机补验](../backend-playlist-lifecycle-device-20261113.md)：正式后端新空歌单各1次创建／删除均 `PASSED`，精确返回ID／空内容核实，QQ3→4→3、网易云2→3→2、全部原行指纹一致。旧QQ创建不确定marker及酷狗marker散列不变，本轮意图保留；统一2,754项、Lint0错误139警告／935条源码冻结与包编译通过，同主包保留数据装机。

- [酷狗普通自建歌单删除后端接入](../backend-kugou-playlist-delete-20261113.md)：单次实样成功后按用户授权接入Base64文本profile，补普通行权限、异常及完整读回回归，开放生产 `delete=true/canDelete`。最终统一2,754项全过、Lint0错误139警告／932条源码冻结与主包／测试包编译通过，净增18项；本轮不改前端、不安装、不再次真实删单。

- [酷狗本人歌单单次删除实机验证](../backend-kugou-playlist-delete-probe-20261113.md)：用户授权的唯一目标 `Musxi创建验证` 实为1首歌，固定AES Base64文本profile只发送1次请求，报告 `OBSERVED_DELETED`；完整列表4→3、精确目标缺席、其余行指纹一致，创建及删除marker保留。最终统一2,736项通过、Lint0错误139警告／930条源码一致性通过，生产删除仍关闭，待生产接入与回归。

- [曲库歌单管理](../backend-playlist-library-management-20261113.md)：接续用户“排序只在Musxi保存，暂不连手机”的安排；统一目录分页与账号排序、行权限、QQ／网易云删单及写后完整核实，酷狗删单因协议证据不足保持关闭。最终主目录2,736项通过、Lint0错误139警告，包编译与928条源码一致性通过；专项75项。没有连接手机或真实删单，不复用创建轮实机结论。

- 用户已验收歌词转场与长按爱心“收藏到”前端；[酷狗新建歌单接续](../backend-kugou-playlist-create-20261113.md)记录一次授权空创建成功、精确响应ID核实与生产创建接入；统一验证2,661项全过，新包已装机；最终“新建并加入”留用户实测，整单订阅仍关闭。未把前端验收扩大成三平台创建通过。

## 2026-11-12 壁纸取景、手动封面裁切与三平台推荐后端

- [壁纸强度放宽到150](../backend-requests-20261112-5.md#交付说明)：两份共用0～150上限、默认35；构造/copy不抛、命令校验返回INVALID_ARGUMENT且不写盘不钳制，v1/v2布局保留，旧版降级限制已写明。专项56项、主目录统一2,622项通过，Lint0错误/135警告/主包及测试包编译/源码一致性通过；不改CC滑条、不安装、不提交推送。

- [推荐总开关](../backend-requests-20261112-4.md#交付说明)：默认开启、旧数据兼容，关闭同步失效三平台工作/内存并串行清推荐元数据，点播报`recommendations_disabled`，校验/提交与关闭共用同步边界；保留共享封面、已接受队列和30秒节流。专项84项、主目录统一2,604项通过，Lint0错误/135警告/主包及测试包编译/源码一致性通过；不改CC页面、不安装、不提交推送。

- [独立界面壁纸](../backend-requests-20261112-3.md#交付说明)：保留播放页入口，新增interfaceWallpaper，两份独立清单/草稿、共用图片池与串行队列、chooseSaved双向复用；专项57项、主目录统一2,575项、Lint0错误/135警告、主包/测试包编译及源码一致性检查通过，不改CC页面或安装手机。

- [壁纸取景位置](../backend-requests-20261112-2.md#交付说明)：保留整图，只存中心/zoom；草稿与强度共用应用/取消、已有图调整、换图重置、v1清单兼容及非法参数防御。专项41项通过；统一验证受CC `MainShell.kt:1102` 缩进Lint错误阻挡，未代改UI、不安装手机。

- [手动封面裁切与选图EXIF](../backend-requests-20261112.md#交付说明)：摆正后的归一化区域、区域采样解码、一次开流及暂存清理；定向70项、主目录统一2,510项、Lint0错误/132警告及源码冻结通过。接口和本轮验证范围见交付，不改CC页面或安装手机。

- [歌曲推荐后端交付](../backend-recommendations-20261112.md)：网易云/酷狗每日歌曲与QQ猜你喜欢，独立账号缓存、刷新/取消和revision点播；原样隔离快照统一2,484项与源码冻结通过，此前并行修改导致的失败尝试独立保留。协议依据及真实账号待验范围见该次交付。[现行接口](../frontend-backend-contract-recommendations.md)。

## 2026-11-11 后端需求与手机重连补验

- [本地条目倍速、封面设置/手动图和书签改名](../backend-requests-20261111-5.md#交付说明)：schema v4、逐条速度/pitch1/平台1倍、OFF保留自动图不显示/手动优先、显式补图及媒体信息更新；本轮验证见交付，不复用前轮或设备结论。

- [本地视频声音与自动封面](../backend-requests-20261111-4.md#交付说明)：第1、2项后端、schema v3迁移、共享封面/取消回收、旧条目显式补图及媒体封面；第3项仅留观看扩展改点。验证边界与证据见交付，本轮没有手机安装/执行。

- [歌单内搜索的缓存补全](../backend-requests-20261111-3.md#交付说明)：三平台可取消逐页补齐、进度、手动翻页共用限速与退避、5000 首/磁盘容量保护；第 2 项只核对队列连续前缀约束，没有修改播放逻辑或安装手机。

- [媒体卡片标题歌词方案 B](../backend-media-card-lyrics-20261111.md)：用户在探针后选择独立实现第 4 项；默认关闭，平台标题当前原句、副标题原歌名与歌手，按服务播放位置调度，原生方案 A 继续暂缓；本包尚未安装手机，不混用原探针设备结果。

- [ColorOS 锁屏歌词 debug 探针与设备记录](../backend-lockscreen-lyrics-probe-20261111.md)：A1／A2 平台写入读回成功，用户锁屏均未见歌词；官方酷狗显示于自有整页锁屏界面，原生歌词页阳性对照未完成。探针已关闭、日志已保存，安装窗口交给 CC；[需求单](../backend-requests-20261111-2.md)第 2 项原生实现暂缓，后续选择的方案 B 见上项。
- [主题色持久化、逐字播放取样与本地音频补缺交付](../backend-requests-20261111.md#交付说明codex-填写)：优先接口与两项本地修正；含其余五项排期、整理／进度版本号不能只拆递增的理由，以及定向／统一验证的独立证据入口。
- [OPPO 取消、通知、SQLite、壁纸及熄屏观察](../backend-local-device-20261111.md)：保留 CC 前端与手机主包，只更新测试包；修正 OEM 逐级返回和跨编码构建锁。熄屏采样停更、长熄屏定时未通过，测试数据已核对清理。

## 2026-11-10 本地音频与后端设备补验

- [本地音频、三种定时、壁纸与系统控制实机补验](../backend-local-device-20261110.md)：单台 Android 16，真实 SAF、解码、暂停恢复、来源与权限恢复；含失败尝试、提供方限制及收尾。
- [扩展格式、OPPO 文件选择器、通知与普通进程熄屏追加验收](../backend-local-followup-device-20261110.md)：用户重连手机后独立留证；裸 AAC 准确 seek、选择器取消及连续熄屏按分项实际结果记账。

## 2026-11-09 本地检索与批量整理

- [检索、筛选分页、批量加入／移出／移动与文件夹规则排序](../backend-local-organization-delivery-20261109.md)。接续昨晚中断代码，统一验证与恢复前快照分别留证。
- [主动检查来源与播放探测复用](../backend-local-source-check-delivery-20261109.md)：轻量读取、状态恢复、取消与来源变化守卫。

## 2026-11-08 本地能力

- [本地数据与导入](../backend-local-audio-delivery-20261108.md)、[同一播放服务](../backend-local-playback-delivery-20261108.md)、[本地队列暂停恢复](../backend-local-resume-delivery-20261108.md)。
- [目录持久关联与手动刷新](../backend-local-directory-delivery-20261108.md)，含本轮工程文档整理和独立验证索引。
- [本地音频导出与整理备份／恢复](../backend-local-transfer-delivery-20261108.md)。
- [定时停止](../backend-sleep-timer-delivery-20261108.md)、[静态壁纸](../backend-wallpaper-delivery-20261108.md)。

## 2026-11-07 字词歌词

- [酷狗 KRC 及手机补验](../lyrics-word-timing-20261107.md)、[QQ QRC](../lyrics-qq-qrc-20261107.md)、[网易云 YRC](../lyrics-netease-yrc-20261107.md)。固定源码、缓存兼容及各自设备边界均在对应记录。

## 2026-11-06 歌单

- [首轮交付](../backend-playlist-delivery-20261106.md)、[后续接入](../backend-playlist-followup-20261106.md)、[追加实机与修复](../backend-playlist-device-20261106.md)。后者覆盖前两轮的部分待验描述，不改变前轮原始证据。
- [QQ 改名实现](../backend-qq-playlist-rename-20261106.md)、[QQ 改名实机](../backend-qq-rename-device-20261106.md)。
- [网易云验证路径与酷狗创建 ID 核查](../backend-playlist-verification-20261106.md)。

## 2026-11-03 集中验收与工作流

- [v0.2 后端集中实机](../backend-v02-device-acceptance-20261103.md)：当次包与单台 Android 16 的账号/播放等样本，不代表之后的本地音频或新包已验。
- [Actions 远端首跑](../actions-first-run-20261103.md)：当次失败、修复、上传下载和报告核对。

## 早期交付与验收

以下记录只对应文中包、提交、日期和场景。原文哈希、测试数、警告数、队列和账号状态均是当次证据；不能据此声称当前代码或正式包已复验。

- [安卓 v0.1 集成与验收](../android-0.1-acceptance.md)
- [播放器基础使用验收](../playback-acceptance.md)
- [P0 后端真机验收（2026-10-20）](../p0-device-acceptance.md)
- [P1 队列恢复、最近播放与歌词验收（2026-10-20）](../p1-data-acceptance.md)
- [P1 随机/循环验收（2026-10-20）](../p1-playback-modes-acceptance.md)
- [P2 收藏同步与队列编辑验收（2026-10-20）](../p2-acceptance.md)
- [后端增量 PR 记录（2026-10-21）](../backend-pr-20261021.md)
- [后端 B1/B2/B3 可靠性记录（2026-10-21）](../backend-reliability-20261021.md)
- [后端现状（更新至 2026-10-23）](../backend-status-20261022.md)：带日期的状态快照。
- [AUDIO 调度隔离与晚间第二阶段补验](../backend-audio-lane-acceptance-20261022.md)
- [B1-08 前台资格门禁与设备回归](../backend-b108-acceptance-20261022.md)
- [B1-08 后台换源补验（2026-10-23）](../backend-b108-alpha01-acceptance-20261023.md)
- [B4 保守匹配与系统专项](../backend-b4-system-acceptance-20261022.md)
- [2026-10-22 晚间后端补验与分阶段译文](../backend-evening-acceptance-20261022.md)
- [歌词翻译第二阶段与设备补验（2026-10-22）](../lyrics-translation-acceptance-20261022.md)
- [酷狗 KRC 翻译接入与设备验收（2026-10-22）](../lyrics-translation-kugou-acceptance-20261022.md)
- [QQ 歌词翻译接入与设备验收（2026-10-22）](../lyrics-translation-qq-acceptance-20261022.md)
- [2026-10-27 晚间真机验收](../backend-acceptance-20261027.md)
- [2026-10-27 队列补全、音质、输出](../backend-delivery-20261027.md)
- [2026-10-27 换源提速与真机验收](../source-switch-speed-20261027.md)
- [QQ 网页登录验证（2026-10-28）](../qq-web-login-validation-20261028.md)
- [酷狗网页登录验证（2026-10-28）](../kugou-web-login-validation-20261028.md)
- [2026-10-31 蓝牙输出补充验收](../output-acceptance-20261031.md)
- [2026-10-31 后端追加实机检查](../backend-followup-20261031.md)
- [2026-11-01 后端交付与验证](../backend-delivery-20261101.md)
- [2026-11-01 后端真机验收](../backend-live-acceptance-20261101.md)
- [第二批后端交付（2026-11-01）](../backend-batch2-delivery-20261101.md)
- [第三批：发布签名与应用内更新交付](../backend-batch3-delivery-20261101.md)
- [第三批真机追加验收](../backend-batch3-device-acceptance-20261101.md)
- [第四批后端交付与验收边界](../backend-batch4-delivery-20261101.md)
- [第四批会员与真机追加验收](../backend-batch4-device-acceptance-20261101.md)
- [第五批后端交付（2026-11-01）](../backend-batch5-delivery-20261101.md)
- [缓存维护追加交付（2026-11-01）](../backend-cache-delivery-20261101.md)

## 历史需求与初始规划

- [安卓端适配规划](../../安卓端适配规划.md)：根目录保留的 2026-10-23 旧规划，不作为当前排期。

这些文件记录当时提出的需求和方案。保留原范围以核对设计和实现，不再直接驱动当前排期；项目完成度必须查对应交付、验收和[当前开发接续](../next-session-handoff.md)。

- [后端需求单 2026-10-27](../backend-requests-20261027.md)
- [后端需求单 2026-11-01](../backend-requests-20261101.md)
- [后端需求单第二批 2026-11-01](../backend-requests-20261101-2.md)
- [后端需求单第三批：正式签名与应用内更新](../backend-requests-20261101-3.md)
- [后端需求单第四批：换源与交互配套](../backend-requests-20261101-4.md)
- [后端需求单第五批：v0.2 收尾](../backend-requests-20261101-5.md)
- [v0.1 开发基础](../v0.1-foundation.md)：v0.1 增量与当时缺口。
- [歌词翻译后端路线与当前进度](../lyrics-translation-plan.md)：2026-10-22 路线与阶段进度。
- [QQ 桌面与安卓播放能力对照](../qq-playback-parity.md)：2026-10-19 调查和后续实验设计。
- [跨平台选择音源：首版实现与验收](../cross-platform-sources.md)：首版行为、B4 更新和当时设备证据。
- [B1/B2/B3/B4 待补实测登记](../backend-device-followup.md)：2026-10-22 的专项登记。

## 入口原文快照

- [本次整理前项目 README](project-readme-through-20261108.md)、[本次整理前开发接续](next-session-handoff-through-20261108.md)：保留追加式时间线，当前入口已精简，历史相对链接已重定位。
- [截至 2026-11-01 的开发接续](next-session-handoff-through-20261101.md)。
- [截至 2026-10-28 的接口总览](frontend-contract-through-20261028.md)。
- [截至 2026-10-23 的前后端交接](development-handoff-through-20261023.md)；[旧交接入口](../development-handoff.md)继续指向当前文档。
