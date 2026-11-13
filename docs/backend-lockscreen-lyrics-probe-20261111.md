# ColorOS 锁屏歌词：debug 探针与设备验收

用户后续选择独立实施方案 B，接口与本包验证见[媒体卡片标题歌词交付](backend-media-card-lyrics-20261111.md)。本文继续保留原生方案 A 探针的固定 APK、设备观察与判断边界，不将这些结果计作方案 B 实机通过。

状态：2026-11-11 16:59，[需求单第 1 项](backend-requests-20261111-2.md#1--探针p0仅-debug-构建)的 debug 探针已交付，A1／A2 实机操作已结束。已保留数据覆盖安装固定 debug APK；**A1、A2 平台写入均读回成功，但用户两组锁屏观察均只有音乐卡片，没有歌词相关入口或内容；两组观察后字段仍读回一致。用户确认官方酷狗的歌词显示于酷狗自有的整页锁屏界面，因此不构成 ColorOS 原生歌词页的阳性对照。** 原生歌词页本身在本机是否可用仍未验证，不能据此确定 Musxi 未显示的原因。16:50 已保存两组日志、执行 `off` 并确认测试字段清除；最终日志及用户反馈已保存，Codex 已释放安装窗口，CC 可以安装新包。正式发布器、设置开关和媒体卡片标题方案未实现。

## 安装与测试包身份

包名 `tech.musxi.player.dev`，安装包由本轮统一验证生成。确切 APK、SHA-256、源码清单和检查结果在 `.local/artifacts/lockscreen-lyrics-probe-20261111/final-summary.json`。为避免 CC 后续构建覆盖验证产物，保留该次 APK 到同目录。16:36 使用 `adb install -r` 覆盖安装，未卸载或清数据；本地与手机已安装 base.apk 的 SHA-256 均为 `7e0dc6543dda2220d714333b627ab9845bce3b509c77aa5f428afa6cb569b537`。apksigner 验证 v2 签名通过，证书 SHA-256 为 `56fe0d87fcba0d99a49e8ed71a74cda173135c193380f48b821cbdb33a7256a5`。准备阶段没有操作手机，后续实机证据另存于 `device/`，不混入原自动验证时间。

本探针只在 debug sourceSet 中：新 Java 包内辅助类位于 `playback/src/debug/java/androidx/media3/session/`，发布器与入口位于 `playback/src/debug/kotlin/`；app 的 debug `ValidationReceiver` 分派四个固定步骤，继续受 `android.permission.DUMP` 保护。没有新增 manifest 组件或任意 JSON／URI 注入入口；main/release、`ui/`、设置模型与真实歌词流均未修改。

## 四个固定步骤

先在 Musxi 正常播放一首足够长的歌曲，让当前平台会话及 metadata 就绪。探针不自行创建队列、播放、seek、暂停或拉取歌词。下面 `adb` 指本机 Android SDK 的 `platform-tools/adb.exe`，有多个设备时在每条命令中加 `-s <serial>`。

```powershell
# A1：只附加 lyricInfo；这组明确不带 oplus.media.config。
adb shell am broadcast -n tech.musxi.player.dev/tech.musxi.player.ValidationReceiver --es step lock_lyrics_a1

# A2：lyricInfo 加 oplus.media.config="1"。
adb shell am broadcast -n tech.musxi.player.dev/tech.musxi.player.ValidationReceiver --es step lock_lyrics_a2

# 读取当前平台会话，更新证据；本步骤不重新写 metadata。
adb shell am broadcast -n tech.musxi.player.dev/tech.musxi.player.ValidationReceiver --es step lock_lyrics_status

# 关闭探针并清理自己写入的字段。
adb shell am broadcast -n tech.musxi.player.dev/tech.musxi.player.ValidationReceiver --es step lock_lyrics_off
```

日志使用 `MusxiLockLyricsProbe` tag。`WRITE_READ_BACK_OK` 表示由**平台 MediaController.metadata** 读回的 `lyricInfo` 和本组 config 均与预期一致；附带 `readBackMatches`、config、writeCount、reattachCount 和实际读回的测试歌词片段。`PLAY_A_TRACK_FIRST` 或 `WAIT_FOR_PLATFORM_METADATA` 表示尚未开始，需播放就绪后重新触发；不能将 `Broadcast completed` 当作写入成功。

私有报告为 `files/lockscreen-lyrics-probe.json`，包含当前完整读回 payload 与最多 64 条精简事件，不保存媒体 URI、signed URL、Cookie 或完整 metadata。每组触发和用户观察后分别保存，避免下一组覆盖：

```powershell
adb logcat -d -s MusxiLockLyricsProbe:I '*:S'
adb shell run-as tech.musxi.player.dev cat files/lockscreen-lyrics-probe.json
```

现场采集分别保存于本轮 `.local/artifacts/lockscreen-lyrics-probe-20261111/device/`，状态索引为 `device-summary.json`。环境与安装身份、A1／A2 触发／就绪／观察后报告及过滤日志、用户观察记录均已保存。A1 观察后采样为 16:47:21：`active=true`、`mediaIdMatches=true`、`readBackMatches=true`、`configReadBack=null`、`writeCount=2`、`metadataCallbacks=3`、`reattachCount=1`、`playbackState=3`、`positionMs=115232`。A2 观察后采样为 16:50:12：`active=true`、`readBackMatches=true`、`configReadBack="1"`、`writeCount=1`、`metadataCallbacks=1`、`playbackState=3`、`positionMs=63389`。报告的 readBackMatches 只证明写入，系统显示结果由用户观察。

16:50:13 关闭报告 `off-report.json` 确认 `lastEvent=OFF`、`active=false`、`lyricInfoPresent=false`、`configReadBack=null`；`off-logcat.txt` 已保存。关闭后的 `readBackMatches=false` 表示当前字段已不等于探针 payload，符合清理预期，不作为发布失败。没有暂停／切换用户曲目，也没有操作官方 App 或系统设置。

16:54 收到用户反馈“用酷狗，App 里有歌词，锁屏也有歌词”，只读复核官方酷狗版本为 20.5.6（20569）。16:59 用户进一步明确“酷狗自己的一整页锁屏界面”，据此将该组分类为酷狗自有锁屏页，不能用于证明 ColorOS 原生歌词页存在或可用。对照记录为 `official-kugou-user-observation.json`，保留原反馈及澄清；最终过滤日志为 `final-logcat.txt`。`post-control-report.json` 为再次保存的关闭报告，仍是 16:50 的 OFF 事件，不冒充新一次平台实时采样。官方 App 的曲目是否相同、逐字与译文未单独确认；root／Bridge 状态也尚未经用户确认。本次澄清仅更新记录，不重新安装、触发探针或操作手机。

## 探针语义

- 使用 Media3 **1.8.0** 的包内访问链取得现有平台会话，Java 辅助类为本项目自写；没有反射、root、包名伪装或 Bridge。升级 Media3 时需重新验证此内部 API。
- 写入的是**合成测试歌词**：“Musxi 锁屏探针 A1/A2 · 第 N 行”，便于与卡片歌名、官方 App 歌词区分；不是当前歌曲的真实歌词。payload 的 songId/songName/artist/album 来自当前公开平台 metadata，provider 为实际 debug 包名。
- `lyric`、`rawLyric`、`translationLyric` 同时提供，分别为行级测试文字、三段扫光及英文测试副行。以触发时的实际播放位置向下取整至 5 秒为首行，每 5 秒一行，覆盖接下来 10 分钟。测试偏移为 0，不读或修改用户 offset／翻译偏好，不能据此声称真实逐字或翻译同步已实现。
- A1 显式移除 `oplus.media.config`，A2 写字符串 `"1"`。启用前保存这两个键的原值，关闭时在最新 metadata 上恢复原值，保留期间更新的标题、时长和封面。
- 按运行时值类型复制 metadata，保留 Bitmap、Rating、Long（包括 0）和 CharSequence；未知类型整次放弃。JSON 不超过 128Ki 字符、整份 metadata Parcel 不超过 512KiB，超限拒绝。
- 平台 `onMetadataChanged` 在主线程重新检查并附加，读取**最新** metadata，防止迟到回调覆盖新封面；自己的写回值已经一致时直接返回，避免反馈循环。只监听 metadata，不随进度或每行重写标题／metadata。
- 切歌／会话销毁停止探针；10 分钟超时任务、metadata 回调或状态读取发现 elapsedRealtime 已超时后，注销回调并清理，不持久保存开关。设备深睡时不保证准时清理，须等主线程执行任务／再次观察，不持有唤醒锁。进程重启后须显式重新触发。

## 三组设备步骤与记录

1. 连接手机后只读记录环境：型号、Android／ColorOS 与 SystemUI 版本。通过 `adb shell dumpsys package com.android.systemui` 取 `versionName/versionCode`，通过 `getprop ro.build.version.oplusrom`／`ro.build.version.opporom` 和 `ro.build.display.id` 记录系统；属性为空时记空，不能猜版本。
2. Musxi 正常播放，触发 A1 并确认平台写入读回成功。**用户自己锁屏、解锁**，观察歌词入口、行级歌词、逐字效果和英文副行；在锁屏观察前后各采一次状态，确认封面更新没有冲掉字段。
3. 用户解锁后切至 A2，确认 config 为字符串 `1` 且写入成功，再由用户锁屏观察同样项目。若第一组的锁屏状态被系统缓存，可让用户解锁后重新进入锁屏；不自动输入密码或操作凭据。
4. 执行 `off` 并确认清理。用户自行在已知受支持的官方 App（如酷狗或 QQ 音乐）播放带歌词的曲目，锁屏确认歌词页存在；不自动操作其他 App。尽量使用同一首曲目和相同系统设置，单独记结果。

| 环境 | 现场值 |
| --- | --- |
| 手机型号／manufacturer | PJF110／OnePlus |
| Android／SDK | 16／36 |
| ColorOS／oplusrom／opporom／build display | `V16.0.0`／`ro.build.version.opporom` 为空／`PJF110_16.0.5.1001(CN01)` |
| SystemUI versionName／versionCode | 16.00.14／160014 |
| Musxi debug APK SHA-256 | 本地与已安装 APK 均为 `7e0dc6543dda2220d714333b627ab9845bce3b509c77aa5f428afa6cb569b537` |
| 是否 root／Bridge／系统歌词相关设置 | 待用户确认；测试目标为无 root／无 Bridge |
| 已安装的官方 App 版本 | 本次对照使用酷狗 `com.kugou.android`：20.5.6／20569；QQ 音乐 `com.tencent.qqmusic`：20.9.5.8／7508，仅查询版本、未执行对照 |

| 组别 | 平台写入读回 | 原生歌词入口／行级显示 | 逐字／翻译 | 结果与证据 |
| --- | --- | --- | --- | --- |
| A1：lyricInfo | 实际平台读回一致，config 不存在；metadata 回调后、用户观察后仍一致 | 用户反馈：只有音乐卡片，可见歌名、封面和播放按钮，没有歌词相关 | 无歌词，无法观察逐字／翻译 | 本组未显示；见 `device/a1-after-observation-report.json`、`device/a1-user-observation.json` |
| A2：加 config=1 | 触发与观察后均读回一致，config 为字符串 `1`，播放状态 PLAYING | 用户反馈：也只有卡片，没有歌词 | 无歌词，无法观察逐字／翻译 | 本组未显示；见 `device/a2-after-observation-report.json`、`device/a2-user-observation.json`；随后已关闭 |
| 官方酷狗 20.5.6／20569，自有锁屏页 | 不注入；未读取官方私有数据 | 用户确认歌词位于酷狗自有的整页锁屏界面 | 未反馈，不作结论 | 原生页阳性对照未完成；见 `device/official-kugou-user-observation.json` |

**判断边界：**A1/A2 读回成功但系统不显示，说明当前设备／系统／debug 包名／合成 payload 组合没有显示测试歌词，不能把它误判为未写入，也不能单凭此证明唯一原因是白名单。官方酷狗显示的是自有锁屏页，不能作为 ColorOS 原生歌词页的阳性对照；原生页是否存在／可用、系统设置、包名策略及 payload 兼容性均未验证清楚。当前没有满足第 2 项“探针 A1 或 A2 显示后再做”的条件；第 2、4 项继续等待用户安排。若后续继续原生路线，应先补受支持官方 App 在系统音乐卡片／原生歌词页显示的有效对照，不自动关闭酷狗自有锁屏、不改系统设置、不新增 Musxi 自有锁屏界面。没有验证正式包名，单台结果不能外推全部 OPPO／OnePlus／realme 系统，root／Bridge 未确认时也不称为已证明无 root 支持或不支持。

## 自动化验证

debug 专项测试覆盖：A1 payload 与平台读回、A2 config／关闭恢复、Bitmap／Rating／0 值复制、封面重写后重新附加及自回调无循环、切歌停止、超时清理、未知类型与大 Parcel 拒绝，以及 Media3 辅助类取得相同平台 token。另编译并检查 playback release AAR 中没有探针类。

专项和统一验证的实际结果、源码冻结清单、APK 散列保存在 `.local/artifacts/lockscreen-lyrics-probe-20261111/final-summary.json` 指向的报告。Robolectric 没有连接 MediaSession 与 MediaController 的系统服务 binder，测试使用专门 shadow 将写入传给控制器并异步投递回调；此处验证发布／反馈／清理逻辑，Media3 辅助类取得相同平台 token 另行覆盖。实机平台读回、清理、A1／A2 用户观察及酷狗自有锁屏页观察独立记录在上表及 `device/`；原生歌词页阳性对照未完成。接机阶段只安装保留 APK、采样并更新文档，没有重新构建，不将原 2268 项 JVM 测试或 Lint 计作再次运行；文档修改另运行链接检查。
