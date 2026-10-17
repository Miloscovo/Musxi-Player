# 二阶段：下载与覆盖升级

2026-10-17。Codex 负责原生/发布/安装，CC 负责前端；未开新 issue。第一阶段 PR #19 尚待合入，二阶段依赖它。

## 两个 PR

1. A：`feat/update-upgrade-foundation`，发布清单、严格解析、隔离安装规则探针及完整产品升级验收步骤。
2. B：基于 A，原生下载管理、进度、取消、校验和用户主动安装；新增 bridge 契约，CC 负责 TS client、hook、界面、demo 和真实产品 UI 点击验收。

## 已实现：官方安装包清单

`package.ps1` 使用最终安装器生成 `MusxiPlayer-Update-<version>.json`，并将其加入 `dist/release-assets.json` 的默认上传列表。维护者应同时上传安装器和清单，不能上传整个 dist。旧 Release 无清单时，第一阶段浏览器下载入口保持可用。

```json
{"schemaVersion":1,"version":"0.5.0","architecture":"x64","assetName":"MusxiPlayer-Setup-0.5.0.exe","bytes":123456,"sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"}
```

清单最多 4096 字节，资产必须唯一、uploaded、官方仓库精确 HTTPS URL；字段严格绑定已选 release 和 installer。SHA-256 为 64 位小写十六进制。清单基于官方 GitHub HTTPS 发布来源的信任，验证文件一致性；它不是数字签名，也不提供独立于 GitHub 账号的发布者身份验证。

本地缓存保留清单资产身份；下载开始时仍需重新下载清单，不从本地偏好文件信任可执行文件哈希。清单失败不会修改第一阶段版本检查结果。

## 安装验证分层

安装器已使用固定 AppId `A696A45D-3218-45C2-A565-EAC5642B976E`、per-user 安装目录、`CloseApplications=yes`、交互安装后的启动选项。profile 在独立 `%LOCALAPPDATA%/MusxiPlayer`，不是安装目录。旧独立测试安装器的身份及目录不同，本轮不迁移它。

### 隔离规则探针

```powershell
./scripts/test-upgrade-installation.ps1 -Compiler <ISCC.exe> -OutputDirectory <全新专用目录> -Execute
```

提取生产安装器的 Setup 指令，替换为随机独立 AppId 和临时目录，生成两个含惰性文件的安装器，验证 0.4.0 → 0.5.0 替换、Unicode 自定义路径、路径记忆、卸载身份、独立 profile 哨兵。结束卸载测试身份，保留日志及样本；不会运行正式播放器，也不清理真实账号数据。无 `-Execute` 只编译探针。

这不等于真实产品覆盖升级成功。探针不覆盖播放、CEF/Node/Python 文件占用、数据库迁移或真实登录。

2026-10-17 本机已执行探针并通过：真实 Inno 安装 0.4.0 → 0.5.0、Unicode 自定义目录记忆、同一卸载身份、哨兵不变和卸载清理。证据 `build/app-updates/upgrade-probe-final-20261017/result.json` 及两个安装日志。首轮探针误写注册表键名（漏 GUID 大括号），已修正并清理首轮独立安装。Core 测试 **10/10** 通过，含清单绑定/篡改/旧版回退和打包哈希生成。

### 完整产品验收（待真实发布包）

在 Windows Sandbox/专用测试账户中安装真实旧发布，再覆盖新版候选包，避免影响日常安装。

| 场景 | 必须核对 |
| --- | --- |
| 默认/自定义安装路径 | 使用原目录、一个卸载项、启动后显示新版本 |
| 播放中、托盘、桌面歌词 | 保存播放历史，所有产品子进程退出，安装无锁文件错误 |
| 数据保留 | 设置、曲库、歌单、歌词绑定与播放历史保持；登录状态按实际平台验证，不在日志复制凭据 |
| 取消安装/安装失败 | 原版可启动，用户数据仍在，日志能够诊断 |
| 安装完成 | 通过现有安装向导主动启动新版，无强制静默重启 |

二阶段不实现静默安装、增量补丁、自动回退、独立渠道或断点续传。

## B 已实现：原生接口（CC 接入依据）

分支 `feat/in-app-update-download`，基于 A。全部是第一阶段接口的附加字段/命令，原有浏览器操作保持兼容。生产下载器仅在真实 ReleaseBuild 且非 smoke/fixture 环境中启用；开发联调通过下述独立 fixture，不绕过正式编译身份。

`updates.getState {}` 返回的原状态新增：

```json
{
  "capabilities": {"installerDownload": true},
  "latest": {"inAppDownloadAvailable": true},
  "download": {
    "status": "ready", "reason": "", "releaseId": "20", "assetId": "120", "version": "0.5.0",
    "receivedBytes": 123456, "totalBytes": 123456,
    "canCancel": false, "canInstall": true, "superseded": false
  }
}
```

这是附加字段示意，不能代替完整第一阶段 DTO。无下载器时 capability 为 false、download 为 null；旧后端没有这两个字段。`latest.inAppDownloadAvailable` 还要求最新版本适用且有唯一有效清单。只看 `downloadAvailable` 不足以启用应用内下载。

| 命令 | 参数 | 成功返回 |
| --- | --- | --- |
| `updates.startDownload` | `{releaseId: string, assetId: string}` | 完整更新状态，后台开始下载；相同已就绪安装包直接复用 |
| `updates.cancelDownload` | `{}` | 完整状态；异步取消需继续轮询直到 cancelled；ready 时取消会丢弃缓存 |
| `updates.install` | `{releaseId: string, assetId: string}` | 完整状态，status=installing；生产环境随后请求正常关闭应用 |

命令参数中的 release/asset ID 为第一阶段正整数字符串。命令不接受 URL、路径、额外字段或 persistent；原有主页面信任、托盘和桌面歌词权限限制继续生效。

下载器已启用但尚未选择任务时，`download` 是对象而非 null：`status="idle"`，`releaseId/assetId/version/reason` 都为空字符串，字节数为 0，`canCancel/canInstall/superseded` 都为 false。未开始下载就调用取消也可产生 ID 为空的 `cancelled` 状态；已选择过任务后的 cancelled 通常仍保留任务 ID。前端解析必须接受这些无任务状态；真正发起下载/安装命令时仍需有效正整数 ID，不应为初始状态伪造 ID。

download.status：`idle → preparing → downloading → verifying → ready → installing`；中途可为 `failed` 或 `cancelled`。准备阶段下载小清单，不计入 receivedBytes。可安装只能依据 `canInstall`；100% 字节仍可能正在校验。检查发现新版已变化时旧下载 `superseded=true`、canInstall=false；旧按钮调用返回 409，刷新状态后由用户下载新版。

复用现有 getState 轮询；下载活动时建议 400～500ms，其余沿用第一阶段。进度仅代表安装包字节，不承诺下载速度/剩余时间估算。取消不是同步终止，不应立即显示“已取消”或启动第二个任务。

### 错误

- 400 `invalid_params`：参数类型、字段或额外路径/URL 不符合契约。
- 409 `download_unavailable`：当前环境/发布不支持应用内下载；可回到浏览器入口。
- 409 `release_changed`：版本/资产已变化；刷新状态。
- 409 `download_busy / download_not_ready / shutting_down`：已有任务、尚未通过校验、已进入退出流程。
- 500 `storage_error`：不能创建下载任务；500 `installer_start_failed`：安装器启动失败，保留 ready 供重试，应用保持运行。
- 后台失败通过 download.status/reason 返回：`network_error / timeout / rate_limited / http_error / unsafe_redirect / too_many_redirects / invalid_download / invalid_manifest / size_mismatch / checksum_mismatch / verification_failed / disk_full / storage_error / internal_error`。取消为 `cancelled`。原有 updates.check 的 retryAt 仅控制版本检查，不表示下载重试倒计时。

### 下载与安装行为

- C++ WinHTTP 独立工作线程流式写文件，不缓存整个安装包；单任务、最大安装包小于 2 GiB、30 分钟总预算、有限阶段超时、50ms 取消检查。最多 5 次手动跟随重定向，仅官方仓库资产初始 URL 和精确 GitHub Release CDN HTTPS 目标；不发送登录信息、cookies 或 Windows 自动凭据。
- 每次下载重新取得官方清单；临时文件必须精确匹配资产大小。完成后使用 Windows BCrypt SHA-256 读取磁盘文件校验，验证句柄持续禁止写入/删除；校验失败不提供安装入口。
- 缓存在当前 profile 的 `update-downloads/job-<随机值>`；不写安装目录。取消/失败/正常退出未安装时清理本次文件，无断点续传或跨重启恢复。因此“稍后”表示本次运行保留 ready，退出后下次需要重新下载。成功启动安装器后保留文件供安装进程读取，当前不自动清理这类已交接安装包。
- 安装动作启动现有交互式 Inno 安装器，成功后异步请求现有正常退出流程，保存播放历史并关闭 CEF、音乐服务和音频；不使用强杀或静默安装参数。启动失败不会退出播放器。安装向导保留用户取消和安装后启动选项，`installing` 不表示安装成功或新版已运行。
- 正常退出与真实安装器进程的完整衔接仍需专用测试账户中的真实双版本验收；原生替身和隔离探针不代替该结果。

## CC 可运行的下载 fixture

```powershell
MusxiPlayerWeb.exe --test-app --update-fixture=<仓库>/tests/fixtures/app-updates/download.json
```

原有 available.json 保持浏览器模式。download.json 启用三字节惰性负载 `abc`，走真实下载文件/大小/BCrypt 校验逻辑，数据源是内存替身。安装只记录 `installedReleases: ["20"]` 到 `updates.fixtureDiagnostics {}`，不会启动系统程序或退出产品窗口。临时缓存放系统 temp，正常退出清理；不读写真实更新偏好或 profile。

可改 `download.delayMilliseconds`（0～30000）观察进度/取消；`reason` 可为 `"" / timeout / network_error / rate_limited / disk_full / checksum_mismatch / size_mismatch`；`launchFails=true` 模拟启动失败并保留 ready。无任意文件负载或执行参数。安装样本仅支持大小 3 的已选资产，且清单大小必须为生成 JSON 的真实大小（当前 0.5.0 样本为 186）。

原分工由 CC 负责 TS 客户端、共享 hook、界面、demo 和产品 UI 验收；2026-10-17 用户要求 Codex 接手 CC 剩余工作。独立 backend CEF smoke 使用测试 HTML，与下述新增产品页面点击验收分别记录。

## 后端验证结果（2026-10-17）

- Core **11/11** 通过（7.42 秒），覆盖下载取消/重试/大小/哈希、只读文件锁、启动失败保留 ready、旧版包失效 409、fixture 隔离、清单和第一阶段回归。
- 完整 MSVC/FFmpeg/CEF 构建与相关目标最终重编译通过；原生/应用生命周期/CEF 桥接及第一阶段 UI 主场景、桌面权限专项 **15/15** 通过（33.78 秒）。本轮不重复宣称全项目 CEF 测试通过；前轮已记录的收起栏尺寸断言限制仍保留。
- 真实 HTTPS 探测成功跟随官方 v0.4.0 安装包重定向，读首个 **16384 字节** 后取消；数据未保存、未执行。这验证传输与取消，不等于完整下载和安装成功。
- 专项首轮 14/15：独立验证副本保留旧时间戳，导致 manager 原生对象未重编译，版本失效断言失败。已显式刷新相关源文件时间戳并重编译，复核 20 个后端/测试源文件哈希一致；最终结果为上述 15/15。
- 原生验证继续沿用第一阶段 ignored 独立源码/构建副本及既有 compile-only FFmpeg 审计例外；仓库正式 `verify-ffmpeg.ps1` 未改，无正式发布构建/签名或产品安装包发布。
- 证据 `build/app-updates/phase2-{a-tests.log,b-core-tests-final.log,b-native-build-refreshed.log,b-native-build-last.log,b-native-tests-final.log,source-record.json}`，安装探针见前节。真实产品双版本覆盖升级、真实安装器与播放/账号数据保留、CC 产品 UI 点击验收仍待完成。

## 前端接手完成与产品页面验收（2026-10-17）

用户授权 Codex 接手后，在 CC 本地实现上补齐了以下内容：

- 下载 DTO 接受无任务 idle/cancelled 的空 ID；非空 ID 仍校验正整数字符串，禁止不一致的安装能力。
- 进度沿用现有配色和圆角，只显示简洁进度条及百分比；准备/校验阶段显示不确定进度，校验通过前无安装入口。
- “稍后”仅收起当前安装提示并提供“查看安装”入口，保留 ready；离开设置再回来仍能安装。提醒卡片下载中显示“查看进度”，就绪时显示“查看安装”，实际安装在详情中明确退出后由用户主动点击。
- 安装按钮遵守 canInstall/superseded；下载失败可重试或通过浏览器下载；安装器启动失败保留包和安装按钮。修复存储失败提示及 409 提示与第一阶段回归不一致的问题。
- 保留用户要求的简洁“关于与更新”图标，修正为圆圈加 i；demo 增加下载、校验、失败、就绪、失效和安装启动失败场景。暂时读取失败后继续轮询；旧后端 404 停止轮询，demo 切回支持的状态后恢复。

前端测试 **83/83**、TypeScript/Vite 生产构建通过；原生 CEF 宿主重编译通过。新增五个真实产品页面点击场景全部通过（100.41 秒），第一阶段九个页面回归全部通过（131.42 秒）：

```powershell
ctest --test-dir <native-build> -C Release -R '^cef-updates-ui-smoke' --output-on-failure
```

五个新选择器为 `download / download-network / download-checksum / download-install-failed / download-changed`，仅在现有 smoke + fixture 校验下启动；测试不替换产品 hook、传输或时钟。覆盖卡片启动、进度、取消重试、校验失败禁止安装、稍后保留/恢复、重新进入设置、浏览器备用入口、安装启动失败、版本变更旧包失效及 409、新包安装记录。CMake 根据场景选择原有 available.json 或 download.json，并生成隔离样本。无生产命令或下载协议变更。

浏览器 demo 通过浅色/深色窄窗口截图检查，以及旧后端演示切回下载后的轮询恢复检查；demo 不代替上述真实 CEF 验收。证据位于 ignored `build/app-updates/phase2-takeover-{frontend-tests,frontend-build,native-build,cef-download,cef-regression,visual}.log`，截图为 `takeover-{ready-light,later,ready-dark-narrow}.png`。验证仍使用前述独立源码副本及 compile-only FFmpeg 审计例外，不代表正式发布审计通过。

产品 UI 的惰性样本验收已完成；真实双版本完整产品安装、账号/曲库等数据保留与安装器退出衔接仍按前节要求单独验收。本轮未执行真实产品安装或发布。

### 接手后的补充复核（2026-10-17）

未发现新的功能阻断问题，本轮仅补强测试：原生下载器断言相同 ready 包复用时不再次请求传输，未安装就正常退出会清理已校验文件且不启动安装器，尚无任务时取消保持空 ID 且不创建缓存。前端命令测试改用 downloading/cancelled/installing 的完整返回状态，并覆盖三个新命令对畸形回复的拒绝及原生 409/500 错误透传。

最终前端 **84/84**、TypeScript/Vite 构建通过；新增断言所在的 `update-download` 专项 **1/1** 通过（0.78 秒）。功能源码未变，前述 14 项 CEF 结果继续适用。补充日志为 ignored `build/app-updates/phase2-review-{frontend-tests,frontend-build,download-build,download-tests}.log`。真实产品覆盖升级及数据保留仍属于发布前待验收项。
