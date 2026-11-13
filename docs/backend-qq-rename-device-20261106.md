# QQ 普通歌单改名实机验收

2026-11-06，用户连接手机并允许后端与前端共同推进后，在 PJF110 / Android 16 开发包上继续验收。接续 [协议实现](backend-qq-playlist-rename-20261106.md)，前端首版任务见 [给 CC 的交接](frontend-playlist-brief-20261106.md)。本轮没有修改正式 UI，没有退出、换号、清数据、创建或删除歌单，没有提交、推送或发布。

## 实际结果

| 专项 | 结果 | 核实边界 |
| --- | --- | --- |
| QQ 编辑详情只读预检 | 通过 | 调用同名改名的幂等分支，代理在 transport 前拒绝任何 EditPlaylist；实际写请求 0。读到数值 id/dirid、非空标题、明确空简介/两张封面、tag 数组和 dir_show，严格完整字段要求可满足当前目标。 |
| 空普通歌单临时改名及恢复 | 通过 | 初始 0 首；临时名称读回确认，恢复原名；完整歌曲顺序与 7 首红心一致。实际改名/恢复各 1 次，无重试，待恢复 0。 |
| 非空普通歌单临时改名及恢复 | 通过 | 初始 4 首；临时名称与原名分别新鲜读回，4 首完整顺序和 7 首红心一致。实际改名/恢复各 1 次，无重试，待恢复 0。 |

每次写入内部还读取并比较原简介、两张封面、标签 id 顺序及可见性；不相等不会返回成功。上述样本的简介/封面为空，不能外推为自定义非空封面、复杂标签或并发编辑样本通过；无条件完整编辑的并发覆盖边界仍保留。能力提示从 `rename_unverified` 改为 `partial_support`，表示当前 QQ 各能力仍有平台/账号与创建不确定边界，不承诺所有账号通过。

## 可复用测试与恢复

新增 `QqRenameDiagnosticDeviceTest`，只编入 androidTest：

- `readAndValidateRenameMetadata`：只读预检。以当前普通目标的原名调用幂等分支，transport 代理遇到 EditPlaylist 立即拒绝，确保无平台写入。
- `observeAuthorizedRenameRoundTrip`：必须指定方法且带 `allowPlatformWrites=true` 和 `allowPlaylistRename=true`；调用既有往返测试，并统计读取结构与写次数。`requireNonEmptyPlaylist=true` 可要求非空普通目标，没有适合目标则跳过，不能算通过。

代理仅导出固定字段的类型、公开 schema 字段名、响应码、布尔和计数；不导出请求、响应体、Cookie、账号、歌单/歌曲 ID 或标题，finally 恢复原 transport。原名和完整内容恢复记录仍保存在手机私有 filesDir，写前保存实时基线，恢复只撤回本轮临时名称。

本轮检查没有任何歌曲/名称/订阅待恢复文件。另有既有 `playlist-created-qq-once.json` 创建不确定 marker，仍无可用返回 ID；只核对其状态，没有改写、重新创建或按名称认领歌单。创建不确定与待恢复改名分开记录。

## 包与工程证据

本地入口 `.local/artifacts/qq-rename-device-20261106/README.md`，原始安全报告和 runner 日志按阶段保留，最终摘要为 `final-validation.json`。

首批主包 SHA-256 为 `ddfcbf97a283498b948131caad8a8277f897659c9c578bddd0992202d972d7e9`，来自前轮 1703 项验证通过的产品源码；新增 diagnostic 后测试包为 `3e79a16a4d8c8bf69bfdc7eb884e1694373357b4e5ffe0f450dda3c19f8e147b`，用于只读与空歌单。新增非空筛选后的测试包为 `95ad0da057c34626cc3a2f331b3db4d7f1d5403e063042a29abbd2d73105cead`，用于 4 首歌单样本。这些是阶段包，不自动代表后续 capability 提示变更及最终源码的冻结包。

最终工程检查仍用 PowerShell 7 `scripts/verify.ps1`，与本轮最终源码散列、测试数量、Lint 及主/测试包编译对应；测试包编译不等于设备执行。最终包的专项复测和恢复数量另存 final-validation.json，不把阶段结果拼成新包通过。

本轮 `20261106T090022668Z-3544` 的 Gradle 任务图通过：1703 项 JVM 测试，失败/错误/跳过 0，Lint 0 错误/118 警告，主/测试包编译完成；统一入口最终为 **failed**，原因为期间 CC 开始并行修改正式 UI，源码身份校验失败。该轮不能视为当前共享工作区完整验证通过，需要前后端编辑收束后重跑。主包为 `5038c0970748b5e6083bb3c486422e0e49d5515afa52ba650278d4257bf19c86`；尝试安装前 ADB 已断开，最终包设备复测未执行，也没有新增平台写入。上述已完成的阶段包恢复结果仍独立有效。
