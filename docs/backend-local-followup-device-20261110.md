# 本地音频扩展格式与设备追加验收

2026-11-10，用户重新连接手机并授权继续补验；沿用 PJF110 / Android 16 / API 36。上午首轮结果保留在[首轮设备记录](backend-local-device-20261110.md)，本轮独立留证，不能用首轮包摘要代替当前源码验证。

## 范围与证据

只使用 `Download/Musxi-Followup-20261110` 中由 ffmpeg 生成的七份样本及本轮 marker 记录的本地条目／分组；不操作游戏、平台歌单、收藏、账号或用户真实文件，不整机重启。没有真人听音。本轮仅扩展 debug 验收桥接、普通进程观察入口与设备测试，不新增正式依赖、生产权限、数据库版本或正式页面。

证据目录为 `.local/artifacts/local-device-followup-20261110/`。最终阶段入口为其中 `final-summary.json`，引用独立统一验证、主包及设备测试包摘要、逐阶段设备结果、失败尝试与清理核对。只有入口存在且对应范围明确通过，才视为完成；编译成功和广播发送成功均不算设备通过。

## 已取得的设备结果

| 场景 | 实际结果 |
| --- | --- |
| M4B | 60 秒 AAC/MP4 文件经标准 SAF 复制导入、解码播放、跳至 40 秒通过；不覆盖章节解析 |
| Ogg/Vorbis | 60 秒样本复制导入、解码、40 秒跳转通过 |
| 立体声 FLAC | ffprobe 核实为 60 秒 96 kHz／24 bit／双声道；复制导入、解码、40 秒跳转通过，不推断输出端原样播放 |
| 裸 AAC/ADTS | 60 秒样本导入和解码通过；请求跳至 40 秒后实际进度约 5.4 秒，准确 seek 未通过，不能等同于 M4A 中 AAC 的表现 |
| 长时观察样本 | 15 分钟 M4A 静音样本导入、解码和 40 秒跳转通过，用于排除五分钟观察中自然播完 |
| OPPO 文件选择器 | 已取得真实两项选择结果、读取 flag，两项 COPY 导入和播放通过；取消结果尚未通过，整个 OEM 阶段不能记为通过 |
| 通知动作 | 取得本应用 active notification 的真实 PendingIntent，暂停→播放→暂停通过，本地身份保留；不是人工点击通知栏或耳机按键 |
| 连续熄屏与定时 | 尚待完成。已有普通进程观察证明进度推进，但中途亮屏，不能当作连续熄屏五分钟通过 |

标准选择器固定为 `com.android.documentsui`、OEM 固定为 `com.coloros.filemanager`，仅用于 debug 验收；正式系统选择器继续由系统／用户选择提供方。OEM 记录实际窗口、displayId、勾选计数、返回 URI 数和读取 flag，不能只按触控命令成功推断两项选中。原始 URI 和系统 result intent 不进入文档或报告。

## 观察方法与失败记录

普通后台观察由 debug `ValidationReceiver` 的固定步骤启动，在应用进程运行，不使用 instrumentation 保持进程，不增加唤醒锁或更改电池设置。每三十秒记录播放身份、时长、位置、播放状态、屏幕和锁屏状态；SCREEN_ON/OFF 广播记录连续熄屏，中途亮屏重新计时。连续熄屏播放五分钟后，在同一熄屏区间设真实一分钟定时，以五秒采样记录实际停止时刻，要求停止在 55～80 秒窗口，随后将落盘停止位置与库中保存位置核对再续播。USB 充电下的屏幕 Dozing 不等于 device-idle Doze，另取系统状态核对，不强制全机 Doze。

保留的失败尝试包括：标准选择器文本节点不可直接点击；debug 桥接过早 finish 导致临时 URI 授权失效（导入完成后再 finish）；裸 AAC 的 seek 断言不满足；OEM 首次只勾到一项；OEM 导入完成后的取消回调超时；后续启动时没有 RESUMED 主 Activity，确认设备已进入锁屏密码确认页。最终等待用户手动解锁，不自动输入或绕过密码。

连续熄屏旧尝试的 `background-attempt2-final.json` 保留 `failed/five_minutes_not_screen_off_playing`：90／120 秒样本熄屏且锁屏，150 秒起重新亮屏。没有将这些分段拼成连续通过；新观察器增加屏幕事件及定时时间上限，避免因采样或音频自然结束误判。

## 重跑与剩余边界

设备专项仅显式选择 `LocalAudioFollowupDeviceTest`，传 `allowLocalAudioFollowup=true`、`localPhase`。阶段为 `setup`、`oem`、`notifications`、`after_background`、`cleanup`；普通进程阶段通过固定广播 `local_followup_background_begin` 启动，`local_followup_background_snapshot` 只落盘，`local_followup_background_abort` 终止本轮观察。已有 marker 时先检查，不重新 setup 或套用用户文件。

最终清理只删除 marker 中的条目和分组、清本轮本地队列并保持暂停，核对原音频和分组 ID 集合；手机七份生成源逐个删除后移除空目录。电脑日志、生成样本、阶段结果及必要快照保留于项目 `.local/`。本轮尚未取得清理通过结果时，不宣称测试数据已清理。

仍待正式页面、真人听音／真实音声、大文件容量与内存、M4B 章节、OEM 目录与其他文档提供方、整机重启／系统回收、数小时后台和 device-idle Doze、真实耳机／蓝牙／电话。当前手机没有可用蓝牙输出；不主动拨号、重启或强制 Doze 干扰挂机游戏。未提交、推送或发布。
