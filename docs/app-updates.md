# 版本更新检查：后端接口与前端交接

关联 [issue #18](https://github.com/Miloscovo/Musxi-Player/issues/18)。CC 已接入 frontend，Codex 补齐持久忽略版本及开发版本地 fixture；现有音乐和窗口命令保持兼容。

## 范围

仅检查 `Miloscovo/Musxi-Player` 的 GitHub Releases，支持其中的官方 prerelease。用户点击下载时，系统浏览器打开安装包 URL；用户自行下载和运行安装器。成功打开浏览器不代表下载或安装完成。没有独立内测渠道、任意更新源、应用内下载、自动执行安装器、自动发布或上游 Git 同步。

开发构建也可以手动查询和查看发布说明，但不提供安装包打开操作，不自动提示升级。正式构建才比较当前发布版本与最新可识别官方版本。`--test-app` 只切换已有测试 profile，不改变发布身份。

## CEF 命令

沿用 `{version:1, command, params}`，成功为 `{version:1, result: ...}`，错误使用现有 CEF failure code/message。调用必须位于受信任主页面，非 persistent；托盘、桌面歌词、子 frame 不可调用。

| command | params | success result |
| --- | --- | --- |
| `updates.getState` | `{}` | 完整状态 |
| `updates.check` | `{}` | 立即返回完整状态；通常为 checking |
| `updates.setPreferences` | `{autoCheck: boolean}` | 保存成功后的完整状态 |
| `updates.ignoreRelease` | `{releaseId: string}` | 保存成功后的完整状态 |
| `updates.openDownload` | `{releaseId: string, assetId: string}` | `{opened:true}` |
| `updates.openReleaseNotes` | `{releaseId: string}` | `{opened:true}` |

不接受 URL、路径或命令行参数。ID 是不透明字符串，不要转成 JS number。打开动作必须与后端当前选择匹配；新版变化后旧 ID 返回 409，需要重新读取状态。下载要求 result 为 `update_available`；查看说明允许开发构建或当前已安装版本。

`ignoreRelease` 的空字符串表示清除忽略；非空必须匹配当前 `latest.releaseId`，否则 409 `release_changed`。参数多余、缺失或类型错误返回 400。忽略仅影响前端提醒，不屏蔽检查、版本详情和手动下载，也不会自动忽略其他 release。保存失败回滚并返回 500 `storage_error`。偏好写入同一 `update-settings.json`；旧 version-1 文件兼容加载，缺失或格式错误的 ignoredReleaseId 视为空串。

`check` 在已有任务时复用并返回状态，不启动第二次请求。距离上次开始不足 30 秒或处于 GitHub 限流退避时，返回 429 `check_throttled`；前端随后读 `getState` 获取 retryAt。错误回调不携带完整状态。

## 完整状态

以下为接口形状示例，版本、ID 和时间均为示例值：

```json
{
  "current": {
    "version": "0.4.0",
    "commit": "e7ccf87f2d5587c1fb650e5f21fc186a3500994f",
    "releaseBuild": true,
    "architecture": "x64"
  },
  "source": "Miloscovo/Musxi-Player",
  "includesPrereleases": true,
  "preferences": {"autoCheck": true, "ignoredReleaseId": ""},
  "automaticCheckEnabled": true,
  "checkStatus": "succeeded",
  "result": "update_available",
  "reason": "",
  "latest": {
    "releaseId": "20",
    "assetId": "120",
    "version": "0.5.0",
    "tag": "v0.5.0",
    "prerelease": true,
    "publishedAt": "2026-10-16T00:00:00Z",
    "notes": "更新说明",
    "assetName": "MusxiPlayer-Setup-0.5.0.exe",
    "bytes": 224013707,
    "downloadAvailable": true
  },
  "lastAttemptAt": 1800000000,
  "lastSuccessAt": 1800000001,
  "stale": false,
  "retryAt": 1800000030,
  "canCheck": false
}
```

- 时间 `lastAttemptAt / lastSuccessAt / retryAt` 为 Unix **秒**，0 表示没有记录；转换 JS Date 前乘 1000。`publishedAt` 是 GitHub UTC 字符串，显示时使用用户本地时区。
- `checkStatus`：`idle / checking / succeeded / failed`，只描述本次检查过程。
- `result`：`not_checked / development_build / no_releases / no_newer_release / no_compatible_asset / update_available`，描述最近可用的版本结论。
- `latest` 可以为 null。缺少安装包时 assetId/assetName 为空，bytes 为 0。即使存在 asset，开发构建、版本相等或当前更高时 downloadAvailable 也为 false。
- `stale`：本轮失败但保留旧结果、从持久缓存恢复、或结果距成功检查满 24 小时。不能只凭 checkStatus==failed 就隐藏 latest。
- `reason` 为结构化英文原因，前端负责中文文案；成功或开始时为空。
- `canCheck` 是当前是否可发起新任务。检查过程中可用 getState 轮询，不重复发送 check。
- `automaticCheckEnabled` 综合发布身份、运行模式、偏好和退出状态；开发构建即使 preferences.autoCheck 为 true，也不会自动请求。
- `preferences.ignoredReleaseId` 始终输出，未忽略为 `""`。前端依此字段识别新后端；旧后端缺失该字段时隐藏“不再提醒此版本”按钮。

## CC 接入建议

1. 在应用级 hook 维护共享状态，并在主页面就绪时读取 getState。设置页只消费共享状态，切换设置分类不丢失结果。
2. 自动检查由原生 tick 调度，前端不再另发一次启动检查。启动初期可每 1 秒读取状态约 20 秒，以接收原生延迟检查结果；进入 checking 后每 300～500ms 查询，结束后停止短轮询。为了处理用户偏好开启、缓存退避等晚到结果，主页面可保留约每 30 秒一次的低频状态读取。不要把检查任务与设置组件挂载绑定。
3. 若后端 result 为 update_available，按 releaseId 去重提示；关闭提示后本次运行不再反复提醒同一 release，设置里仍保留下载。开发构建不显示升级提示。
4. 自动失败不弹错误，手动检查失败展示 reason 和重试。失败但仍有 latest 时标注上次成功检查时间，不写“已是最新版”。no_newer_release 可以显示“未发现更高版本”，开发构建显示“开发版本”。
5. notes 仅按纯文本呈现，后端已限制约 8 KiB；先不渲染可执行远程 HTML。完整说明通过 openReleaseNotes 打开。
6. 打开成功仅显示“已在浏览器中打开下载/发布说明”。429 时刷新状态并显示可重试时间；409 release_changed 时刷新详情。旧后端未知命令 404 可降级为“不支持检查更新”，不把所有错误都吞成兼容问题。
7. TS native client、严格返回类型/校验、demo、界面和前端测试由 CC 维护。本轮后端补充未改 frontend。

## 本地 fixture 联调（开发二进制）

可直接使用真实 ui-vue，无需打标签或访问 GitHub。仓库提供 `tests/fixtures/app-updates/available.json`；从源码根目录用 PowerShell 启动现有集成构建：

```powershell
$fixturePath = (Resolve-Path 'tests/fixtures/app-updates/available.json').Path
$hostPath = (Resolve-Path 'build/app-updates/native/src/cef/Release/MusxiPlayerWeb.exe').Path
Start-Process -FilePath $hostPath -ArgumentList @('--test-app', ('--update-fixture="' + $fixturePath + '"'))
```

这是人工查看界面的可见窗口，正常启动不要加 `--cef-smoke`。示例会模拟当前正式版 0.4.0，启动至少 5 秒后自动检查，延迟 1 秒显示 0.5.0 新版提醒。`current.commit` 固定为 `update-fixture`，便于验收识别。启动前退出同类验收实例，避免 CEF 同一个缓存目录的单实例重定向。

只在**实际编译身份**为非 ReleaseBuild 的二进制中允许；判断先于 JSON 读取和 current 覆盖。ReleaseBuild 使用此参数退出码 2；无此参数的程序保持原来的正式检查行为。路径缺失、文件不可读、超过 1 MiB、JSON/字段错误均退出码 2，向 stderr / Windows 调试输出报错，绝不回落到真实更新源。

fixture 格式（顶层 schemaVersion 必须为整数 1，其余字段可省略）：

```json
{
  "schemaVersion": 1,
  "current": {"version": "0.4.0", "releaseBuild": true},
  "releases": [],
  "fetch": {"reason": "", "retryAfter": 0, "delaySeconds": 0}
}
```

- `current` 仅允许 version / releaseBuild；省略值沿用编译身份。version 必须为合法 SemVer，releaseBuild 必须为布尔值。模拟为 false 时依旧不能自动检查或下载。
- `releases` 使用 GitHub API 的原始数组，省略为 `[]`；经过真实 selectRelease，包括版本排序、官方 URL、安装包名称/ID/大小等规则。安装包不匹配会得到 no_compatible_asset，不能通过 fixture 绕开正式规则。
- `fetch` 可为一个对象，也可为 **1～32 项对象数组**。每次实际 fetch 消耗下一项，用完重复最后一项。每项允许 reason / retryAfter / delaySeconds / releases；省略 releases 继承顶层数组，单项数组仍走相同校验。省略 fetch 等价于一次立即成功的默认响应。
- reason 默认为空串，表示成功；可用值为本文“状态 reason”清单。非空产生失败状态；失败继续保留之前成功的 latest 和 stale，模拟错误不访问网络。
- retryAfter 是 **fetch 返回时起的相对整数秒数，0～86400**；0 不附加服务端退避。后端状态 retryAt 仍为绝对 Unix 秒，且继续遵守至少 30 秒的手动检查间隔。
- delaySeconds 是 **0～60 的整数秒数**；等待期间展示 checking，退出可取消，检查粒度约 10ms。fixture 不缩短真实启动延迟、缓存有效期或节流规则。
- 未知字段、未知 reason、不合范围或类型错误直接拒绝。延迟及错误也在加载时先校验全部 releases，避免坏样本悄悄提供错误场景。

常用场景：

| 目标 | 样本改法 |
| --- | --- |
| 有新版、下载、忽略按钮 | 直接用 available.json |
| 未发现更高版本 | current.version 改为 0.5.0 |
| 尚无发布 | releases 改成 [] |
| 缺少安装包 | release 的 assets 改成 [] |
| 开发版本 | current.releaseBuild 改为 false，手动检查 |
| 首次失败 / checking | fetch.reason 改为 timeout / delaySeconds 改为 20 |
| 失败保留旧结果 | fetch 改成 `[{}, {"reason":"timeout"}]`，首次完成后等待 30 秒再手动检查 |
| 版本切换与旧 ID 的 409 | fetch 第二项提供更高版本的 releases，等待 30 秒再检查，然后发送旧 ID |

更新模块的设置/忽略/缓存在 fixture 模式中全为内存状态，既不读也不写 update-settings.json；退出即清空。音乐云服务启动、曲库/播放历史恢复及保存、原生窗口偏好加载和保存也关闭。CEF 自身仍需写独立 `cef-update-fixture-cache` 和 `cef.log`（位于选中 profile），因此这里不承诺整个 Chromium 进程零磁盘写入。

点击下载或说明只记录官方 URL，不启动系统浏览器。Windows 调试输出包含 `Musxi update fixture recorded URL:`。只有 fixture 模式受信任主页面可用的非 persistent 命令 `updates.fixtureDiagnostics {}` 返回：

```json
{"fixtureMode":true,"fetchCount":1,"openedUrls":["https://github.com/Miloscovo/Musxi-Player/releases/tag/v0.5.0"]}
```

记录仅驻留内存；非 fixture 后端返回 404，桌面歌词/托盘仍返回 403，不接受参数、URL 或路径。真实 UI smoke 通过现有 transport 调用它，断言点击是否产生了预期 URL。

`cef-updates-fixture-smoke` 使用独立 HTML 测原生桥接。2026-10-17 用户授权 Codex 接手后，另已添加真实 ui-vue 的 `cef-updates-ui-smoke` 和八个后缀场景，卡片和设置操作都使用产品 React/hook/CEF/Manager，无前端 demo、假状态、transport 替换或加速时钟。

```powershell
ctest --test-dir build/app-updates/native -C Release --output-on-failure -R '^cef-updates-ui-smoke'
```

这些 CTest 仅在开发身份、启用真实产品 UI 构建时注册；生成 fixture 到构建目录，不改源码样本。`--cef-updates-ui-smoke=<场景>` 必须与 `--cef-smoke --update-fixture=<路径>` 一起用；未知场景、preview、独立 HTML smoke 或 desktopSmoke 等冲突标记返回 2。支持 available/current/empty/missing/development/checking/failed/stale/changed。UI 仍从 ui-vue 加载，测试选择通过受信任主页面的查询参数传递，fixture 仍由原来的开发身份验证入口加载。

每次点击前滚动目标到可见区域并验证中心命中、未禁用且不在 inert 区域，再执行 DOM click；断言 React 真实渲染结果和后端状态/URL 记录。这覆盖实际页面事件与桥接链路，不冒充操作系统鼠标输入、肉眼观感或真实下载安装。普通 smoke 的 20 秒退出保护保持不变，专用 UI smoke 最多 90 秒；失败保留旧结果和 409 场景等待真实 30 秒节流及正常轮询。

界面细节：开发构建自动检查开关显示关闭并禁用（后台 preferences.autoCheck 仍保留用户偏好），fixture 源码标识显示 update-fixture。卡片保持简洁的版本号、预发布标记和操作按钮；日期、大小、详细说明在设置页查看。

详细范围与结果见 [界面验收清单](../版本更新功能-界面验收清单.md)。

## 后端行为

- 单次启动延迟至少 5 秒后考虑自动检查，进程内最多一次自动任务；手动检查也会消耗本次自动机会。
- 持久成功结果 24 小时内不自动重新检查；上次尝试不足 1 小时或尚在服务端退避期时延后，直到条件满足再考虑。用户可手动检查，仍受 30 秒及服务端退避保护。
- 设置和有限长度缓存保存到当前 profile 的 `update-settings.json`，采用临时文件原子替换。缓存重新走 release/URL/安装包验证；不保存任何登录信息。损坏缓存不应提供任意 URL。
- 网络与音乐平台队列分开。WinHTTP HTTPS 请求异步回调，工作线程等待带取消检查；12 秒总预算、各阶段 1.5 秒超时，取消等待检查粒度为 50ms。请求关闭后保留回调拥有的状态与缓冲区，避免退出期间访问已释放数据。
- 使用系统代理；不自动发送 Windows 凭据，不发送音乐账户信息。禁用自动重定向与 cookies，不绕过 TLS 校验。
- GitHub 非 200 状态不算“无更新”，限流按响应头退避并限制为最多 24 小时。单次响应最多 1 MiB，最多读取前 100 条；若出现分页 Link，保守返回 `release_list_incomplete`，不把不完整列表说成最新版。未来发布量增加时可扩展有界分页。
- 先选择最高 SemVer release；若最高新版缺少适用安装包，返回 no_compatible_asset，不静默回退到较低版。GitHub prerelease 标记与版本后缀分别保存/比较，正式版高于同版本 prerelease；build metadata 不改变优先级。
- 当前安装包白名单：`MusxiPlayer-Setup-<version>.exe`、`MusxiPlayer-Setup-<version>-x64.exe`；兼容 v0.1.0 的 `MusxiPlayer-0.1-Setup-x64.exe`。依照项目当前 Windows x64 发布约定，不推断未知资产架构。匹配项必须唯一、已 uploaded、有正数且小于 2 GiB 的 size，以及精确匹配官方仓库的 HTTPS 下载地址。
- release 页面、下载 URL 均由已验证 tag/assetName 生成，打开时再次限制为官方仓库路径。安装器签名、实际下载文件哈希、安装成功与用户数据保留不属于本轮浏览器交接已经验证的能力。
- CEF smoke 模式禁用真实网络/浏览器打开及 profile 持久化；纯原生测试使用注入的网络、时钟、浏览器替身。

## 错误约定

CEF 操作错误：400 `invalid_params`；403 由现有 frame/托盘/桌面歌词权限检查产生；404 `unknown_command`；409 `updates_unavailable / shutting_down / release_changed`；429 `check_throttled`；500 `storage_error / browser_open_failed / internal_error`。

状态 reason：`network_error / timeout / cancelled / rate_limited / http_error / invalid_data / response_too_large / release_list_incomplete / network_disabled / internal_error`。storage_error 表示偏好未保存，前端不要乐观保留开关。网络结果缓存写入失败不影响已经查到的结果。

## 发布构建

默认构建为 development，不提供自动升级提示。发布者明确使用：

```powershell
./build.ps1 -CefRoot <CEF> -FfmpegRoot <FFmpeg> -ReleaseBuild
# 或完整打包时：
./package.ps1 -CefRoot <CEF> -FfmpegRoot <FFmpeg> -ReleaseBuild -RequireCleanSource
```

这会传递 `MUSXI_RELEASE_BUILD=ON`，要求 Git 可用、工作树干净、HEAD 等于 `v<PROJECT_VERSION>` 标签指向的 commit，且 CMake / frontend package.json / 主安装脚本 AppVersion 一致。检查在 configure 和每次构建更新库前执行，避免缓存了旧发布身份。标签仅用于确认构建身份，不代表二进制已经上传 Release；最终发布仍由维护者完成。

源码 ZIP 没有 Git 信息时可做开发构建，commit 显示 unknown，不能开启 ReleaseBuild。源码副本缺少标签时不会猜测发布版本。无新增第三方生产依赖，WinHTTP/ShellExecute 为系统 API。

## 真实更新 UI 验证（2026-10-17，Codex 接手）

- 前端 66/66 通过，TypeScript/Vite 和原生 cef_host 构建成功。真实更新 UI 九种场景及应用/桥接/桌面相关回归通过，详见界面验收清单与工作日志。
- 有新版流程覆盖自动提醒、查看跳转设置、两处下载入口、发布说明、忽略/恢复、关闭后去重、自动检查切换、浅/深主题和 800 DIP 窄窗口；其余场景覆盖无新版、空发布、缺安装包、开发构建、checking、首次失败、stale 和真实 409 刷新。
- 修正开发构建自动检查开关显示与实际行为不符、fixture 标识被显示为“未知”；未更改正式版本比较、节流或下载行为。
- 构建日志 `build/app-updates/native-build-update-ui.log`；测试日志 `ctest-update-ui-{initial,scenarios,final}.log`。原有主窗/Unicode 收起栏断言限制仍见下节，本轮不把专项通过写为全项目测试通过。真实跨版本覆盖安装和用户数据保留仍未验收。

## 前轮补充验证（2026-10-16，CC 前端接入后）

- Core **8/8**，前端 **66/66** 通过；完整 MSVC/FFmpeg/CEF 构建成功，同时重新构建并部署当前 CC 前端，TypeScript/Vite 通过。本轮未修改 frontend 源码。
- 全量 CTest 首轮 **24/28** 通过（86.31 秒）。新增 update-fixture / updates-fixture-interface / cef-updates-fixture-smoke 以及扩充后的 app-updates / updates-interface / cef-updates-smoke 全通过。
- 三项原有主窗/测试窗/Unicode smoke 仍在收起栏尺寸断言失败，与前轮记录一致。另有 cef-tray 首轮报 Desktop resize stole focus，单独复核 **通过（20.13 秒）**；保留首轮及复核结果，不把全量报告写成全部通过。
- 覆盖忽略版本保存/重启/清除、旧版 ID/新版切换、旧文件兼容、错误保存回滚；fixture 覆盖真实版本/资产验证、顺序成功和失败、退避、延迟、取消、格式/大小/类型错误、正式身份拒绝、已有 update-settings.json 不被读取或改写、记录 URL 和 CEF 权限。
- 构建及测试继续采用 ignored 的独立验证副本；仅该副本保留原有 compile-only FFmpeg 审计脚本，仓库正式审计未变。记录：`build/app-updates/native-build-cc-backend.log`、`ctest-cc-backend.log`、`ctest-cc-backend-tray-recheck.log`。
- 真实更新产品 UI 全场景点击断言和跨发布版本覆盖安装仍未完成；未下载/安装更新、未生成安装包、未提交/推送代码。

## 首轮后端验证记录（CC 接入前）

2026-10-16，分支 `feat/app-update-check`，基于 upstream/main `e7ccf87`：

- Core 配置/编译及 CTest **7/7** 通过，包含更新规则、缓存、节流、取消、自动调度、设置失败回滚、浏览器失败和构建身份测试。
- 完整 MSVC/FFmpeg/CEF Release 构建通过，使用现有上游前端；TypeScript/Vite 生产构建也通过。新更新 UI 尚未接入。
- 全量 CTest **22/25** 通过（55.17 秒），四项新增测试 `app-updates / update-build-info / updates-interface / cef-updates-smoke` 全通过。真实 CEF fixture 验证主页面命令、参数校验、persistent 限制、桌面歌词拒绝全部更新命令、smoke 网络禁用与退出。
- 三项失败为 `cef-app-smoke / cef-test-app-smoke / cef-unicode-smoke`，均停在原有 `Player collapse rail is visible, blocked or not across the dock edge`；此前日志已记录该上游断言在本机 150% 缩放的失败。本轮未修改该断言或窗口收起功能，不能宣称全部 CEF 测试通过。
- 只读真实 WinHTTP 请求识别官方 `0.4.0 / MusxiPlayer-Setup-0.4.0.exe`；一次真实在途请求取消返回 `cancelled`，约 66ms。未打开真实下载链接、未下载或安装程序。
- PowerShell 三个修改脚本语法解析、Git diff 空白检查通过；frontend 源文件未改。
- 验证采用 ignored `build/app-updates/{source,native}` 独立副本，沿用本机固定来源的 compile-only FFmpeg SDK，逐项核对 SDK 及正式 CEF/音频运行库哈希。替代审计脚本仅在验证副本，仓库正式 `verify-ffmpeg.ps1` 未改。此副本无独立 Git 身份，显示 development / commit unknown。
- 首轮新增 CEF fixture 未开启现有桌面 smoke 标记，权限报告因而不可用；补齐测试启动标记后完整重跑，新增测试通过。首轮及最终记录分别在 `build/app-updates/ctest-initial.log`、`ctest-final.log`，完整构建记录为 `native-build-final.log`。

前端 UI、实际系统浏览器下载动作、两个发布版本之间的覆盖安装和用户数据保留尚未实测。本轮未创建安装包、提交代码、推送或创建 PR。
