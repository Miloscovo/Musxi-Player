# v0.2 后端集中实机验收（2026-11-03）

状态：本轮设备操作与恢复完成。对象是当前源码构建的开发包，结果按下表限定；正式发布包、跨系统和长待机仍需专项验收。当前接续见 [开发接续](next-session-handoff.md)。本轮没有修改产品前端或音乐业务实现，新增和修正的是实机验收测试。

## 包与环境

- 源码基线 `70778e98699da8cee3b7cfc000266a3ddaa4eb1b`，另含本轮 androidTest 和文档改动。
- 手机 PJF110，Android 16 / API 36，补丁 2026-07-01；仅验证这一台设备。
- 包名 `tech.musxi.player.dev`，`0.2.0` / versionCode 5。手机原有 `.dev` 包保留数据覆盖升级，签名与原安装一致。手机未安装正式包 `tech.musxi.player`，没有跨包搬运账号。
- 主 APK SHA-256：`f7f4468af72791e29864b73fa41dd7a8e767fb86270bcd9b234ea08b53a4dc2f`。本轮测试驱动调整后主 APK 哈希始终一致；各阶段测试 APK 单独记录，不能混用测试结果。
- 本地证据根为 `.local/artifacts/v02-device-20261103/`。首次完整构建摘要在 `.local/artifacts/verification/20261103T023333589Z-14432/`，1599 项 JVM 测试通过、Lint 0 错误 / 119 警告。最终冻结源码的复验位置由证据目录 `final-verification.json` 记录。
- 三平台账号连接且有真实网络。酷狗会员 YES、QQ NO、网易云 UNKNOWN 是当次探测结果。用户明确允许临时收藏/普通歌单写入并恢复；未退出、换号或清除应用数据。

## 本轮结果

| 范围 | 实际结果与证据文件 |
| --- | --- |
| 三平台头像/账号 | 在线资料刷新、加密保存一致、头像 CDN HTTP 200 及解码通过，身份/账号代次/队列保持；`avatars-online-*` |
| 曲库/搜索 | 三平台可写歌单、收藏与歌曲读取成功，搜索各返回 10 首；酷狗歌曲 0/30 偏移各 30 首；QQ 7 首、网易云 6 首，无第二页。`live_read.json`、`metrics-read-summary.json` 及 XML；QQ/网易云歌单页面曾显示 CACHE，请求成功计数与 `listWritable` 另有真实网络证据 |
| 歌手/专辑 | 三平台歌手各两页 30 首，页间无重复；专辑各 11/1/1 首。`catalog-with-activity-*`；包含完整三平台数组及 JUnit 成功，替代旧部分报告 |
| 原平台播放/通知 | 三曲酷狗测试队列真实播放，用户确认“声音正常”；通知实际 PendingIntent 暂停/播放均生效。`live_power_prepare.json`、`ss_notification_*.xml` |
| 自动换源 | QQ《晴天》真实权限失败后严格切酷狗，约 1528 ms；下一首约 337 ms，源与选择一致、播放确认成立；队列/设置恢复。`auto-qq.json`、`auto-qq.txt` |
| 手动换源 | 酷狗《孤勇者》候选包含无可用音质项，逐个检查后选择可播 QQ 候选；实际播放、续播、队列/槽位/票据稳定，取消保持原曲且过期选择被拒绝。首个成功轮约 1301 ms；最终增加 1.5 秒持续播放检查的轮次总观察 2833 ms，位置 33588→35009 ms，不能把该总观察时间当作纯开播延迟。`manual-source-verified-samples-*`、`manual-source-final-*` |
| 三平台真实红心 | 酷狗原 759 首，三首并发入队，确认约 476/929/1428 ms；QQ 7→8→7、网易云 6→7→6。均移除测试歌，核对完整集合和顺序一致，无待恢复记录。`live_favorites.json`、`favorites-qq-netease-*` |
| 队列后台补全/编辑 | 首批 30 首约 49 ms 入队，约 1072 ms 开播；全量读取 759 首后保留补全期间删除/移动，队列为 758 首且顺序/当前槽/票据正确。`accept_queue_edit.json` |
| 队列约束 | 批量幂等、旧 epoch/revision、跨平台和超限操作原子拒绝；替换与停止阻止旧补全回写。`accept_batch.json`、`accept_cancel.json` |
| 双语歌词 | 三平台《Lemon》原文/译文成功且时间轴有序；网易云 46 行/42 行译文，QQ 和酷狗 57/54。`lyrics-read-summary.json`、`lt_probe*.xml`；不代表逐字歌词/QRC |
| 音质查询 | 酷狗当前曲首次约 1169 ms、复查约 20 ms，返回相同选项；六首样本只有 `kugou:128`，未证明实际多档切换。`batch3-*`、`accept_quality_scan.json` |
| 缓存 | 封面/歌词/曲库字节统计成功；重新浏览后搜索保留页面，酷狗 30 首部分缓存、QQ 7 首与网易云 6 首完整缓存；查询不增加平台请求，账号/队列/日志不变。`cache-read-populated-*`。本轮未清空手机真实缓存 |
| 开发包更新保护 | GitHub 最新元数据读取成功，主线程回调；开发包拒绝下载安装。`batch3-development-device.json`；不等同正式更新安装通过 |
| 断网冷启动 | 实际关闭移动数据，测试断言无 activeNetwork，三平台显示保存头像与 RETRYABLE_ERROR；恢复原网络后冷启动三账号 CONNECTED。`offline-cold-start-*`、`network-restored.json`、`online-after-offline.json` |
| 短后台/锁屏 | 三曲队列自动切歌一次；锁屏两次采样间约 45 秒持续播放、位置前进、前台服务存在。`screen-off-start/end.xml`。USB 接电、`deviceIdle=false`，没有强制 Doze，不能外推数小时自然待机 |
| 进程重启 | 暂停后 force-stop 冷启动保留三平台日志，不自动播放；恢复本轮三曲队列时位置约 10.39 秒。`process-*.json/xml`。这是显式终止，不是系统低内存回收或重启系统 |

## 尚未通过与样本限制

1. **普通歌单写入往返**：三平台普通歌单有添加接口，尚无经过实现与验证的普通歌单移除闭环。酷狗有 2 个普通可写歌单，QQ/网易云当前样本仅喜欢列表。未开始添加，避免无法恢复；已有写入授权不等于恢复路径已经具备。
2. **固定歌曲取消自动换源**：`accept_source_cancel` 等待当前列表第 36 首进入 SEARCHING 超时；该用例预设歌曲必定触发失败，当前账号/样本不成立。保留失败报告，不能算通过，也没有据此修改播放业务。已独立通过的手动搜索取消和队列停止约束不替代这一分支。
3. **实际音质切换**：六首样本只确认一档，不能用查询通过代替切换后续播通过。
4. **未做**：网易云网页登录全流程、真实来电、数小时自然待机、系统回收/重启、其他 Android 版本、正式签名包升级安装。没有制造模拟来电或故障来替代真实场景。
5. 初始 Catalog 无前台 Activity 时在酷狗歌手第二页等待回调超时；修正驱动后全三平台通过。手动测试开发期间还修正了 MediaController 非主线程调用和无候选时错误断言。这些失败均保留原报告，不能算产品功能通过次数。

## 数据恢复与设备收尾

开测时服务活动队列为空、暂停，保存日志中酷狗/QQ/网易云队列分别为 759/1/6 首。测试恢复只使用本轮实时基线，未使用 11 月 1 日旧队列。收尾进程停止后还原三份原始日志，逐字节和 SHA-256 对照成功，冷启动后再次核对未变；最终活动队列为空、暂停、无错误/播放通知，三账号 CONNECTED，收藏数 759/7/6。

本地 `final-restoration.json`、`final-settled-snapshot.json`、`final-platform-read.json` 与 `final-system.xml` 保存收尾。恢复操作中一次通过 shell stdin 写二进制日志发生截断，立即通过文件传输恢复原始完整副本，随后三平台哈希和冷启动核对均通过；没有将该中间状态当作通过。

恢复后的日志 SHA-256：

| 平台 | SHA-256 |
| --- | --- |
| 酷狗 | `9da198418793b9783bd37c22cf624868662773549873c496b2e822722807d476` |
| QQ | `72efdd327ff84eda0c9f2522985945b02928ccd7a8e5108867c47fecbbb8d85d` |
| 网易云 | `adab83fd650a5943e9589916dc2584a8b7462e5803adadbbff704140edbb3f36` |

手机移动数据开、Wi-Fi 关、飞行模式关、省电模式关，熄屏超时恢复原 300000 ms；未改音量、账号凭证或电源白名单。无待处理收藏恢复文件。设备中已完成的旧/本轮恢复目录分别存为 `files/backend-live-baseline-archive-20261101` 和 `files/backend-live-baseline-archive-20261103`，仅作留档，不能作为下一轮恢复基线。未读取或导出账号会话文件。

## 复用测试

构建/安装按 [验证工作流](validation-workflow.md)。设备必须已解锁并停留在 Musxi；后台/锁屏测试单独安排。手机安全锁需使用者解除，不绕过安全锁。每次读取报告必须核对新时间或本轮 instrumentation 结果，不以 `am broadcast` 返回 0 或旧 JSON 作为通过。

新增 `ManualSourceSwitchDeviceTest` 显式使用 `-e allowManualSourceSwitch true` 才执行，逐候选核实可播音质、实际来源和持续播放，最终恢复设置、队列及三平台日志。新增 `FavoritesRoundTripDeviceTest` 必须获得当轮真实写入授权并传 `-e allowPlatformWrites true`；持久记录原收藏，先处理上次未完成恢复，再做新写入，核对服务器完整顺序。省略参数时两类测试跳过。

`CatalogDeviceTest` 持有正式前台 Activity；`BackendCacheReadDeviceTest` 重新填充收藏写入后已失效的页面，再测统计与纯本地搜索。所有测试文件只进 androidTest，不进入发布包。

示例（选定设备后将 `SERIAL` 替换为实际序列号，勿直接执行全测试套件）：

```powershell
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s SERIAL shell am instrument -w -r -e class tech.musxi.player.CatalogDeviceTest tech.musxi.player.dev.test/androidx.test.runner.AndroidJUnitRunner
adb -s SERIAL shell am instrument -w -r -e class tech.musxi.player.BackendCacheReadDeviceTest tech.musxi.player.dev.test/androidx.test.runner.AndroidJUnitRunner
adb -s SERIAL shell am instrument -w -r -e class tech.musxi.player.ManualSourceSwitchDeviceTest -e allowManualSourceSwitch true tech.musxi.player.dev.test/androidx.test.runner.AndroidJUnitRunner
# 只有本轮已获真实写入授权时运行：
adb -s SERIAL shell am instrument -w -r -e class tech.musxi.player.FavoritesRoundTripDeviceTest -e allowPlatformWrites true tech.musxi.player.dev.test/androidx.test.runner.AndroidJUnitRunner
```

断网、普通歌单变更、系统场景不包含在以上命令中。设备证据与本地/CI 摘要分别记录；下一轮重新保存当前基线，不复制本文件中的队列或授权。后续优先补普通歌单移除与写后核实，随后再做真实长后台和正式包专项；前端功能按用户要求暂缓。
