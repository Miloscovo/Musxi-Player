# 歌单订阅验证路径接续

2026-11-06，接续 [QQ 改名后端](backend-qq-playlist-rename-20261106.md)。本轮修正网易云整单订阅的默认令牌选择与能力声明，并只读核查酷狗创建返回身份。手机未连接 ADB，未安装或执行设备测试，没有平台写入、创建、删除、退出账号或数据恢复操作；没有提交、推送或发布。

## 网易云当前行为

固定 MIT 来源 `NeteaseCloudMusicApiEnhanced/api-enhanced@2aab9957dfd5231e5b192aecdb177f93ace4c92e` 的 `module/playlist_subscribe.js` 明确设置 `query.checkToken = 'v2'`，`util/request.js` 按版本选择不同令牌提供者。`register_checktoken_v2.js` 使用 Watchman SDK 的 `getToken(businessId)`；`register_checktoken_v3.js` 是匿名 `/v3/b` HTTP 请求。两者不是改 URL 就能替换的版本。

上一轮 Android 默认取 v3 令牌代替 v2，实际订阅已被平台 `risk` 拒绝。本轮移除该默认 v3 HTTP 获取器；未接入兼容 v2 提供者时，`NeteaseCheckToken.freshV2` 立即返回固定 `verification_required`，不会获取令牌或向订阅/取消订阅端点发请求。没有自动重试、切换旧协议或载入远端 SDK。

`capabilities(NETEASE)` 改为 `follow=false, reason=verification_required`。歌曲红心、普通歌单歌曲管理、创建和改名不受此门禁影响；整单订阅状态读取仍可用。明确保留当前订阅状态仍可经新鲜读取返回 `UNCHANGED`；需要实际改变关系时返回验证缺口，不能宣称写入成功。内部注入接口只保留后续兼容 v2 实现和测试的接入点；使用 fixture 的 EAPI 载荷测试不能证明生产默认已具备令牌能力。

本轮核对的既有本地固定源：

| 文件 | SHA-256 |
| --- | --- |
| `module/playlist_subscribe.js` | `1541d0d2ba4062bf51517c9b4a3d9e95682e701688fa2cc2a93f794858f3a089` |
| `module/register_checktoken_v2.js` | `375c690375c7e1da4bc162073331c839ddec4e6463696cd26912b71504b58056` |

文件位于 `.local/artifacts/playlist-followup-20261106/reference/netease/`，只作兼容性研究证据；未将 SDK 或脚本纳入产品。

## 酷狗核查与下一步

固定 `MakcRe/KuGouMusicApi@7ac64e1cdd333ad9bfff644ded82a0ffeef002d1` 的 `module/playlist_add.js` 只把 `POST /cloudlist.service/v5/add_list` 请求交给 transport 并原样返回；文档与通用返回类型未提供可靠的新建 ID 字段路径。已有本人歌单读取中的 `listid` 不能当作创建响应的身份依据。因而继续保持 `create=false/follow=false`，没有通过名称、列表差集或猜测字段关联新歌单，也没有发请求来补样本。

后续需要兼容 v2 的实际令牌提供者，以及酷狗创建响应中可可靠关联 ID 的证据。QQ 改名已编译但仍待手机连接后单独验收；既有 QQ 创建不确定 marker 原样保留，不能通过重建同名歌单对账。

## 验证边界

新增生产默认路径测试覆盖订阅/取消订阅均在 transport 前拒绝、状态读取与不变意图继续工作，以及 v2 提供者失败时不回退/不写入。保留 EAPI 正文令牌与 header/Cookie 令牌分离、账号变化、权限、幂等及风控不重试等测试。旧 v3 获取/JSONP 测试随该实现移除，测试总数可能减少，不把旧数量当作覆盖标准。

完整工程验证仍用 PowerShell 7 `scripts/verify.ps1`，冻结结果以该次 `.local/artifacts/verification/<run>/summary.json` 与源码散列为准；不沿用上一轮 1710 项作为本轮结论。设备测试仅编译，手机缺席不影响本轮门禁修复，但真实 SDK 及歌单能力尚未完成实机闭环。
