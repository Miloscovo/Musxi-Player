# 酷狗普通自建歌单删除后端接入

2026-11-13。用户在[唯一目标实机删除成功](backend-kugou-playlist-delete-probe-20261113.md)后，明确要求接入正式后端、补齐权限／异常／完整读回回归，并开放给前端。此次不改正式页面，不再执行真实删单，不安装、不提交推送；手机上的创建与删除防重marker保持。本轮主目录有既有后端和CC前端未提交修改，以任务开始清单和统一验证清单区分本次改动。

## 已接入行为

- `KugouApi` 固定 `POST https://gateway.kugou.com/v2/delete_list`，`x-router=cloudlist.service.kugou.com`，解密正文恰为 `{listid, total_ver:0, type:1}`。六位小写字母／数字AES会话，MD5派生CBC、RSA portrait、lite签名及默认设备／会话query沿用实机成功profile；Base64文本正文以 `text/plain; charset=utf-8` 发送并参与Android签名。与改名的二进制CBC profile分开，不允许外部传type、版本、URL或任意正文，不自动重试。
- `KugouLibraryApi.deletePlaylist` 写前完整读取本人列表，按精确ID检查服务端 `is_edit=1,is_def=0`，要求普通自建；喜欢、系统默认、他人收藏、缺失／未知系统标志均不允许删。非空普通歌单允许整单删除，不先清歌、不依赖改名元数据，不改红心。ID限正整数且不超过JS安全整数上限。
- 完整读取受账号代次、线程中断、60秒、20,000条与重复ID保护。有total时要求稳定且最终数量完全一致，后续total消失拒绝；没有total时沿服务端每页100的页号窗口继续取到显式空数组终页，短页本身不构成结束。未知total的null终页、错误响应或重复页都不能证明目标缺席。
- `PlaylistRepository.capabilities(KUGOU).delete=true`；`managementInfo(key).canDelete` 使用同一行权限，与 `canRename` 独立。已有 `deletePlaylist` 入口、同平台写互斥、账号代次及缓存／顺序／未完成播放工作失效链自然复用。

## 结果与异常

`PlaylistDeletionCoordinator` 先完整前检；适配器在真实写入前再读完整列表重验权限；最多发一次请求；随后完整读回。网络、超时、官方验证、业务拒绝或坏响应不会触发重发，写响应丢失仍用完整列表核实。生产请求不读取设备探针的固定目标／确认码／marker；每次调用依赖用户传入的当前代次目标和新鲜服务端权限，测试marker只负责防止历史实机探针重跑。

| 情况 | `status` | `confirmedAbsent` | 行为 |
| --- | --- | --- | --- |
| 前检已确认不存在 | `UNCHANGED` | `true` | 不发送写请求 |
| 写后完整回读缺席，包含写响应丢失 | `APPLIED` | `true` | 失效相关列表／详情，移除本地顺序，通知未完成播放工作 |
| 写后完整列表仍在 | `FAILED` | `false` | 返回写错误类别或 `not_confirmed` |
| 写后回读失败、不完整或账号变化 | `UNKNOWN` | `null` | 提示刷新核对，不能断言尚未删除或自动重发 |
| 前检失败 | `FAILED` | `null` | 不写，返回固定错误类别 |
| 入口能力／受保护目标／旧代次／同平台忙 | 外层 `Result.failure` | — | 不写 |

平台异常只对前端提供固定类别；不传原始响应、凭证、URL或异常原因链。已接受的播放队列继续保留，不暂停、不重建。正式后端核实目标精确缺席，不要求其他客户端的其他歌单完全静止；实机探针额外比较其余行指纹仅用于那次受控验收。

## 前端接线

继续使用 `app.playlists`，调用方式不变：

1. 全局检查 `capabilities(platform).delete`，菜单打开时读取 `managementInfo(key)`，只在 `canDelete=true` 时提供删除。不能用“能改名”代替删除权限。
2. 使用返回的 `target: EditablePlaylist` 调用 `deletePlaylist(target, callback)`；观察 `busyPlatforms` 禁用同平台冲突操作，用户取消页面等待不代表撤销已发出请求。
3. 检查返回值内的 `status/confirmedAbsent/error`。仅确认缺席可移除展示项；`UNKNOWN` 提示刷新核对，不自动再删。不要只检查外层 `Result.isSuccess`。

分页／revision／排序沿用[曲库管理合同](backend-playlist-library-management-20261113.md)，不要混用旧browser累积列表。前端页面及生产入口的同包实机验收留后续安排；本轮只开放后端能力。

## 来源、回归与验证

请求形状改编自MakcRe/KuGouMusicApi固定MIT提交 `7ac64e1cdd333ad9bfff644ded82a0ffeef002d1` 的 `module/playlist_del.js`、`util/crypto.js`、`util/request.js`、`util/helper.js`，复用现有密码实现和传输，没有新增运行时依赖或业务服务器。既有完整MIT许可、模块NOTICE与[第三方声明](../THIRD_PARTY_NOTICES.md)已保留／更新。

平台单测覆盖解密后的精确单ID／type／total_ver、独立签名计算、正文类型、HTTPS精确白名单、空／非空目标、普通行权限／缺标志、无改名元数据、非法ID、账号变化、拒绝／风险／坏响应、未知total终页、跨页目标、total消失、null终页、重复／漂移／超界及不重发。删除适配器24项、密码9项、协调器既有15项及新增真实适配器＋协调器集成5项全部通过。本轮净增18项：平台13项、accounts集成5项；原禁用删除测试已替换为固定已验证profile测试。

主目录官方 `scripts/verify.ps1` 最终 **2,754项通过，0失败／错误／跳过，Lint0错误／139警告**，debug主包与androidTest包编译、932条源码冻结通过。证据 `.local/artifacts/verification/20261113T070820262Z-27544/`，本轮索引 `.local/artifacts/kugou-playlist-delete-production-20261113/final-summary.json`。各模块为accounts66、app1477、core663、酷狗186、网易云81、QQ121、playback160。

共享构建锁内复核全部源码／HEAD／Git状态和主APK后保留 `.local/artifacts/kugou-playlist-delete-production-20261113/Musxi-kugou-playlist-delete-debug.apk`，SHA-256 `69364d911ddb989720b2b22d88dc7dfca5851a3002f68f00dd9c6c0efa40e362`。独立请求profile只读审查无阻塞；主代理审查并修正集成测试的异常映射和写后阶段模拟，再执行上述统一验证。验证完成后仅回填结果文档，复核非文档源码保持一致。

首轮 `20261113T070644890Z-17208` 因旧改名测试以账号guard调用次数模拟切号而失败：新增读后检查令其在写前停止，该断言原本要覆盖写后切号。改为按实际写请求出现后切号，不削弱生产检查；随后完整重跑通过。失败尝试保留，不将它的部分测试／编译结果计为成功验证。

没有改CC页面或测试，没有安装或再次真实删单，没有提交推送。此前一次真实删除只验证当次探针profile，本轮生产入口及正式菜单仍待后续同包实机验收；不为此自动消耗其他真实歌单。

同日用户随后授权[QQ／网易云创建和删除补验](backend-playlist-lifecycle-device-20261113.md)，已保留数据安装本页同一主APK并完成两个平台正式后端闭环；酷狗生产入口没有重复真实删单。本页前述不安装／不写入属于本页生产接入阶段，不覆盖后续授权与独立实机记录。
