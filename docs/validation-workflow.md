# 可复用验证工作流

状态：2026-11-03 建立。当前产品与待验范围见 [开发接续](next-session-handoff.md)，完整资料见 [文档入口](README.md)。

## 唯一完整验证入口

Windows PowerShell 7、JDK 17、Android SDK 36：

```powershell
pwsh -NoProfile -File scripts/verify.ps1
```

`JAVA_HOME` 指向 JDK 17；`ANDROID_HOME` 或 `ANDROID_SDK_ROOT` 指向 Android SDK。安装 `platforms;android-36`、`build-tools;35.0.0` 和 `build-tools;36.0.0`。Gradle wrapper 固定为 8.13，并校验发行包 SHA-256；AGP、Kotlin 与依赖版本沿用工程配置。不要强制 Windows Gradle daemon 使用 UTF-8，源码与测试内容已有独立编码设置。

脚本执行文档相对链接检查和根任务 `:verifyAndroid`。任务通过模块插件汇集所有 JVM 测试和 Android Lint，并编译 debug 主包与 androidTest 包。新增同类模块自动进入任务图，不重复维护 CI 模块清单。设备测试包只编译，不安装、不执行。

完整验证强制重跑 JVM 测试，不接受上次测试的缓存命中；编译和 Lint 可复用 Gradle 判断仍有效的输出。每模块必须产生本轮 JUnit XML，测试数大于零，失败/错误/跳过均为零。Lint 错误使验证失败，警告按模块保留。

源码编辑停止后再运行。入口记录 Git HEAD、未提交文件状态与逐文件 SHA-256，结束前再次核对；期间改动源码或文档，本次结果失败。所有 `build.ps1` 调用共享操作系统文件锁，报告采集也在锁内；进程结束自动释放，不按锁年龄删除活跃构建。旧 `.local/tmp/*build*.sh` 不再作为推荐入口。直接调用 Gradle 不参与锁，不能与验证同时运行。

## 证据与失败处理

每轮独立目录为 `.local/artifacts/verification/<UTC时间>-<进程号>/`，保存 `summary.json`、`source-files.json`、`build.log` 和 `reports/<模块>/`。摘要包含源码身份、时间、实际测试数、Lint 计数、APK SHA-256 与结果；`deviceTestsExecuted=false` 表示没有设备验收。

失败同样写摘要，不用旧目录或模块 build 中残留报告替代；构建日志与 XML/HTML 用来定位失败，不能把部分通过拼成整包通过。证据目录排除 Git。

小改可用 `build.ps1 -Test` 或 `-Lint` 做局部反馈；`build.ps1 -Test -Lint` 使用同一完整任务图，但只有 `scripts/verify.ps1` 提供强制新测试、源码核对和独立证据。截图用例执行不等于视觉基线比较，JVM 通过不等于真实后台播放或听音通过。

## GitHub Actions

[android-verify.yml](../.github/workflows/android-verify.yml) 在 main 推送、PR 和手动触发时使用相同入口，采用 Windows 2025、Microsoft JDK 17、SDK 36。第三方 Action 固定完整提交，权限为 `contents: read`，PR 缓存只读；不需要正式密钥。失败也上传报告，保留 14 天。

GitHub Actions 是仓库中的自动任务执行服务：触发后由 GitHub 提供独立机器检出对应提交、准备工具、运行脚本并给出状态。它能发现本机缓存或环境掩盖的问题；通过构建和单元测试仍不能证明真实账号、听音或手机后台行为通过。

查看仓库 Actions 页的 `Android verification`；需要重跑同一提交时使用运行详情中的 Re-run jobs，需要验证所选分支时使用 Run workflow。失败先看具体步骤与 `build.log`，再读取该次 Artifact 的 `summary.json`、源码清单及模块报告。报告保留期为 14 天，有效交付证据需另存项目 `.local/artifacts/` 并记录运行 URL、提交与 attempt。缓存仅用于加速，完整入口仍强制执行本轮 JVM 测试。

2026-11-03 已完成 [远端首跑联调](actions-first-run-20261103.md)：首次失败暴露的短路径问题修复后，提交 `d2e0018` 的 [运行 41559846988](https://github.com/qwt1011/Musxi-Player-Android/actions/runs/41559846988) 构建、1599 项 JVM 测试、Lint 与报告上传全部通过；报告下载核实完成。本机和远端结果分别留证，不能互相替代。分支保护需要另在仓库设置中选择 `verify` 检查，不由 YAML 自动完成，本轮没有修改分支保护设置。

## 设备与发布

真实账号、网络取流、焦点、长后台、来电和回收需要专项实机验收。记录包哈希、设备/系统、账号权益、步骤、实际声音和收尾状态，不恢复历史队列、不借旧授权改账号或收藏。

设备复用步骤、明确选择测试类、真实写入参数与本轮恢复证据见 [v0.2 后端集中实机验收](backend-v02-device-acceptance-20261103.md)。新增收藏测试默认跳过，只有已获当轮授权并传 `allowPlatformWrites=true` 才执行；手动换源测试须传 `allowManualSourceSwitch=true`。先解锁并保持前台，后台/锁屏单独安排；报告必须核对本轮时间，广播成功不等于测试通过。不要直接执行包含历史故障注入与更新测试的整个设备套件。

2026-11-06 新增 `PlaylistMembershipDeviceTest`，必须显式选择该类并传 `allowPlatformWrites=true`；它不在统一入口中自动执行。写前保存当轮普通歌单与红心基线，完成后核对完整顺序；中断时只删除本轮新加的测试歌，不恢复历史队列或重建账号。首轮无 ADB 设备时的交付边界保留于 [首轮记录](backend-playlist-delivery-20261106.md)。

同日后续手机重连后已执行该专项，酷狗空普通歌单往返通过、网易云跳过、QQ 写前连接超时，见 [后续接入与实机证据](backend-playlist-followup-20261106.md)。这里的结果独立于冻结构建摘要，后者 `deviceTestsExecuted=false` 不改写；不能因 instrumentation 汇总为 3 项就忽略平台跳过或失败。

用户完成 QQ 登录与网易云歌单准备后，新增改名、整单订阅、QQ 两歌单联合管理及创建一次性 marker 专项，步骤、额外开关、真实通过/风控/不确定状态见 [追加验收](backend-playlist-device-20261106.md)。创建 marker 的只读检查返回测试成功，业务仍可能为 `UNKNOWN`；必须同时读取当轮安全报告，不能只看 runner 汇总。

正式发布另用 [Release 构建](release-build.md) 与 [签名说明](signing-security.md)，沿用原正式密钥；持续集成不自动签正式包、推送、合并或发布。

2026-11-10 本地音频设备专项必须明确选择 `LocalAudioDeviceTest`、`allowLocalAudioDevice=true` 和 `localPhase`，使用当轮生成文件与 marker 协调源删除、重启、撤权和清理；不得套用户真实文件或直接重放旧 marker。独立 `LocalAudioStorageDeviceTest` 与 `WallpaperDeviceTest` 只用自建临时存储。真实标准 SAF、解码、暂停恢复、定时、权限与限制见[当轮设备记录](backend-local-device-20261110.md)。内部 `LocalAudioPickerValidationActivity` 只在 debug Manifest 注册且不导出，系统原始选择结果留在内存，不进入公开状态或报告。

同日重连后的扩展格式与 OEM／通知补验明确选择 `LocalAudioFollowupDeviceTest`、`allowLocalAudioFollowup=true` 和单个 `localPhase`。连续熄屏观察用普通 debug 进程的固定广播，不在 instrumentation 内声称后台存活；核对音频身份、屏幕事件、连续时长、真实定时停止窗口与落盘进度，并另记充电/device-idle 状态。阶段结果、失败尝试、abort 与清理见[追加设备记录](backend-local-followup-device-20261110.md)。
