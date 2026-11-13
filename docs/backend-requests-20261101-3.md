# 后端需求单 · 第三批：正式签名与应用内更新（2026-11-01，前端 -> Codex）

> **状态：历史需求单（2026-11-01，第三批）。** 本文保留正式签名和应用内更新需求及当时开工前提；后续发布与合同以现行发布、接口文档为准。当前状态见 [当前开发接续](next-session-handoff.md)。

读者：负责 accounts / core / playback / 构建脚本的后端开发。约定同前两批：不改 `app/src/main/kotlin/tech/musxi/player/ui/`，接口变化写进本文末尾"交付说明"。行号以 2026-11-01 工作区为准，开工前复核。

**产品负责人已定**：①v0.2 起使用正式身份，即专用发布密钥加正式包名 `tech.musxi.player`；②做应用内下载与安装，不再只打开浏览器；③启动后每天自动检查一次，可关闭，并保留手动检查。

**开工前提**：先收尾第二批，包括 `platform-kugou/src/test/java/tech/musxi/loginpoc/KugouAuthorizedAudioTest.java` 的编译错误（lambda 中未处理 JSONException）。建议等第二批改动提交为存档点后再开工（是否提交由使用者决定），两批改动不要混在一处。

**PC 参考**（`C:\text\Musxi-Player\Musxi-Player`，读 main 请用 `git show main:<path>`）：`docs/app-updates.md`（一期：检查、忽略、节流）、`docs/app-update-installation.md`（二期：发布清单、下载、校验、用户主动安装）、`frontend/src/composables/appUpdates.ts`（失败原因的中文说法、发布说明转纯文本）。安卓沿用其清单与状态思路，安装方式不同。

## P0 · 正式签名与发布构建

### 1. 发布签名配置
- 现状：只有 debug 签名的 `.dev` 包；`app/build.gradle.kts` 的 release 没有 signingConfig。
- 目标：release 构建用产品负责人自己保管的发布密钥签名，包名 `tech.musxi.player`（defaultConfig 已是此值，debug 另加 `.dev`）。
- 密钥路径和别名从仓库之外读取：`~/.gradle/gradle.properties` 中的 `musxi.release.storeFile`、`musxi.release.keyAlias`。密码只读环境变量 `MUSXI_RELEASE_STORE_PASSWORD`，未设置时由 build.ps1 用 `Read-Host -AsSecureString` 交互输入。密码不得写入任何文件、日志或命令行回显。
- 缺少配置时，release 构建要明确失败，不能退回 debug 签名，也不能产出未签名包。
- 本轮 release 保持 `isMinifyEnabled = false`（混淆另立一项，要先补反射与 JSON 规则）。
- 开发和测试一律用一次性测试密钥；不得读取、打印或复制真实密钥与密码，任何密钥都不提交。

### 2. `build.ps1 -Release`
- 执行 `:app:assembleRelease`，产物放 `.local/artifacts/release-<versionName>/`：`Musxi-Player-<versionName>.apk`、`SHA256SUMS.txt`，以及第 3 条的更新清单。
- 用 apksigner 校验签名，打印签名证书 SHA-256（证书指纹是公开信息，可以打印）。
- 核对合并后的 release 清单不含任何调试入口（沿用 alpha03 的检查方式）。

## P0 · 应用内更新

### 3. 更新清单（随 Release 一起上传）
```json
{"schemaVersion":1,"packageName":"tech.musxi.player","versionName":"0.2.0","versionCode":4,"minSdk":26,
 "assetName":"Musxi-Player-0.2.0.apk","bytes":27000000,"sha256":"<64 位小写十六进制>","signerSha256":"<证书指纹>"}
```
文件不超过 4 KB，字段与所选 Release 和 APK 严格对应（参照 PC 二期的严格解析）。没有清单的旧 Release，仍只提供"查看"（打开 Release 页面）。

### 4. 下载
- 只下载本仓库（qwt1011/Musxi-Player-Android）Release 中的资产。重定向只允许 GitHub 官方资产域名，需实测确认，并限制重定向次数；累计大小超过清单 bytes 立即中止。
- 写入应用私有缓存目录，不申请存储权限；可以取消；成功、失败或取消后都清理半成品。
- 提供"当前是否为按流量计费网络"的状态；用移动数据时由前端先征得用户同意，后端不自行判断。

### 5. 安装前校验
- 大小与 SHA-256 必须和清单一致。
- 用 PackageManager 解析下载的 APK：包名必须等于当前包名；versionCode 必须大于当前；签名证书必须与**当前已安装应用**的证书一致（不只是与清单一致）。任一项不符就失败并删除文件。

### 6. 安装
- 用 PackageInstaller 会话（或 FileProvider 加 ACTION_VIEW）调起系统安装确认页，并回报结果（成功、用户取消、失败原因）。
- 声明 `REQUEST_INSTALL_PACKAGES`。`canRequestPackageInstalls()` 为 false 时，进入"需要授权"状态，并提供打开本应用"安装未知应用"设置页的命令。
- 安装会结束进程：发起安装前保存播放进度与队列（沿用现有的持久化），不需要额外确认。

### 7. 开发版本
- `.dev` 包（以及任何非 release 构建）不自动检查、不提示升级、不提供下载和安装。手动检查时显示"开发版本"，并给出官方最新版本号（同 PC 的 development_build）。

## P0 · 自动检查与状态契约

### 8. 每天自动检查一次
- 仅 release 构建：启动约 5 秒后在后台检查；距上次成功检查不到 24 小时则跳过。上次成功时间和"自动检查"开关持久化。手动检查不受 24 小时限制，仍受现有 30 秒节流与限流退避约束。
- 首次启动或更新后首次启动时，开关默认开启。

### 9. 可观察的状态（取代现在的单次回调）
建议 `StateFlow<UpdateState>`，前端只读：
- `current`（versionName、versionCode）、`developmentBuild`、`autoCheck`、`lastSuccessAt`；
- `check`：idle / checking / succeeded / failed，附原因；
- `latest`：版本、标签、是否预发布、发布说明原文、发布时间、清单是否可用、大小、是否已被忽略；
- `download`：none / downloading（已收 / 总量）/ verifying / ready / needsPermission / installing / failed（原因）；
- `justUpdatedFrom`：更新后首次启动时为旧版本号，前端读取后调用 `consumeJustUpdated()` 清除。

命令：`check()`、`setAutoCheck(Boolean)`、`ignore(version)`、`clearIgnore()`、`startDownload(version)`、`cancelDownload()`、`install()`、`openInstallPermissionSettings()`。所选版本与当前 latest 不一致时拒绝执行（同 PC 的 release_changed）。所有回调回到主线程。失败原因用枚举，前端负责中文说法：网络、超时、限流、重定向不安全、大小不符、校验失败、签名不符、包名不符、版本不高于当前、存储不足、权限未授予、安装被取消、安装失败。

## 验收
- 单测：清单解析与篡改、重定向主机、大小或 SHA-256 不符、签名、包名或版本不符、24 小时节流、开发版本、主线程回调。
- 真机：用一次性测试密钥，加独立包名后缀（例如 `.updatetest`），构建新旧两个版本；从发现新版本、下载、校验、系统安装页，到新版启动并显示"已更新"，走通一遍；再验证取消下载、关闭网络、拒绝授权三种情况。结束后只卸载这个测试包。不要卸载、清除或覆盖 `.dev` 包和正式包。

## 前端分工（CC）
"我的 → 关于"的完整状态：下载进度与取消、校验中、可以安装、需要授权（先说明原因，再跳转设置）、失败原因。另外还有：发布说明 Sheet（转纯文本，规则同 PC 的 noteLines）、"自动检查更新"开关、"我的"标签上的小红点（有未忽略的新版本时显示）、"已更新到 v0.x"提示、移动数据下载前的确认。

## 不做
静默安装、增量补丁、自动回退、未经用户点按的自动下载、第三方镜像或加速地址（以后要做另议；签名与哈希校验仍能防篡改）、应用商店分发、本轮混淆。

## 交付说明（Codex 填写）

第三批实现与验证记录见 [第三批交付](backend-batch3-delivery-20261101.md)。状态流、命令、开发包行为、流量确认和安装权限接线见 [安卓更新接口](android-app-updates.md)；外部签名和构建产物见 [Release 构建](release-build.md)。本批不修改 CC 的 UI 源码，保留旧回调供过渡。测试使用一次性密钥，未提交、推送或发布；后续连接手机后完成独立测试包的真实双版本覆盖安装，并做真实账号只读音质检查，没有退出账号、清除用户数据或写平台收藏。

08:44 本地收尾：包含酷狗音质菜单错误隔离与 60 秒缓存的最终版本，1056 项 JVM 测试全部通过，Lint 0 错误/111 警告；一次性签名 Release、缺配置失败门禁和实际生成清单的严格解析均已验证。前端接线及无连接设备而未完成的真机项，在交付记录中明确列出。

设备追加后的最新基线为 1059 项 JVM 测试、3 项真机检查通过，Lint 0 错误/111 警告。系统升级、错误 APK 拦截、每日检查、酷狗真机菜单修复与剩余前端接线见 [真机追加验收](backend-batch3-device-acceptance-20261101.md)。受控下载与真实线上新版下载的边界已单独记录。
