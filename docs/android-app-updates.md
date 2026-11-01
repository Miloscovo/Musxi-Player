# 安卓应用内更新接口

第三批后端接口，供 CC 接线。`MusxiApplication.updates.state` 是只读 `StateFlow<UpdateState>`，类型位于 `core/update/UpdateState.kt`。现有 `check { UpdateCheckResult }` 暂时兼容；开发包的旧回调返回 `NoUpdate`，完整信息请读新状态。

`current` 含 `versionName/versionCode`；`developmentBuild` 在 `.dev`、其他包名和所有非 release 构建为 true。开发包只允许手动查看官方发布版本，`latest.newer/canDownload` 均为 false，不启动自动检查、不下载、不安装。手动结果显示“开发版本”，同时显示 `latest.version`。

`check` 含 `phase`（IDLE/CHECKING/SUCCEEDED/FAILED）、`reason` 和限流时的 `retryAt`（epoch 毫秒）。`latest` 含 `version/tag/prerelease/notes/publishedAt/releasePage/manifestAvailable/bytes/ignored/newer/canDownload/manifestFailure`。`notes` 为不可信发布说明原文，前端按 PC `noteLines` 转纯文本；不要直接渲染 HTML。旧发布没有 `update-manifest.json` 时仍能查看 Release 页面。

红点条件为 `!developmentBuild && latest?.newer == true && !latest.ignored`。忽略只关闭提醒，仍允许主动查看、下载；下载按钮另外依据 `canDownload`。

`download` 含 `phase`（NONE/DOWNLOADING/VERIFYING/READY/NEEDS_PERMISSION/INSTALLING/FAILED）、`version/receivedBytes/totalBytes/reason`。收到全部字节后还有校验阶段，只有 READY 可以发起安装。NEEDS_PERMISSION 时先解释，再让用户点按打开设置；返回后重新调用 `install()` 检查授权。INSTALLING 表示会话已开始，系统仍需确认；不代表安装成功。`installOutcome` 记录 SUCCEEDED/CANCELLED/FAILED。覆盖安装可能结束进程，新版启动以 `justUpdatedFrom` 为准。

命令均可从任意线程调用，状态修改及旧回调在主线程。网络和文件操作在后台：

| 命令 | 行为 |
| --- | --- |
| `start()` | Application 自动调用一次；release 启动约 5 秒后按 24 小时策略检查 |
| `check()` | 手动检查，绕过每日限制，保留 30 秒请求节流和 GitHub 退避 |
| `setAutoCheck(Boolean)` | 保存自动检查开关；初装与升级首次启动默认开启 |
| `ignore(version)` / `clearIgnore()` | 本地保存或清除忽略；传 `latest.version` |
| `startDownload(version)` | 仅接受当前 latest；传 `latest.version`，旧按钮返回 RELEASE_CHANGED |
| `cancelDownload()` | 取消传输并删除半成品；也可丢弃 READY 文件 |
| `install()` | 重新校验文件并等待播放队列/进度写盘，再进入系统安装确认 |
| `openInstallPermissionSettings()` | 打开本应用的“安装未知应用”设置 |
| `consumeJustUpdated()` | 显示“已更新”后消费旧版本提示 |
| `refreshNetworkState()` | 可在下载确认弹窗前刷新 `meteredNetwork`；也有系统网络变化监听 |

前端在 `meteredNetwork` 为 true 时先征得下载同意，再调用 `startDownload`。后端不把命令之前的网络状态当作用户已同意；网络变化会刷新标志，但本轮不添加自动暂停或后台自动下载。

失败枚举与建议说法：NETWORK 网络连接失败；TIMEOUT 连接超时；RATE_LIMITED GitHub 暂时限制访问；UNSAFE_REDIRECT 不安全的下载跳转；INVALID_MANIFEST 更新清单异常；DOWNLOAD_UNAVAILABLE 暂无应用内安装包；SIZE_MISMATCH 大小不符；CHECKSUM_MISMATCH 校验失败；SIGNATURE_MISMATCH 签名不符；PACKAGE_MISMATCH 包名不符；VERSION_NOT_NEWER 版本不高于当前；STORAGE 存储不足或写入失败；PERMISSION_DENIED 未授予安装权限；CANCELLED 已取消；INSTALL_FAILED 安装失败；RELEASE_CHANGED 版本已变化；DEVELOPMENT_BUILD 开发版本；BUSY 正忙；NOT_READY 尚未就绪；CHECKPOINT_FAILED 播放进度保存失败；UNSUPPORTED_SDK 系统版本不受支持。

更新只从本仓库 Releases 的精确资产 URL 开始；最多三次重定向，仅允许 HTTPS 官方资产主机。清单最多 4096 字节，APK 上限 1 GiB，拒绝字段重复、类型混淆、未知字段及不匹配的版本、资产或签名。下载前重新读取清单，安装前重新计算大小、SHA-256，并由 PackageManager 解析 APK，核对当前应用的签名；不信任清单单方面给出的发布者身份。缓存仅在当前进程保留，取消/失败清理，下次启动清理遗留文件。

此文是接口合同。测试结果、构建包与真机验收边界见第三批交付记录，不将本地替身测试视为真实 Release 升级通过。
