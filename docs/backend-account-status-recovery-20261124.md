# 账号后端首批：状态、恢复与验证事实

2026-11-24。用户已同意[账号讨论](backend-account-planning-20261124.md)的第一批，前端继续暂缓。改动位于 `accounts`，沿用现有账号状态、持久会话与 generation 边界，不新增业务服务器或依赖。

## 明确过期

三平台 `with*Session` 和 `refreshProfile` 仅在平台异常明确分类为 `expired` 时淘汰匹配的已连接 generation，进入 `REAUTH_REQUIRED / EXPIRED`。资料、本地保存会话和展示缓存保留；后续业务不再可用，必须重新验证或重认证。网络、风控、频控、权限、版权和协议失败不据此退出账号。

资料刷新成功仍保持同一账号及 generation，更新平台返回的会话和资料。失败或成功结果交付到主线程时重新检查状态，退出或新账号代次出现后不再交付旧成功。登录／启动验证仍沿用既有错误映射，其中 `credentials` 属于验证失败；不将业务单曲错误泛化为账号失效。

## 暂时网络失败的恢复

仅有本地身份、处于 `RETRYABLE_ERROR / NETWORK` 的账号符合资格。返回前台、默认网络恢复为 `NET_CAPABILITY_VALIDATED`，或前台中的首次／手动恢复验证刚发生网络失败时，可以安排一次恢复；距该平台上次验证开始至少 60 秒。未到冷却边界只安排一个延迟任务，不建立周期任务。

自动任务执行和进入 HTTP 前都检查前台及当前默认网络，代次变化、退后台或退出会取消／拒绝旧任务。网络 callback 仅作为事件提示，实际资格读取当前网络，避免使用迟到的旧网络信息。已开始的 HTTP 不强行中断，结果仍受 generation 校验。

同一失败 generation 只能被自动认领一次。自动验证失败不会自行重新安排；新的前台／网络恢复事件可以处理这次失败的新 generation，仍受冷却限制。明确过期、风控或其他平台错误不自动重试；没有本地会话不会自动登录。显式 `restore(platform)` 保留已有资料显示，并保留原手动重试能力。

首批交付时账号 I/O 仍共用既有单线程执行队列；后续已由[第二批](backend-account-execution-20261124.md)补充平台隔离、重复校验合并和等待时限，首批恢复资格与冷却语义保留。

## 后续前端读取

保留 `accounts.states` 作为账号授权状态的唯一事实来源。新增只读 `accounts.verification: StateFlow<Map<Platform, AccountVerification>>`，每个平台提供：

| 字段 | 语义 |
| --- | --- |
| `lastVerifiedAtEpochMs` | 本进程最近一次成功验证的时间；未知为 null |
| `lastFailure` | 最近失败的固定 `AccountError` 类别；成功后清除 |
| `lastFailureAtEpochMs` | 对应失败时间；成功后清除 |

仅记录内存事实，进程重启归零，退出或本地无会话时清除。不含账号 ID、Cookie、Token、URL 或平台原始错误文本；时间不充当请求权限或冷却时钟。失败保留上次成功时间，迟到结果不能覆盖新代次。是否需要重新登录应依据 `states.phase == REAUTH_REQUIRED`，不以成功时间久远自行推断。

账号页以后可直接展示状态、验证时间和重新验证操作；账号管理、新建歌单、本地删除三处应用浮卡统一视觉及锁屏歌词 C 仍为前端待办。本批没有修改前端文件。

## 验证与边界

新增 19 项定向用例（恢复门禁 7 项、真实 Repository 配合私有假 Driver 12 项），覆盖三平台资料过期、非认证错误、成功清错、冷却边界、自动失败不循环、未验证网络与重复回调、迟到网络事件、退后台、无保存身份及退出后的迟到结果。测试不访问真实平台，不制造真实账号过期。

定向记录：`.local/artifacts/account-status-recovery-20261124/targeted.log`。交付前完整入口为 `pwsh -NoProfile -File scripts/verify.ps1`，新一轮源码散列、JUnit、Lint 和 APK 散列由 `.local/artifacts/verification/` 下对应时间目录记录，具体结果见最终交付消息及摘要；不引用前一轮 XEAPI 的 3,061 项作为本批结果。

本批未安装手机、未对真实账号执行退出／换号／过期或平台写入。真实断网恢复及三平台完整登录仍需同包实机验证；之前 XEAPI 播放包的设备结果保持其原有边界。本批不提交、推送或发布。

修改前快照：`.local/checkpoints/account-status-recovery-20261124/README.md`；本批工作区材料说明：`.local/artifacts/account-status-recovery-20261124/README.md`。
