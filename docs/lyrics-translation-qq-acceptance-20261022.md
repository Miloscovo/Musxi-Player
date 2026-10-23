# QQ 歌词翻译接入与设备验收

> 历史专项记录：以下只对应文中日期、包和测试范围；当前状态以 [0.1 验收](android-0.1-acceptance.md) 为准。本机证据路径仅用于本地复查，不随仓库分发。

2026-10-22 文档校准：本文件保留对应阶段的包、测试、听音及未覆盖范围，正文“下一步/待实现”为当时安排。当前三平台翻译、原文先发/译文后补及独立刷新均已实现，B4保守匹配已实现，B1-08正在接续；以[当前后端现状](backend-status-20261022.md)和[歌词契约](frontend-backend-contract-lyrics-translation.md)判断当前状态，不把本阶段证据迁移为新包通过。

后续更新：酷狗KRC已接入，最新350项测试和当前APK/设备范围见[酷狗阶段](lyrics-translation-kugou-acceptance-20261022.md)。本文保留QQ阶段证据，其“酷狗待做”为当时状态。

2026-10-22（北京时间）。用户授权趁手机在线继续推进。在 `<project-root>/.local/worktrees/Musxi-Player-android-backend-next` / `android/backend-reliability` 隔离实现，再同步主工作区；前端继续由 DS 主导、cc 辅助。本轮没有修改 UI、MainActivity、manifest、Gradle 或迁移前安卓副本。

## 当前实现

`QqLibraryApi.lyricsDocument()` 沿用 `music.musichallSong.PlayLyricInfo/GetPlayLyricInfo`，保持 crypt=0、qrc=0、roma=0，打开 trans=1、trans_t=0；同次响应分别读取 lyric/trans。参考固定 QQMusicApi 修订 `3fc57f02e4eddb68a069bba4e0e12a7893f2a814` 的 modules/lyric.py 与 models/lyric.py，原 GPL 声明保留，并追加 NOTICE；没有增加生产依赖、端点或 Python 运行时。

旧 `lyrics()` 委托并返回 original。新增结构化 LyricsDocument 仅含 original/translation/translationStatus，toString 脱敏。原文解析仍走既有 512KiB 明文/Base64 LRC 路径；译文原始表示最多350KiB、最终UTF-8文本256KiB，严格检查控制字符、代理项和解码边界，空白也不能绕过限长。缺失/null/正常空白为 ABSENT；非法译文为 INVALID，原文继续可用。短而无padding的ASCII可能是普通未同步文字，不能只因字符表像Base64便断言其编码错误；交给core报告未同步格式。

`LyricsRepository` 的 QQ 分支将结构化结果交给已有 `LyricsTranslationAligner`，INVALID 只转换为译文 FAILED/INVALID_DATA，原文异常仍整体失败。沿用精确优先、无歧义±100ms、80%译文时间行配对与原文唯一播放时钟，不按行号拼接。账号代数、实际sourceKey、原队列隔离及8份内存缓存保持。QQ/网易云均可返回译文，酷狗仍未接入翻译；没有QRC解密、罗马音、AI翻译或独立译文刷新。当前接线见[契约](frontend-backend-contract-lyrics-translation.md)。

## 设备与证据归属

OPPO PJF110、Android16、ADB `<device-serial>`、包名 `tech.musxi.player.dev`。保留账号与数据覆盖安装；三平台自动重验 CONNECTED。安装前包为[上一阶段](lyrics-translation-acceptance-20261022.md)的 `749984794abe6337fdc307ee36ae163fb59b522c0bcd73d3f443e50ccc6afefb`，其备份在上一阶段 `.local/artifacts/musxi-translation-20261022/final/backend-translation-debug.apk`。

首版设备检查 APK SHA-256：`5b9bcf49be4aa73996da5c77d3dee8c8f92e0c23f5f942d294b41c318698f316`。以下初版结果均保存于 `<project-root>\.local\artifacts\musxi-translation-qq-20261022`，只含固定状态、计数、时间和匹配布尔，不保存歌词、歌曲身份、凭证、私有响应或音频地址。首次读取遇到旧偏好数据，已单列为 stale-before-probe.xml，不作为成功证据；后续确认当前 step、probePlatform、SUCCESS 后才验收。

搜索固定关键词后取本次结果中首个标题精确匹配项，不将标题等同于录音版本或歌手身份。真实结果如下：

| 操作 | 实际观察 | 证据 |
| --- | --- | --- |
| QQ `Lemon 米津玄師` → `Lemon` | 57行原文/54行译文，AVAILABLE/SUCCEEDED，时间有序 | probe-lemon-initial.xml |
| QQ `Hello Adele` → `Hello` | 47/41，AVAILABLE/SUCCEEDED，时间有序 | probe-hello-initial.xml |
| QQ `周杰伦 晴天` → `晴天` | 63/0，NONE/SUCCEEDED/NOT_PROVIDED，原文正常 | probe-qing-initial.xml |
| 点播QQ搜索命中的Lemon | 原QQ队列保留，实际起播来源为NETEASE，显示其46/42，不混用QQ原译文 | play-lemon-initial.xml |
| 该网易云替代来源暂停seek30s | 30000ms、activeIndex8、同source/同索引，保持暂停 | fallback-seek-30-initial.xml |
| 选择网易云Lemon，注入一次原曲PERMISSION后点播 | 实际QQ候选取流并起播，原NETEASE队列仍保留；57/54，AVAILABLE，无播放器错误，同source/同索引 | netease-to-qq-initial.xml |
| 实际QQ来源暂停seek30s/5s | 位置30000/5000ms、activeIndex8/4，当前行有译文，同索引并保持暂停 | qq-seek-30-initial.xml、qq-seek-5-initial.xml |

原曲PERMISSION是debug注入；QQ候选搜索/取流/歌词为实际请求。第一次QQ点播走真实换源，并不证明该搜索命中的QQ录音可播，也不把“歌词可读”当音频权限。实际QQ候选与搜索命中可能是不同版本，不证明同录音或听感相同。初版seek前执行fault_clear重置过诊断源计数，不能跨重置点比较累计取流。playing/进度为程序状态，本轮没有新增用户听音确认。

## 自动验证与最终产物

最终 `./build.ps1 -Test -Lint` 成功：318项JUnit，0 failures/errors/skipped（core183/app11/playback45/网易云22/QQ29/酷狗28）；比上一阶段311项新增7项。覆盖缺失/正常译文、明文/Base64、Unicode、坏编码/密文/控制字符降级、256KiB精确与空白边界、编码输入上限、原文异常及toString脱敏。DS独立补测、主代理复审并修正测试预期，另一次只读审查发现的短英文/Base64歧义已修复并补断言。六模块Lint 0 errors/12 warnings，与前轮数量一致。

最终APK SHA-256：`ef23a706aba5728dbd2fdcc618227cb19dc2e8b58de225f4d8443842120499d6`，v2签名通过，覆盖安装后显式强停再打开。最终包冷启动队列0、取流0、保持暂停，三平台CONNECTED；最终复测结果与收尾快照位于 artifacts 下 final 子目录，详细结果补于下文。当前装的是隔离后端验收包，不是DS/cc进行中的正式前端。

同步前核对主工作区5个原文件SHA-256未变，再同步QqLibraryApi、其单测、LyricsRepository、固定debug runner及QQ NOTICE；最后的短英文解码补丁也同步并逐项核对。主工作区 `:app:compileDebugKotlin :app:compileDebugUnitTestKotlin` 通过，不将编译当正式前端页面验收。最终测试汇总和文件散列另存 final/junit-summary.json、final/source-parity.json。

无收藏写入、退出、换号或清数据；播放测试更新本机队列位置及recent，不改平台歌单。未提交、推送、更新PR #24或发布。待补Binder/长后台/焦点、B2真实收藏联动及B3完整性能矩阵，仍按[清单](backend-device-followup.md)执行。

后续：DS可接网易云/QQ真实双语行；后端下一阶段单独推进酷狗KRC有界解码及LRC兼容验证，再处理独立译文刷新。QQ QRC、机器翻译、B4录音匹配及取流队列调度尚未实现。

## 最终包复测与短后台补验

最终安装包已从手机回拉，SHA-256与 `ef23a706aba5728dbd2fdcc618227cb19dc2e8b58de225f4d8443842120499d6` 一致。final/probe-lemon.xml、probe-hello.xml、probe-qing.xml重新确认QQ为57/54、47/41、63/0；final/probe-netease-regression.xml确认网易云仍46/42。每个probe确认当前step、平台及SUCCESS，不以持久偏好旧值验收。

final/play-qq-source.xml再次完成一次原曲PERMISSION注入后的真实QQ替代来源播放：原队列NETEASE、实际QQ、57/54、playing=true、无错误，source/index匹配。final/seek-30.xml及seek-5.xml为30000/5000ms、activeIndex8/4，当前行有译文并保持暂停，源解析计数保持3、未在中途执行fault_clear。计数包含此前取流尝试，不等于3次成功HTTP。测试末尾再清除故障配置/计数，不能跨此重置比较。

随后HOME退桌面，通过固定 `system_play_current` MediaController命令继续，约17.8秒观察窗口中位置从5000增长到21621ms，实际QQ来源/57行/54译文保持、source/index一致，取流计数仍3，failure=NONE。`dumpsys activity services`显示MusxiPlaybackService的isForeground=true、mediaPlayback类型；之后后台 `lt_pause` 成功、wanted/playing=false。证据为final/background-window.json、background-playing.xml、background-service.txt及background-paused.xml。这是短时后台已准备音频的继续/暂停状态证据，不证明后台新取流、曲尾接续、锁屏、长Doze、通知点击、独立系统焦点或真实可闻效果；不把B1-06/07整项记为通过。

最终回到验收页并保持暂停，清除注入配置、三平台账号保留，final/device-final.xml记录收尾状态。文件仍仅作本地脱敏留证，不加入Git生产资源。

后台首个暂停快照早于状态回调，单列为background-pause-pending.xml，不作为暂停成功证据。随后另做一轮HOME继续/暂停，广播使用 `--receiver-foreground` 确保调试命令及时投递；background-repeat-playing.xml显示QQ来源、位置43284ms、playing=true，background-paused.xml显示43871ms、playing/wanted=false。该标志提高广播调度优先级，不把Activity拉回前台；17.8秒仅是第一轮继续观察窗口，不是整组测试或静音等待时长。回到验收页后的最终状态仍暂停、57/54、来源/索引匹配、注入计数清零。
