# 2026-10-31 后端追加实机检查

> **状态：日期专项检查（2026-10-31）。** 静态发现与设备结果只对应当时包；当前状态见 [当前开发接续](next-session-handoff.md)，文档分类见 [文档入口](README.md)。

接续 [蓝牙输出验收](output-acceptance-20261031.md)，测试同一 APK：`38F68AB1B3F4D35DD619151F782D91AC79BA3EEC4C6A42A755788FC6154465C9`。本轮不改产品源码、不覆盖安装，沿用用户选定的 758 首队列。设备为 Android 16 / API 36。

## 熄屏与通知

第一次恢复播放尚在取流时就熄屏，验收入口标记 `PRECONDITION`，报告刷新一度延迟，不能作为后台播放通过证据。亮屏后用户解锁、选歌并确认耳机正常出声，随后重新开始观察。

确认出声后的熄屏观察持续 79986ms，进度 38413→118333ms（增加 79920ms），同一来源及队列保持，`playing=true`、`playWhenReady=true`、`serviceForeground=true`、`foregroundGatePending=false`。开始后及结束时 `interactive=false`、锁屏；`deviceIdle=false`，因此仅覆盖短时锁屏播放，不代表深度 Doze 或自然长时间后台通过。

通过本 App 实际通知的 PendingIntent 发送暂停和播放，均记录 `actionSent=true`、`status=SENT`；暂停后位置 119008ms，随后恢复播放并继续前进，来源、队列保持。未使用其他 App 的通知或控制。

用户确认熄屏期间耳机声音正常，通知暂停后恢复也正常。

## 系统音频焦点

使用现有 debug `ValidationFocusActivity` 请求真实 AudioManager 焦点；两次系统均返回 `AUDIOFOCUS_REQUEST_GRANTED`（1），不是直接写入模拟焦点状态。该页面与播放器属于同一 App，不等同于真实来电或其他音乐 App 的完整场景。

- 临时焦点占用：`playing=false`、`playWhenReady=true`、`suppression=AUDIO_FOCUS`，位置停在 25849ms；归还后 `playing=true`、`suppression=NONE`，继续到 36851ms。
- 永久焦点占用：`playing=false`、`playWhenReady=false`，位置 37672ms；归还后仍保持此位置及暂停。上述焦点检查过程中队列数为 758、槽位索引 6、revision 为 31，未见重建或跳曲。

焦点部分核对的是系统请求结果与播放器状态，未再单独索取真人听音结论；真实电话、导航 duck、其他应用抢占/恢复仍需另测。

## 待跟进的电源配置

静态复核确认：前台服务类型为 `mediaPlayback`，前台服务权限齐全；播放器未设置 `setWakeMode`，Manifest 未声明 `WAKE_LOCK`。前台服务本身不保证 CPU 在缓冲/网络取流时持续唤醒，需要单独评估播放器电源管理并进行深睡眠、长缓冲和后台换歌验证。该静态发现不能证明第一次报告延迟由唤醒锁缺失造成。本轮未修改电源策略。

本机证据放 `.local/artifacts/backend-followup-20261031/`，分类状态中无账号、设备名、歌曲标识和音频 URL；旧时间戳及 PRECONDITION 报告保留为前置条件失败记录。

## 收尾与余项

焦点验收页已退出、焦点已归还、事件记录器已停止；最终暂停于 37672ms、队列 758 首，保留用户当前选择及账号。开始时 QQ 状态为 `REAUTH_REQUIRED`，结束时三平台均为 `CONNECTED`；本轮未发起登录、退出或换号。未修改/构建/安装新的源码包，沿用此前的 681 项测试、Lint 零错误结果，其验证范围不扩大。

可继续前端测试。本次剩余后端专项为深睡眠/长后台及电源策略、真实来电/跨应用焦点、其他音频设备/旧系统、QQ 无损等其他平台档位、酷狗 FLAC 结束后重播，以及自然平台故障矩阵；不将普通/极高音质听音替代 FLAC 验收。
