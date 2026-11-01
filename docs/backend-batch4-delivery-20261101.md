# 第四批后端交付与验收边界（2026-11-01）

本批需求为 [第四批需求单](backend-requests-20261101-4.md)，前端调用见 [接口合同](backend-batch4-contract.md)。当前代码是未发布的 `0.2.0-alpha01`；没有提交、推送或发布。使用者之后已重新交还设备并授权继续验收；最新会员与真机结果见 [设备追加验收](backend-batch4-device-acceptance-20261101.md)。下文 12:04 的检查和旧设备材料保留其历史边界，不代表最后一次构建。

随后按“我的”页需求追加缓存维护，后端已完成并通过 663 项测试，前端接线与本轮整应用未通过项见 [缓存追加交付](backend-cache-delivery-20261101.md)。折射已确定，CC 将在第四批存档后升级 Kotlin 2.3.21、引入 Backdrop 2.0.0-alpha03，升级后另跑全量和真机，不沿用本页旧包结论。

## 已实现

- 酷狗基础取流已确认 permission 时，授权链 IOException 不再覆盖该分类；会员 NO 不发授权签名请求。保留第三批音质菜单的逐档失败隔离、60 秒账号代次缓存及并发合并。
- 自动换源和放宽时长两项偏好持久化。严格匹配同时满足标题、完整歌手、无版本歧义和 3 秒/2% 时长上限；放宽只把时长上限改为同时满足 15 秒/8%，翻唱、现场、未知歌手仍不能自动选择。按时长差、同专辑排序；实际播放后才发成功确认。
- 候选在两个平台间并行准备；只有单曲权限/不可用/解析失败才尝试该平台下一候选，恢复总尝试数不超过三首。账号和限流等失败不连续请求。准备结果提交前复核票据与账号代次，播放器复用能力，避免二次取流。提交替代源会清除原音源遗留的音质名称。
- 下一首能力预取遵守实际顺序与随机顺序，节流至少 15 秒；缓冲时保留准备，暂停或队列/偏好变化取消。只准备能力，不下载整首音频。7 天内真正播过的映射可从当前原账号播放日志恢复，重新取流、重新核对来源账号和匹配规则；URL 不落盘。
- 统一 `AppSettings` 与持久化命令，接管主题、透明、换源、默认音质和歌词显示偏好。写入成功才发布 StateFlow；兼容旧默认音质和逐平台覆盖。MainActivity 已跟随主题，视觉设置由 CC 接入。
- 按已确定的 v0.2 范围新增三平台歌手/专辑歌曲接口、完整对应的可选歌手 ID 和专辑 ID，并兼容旧曲库/播放日志。无合法 ID 只影响跳转，不丢弃普通歌曲。歌单内搜索只查当前账号缓存页，明确返回已加载数量和完整性。

## 本地验证（12:04 历史冻结点）

12:04 冻结本地检查证据。全部 1353 项 JVM 测试通过，0 失败、0 跳过；其中后端六模块 649 项，app 704 项。之前 `SheetBehaviorTest` 的两项超时在 CC 后续代码上已消失。

| 模块 | JVM 测试 | Lint 错误 / 警告 |
| --- | ---: | ---: |
| core | 335 | 不适用 |
| accounts | 25 | 0 / 3 |
| platform-kugou | 104 | 0 / 3 |
| platform-qq | 58 | 0 / 6 |
| platform-netease | 40 | 0 / 5 |
| playback | 87 | 0 / 3 |
| app | 704 | 2 / 95 |

`build.ps1 -Test -Lint` 因 app 的两项 `NoCollectCallFound` 报错失败，**整应用检查尚未全绿**。位置为 `app/src/main/kotlin/tech/musxi/player/ui/nav/NavStackHost.kt:70` 和 `app/src/debug/kotlin/tech/musxi/player/lab/NavigationLab.kt:77`。两处均把 PredictiveBackHandler 的 events 委托给 `handleBack`；正式 `NavStackState.handleBack` 内已有 collect，仍需 CC 在其导航代码范围核实并处理 Lint。未替其改 UI、实验页或添加基线忽略。后端五个 Android 库合计 0 错误/20 警告，全应用六模块合计 2 错误/115 警告（另有 app 两项提示）。

随后仅在电脑执行 `:app:assembleDebug :app:assembleDebugAndroidTest` 成功，没有安装、启动或操作手机。冻结 APK 为 debug 开发包，**尚未上机**：

```text
app-debug-local-checked.apk
27395876 bytes
SHA-256 b4e97f4f8055e607542f4d2546e094cbc5dc4f8405a470cab803ac9ff1a8e04f
```

详细计数、两处 Lint 位置、日志、XML 报告、源码散列及测试 APK 摘要见 `.local/artifacts/backend-batch4-20261101/README.md` 与 `final-local-checks.json`。共享工作区后续仍可能变化；这些结论只对应本次冻结材料。前三批的 988/1056/1059 和本批中途的 646 等数量均为历史结果，不能替代本次结论。

构建统一使用 `.local/tmp/build.lock`，没有另起独立工作树，也未修改 CC 的 `ui/`。当前 Kotlin 仍为 2.2.21；折射依赖未整合。日后实际升级 Kotlin 或引入该依赖，须对整合后的代码重新全量检查和真机回归。

本批未改第三批的 release 签名与更新验证路径，未再次生成一次性密钥；正式发布仍按 [签名构建说明](release-build.md)，由负责人保管自己的密钥。

## 会员处理与最新构建

账号是会员时先播放原平台，只有当前歌曲确认不可用才换源。真实酷狗账号现在返回 YES，《晴天》已直接在酷狗播放，没有触发替代源。补修同一进程内的会员更新：返回前台后按一分钟节流刷新，显式 `refreshMembership` 可立即刷新；会员状态变化立即失效酷狗音质菜单缓存。没有改账号、购买会员或模拟会员资格。

12:38 本地检查为 1357 项 JVM 全通过（后端 653、app 704，accounts 从 25 增至 29），0 失败/跳过。后端 Lint 仍为 0 错误/20 警告；全应用仍是上述两处前端 NoCollectCallFound，合计 2 错误/115 警告，尚未全绿。debug 和 androidTest 本地构建成功。新冻结证据位于 `.local/artifacts/backend-batch4-20261101/member-final/`；APK 27395893 bytes，SHA-256 `b542fcb82253a83d5308a704524b7771251905e76b236f5b556fad003ab33678`，已覆盖安装并用于设备追加验收。

## 设备结论与剩余边界

设备材料目录为 `.local/artifacts/backend-batch4-20261101/`。以下已包含用户重新交还设备后的结果，完整口径见 [设备追加验收](backend-batch4-device-acceptance-20261101.md)。

| 场景 | 证据与结论 |
| --- | --- |
| 会员优先原平台 | `member-final12.json`：酷狗会员 YES，《晴天》实际播放，2538 ms，没有进入换源。 |
| 无权限后严格换源 | `restart-mapping13.json`、`prefetch-retry16.json`：QQ 真实 permission 后严格换到酷狗，3410 / 3400 ms，实际播放确认；较早同日包为 1922 ms。 |
| 重启后的映射复用 | `restart-mapping13.json`、`isolated-live-trace8.txt`：新进程首次换源读取持久日志，source_search_ms=15、cache=true，仍先尝试原平台。 |
| 下一首能力预取 | `prefetch-retry16.json`、`isolated-live-trace8.txt`：确认 prefetch_ready 后，Next 命令到实际播放 208 ms；包含测试框架等待的整段调用 2229 ms。 |
| 三平台目录与酷狗封面 | `catalog-artwork-final-device11.txt`：三平台歌手首/次页各 30 首且无重复，专辑读取成功；400/480/800 均为实际对应尺寸。此完整通过属于会员刷新前的同日包。最新包 device17 只完成酷狗/QQ，网易等待过长后停止，封面未执行。 |
| 酷狗无会员方向 | `batch4-kugou-switch-device.json`：优化前酷狗→网易 9038 ms；当前真实账号已有酷狗会员，新版本缺少该方向的直接复测，不冒用 QQ→酷狗结果。 |

这些真实换源样本满足五秒目标，匹配规则未放宽；单设备少量歌曲不能代表所有网络与版权场景。慢网络的候选准备阶段最多等待 8 秒，首个成功后给另一平台 150 ms 收集窗口，不是总开播上限。仍待 CC 处理两项导航 Lint；后续 Kotlin/折射整合须另做整包回归，不重复运行本轮已通过的性能检查。
