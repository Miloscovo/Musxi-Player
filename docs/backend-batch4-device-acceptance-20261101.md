# 第四批会员与真机追加验收（2026-11-01）

使用者重新交还手机后，继续本批验证。设备为 PJF110 / Android 16；只覆盖安装开发包、读取现有账号与目录、控制 Musxi 播放，没有退出/更换账号、清数据、购买会员或修改平台收藏。验收总览见 [第四批交付](backend-batch4-delivery-20261101.md)。

## 会员优先与修复

本机现在读到酷狗 YES；《晴天》真实取流成功，因而应留在酷狗。早期脚本预设必须返回 permission，在该账号状态下已不适用，首次检查在播放前停止，不能记为换源功能失败。脚本现以实际取流结果分别验证“可播留原平台”和“无权限后严格换源”。

会员 NO 只跳过酷狗授权重试，基础取流仍执行；YES/UNKNOWN 可尝试授权链。已有成功映射仍等原平台失败后才查询，不抢占可用原音源。会员也可能遇到版权或需单独购买的歌，这时依然按真实结果换源。

发现同一进程原来只在登录/重新验证后刷新会员，已补返回前台的刷新，按平台/账号代次节流 60 秒并合并在途请求。显式 `accounts.refreshMembership(platform)` 不受时间节流限制；读取失败为 UNKNOWN，不注销账号。会员变化立即让酷狗音质菜单缓存失效，不等原 60 秒 TTL。

## 包与本地检查

最终应用 APK：27395893 bytes，SHA-256 `b542fcb82253a83d5308a704524b7771251905e76b236f5b556fad003ab33678`。测试 APK：1143089 bytes，SHA-256 `a8e40a98a96d9cbca252d6b2960cc8ccd170263228dcbe6b64f941989fa06679`。

收尾另在 AndroidTest 追加 `restoreLatestQqQueuePaused`，仅装入实时保存的队列、保持暂停，不播放或修改设置。该测试驱动单独保留为 `restore-final-androidTest.apk`：1143963 bytes，SHA-256 `57c959a6f8cf6a9300f4349beae47ee0d1b18bea8886a72303449db1246254b4`；编译日志及测试源码分别为 `restore-final-build.log`、`restore-final-BackendBatch4DeviceTest.kt`。仅安装新测试驱动，未安装此次编译生成的应用 APK，也未覆盖上述冻结性能测试包。

`.local/artifacts/backend-batch4-20261101/member-final/` 保存 APK、源码散列、全量日志及 XML 报告。1357 项 JVM 测试全过，其中后端 653 / app 704；后端 Lint 0 错误/20 警告。整应用检查仍因 CC 导航的 `NavStackHost.kt:70` 和 `NavigationLab.kt:77` 两项 NoCollectCallFound 失败，未改 UI 或加忽略基线。

## 真实样本结果

计时从测试发起队列跳转到服务实际播放确认，包含调度；选样搜索和预探测耗时单列在 JSON。两个场景均通过正式服务与 MainActivity，未注入权限错误。

| 场景 | 结果 | 证据（均在本批材料目录） |
| --- | --- | --- |
| 酷狗会员保留原平台 | 《晴天》在酷狗实际播放，未进入换源；原冻结包 679 ms，会员刷新包 2538 ms。 | `member-original9.json`、`member-final12.json` |
| QQ 确认无权限 | 严格匹配到酷狗原唱，实际播放确认；原冻结包 1922 ms，会员刷新包重启复用 3410 ms，最终单曲队列复测 3400 ms。 | `qq-fallback10.json`、`restart-mapping13.json`、`prefetch-retry16.json` |
| 重启后成功映射复用 | 首轮进程 31445 搜索 cache=false；新进程 3726 的首次换源读取日志映射，source_search_ms=15、cache=true，仍先经历 QQ 的真实 permission。 | `isolated-live-trace8.txt`、`restart-mapping13.json` |
| 三平台目录与大封面 | 三平台歌手页第二页均 30 首，与首屏无重复；专辑实际页均成功。酷狗同主机 400/480/800 均 HTTP 200 且真实尺寸正确。 | `catalog-artwork-final-device11.txt`、`catalog-final11.json`、`artwork-final11.json` |

以上换源样本都在五秒内，但账号现有酷狗会员，无法把当前 QQ→酷狗的结果当作“酷狗无会员→其他平台”新版本的直接验证。后者只有优化前 9038 ms 的历史样本；没有为测试去改真实会员或登录状态。只有单设备、少量歌曲，不能承诺所有网络与版权场景五秒。

## 下一首与数据恢复

首轮预取成功后切到下一首为 291 ms。后来插入下一首的时间碰到 15 秒预取节流，未赶上预取的切歌为 3025 ms；日志中该次切歌后重新换源，因此不能记作预取命中。

最终单曲队列复测已通过：先播单曲，再只插入一个实际下一首。进程 6424 在 12:42:29.279 记录 prefetch_ready，12:42:38.845 收到 Next，12:42:39.053 实际播放，命令到播放为 **208 ms**，没有再次换源搜索。JSON 的整段 nextElapsedMs 为 **2229 ms**，包含测试框架在发命令前后等待 UI 空闲的调度时间；两种口径分别保留，不把 208 ms 冒充整个测试调用耗时。首次换源为 3400 ms、strict、playbackConfirmed=true，设置、队列/索引/位置和播放模式全部恢复并暂停。证据为 `prefetch-retry-device16.txt`、`prefetch-retry16.json` 与 `isolated-live-trace8.txt`。

最初从旧 diagnostics 读到的 QQ 7 首/49.8 秒是缓存，不能当作当前状态。实际账户日志为 QQ 单曲、索引 0、进度 0；测试开始后以实时日志保存和恢复，未用旧缓存或历史 759 首快照覆盖。酷狗原保存单曲及 174191 ms 位置也在相关测试后恢复。各次 JSON 记录队列/索引/进度/播放模式、设置和暂停恢复结果；这些都是当次结束状态。

有一次最终测试框架挂在启动阶段，未进入测试方法，主代理终止后仅重跑一次；该终止输出保留为 `prefetch-final-device14.txt`，不记为验收通过，不使用其旧结果 JSON。

最新会员刷新包的目录追加复查 `catalog-artwork-final-device17.txt` 在酷狗和 QQ 的首/次页、专辑成功后，长时间等待网易返回，已主动终止，未再重复；`catalog-partial17.json` / `catalog-final17.json` 只包含两平台，不能算三平台完整通过，且该次没有运行封面检查。三平台完整通过仍限于先前未含会员刷新改动的同日包（device11）；最后一包的网易目录及封面未追加确认，不把旧 JSON 复制品记成新结果。

## 最终交还状态

终止目录复查和清理测试驱动后，重开的服务队列曾为空，保存的 QQ 单曲仍在。解锁手机后，单独运行 `restoreLatestQqQueuePaused`，`restore-final-device18.txt` 为 OK (1 test)。`post-acceptance-state.json` 已从本次测试新生成的报告重新读取：QQ 队列 1 首、索引 0、位置 0，顺序一致，playing=false、playWhenReady=false，设置未变。没有恢复旧 7/759 首快照。

收尾再次从手机 base.apk 核对 SHA-256，仍为上述 `b542fcb8…` 已验收包。保留无桌面入口的 debug 测试驱动，避免卸载再次结束目标进程；日志采集和测试已停止，手机保持暂停，可正常使用。全应用仍有两项 CC 导航 Lint 待处理；最后一包网易目录/封面及酷狗无会员方向的限制保留，不影响已取得的会员、QQ 换源、重启映射和预取证据。
