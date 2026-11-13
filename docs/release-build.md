# 正式签名与 Release 构建

`build.ps1 -Release` 只构建 `:app:assembleRelease`。正式包为
`tech.musxi.player`，使用仓库外的 owner-managed keystore；debug 包仍为
`tech.musxi.player.dev`。项目不提供 debug 或 unsigned 的 release 回退。

## 一次性配置

在 Gradle user home 的 `gradle.properties`（默认
`%USERPROFILE%\.gradle\gradle.properties`）中填写：

```properties
musxi.release.storeFile=C:\absolute\path\outside\repository\release.jks
musxi.release.keyAlias=release
```

密码只从进程环境变量 `MUSXI_RELEASE_STORE_PASSWORD` 读取；专用发布密钥的
store password 与 key password 使用同一个密码。不要把密码写入
`gradle.properties`、仓库文件、日志或命令参数。测试签名流程使用独立的一次性测试密钥；
经所有者授权的实际版本发布使用长期保存的发布密钥。首次公开发布后，后续覆盖更新必须沿用同一密钥，
不能再把该密钥当作一次性材料删除。私钥库、别名和密码应另作安全离线备份，不能加入 Git 或 Release 附件。

v0.2 是 `tech.musxi.player` 的首次正式签名发布，版本为 `0.2.0` / code 5。
v0.3 沿用相同签名及包名，版本为 `0.3.0` / code 6，支持覆盖更新。
旧 v0.1 的 `tech.musxi.player.dev` 使用开发签名，两种包各自保存数据，可以并存；不能用正式包自动迁移开发包账号或队列。

## 构建与安装

先设置 JDK 17 和 Android SDK：

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-17'
$env:ANDROID_HOME = 'C:\path\to\android-sdk'
.\build.ps1 -Release
```

未设置 `MUSXI_RELEASE_STORE_PASSWORD` 时，脚本使用
`Read-Host -AsSecureString` 交互读取，只在 Gradle 子进程运行期间写入进程环境，
并在 `finally` 中清除或恢复。脚本不把密码传给命令行。

安装并打开正式包：

```powershell
.\build.ps1 -Release -Install
```

`-Release` 不能和 `-Test`、`-Lint` 组合；release 的签名、APK 元数据与清单检查
会在构建后自动执行。缺少 store 路径、alias 或密码，或者检查失败时，脚本明确失败，
不会退回 debug 包，也不会发布 unsigned 包。debug 的
`.\build.ps1 -Test -Lint` 不要求 release 配置。发布密钥文件必须位于仓库之外。

## 产物

正式产物写入：

```text
.local/artifacts/release-<versionName>/
  Musxi-Player-<versionName>.apk
  SHA256SUMS.txt
  update-manifest.json
```

`update-manifest.json` 使用 `schemaVersion: 1`，只含约定字段：
`packageName`、`versionName`、`versionCode`、`minSdk`、`assetName`、`bytes`、
`sha256`、`signerSha256`，文件不超过 4 KiB。哈希和签名指纹均为 64 位小写十六进制。

构建完成后脚本执行以下门禁：

- 用 `apksigner verify --verbose --print-certs` 验签，要求 v2 签名，并打印公开证书
  SHA-256 指纹。
- 用 `aapt` 核对 APK 的 package、versionCode、versionName 和 minSdk。
- 检查合并后的 release manifest 和 APK 清单，不允许 `debuggable=true`、
  `testOnly`、debug application 或 debug/validation/glass-preview 入口。
- `-Release -Install` 安装并启动 `tech.musxi.player`，不会启动 debug 的
  `tech.musxi.player.dev`。

应用内更新见 [安卓更新接口](android-app-updates.md)，公钥证书与私钥的区别见 [签名说明](signing-security.md)。`build.ps1` 已内置主目录/辅助工作树共享文件锁，进程结束自动释放；不再使用按时间删除锁的旧包装器。完整验证入口见 [验证工作流](validation-workflow.md)。
