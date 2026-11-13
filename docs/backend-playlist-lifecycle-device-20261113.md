# QQ／网易云歌单创建及删除实机补验

2026-11-13。用户明确要求趁手机连接补验QQ创建、网易云创建、QQ／网易云删单。此次独立创建每平台一个唯一名称的空测试歌单，只在生产创建入口返回精确ID、完整列表确认本人普通空歌单、真实曲目为空且行权限允许后，删除本轮该精确目标。每平台最多一次创建和一次删除，不触碰任何既有歌单，不加歌、不改红心，不删除或重跑旧QQ创建与酷狗防重marker，不提交推送、不改前端。

## 执行入口和边界

新增仅androidTest的 `PlaylistLifecycleDeviceTest`，必须显式选择单个方法，不运行全类或历史写入套件。主包生产源码保持上一轮酷狗删除后端交付的状态。

- 只读方法 `qqReadOnlyPreflight`／`neteaseReadOnlyPreflight` 核实账号、完整列表、测试名未存在及能力。QQ仅汇报历史创建marker是否含精确ID及同名数量，不按名称关联旧不确定请求，也不改旧记录。
- 写入方法 `qqCreateAndDeleteOnce`／`neteaseCreateAndDeleteOnce` 需要显式 `allowPlatformWrites=true`、`allowNewEmptyPlaylistRoundTrip=true` 和 `allowPlaylistLifecycleProbe=true`。实际调用生产 `app.playlists.createPlaylist/managementInfo/playlistSongIds/deletePlaylist`。
- 先以create-new、fsync保存永久意图再调用创建；仅以返回ID绑定目标，不以同名或前后差集推测身份。创建或核实失败则停止，不重发、不猜目标。删除前保存永久意图，再至多调用一次生产删除；失败也不重发。
- 在测试进程短暂包装既有Transport，原请求原样交给原生产传输，不添加请求，统计实际create／delete发送次数并拒绝第二次。QQ仅导出白名单响应字段路径及类型，便于定位旧创建响应协议问题，不导出值、凭证、URL、ID、名称或私有意图原文。结束恢复原Transport。
- 完整列表有重复、漂移、超界或不能推进即失败。创建后要求列表恰多一个返回ID、其他行指纹一致；删除后要求目标缺席且全部原行ID／名称／数量／权限指纹恢复，另外检查生产返回 `APPLIED/confirmedAbsent=true`。

私有文件为 `playlist-lifecycle-{qq,netease}-once-20261113-v1.json` 和 `playlist-lifecycle-{qq,netease}-report.json`。意图一旦存在不得删除重跑。仅导出report；若中断或结果不明，后续只读核实，不重新创建或删除。

## 实机结果

PJF110 `c201e9f0`、Android16／SDK36，保留数据覆盖安装后，分别单方法执行只读前检及正式后端循环。两个前检均 `READY`、0次写入、无本轮意图；两个循环均 **`PASSED`**。业务证据位于 `.local/artifacts/playlist-lifecycle-device-20261113/device/`。

| 平台 | 本轮唯一空测试名 | 实际请求 | 完整列表变化 | 生产删除结果 | 最终核实 |
| --- | --- | --- | --- | --- | --- |
| QQ | `MusxiQQ验证1113` | 创建1次、删除1次 | 3→4→3 | `APPLIED, confirmedAbsent=true` | 目标缺席，全部原行ID／名称／数量／权限指纹一致 |
| 网易云 | `Musxi网易验证1113` | 创建1次、删除1次 | 2→3→2 | `APPLIED, confirmedAbsent=true` | 目标缺席，全部原行ID／名称／数量／权限指纹一致 |

创建均使用生产返回的精确ID绑定，新鲜完整列表确认普通本人空歌单、实际曲目ID为空、`managementInfo.canDelete=true` 后才删除。没有按同名或前后差集认领，不删除旧歌单；没有加歌、移除歌曲、改红心、改名、排序、退出账号或清数据。其他行只比较元数据指纹，未逐首读取其他歌单全部内容。

QQ创建响应确认白名单结构为 `root.req_0.data.result.{tid,dirId,dirName}` 和 `root.req_0.data.retCode`，新样本通过当前完整身份解析与回读；不导出字段值。**旧 `playlist-created-qq-once.json` 仍缺精确ID，其旧请求仍为UNKNOWN**；读到一个旧同名歌单也不自动建立关联。本轮独立样本已证明当前生产创建和删除可用，不能把本轮成功改写成旧请求已对账成功。

安全报告为 `qq-preflight-report.json`、`netease-preflight-report.json`、`qq-roundtrip-report.json`、`netease-roundtrip-report.json`，对应四份instrumentation记录均 `OK (1 test)`，业务结果另由JSON核实。QQ／网易云本轮永久意图仍存在，旧QQ创建、酷狗创建／删除三份marker的前后SHA-256全部一致；见 `historical-marker-hashes-before.json`、`historical-marker-hashes-after.json`、`final-device-checks.json`。未导出私有意图／目标原文，**不得清marker或重复本轮写入**。

## 构建、安装与保留

实机执行包对应主目录官方 `scripts/verify.ps1` **2,754项全过，0失败／错误／跳过，Lint0错误／139警告**，主包／测试包编译及935条源码冻结通过。证据 `.local/artifacts/verification/20261113T072746605Z-31848/`；本轮总索引 `.local/artifacts/playlist-lifecycle-device-20261113/final-summary.json`。与前轮相同的测试数是本轮完整重跑，四个初始设备方法另行实机执行。

共享构建锁内复核全部源码、HEAD、Git状态及主APK后保存／安装：

- 主APK复用前轮保留的 `.local/artifacts/kugou-playlist-delete-production-20261113/Musxi-kugou-playlist-delete-debug.apk`，SHA-256 `69364d911ddb989720b2b22d88dc7dfca5851a3002f68f00dd9c6c0efa40e362`；生产源码及APK与前轮一致。
- 测试APK `.local/artifacts/playlist-lifecycle-device-20261113/Musxi-playlist-lifecycle-androidTest.apk`，SHA-256 `730c48517f6c189218b596e1af059f2a7624b8efa4077515aa1abf15daa394e8`。

两包 `adb install -r` 成功，安装后手机base.apk散列分别与上述一致；主包首次安装时间仍为10-19，账号及数据保留。包与锁内检查在 `verified-packages.json`，装机记录／安装后检查在 `device/`。

实机通过后只读交叉审查发现测试观察器仅识别目标写接口，未主动拒绝其他操作，且恢复Transport异常可能阻止报告收尾；主代理在测试源码补了明确读取／创建／删除路由白名单与收尾异常记录。**未重跑任何创建／删除**。最终源码再次官方统一验证 **2,754项全过、Lint0错误139警告／935条源码冻结及两包编译通过**，证据 `.local/artifacts/verification/20261113T074248552Z-28112/`。主包散列完全不变；加固测试包 `Musxi-playlist-lifecycle-hardened-androidTest.apk` 的SHA-256为 `af8dc9db24b857ee434ff2342e04269eddac31c8de868a9e8d2ddcc22f453ab6`，锁内复核后仅覆盖安装测试包，主包未再次安装。

最终测试包只执行两个只读方法，均 `PREVIOUSLY_ATTEMPTED/savedState=PASSED/savedTargetPresent=false`、创建与删除请求均0，证明新marker仍阻止再次写入、两个目标仍缺席；对应 `*-final-readonly-report.json` 和instrumentation。安装包与历史marker最后再核对通过，见 `hardened-final-device-checks.json`。实际创建／删除的旧测试源码另外保留为 `PlaylistLifecycleDeviceTest-executed.kt`，散列与实机执行阶段清单一致，见 `executed-source.json`；原执行APK和报告均不覆盖。

观察器不解密网易云请求正文，精确删除ID保障来自本轮生产返回ID、空内容／权限／快照复核和生产适配器，不声称测试Proxy独立验证加密正文。QQ列表API每页内部重取整表，大账号可能触发有界超时；本次小账号样本不扩成大账号性能验收。无其他写入的结论同时基于实际调用路径和原行指纹，旧观察器的 `otherMutationInvocations=0` 本身不构成通用写入拦截证明。

本轮仅新增androidTest入口与验收／状态文档，不改生产源码、CC页面或CC测试，没有提交推送。结束时核对任务开始清单与最终冻结清单，验证后仅回填结果文档并检查链接。后续主要是前端接线与正式页面同包验收；本轮样本是当前账号下的空普通歌单，不扩成跨账号／大歌单／并发／风控全通过。
