# QQ 普通歌单改名后端接续

2026-11-06，接续 [歌单管理追加验收](backend-playlist-device-20261106.md)。本轮只推进 QQ 普通自建歌单改名后端、协议测试和默认关闭的设备专项；前端暂缓。未执行真实改名或其他平台写入，没有提交、推送或发布。既有 QQ 创建不确定记录保留，不重复创建。

> 后续用户允许前后端共同推进并连接手机，QQ 只读详情预检与空/4 首普通歌单改名恢复均已通过；本文件下方的“待验”保留协议实现阶段边界，现状见 [实机追加记录](backend-qq-rename-device-20261106.md)。

## 协议依据与实现

使用既有官方公开脚本快照作兼容性研究依据，不打包或执行脚本：

| 来源（`https://y.qq.com/ryqq/js/`） | SHA-256 | 已确认行为 |
| --- | --- | --- |
| `common.chunk.5b378f933e2203b7da48.js` | `c6c5e7c652a9133779bec192a3fd79515c4ab5462e8d9d6f6d5e8cc75a470f5d` | 详情 `music.srfDissInfo.aiDissInfo/uniform_get_Dissinfo`；编辑 `music.musicasset.PlaylistBaseWrite/EditPlaylist`。 |
| `playlist_edit.chunk.2725441057c73cad7c10.js` | `11e05bc3fdfa356ed509ea8fb6ecd996cafaaa67807043b4219c921204fcd76d` | 详情回填、完整编辑的八字段请求和固定 `mask=95`。 |

详情请求固定为 `{dirid, optype:3, tag:1, userinfo:0, pic_dpi:300}`。编辑固定为 `{dirId, mask:95, dirNewName, dirNewDesc, dirNewPicUrl, dirNewPicUrl2, dirNewtaglist, dirShow}`，标签按官方脚本拼接 id。没有推导 mask 的单个位语义，也没有用省略字段的请求试验平台。

`QqLibraryApi.renamePlaylist` 先读取当前账号完整本人创建列表，精确匹配 `tid_dirId`；拒绝红心、201..209 系统目录、目录 0、非本人目标和同一目录对应不同 tid 的歧义。目录 0 虽可用于既有普通歌曲协议，官方编辑页将其用于创建分支，因此不开放改名。

再读取编辑详情，要求明确回显相同的 tid 与 dirid，并完整提供标题、简介、两张封面、标签和可见性。缺失、null、格式不完整或超过本地边界时拒绝，不以空值填充。只替换标题，其余字段原样回填；支持空的原封面、空标签及私密可见性，保留简介换行。

QQ 改名额外沿用官方编辑页限制：去掉首尾空格后的新标题最多 20 个 UTF-16 code unit（与 JavaScript `String.length` 一致），原简介最多 2000。创建及其他平台原有本地名称边界不变。原名超过改名边界的歌单不会进入设备往返测试，避免无法恢复原名。服务端若自行调整名称、标签顺序或封面，严格回读可能返回 `not_confirmed`；这表示未确认完整目标，并不证明服务器没有写入，需先刷新，不能自动重试。

写入最多一次；写后重新核对本人列表、目标身份、实际标题以及全部非标题字段。即使写响应丢失，也只有新鲜读回确认目标且非标题字段不变时才返回成功；读回失败或未达到目标继续报错，不自动补偿或重试。账号代次检查贯穿网络调用。能力提示为 `rename_unverified`，不表示真实平台已经接受请求。

完整编辑缺少服务器条件写入/版本比较的已知依据，因此不能承诺与其他客户端同时编辑时无冲突：本次提交会回填写前读取的元数据，写后核对也不能排除并发覆盖。实机专项应在没有其他客户端编辑同一歌单时执行。

## 验证与边界

新增 `QqPlaylistRenameTest` 覆盖真实目录传递、八字段形状、非标题字段保留与逐字段回读、同名幂等、缺失/null/重复标签、错误身份、系统/非本人/歧义目录、响应丢失回读、写后权限丢失、账号变化和 transport 对额外字段/其他 mask 的拒绝。fixture 不证明真实平台接受协议。

完整工程检查使用 PowerShell 7 的 `scripts/verify.ps1`，结果与数量以本轮 `.local/artifacts/verification/<run>/summary.json` 为准，摘要同时关联源码散列和实际 APK。此前 1698 项及五项设备专项属于上一轮包，本轮不能复用为新源码验证。

新增 `PlaylistRenameDeviceTest#qqOrdinaryPlaylistRename`，仍要求 `allowPlatformWrites=true` 与 `allowPlaylistRename=true`。必须单独选择方法；写前记录实时原名、完整歌曲顺序与红心基线，恢复仅撤回本轮临时名，其他客户端改为第三个名称时保留冲突。QQ 请求本身还会核对简介、封面、标签和可见性。该专项本轮只编译，未安装或执行；新增详情字段与真实平台行为仍待当前账号实测。

当前缺口仍包括网易云订阅 v2 令牌路径、酷狗创建可靠返回 ID/整单订阅，以及既有创建不确定记录的独立对账。没有以新请求重试网易云风控样本。
