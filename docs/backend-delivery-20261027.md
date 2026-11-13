# 2026-10-27：队列补全、音质、输出设备

> **状态：日期交付与验收（2026-10-27）。** 接口变化需结合现行专门合同理解，测试和真机结果只对应当次包；当前状态见 [当前开发接续](next-session-handoff.md)。

晚间后续：QQ/网易云换源提速已完成，严格合格项立即选择、独立并发搜索与成功映射复用的规则、当前 APK 和实测见 [换源提速与验收](source-switch-speed-20261027.md)。本文末尾 639 项测试及 13–15 秒为提速前包的历史记录。

本轮对应 [后端任务单](backend-requests-20261027.md)。改动保留当前工作区已有的前端和平台修复；不提交、推送或发布。原有日期文档中的标准档、整表队列和输出占位说明是当时实现与历史验收范围，本增量补充当前接口。

## 队列先播后补

正式歌单入口把已显示的连续歌曲前缀传给 `playPlaylist(playlist, startSong, knownSongs)`。QQ/酷狗优先提交所点歌曲所在的 30 首窗口，网易云在读取 ID 列表后提交附近 50 首，其余仍按需补元数据。首批未成功前保留旧播放；不再先停止旧歌曲等整表读取。

`queueHead` 返回 `PlaylistQueueHead(queue, remainder)`；`remainder.before/after` 保持显示顺序，`loadNext` 串行拉取后续页，`cancel` 拒绝迟到回调。酷狗保持最新在前；后台 100 首分页对齐其 API 边界，再跳过已显示前缀，避免 30/100 页大小交界的错位。稳定歌单且未编辑队列时，补全后的顺序与整表读取相同，不按歌曲 key 去重。

`PlaybackConnection.enqueueBatch(songs, at, batchId, queueEpoch, callback)` 支持 HEAD/TAIL。服务一次校验整批、一次 `addMediaItems`、一次强制发布（revision +1），总上限 20,000。HEAD 插入保留当前槽位 URI、歌曲、进度、选源与重试状态，外部索引随时间线移动；checkpoint 使用新索引。

邮箱保持 latest intent wins。批次与用户操作撞车时有界重试同一 `batchId`；服务对同一队列 epoch 的已应用批次返回成功，不重复插入。普通拖动、删除、插入沿用 expectedRevision，补全按 epoch 验证，因此不会用旧快照覆盖用户编辑；补全导致 revision 改变时，旧手势按原规则拒绝。清空/STOP、换歌单、换账号取消剩余补全。旧 epoch、旧账号、跨平台、超量批次整批拒绝。

该实现消除了“整表读完才开始取流”的等待；一秒开播仍受平台取流、网络、CDN 与前台资格影响，需要按真实账号和歌单实测。

## 音质

公开入口为 `PlaybackConnection.qualities(mediaKey, callback)`、`setQuality(id, callback)` 和 `PlaybackState.qualityName`。查询针对当前实际来源及账号代次；队列槽位/来源变化后拒绝旧结果。查询走普通请求通道，避免批量探测档位占用当前播放取流的 AUDIO 通道。

| 平台 | 请求 vocabulary（只列平台实际确认成功的档位） |
| --- | --- |
| 酷狗 | `kugou:128`、`kugou:320`、`kugou:flac` |
| QQ | `qq:standard`（M500）、`qq:high`（M800）、`qq:lossless`（F000） |
| 网易云 | `netease:standard`、`netease:higher`、`netease:exhigh`、`netease:lossless` |

ID 仅作后端请求标识。列表来自该歌曲本次官方返回；试听、身份不符、受限或未确认可取流的档位不列出。网易云按实际码率映射，并要求 FLAC 类型才标为无损，不接受超过请求上限的响应。UI 只显示后端中文名称，未确认时为空。

切换先在 IO 线程取得、验证新流，准备失败不动当前播放器。提交时只替换当前媒体源的缓存标识以强制重新打开，保持槽位 URI、队列顺序/epoch、当前位置和 playWhenReady；新源 READY 后保存该平台偏好。打开/解码失败或超时则恢复原票据与原档位；换歌、STOP 或换账号丢弃未完成切换。偏好用于后续取流，不可用时仅对权限/不可用错误向下尝试，网络/账号错误保留真实失败，不跨平台套用 ID。

## 输出设备

`PlaybackConnection.outputs` 是可观察的 `PlaybackOutputs`；`availableOutputs()` 返回设备列表，`PlaybackState.outputDevice/outputInferred` 给出当前设备与是否为推断。设备 ID 是本进程不透明 UUID，不暴露 MAC。设备监听位于服务，150ms 合并回调，仅状态变化发布；销毁时反注册。监听不发 play/pause，也不改 playbackWanted。

检测使用 AudioManager 和 MediaRouter2 可见路由。API 33+ 尝试读取媒体 AudioAttributes 的实际路由；拿不到时按蓝牙 > 有线/USB > 远程 > 扬声器推断，标记 inferred。API 32 及以下也使用该推断。远程设备只展示系统/路由提供方实际暴露的路由，不新增投屏 SDK 或扫描权限。

| 版本 | 选择方式 |
| --- | --- |
| Android 15+（API 35+） | 系统公开为 transferable 的路由可直接请求转移；回调确认后才报告成功，拒绝/8 秒超时返回固定失败类别 |
| Android 14（API 34） | 打开 `MediaRouter2.showSystemOutputSwitcher()` |
| Android 11–13 | 尝试系统/OEM 媒体输出面板；不可用时转音量面板/蓝牙设置 |
| Android 8–10 | 系统可解析的音量面板或蓝牙设置 |

`selectOutput(id, callback)` 只有当前已选或实际确认转移成功才回成功；不可直接切换时返回 `OutputSelectionException(SYSTEM_SWITCHER_REQUIRED)`。UI 可调用 `openOutputSwitcher(activityContext)`；`outputSwitcherIntents()` 也提供降级 Intent 候选。面板由可见 Activity 启动，系统不提供媒体切换器时最终打开蓝牙设置。

不新增蓝牙、位置或其他危险权限。Android 12+ 未授权 BLUETOOTH_CONNECT 时蓝牙名称为 null，页面显示“蓝牙设备”；有线/USB 等显示可取得的产品名。拔耳机依旧遵守 Media3 NOISY 暂停；系统转移若另发 NOISY 也保留系统暂停，不强行自动恢复，该行为须在实际设备切换时核验。

## 验证边界

针对性 JVM 测试覆盖分页衔接/顺序/重复项、批次原子性与覆盖重试、音质准备/提交/回滚及迟到来源结果、平台档位解析、输出类型/名称降级/去抖/选择确认与系统降级。酷狗另有 755 首夹具，直接对比整表读取后反转和 30 首前缀接 100 首补全分页。

本轮最终 `build.ps1 -Test -Lint` 通过：638 项 JVM 测试，0 失败、0 错误、0 跳过；六模块 Lint 0 错误、94 警告（app 83、accounts 1、playback 3、酷狗 1、QQ 1、网易云 5）。debug APK SHA-256：`8909425D51A5C124FE51B1F96B56AADC172A0E25DFFEF43F89D70CD6BB59F7F1`。

`:app:assembleRelease` 通过；release APK 的 DEX 与合并清单均未包含 `PlaybackToolsValidationRunner`、`ValidationReceiver`、`DebugMusxiApplication`、`ValidationFocusActivity`。本轮 Manifest 无新增权限，未添加依赖。最终日志与汇总保留在忽略目录 `.local/artifacts/backend-20261027/`，不作为发布附件。

下午只做旧包只读检查；用户晚间授权后已覆盖安装并完成本包队列、QQ/网易云原源音质切换、手机外放和系统输出面板验收，发现并修复内置听筒误列为媒体候选。最终包 639 项 JVM 测试通过、Lint 0 错误/94 警告，debug SHA-256 为 `72A248D4629E92AC626D5AE1D2BA8AFCFFD9E35A4097EE7389ABCEBE52E5FB9E`。具体耗时、原始证据、脚本修正及未覆盖边界见 [晚间真机验收](backend-acceptance-20261027.md)。真实听音及外接设备测试按用户本轮条件保留待测；QQ/网易云所选歌换源时约 13–15 秒开播，不能宣称全平台达到一秒。

debug 固定步骤 `tools_snapshot` 只记录当前连接、播放布尔值、队列数量、音质名与输出类型数量；不请求音频、不更改账号/收藏/队列、不记录设备名/歌曲标识/URL。入口受 DUMP 权限限制，文件位于 debug 源集，release 不包含。
