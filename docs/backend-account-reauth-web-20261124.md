# 账号后端第三批：三平台原位重认证与网页登录会话

2026-11-24。用户授权第三批并明确三轮均覆盖网易云、QQ 音乐、酷狗。本批完成三平台共同的原位重认证后端；网易云另补其现有网页登录的会话生命周期。QQ／酷狗正式入口继续使用官方 App 授权，不将 debug 网页探测入口升级为正式登录。本轮无手机，前端布局及操作接线继续暂缓。

## 三平台共同语义

有本地身份且处于 `CONNECTED`、`REAUTH_REQUIRED` 或 `RETRYABLE_ERROR` 时，可发起原位重认证。已有活跃操作则拒绝重复开始，没有身份仍使用普通登录。新尝试取得新 generation，保留原账号资料、会员、原凭据及展示缓存，候选会话只在内部暂存。

候选必须经过对应平台 verify。候选已有身份时，验证身份必须一致；网易云从网页／二维码取得的候选允许尚无 ID，由官方账号响应确认真实身份后才能保存。成功后沿用平台已有原子会话存储，替换凭据并提交 CONNECTED。新 generation 拒绝之前的曲库、会员、验证或登录响应；成功后重新读取会员。

同用户成功保留 owner 与缓存。不同用户成功使用新的 owner；曲库、推荐、播放日志、歌词及手动绑定仍按既有 owner／generation 访问和隔离，不预先清除原账号缓存，也不让旧账号快照成为新账号内容。显式退出仍按原规则清除该平台缓存和凭据。

取消、候选失败、保存失败或 180 秒尝试超时，会恢复发起前的 phase、资料、会员和错误，但保持新的 generation；旧回调永远不能再次提交。原来已过期的账号仍是 `REAUTH_REQUIRED / EXPIRED`，不会因候选网络失败而恢复业务授权。候选失败类别记录在 `accounts.verification`，UI 授权状态仍只看 `accounts.states`。

尝试期间账号不处于 CONNECTED，因此原凭据不能用于新业务；取消／失败后原本已连接的账号恢复原有权限。HTTP 及存储提交继续遵守[第二批](backend-account-execution-20261124.md)的线程、等待与提交锁边界，不强制中断进行中的磁盘提交。

## 后续前端接线

| 平台／入口 | 新后端调用 | 说明 |
| --- | --- | --- |
| QQ 音乐、酷狗 | `accounts.reauthenticateOfficial(platform, launch)` | 直接发起现有官方 App 授权，候选成功后再替换 |
| 网易云网页登录 | `NeteaseWebLogin.reauthenticate(activity, accounts, …)` | Activity 持有并关闭窗口；Cookie 不进入产品 UI |
| 网易云相册扫码 | `accounts.reauthenticateNeteaseScanner(launch)` | 沿用现有临时二维码与官方扫一扫 |
| 重新检查现有凭据 | `accounts.restore(platform)` | 验证已有会话，与重认证区分 |
| 明确退出 | `accounts.disconnect(platform)` | 删除本地平台凭据／缓存，网易云还清理本应用网页会话 |

本批没有修改 `MainShell`、`AccountViewModel` 或 Compose 页面。现有“重新登录”仍是 `reconnectAfterClear → disconnect → 普通 connect`；待前端集中阶段换成上述原位入口，再移除等待 DISCONNECTED 的旧跳转。新能力存在不等于正式按钮已接入，不应直接改变 `disconnect` 的含义来兼容旧接线。

## 网易云网页会话特有规则

应用在 API 28+ 已于启动早期设置 `WebView.setDataDirectorySuffix("netease_login")`，本批沿用该应用专属 profile，不引入 AndroidX WebKit、多 profile 框架或新依赖。

- API 28+：普通关闭、取消、Activity 销毁及提交 Cookie 后关闭只销毁窗口并 flush，保留网页 Cookie 与 DOM 存储。再次打开仍须由用户提交并由平台 verify，不自动把网页状态当作原生账号。
- API 26／27：无法提供现有 suffix 隔离，继续采用临时会话；准备和关闭时清理应用 WebView 会话。当前其他正式平台不使用这套浏览器，不影响其他 App 或官方 App 会话。
- 本轮第一次打开或存在待清理标记时先完成清理，防止旧版本／中断退出残留。窗口尚未准备好时禁止重新加载、提交和弹新窗口；失效账号 token 不加载或提交。
- 明确网易云退出先持久记录清理意图，再清原生凭据，主线程关闭已登记网页登录窗口、清 WebStorage 并异步删除 Cookie。删除后 flush，确认 jar 无 Cookie、且清理 revision 仍匹配，才清待办标记。空 jar 回调的 `removed=false` 不等于失败；残留 Cookie 不得算成功。
- 清理等待 15 秒未完成则回报失败，但仍保持清理门关闭，后续打开不能越过实际未完成的删除。迟到删除不能覆盖更新的持久清理意图；进程中断后下一次打开会重新补清。WebStorage 清理使用系统无完成回调的 API，实际提供方行为仍待设备验证。
- 网易云只有原生和网页清理都成功才发布 DISCONNECTED；失败保留 STORAGE 提示，用户可明确重试退出。退出中的取消不能恢复已经删掉的原生会话。QQ／酷狗退出不清网易云网页会话。

保留官方 HTTPS 导航白名单、SSL 错误拒绝、无 JS bridge、不读取密码／DOM，以及窗口 FLAG_SECURE；不改网页登录外观或新增登录方式。

## 验证与待验

新增 28 项：纯协调器重认证 8 项、Repository 11 项、WebView 生命周期 9 项。Repository 的同用户缓存保留、不同用户隔离、候选失败、原子保存失败、取消后迟到结果、身份不符和超时均逐一覆盖三平台；网易云候选导入通过内部 Driver seam 测试，未请求真实平台。网页用 Robolectric API 35／27 检查关闭保留／旧系统清理、明确退出、持久待办、迟到清理与 Cookie 残留。前两批 30 项一并回归，另回归原协调器用例。

定向日志位于 `.local/artifacts/account-reauth-web-20261124/targeted.log`；单独网页检查为 `web-targeted.log`。完整入口 `scripts/verify.ps1` 将本轮源码清单、测试、Lint 和 APK 散列保存在 `.local/artifacts/verification/<UTC时间>-<PID>/`，最终精确目录与结果见本批本地材料说明及交付消息。

本批实现阶段无手机，未安装或真实登录／换号／退出，不写平台歌单；随后用户连接手机的[独立实机补验](backend-account-device-20261124.md)已覆盖现有账号校验、候选取消和网络恢复。三平台官方授权、真实凭据替换、Android Keystore 存储故障、更多 WebView 提供方和 Cookie 跨进程持久化仍待验。前端不接本批新入口；不提交、推送或发布。

改前快照 `.local/checkpoints/account-reauth-web-20261124/README.md`；日志及验证摘要引用 `.local/artifacts/account-reauth-web-20261124/README.md`。已有未提交开发保留，不创建辅助工作树。
