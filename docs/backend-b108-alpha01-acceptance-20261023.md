# B1-08 · 0.1.0-alpha01真实后台换源补验

> 历史专项记录：以下只对应文中日期、包和测试范围；当前状态以 [0.1 验收](android-0.1-acceptance.md) 为准。本机证据路径仅用于本地复查，不随仓库分发。

2026-10-23，OPPO PJF110 / Android16/API36。**本候选真实后台自动换源样本已通过**：QQ原曲和网易候选真实PERMISSION后，按保守资格及两轮5秒倒计时自动接到酷狗，桌面上的系统媒体会话确认持续播放；原QQ队列7首、索引1保持。用户在同包同样本前一轮酷狗来源播放时确认“声音正常，歌曲正确”。不将这条样本结论扩大为所有后台/系统专项通过。

包名 `tech.musxi.player.dev`，版本 `0.1.0-alpha01`，APK SHA-256 `cb3464f8a7458354fc903c72e1e61f25f762a373b94c4a84635503861b1fe560`。未改生产代码或换包，沿用本轮已听音候选；历史最终后端包的两次NETWORK仍保留在[旧记录](backend-b108-acceptance-20261022.md)，不改写历史。

## 触发与证据

1. 先暂停并在手机私有目录 `no_backup/v01-user-before-b108-20261023/` 备份用户最新三个journal，仅复制固定bin文件；不导出凭证、不写收藏。
2. 固定debug入口 `qq_playlists`、`qq_tracks` 读取本人7首QQ队列；`qq_audio_aizo` 选择原有AIZO，不注入播放错误。首次在HOME提交后部分广播快照未更新，回前台后自动换源至酷狗播放成功。这轮仅算前台/回前台成功，不据此判定后台。
3. 第二轮22:16:01.858提交同一固定样本，22:16:02.078按HOME，此后35秒没有返回应用或选择候选。连续读取仅本包 `MusxiQueue` 标签；桌面停留期间独立读取系统媒体会话。
4. 22:16:37系统媒体会话为 `PLAYING(3)`，position=18648ms、speed=1.0、error=null。返回应用后新快照确认原QQ队列7首/索引1、实际KUGOU、playing/wanted/foreground=true、suppression/failure=NONE、metadataMatches=true，注入错误数0。回前台后位置继续约54秒。

| 第二轮设备时间 | 实际事件 |
| --- | --- |
| 22:16:03.104 | foreground_gate_wait，foreground=false |
| 22:16:03.127 | Media3通知更新后foreground=true |
| 22:16:03.166 → .167 | gate_ready(true)之后才ready=true |
| 22:16:03.548 | QQ原曲真实PERMISSION；网易/酷狗随后各10候选 |
| 22:16:09.571 → .573 | 第一轮5秒后，gate_ready(true)之后才起播 |
| 22:16:10.156 | 网易候选真实PERMISSION，继续有限替代策略 |
| 22:16:15.212 → .218 | 第二轮5秒后，再次资格先于起播，最终酷狗播放 |
| 22:16:37 | 仍在桌面，系统媒体会话PLAYING，位置约18.6秒 |

这些是实际服务/平台结果，不是debug注入故障；固定debug只负责选样本及触发正式播放链路。完整材料在 `.local/artifacts/android-0.1-acceptance-20261023/`：`device-b108-background-r2.json`、`device-b108-background-r2-events.log`、`device-b108-background-r2-after-return.xml`、`device-b108-background-r2-system.xml`。HOME后的早期preferences曾保持旧值，不能将其当当前状态；本轮用连续事件和返回前系统媒体会话补足。

## 数据恢复与限制

测试结束暂停、强停后，将上述**用户最新队列**的三个bin逐一恢复；`device-b108-before.sha256` 与 `device-b108-restored.sha256` 完全一致。随后只显式恢复用户752首酷狗队列、索引3、位置159686ms并保持暂停，不prepare、不取流；原最近播放也随同journal恢复。没有把更早 `v01-before-20261023/` 的1首旧队列覆盖回来；没有退出/换号/写真实收藏。

本样本是在近期前台操作后退到桌面，USB连接，未强制Doze；没有验证自然十分钟后前台资格丢失、深度Doze、任意系统拒绝、真实进程回收/来电/输出切换。两次自动候选只是实际这一首样本，不保证所有歌曲或录音一致性。0.1产品入口、队列编辑与视觉矩阵仍按[统一验收](android-0.1-acceptance.md)继续，不据此宣布正式发布。
