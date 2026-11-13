# 酷狗本人歌单单次删除实机验证

2026-11-13。用户同意设备窗口，唯一允许删除目标为 `Musxi创建验证`，即使非空也允许整单删除。PJF110实机核实该歌单含 **1首歌**；14:45按固定候选只发送 **1次**请求，安全业务报告为 **`OBSERVED_DELETED`**。完整新鲜列表从4个变为3个，精确目标缺席，其余行ID／名称／歌曲数／权限指纹一致。没有先清歌、加歌、创建歌单或发送其他修改请求。生产 `capabilities(KUGOU).delete=false` 及 `deletePlaylist` 写前 `unsupported` 保持，本轮交付是删除协议的实样验证。

## 复核结果

固定来源 [MakcRe/KuGouMusicApi 7ac64e1](https://github.com/MakcRe/KuGouMusicApi/blob/7ac64e1cdd333ad9bfff644ded82a0ffeef002d1/module/playlist_del.js) 的候选请求为 `POST https://gateway.kugou.com/v2/delete_list`，`x-router=cloudlist.service.kugou.com`，正文解密后固定 `{listid, total_ver:0, type:1}`。注释仍为“取消收藏”。保留的 C# 移植标为“取消收藏／删除”；Rust移植一处声称删除类 `1=自建、0=收藏`，其handler本身仍写取消收藏。这些是同源移植和相互矛盾的注释，缺明确本人删除调用样本，不能据此启用生产。

请求必须复原旧 `playlist_del` profile：`playlistAesEncrypt().str` 为 **Base64文本**，随机六位小写字母／数字密钥，MD5派生AES-CBC和RSA portrait。概念版 `appid=3116/clientver=11440`、lite salt／RSA key，query带 `key/p/last_area/last_time` 及现有默认账号／设备参数，Android签名覆盖Base64正文；与 `/v1/modify_list` 原始CBC字节不同。不得试错切换type、追加其他端点或自动重试。

原固定 `util/request.js` 表明字符串正文原样参与Android签名，并将会话Cookie的userid/token并入默认参数；固定 `util/helper.js` 的 `signParamsKey` 使用平台对应盐值。新网络获取 `raw.githubusercontent.com` 被WinError10054断开，沿用项目已保留固定参考，不伪称本轮重新下载成功。参考路径／SHA-256索引 `.local/artifacts/kugou-playlist-delete-20261113/reference/index.json`；没有复制其他项目代码或引入依赖。

## 已准备的探针

[KugouPlaylistDeletionProbeDeviceTest](../app/src/androidTest/kotlin/tech/musxi/player/KugouPlaylistDeletionProbeDeviceTest.kt) 只在androidTest包，不在debug主包／release。**必须显式选单个方法，禁止运行整个类或整个历史测试套件。**

1. `prepareOwnedPlaylistDeletion`：仅只读，完整新鲜本人列表按用户明确指定名称找唯一候选，核实非系统／非我喜欢、本人普通可写，列表count与真实track IDs一致。账号列表没有显式total时按服务端每页100的窗口继续取到终页；有total时要求稳定且最终数量一致，始终拒绝重复ID或不能前进的续页。默认仍要求空；此次用户对非空目标明确授权后带 `allowNonEmptyKugouDelete=true`，允许保留原歌曲执行整单删除，歌曲数量及顺序指纹保存到私有准备文件。精确ID及账号owner只保存应用私有目标文件，不导出。报告导出随机确认码，供下一步绑定本次目标；同名多个、旧准备文件目标改变、存在旧删除意图均拒绝。
2. `deletePreparedPlaylistOnce`：必须同时有用户本轮对这一歌单的明确许可、`allowPlatformWrites=true`、`allowKugouPlaylistDeleteProbe=true`、相同指定名称和准备报告确认码。准备有效期30分钟；写前再次完整核对账号、精确ID、名称、普通权限及歌曲快照／数量未变；非空还要求准备与执行两阶段都显式允许，永久create-new意图文件并fsync后才发送一次候选请求。失败也不换参数重发。
3. 响应丢失仍完整读取 `/v7/get_all_list` 核对，报告精确目标缺席、总数减一及其余行ID／名称／歌曲数／权限指纹不变等布尔结果；不是只看HTTP或runner成功。响应只导出固定白名单字段结构类型／成功布尔，不导出原始响应、ID、名称、凭证或请求URL。

私有文件：`files/kugou-delete-probe-target-v1.json`、`files/kugou-delete-probe-once-v1.json`、`files/kugou-delete-probe-report.json`。**意图文件一旦存在，不得删除来重跑**；即使网络尚未送出、返回拒绝或结果不明也保留。此前创建探针 `kugou-create-probe-once-v1.json` 完全不动。旧方法名 `prepareEmptyOwnedPlaylistDeletion`／`deletePreparedEmptyPlaylistOnce` 保留兼容，但执行时只选择新的一对方法，绝不运行全类。

业务报告 `READY` 仅代表只读选定目标；`OBSERVED_DELETED` 要求有一次发送意图、目标缺席、总数减一且其余行指纹一致。`NOT_DELETED` 表示完整读回仍在；`UNKNOWN_AFTER_INTENT` 或 `ABSENT_WITH_OTHER_CHANGES` 不能声称本候选已经可靠成功。并发其他客户端和发送前极短窗口内加歌无法原子锁住，验收时用户应停止该测试歌单的其他编辑；不因HTTP成功或任一缺失页面直接开放能力。

## 本轮实机授权

- 用户同意进入设备窗口，连接并解锁此前PJF110，允许覆盖安装当前已验证debug主包及独立测试包，保留数据和账号。
- Musxi调试包中酷狗账号已连接；若需验证码／官方确认由使用者完成，不要求发送账号密码或token。
- 用户已指定可永久删除的本人普通歌单 `Musxi创建验证`，即使非空也同意。该许可只作用这个名称唯一且精确ID已核实的目标，不扩展到其他歌单；无需先清歌或再建测试单。
- 用户已明确允许只向该精确目标发送一次候选删除请求；不加歌、不删除其他歌单、不改红心、不测试type=0等变体。

本轮授权已保存到 `.local/artifacts/kugou-playlist-delete-20261113/device/authorization.json`。已保留数据覆盖安装主包／测试包，保存当轮散列、准备及删除报告。成功只证明当前账号、这个普通非空歌单及本次固定请求；生产接入还须另补请求边界、完整确认及回归，不把设备测试直连代码搬进主包。

## 实机结果与保留证据

安全报告与runner记录位于 `.local/artifacts/kugou-playlist-delete-20261113/device/`：

| 阶段 | 业务结果 | 证据 |
| --- | --- | --- |
| 首次只读准备 | `PREWRITE_FAILED/incomplete_listing`，0次写入、无删除意图。真实列表省略total，探针额外强制该字段导致提前停止 | `prepare-total-required-report.json`、`prepare-total-required-instrumentation.txt` |
| 分页修正后准备 | `READY`；4个歌单、1个同名、本人普通可写、实际1首歌，非空授权已绑定 | `prepare-report.json`、`prepare-instrumentation.txt` |
| 唯一一次删除 | `OBSERVED_DELETED`；`writeRequests=1`，`trackSnapshotUnchanged=true`，响应成功且无非零错误码 | `delete-report.json`、`delete-instrumentation.txt` |
| 写后完整核实 | 4→3；`confirmedAbsent=true`、`otherRowsUnchanged=true`、`exactlyOneListRemoved=true` | 同上删除报告 |
| 防重文件 | 删除意图、此前创建意图及私有准备目标均仍存在；未导出意图／目标原文 | `marker-retention.json` |

报告中的其余行指纹核对覆盖ID、名称、歌曲数量及权限；未逐首读取其他歌单。两次有效设备方法各自runner为 `OK (1 test)`，业务结论以上述JSON为准。没有播放、操作其他应用、退出账号、清数据或重建目标。删除意图已永久保留，**本次唯一写入额度已用完，不得删除marker重跑或改type试验**。

## 当前源码验证

最终分页修正版的主目录官方 `scripts/verify.ps1` **2,736项通过，0失败／错误／跳过，Lint0错误／139警告**，主包／设备测试包编译与930条源码冻结检查通过。证据 `.local/artifacts/verification/20261113T063746326Z-30128/`，总索引 `.local/artifacts/kugou-playlist-delete-20261113/final-summary.json`。之后在共享构建锁内再次复核全部源码、HEAD、Git状态和主APK，才保留并覆盖安装最终测试包。

最终测试包为 `.local/artifacts/kugou-playlist-delete-20261113/Musxi-kugou-delete-paging-androidTest.apk`，SHA-256 `e73190b0aeaddbc792506ccfe0a1c51f7989ea43f0e342dd2d75d4a414673ecf`；锁内记录 `device/verified-paging-packages.json`。主APK SHA-256仍为 `99d3fbf67a846a5b4c3c7ae18647017c90ac3f0f50142697ec77421a24d245e5`，保留于 `.local/artifacts/playlist-library-management-20261113/Musxi-playlist-management-debug.apk`。首次安装时间保留为10-19，主包及测试包安装记录在本轮 `device/`。删除探针只存在于测试APK。

早期空目标准备包 `Musxi-kugou-delete-probe-androidTest.apk`（`ef4fb7db…`）及允许非空但未修正分页的 `Musxi-kugou-delete-authorized-androidTest.apk`（`a18a71a0…`）仅保留为阶段证据，不再用于执行。对应验证为 `20261113T061536135Z-32384`、`20261113T063051435Z-23008`；原准备索引保留为 `preparation-summary.json`。这些早期包不能冒充最终实机包。

本轮业务改动仅在androidTest探针，另有第三方声明及交付／当前入口的文档更新；没有改生产源码或CC文件，不提交推送。最终源码验证后仅回填结果文档，再检查文档链接与非文档源码一致性。

用户随后明确授权把已验证的Base64文本profile接入生产；该后续实现、回归和能力开放见[生产删除接入](backend-kugou-playlist-delete-20261113.md)。本页保留当次实机包与生产尚未启用时的证据；正式页面验收另行安排，本次实样不代表酷狗整单订阅／取消订阅可用，也不授权另删其他歌单。
