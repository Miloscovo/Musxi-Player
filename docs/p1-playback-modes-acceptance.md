# P1 随机/循环验收（2026-10-20）

> 历史专项记录：以下只对应文中日期、包和测试范围；当前状态以 [0.1 验收](android-0.1-acceptance.md) 为准。本机证据路径仅用于本地复查，不随仓库分发。

本文件保留 P1 播放模式阶段的验收记录，当时持久队列、歌词和最近播放尚未实现；同日后续实现和当前限制见 [P1数据验收](p1-data-acceptance.md)。接口见 [P1 后端接线](frontend-backend-contract-p1.md)。正式前端无需先接通，后端可通过固定 debug 命令与真实 MediaSession 独立验收。

## 构建

在 `<project-root>` 执行 `./build.ps1 -Test -Lint` 成功：173 项既有 JUnit 全通过、0 failures/errors/skipped；六个 Android 模块 Lint 0 errors，warnings：app 35、accounts 1、playback 2、酷狗 1、QQ 1、网易云 5。本轮没有新增生产依赖，没有改 cc 的 UI 或主题。

debug APK SHA-256：`da47c35f1bfc06e95c49bac53097d996cd34e56b7a243b0a50d5c81209b7b358`。v2 签名验证通过，覆盖安装至 OPPO PJF110 / Android 16。核对既有 release 合并清单无本项目 debug Application/Receiver/FocusActivity；P1ValidationRunner 只存在于 debug 源集。本轮没有发布 release 包或提交/推送。

## 手机实际检查

使用受 DUMP 权限保护的固定 debug 广播，未使用坐标点按。先保留完整队列 key 顺序和当前项元数据在内存，仅落盘枚举、布尔比较、计数及索引。

| 检查 | 实际结果 |
| --- | --- |
| 默认状态及能力 | repeatMode=OFF、shuffleEnabled=false、canSetPlaybackModes=true |
| QQ 搜索单曲→网易云替代→ONE | 曲尾后自动回到开头，automaticTransitions 从 0 到 1，仍是同一队列 key/索引、NETEASE 来源、SELECTED 状态及相同显示字段 |
| 同一项暂停/继续 | 暂停后 2 秒位置相等，来源和显示字段不变；继续成功 |
| 单项队列 ALL | 再次曲尾后回到开头，automaticTransitions 到 2，来源和元数据仍保留 |
| 关闭循环 | 曲尾 ended=true、playing=false，未回绕 |
| 多项队列 ONE 下手动 next | QQ 歌单索引从 1 到 2，原 key 顺序不变；该项只验导航，不将瞬时缓冲当作听音 |
| 多项队列 ALL | 7 项歌单末项起播后，seek 至曲尾前 1 秒，正常曲尾回到索引 0，automaticTransitions+1，队列顺序不变 |
| 随机一轮手动导航 | 本次索引依次为 0、3、1、6、5、2、4；7 项各一次，全程原 timeline key 顺序相等 |
| 随机回绕/回退 | 再 next 回到本轮首项 0，立即 previous 回到本轮末项 4；验证 shuffle 遍历，不宣称每首音频已播放 |
| 暂停时修改模式 | 当前项/key/来源/标题/歌手/专辑/封面不变，仍暂停，没有因设置而播放 |
| 页面重建 | 暂停时设置 ALL+shuffle 后 recreate，页面仍读回 ALL+shuffle，原队列/当前项保持 |
| 替换队列保留模式 | 服务内先设置 ALL+shuffle，再重新建立 QQ 歌单，仍为 ALL+shuffle |
| 随机曲尾自动接续 | 先以 next/立即 previous 确定该排列的下一项；原索引 1 正常起播后 seek 辅助曲尾，自动到预期索引 3，automaticTransitions+1，随后 QQ 来源实际 playing=true |
| OFF+shuffle 末尾 | 该次排列索引 3 已是末项，hasNext=false；曲尾 ended=true，无循环，队列顺序不变 |
| 强停重开 | 强停前为 ALL+shuffle，进程重建后 OFF+shuffle=false、queueSize=0，三账号恢复 CONNECTED；符合尚无模式/队列持久化的边界 |

所有曲尾操作均由固定 seek 到曲尾前 1 秒后完成，不能记为完整听完音频。快速随机导航只确认 MediaSession 索引/队列遍历；一轮中有正在缓冲或等待选源的瞬时快照，不据此声称 7 首听音全通过。本轮没有新增用户听音确认，之前 P0 的听音反馈不迁移到本 APK。

保留本机忽略产物：`build/p1-modes-build.log`、`build/p1-modes-device-run.log`、`build/p1-modes-extra-run.log`、`build/p1-modes-cold-run.log`、`build/p1-modes-evidence.jsonl`。没有账号身份、歌曲标识、Cookie、私有原始响应或媒体地址。最后三账号 CONNECTED，播放暂停，repeat=OFF、shuffle=false，故障注入为 0；未清账号数据、断开、换号或修改平台歌单。

## DS 协作

使用 `deepseek_flash`（默认 low）、独立上下文并传入完整小任务，只读审查 repeat/shuffle 与跨平台换源边界。DS 实际返回了函数/行号、同 ticket 保留所选来源的结论和三项具体验收建议；没有编辑文件或运行 Gradle/adb。主代理核对建议并完成接口实现、构建和上表设备验证。本轮该 DS 子任务正常交付，不代表更大任务已验证稳定。
