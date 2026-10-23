# 酷狗 KRC 翻译接入与设备验收

> 历史专项记录：以下只对应文中日期、包和测试范围；当前状态以 [0.1 验收](android-0.1-acceptance.md) 为准。本机证据路径仅用于本地复查，不随仓库分发。

2026-10-22 文档校准：本文件保留对应阶段的包、测试、听音及未覆盖范围，正文“下一步/待实现”为当时安排。当前三平台翻译、原文先发/译文后补及独立刷新均已实现，B4保守匹配已实现，B1-08正在接续；以[当前后端现状](backend-status-20261022.md)和[歌词契约](frontend-backend-contract-lyrics-translation.md)判断当前状态，不把本阶段证据迁移为新包通过。

2026-10-22（北京时间）。按用户要求继续酷狗KRC翻译。在 `<project-root>/.local/worktrees/Musxi-Player-android-backend-next` / `android/backend-reliability` 隔离开发，再同步 `<project-root>`。正式前端仍由DS主导、cc辅助；本轮未改UI、MainActivity、manifest、Gradle或迁移前安卓副本。

## 实现与边界

`KugouLibraryApi.lyricsDocument()` 按实际sourceKey中的hash+album_audio_id只搜索一次，复用同一候选id/accesskey先下载LRC，再下载KRC；不按歌名另找翻译，不重试其他候选。旧 `lyrics()` 保留原文专用行为，不增加可选下载。原文请求失败仍整体失败，KRC请求/解码/兼容校验失败保留成功LRC，并仅将译文标为UNKNOWN/FAILED及固定reason。

`KrcLyricsDecoder` 检查canonical Base64、小写krc1头、固定XOR及zlib；容器和解压结果各最多1MiB，输出限制在解压循环中执行，拒绝截断/额外压缩数据。UTF-8严格解码，去除文本开头一个BOM，控制字符/非法代理项拒绝。真实请求定位出BOM导致首行被拒绝的问题，修复后有译文样本通过，加入单/双BOM测试。

language元数据Base64解码后最多256KiB，严格JSON、嵌套深度8、最多16块；进入org.json前拒绝宽松语法、重复key、尾随内容和过深结构。只认整数version=1、type=1/language=0；type=0罗马音忽略，未知数值结构为UNSUPPORTED，schema损坏、重复language/翻译块、行数不符为INVALID。译文先按KRC原文件行号绑定，包括空原文占位，不在排序前删行。原文/译文每行4096码点，KRC最多10000源行，原文总512KiB、译文总256KiB。

`KrcTranslationAligner` 保留LRC全文、显示文字、timeMs及索引。非空KRC原文与LRC分别按signed time/文件顺序稳定排序，要求数量完全相同、文本trim并折叠连续空白后相同、每行时间差不超过100ms；任一不符整份译文VERSION_MISMATCH。负offset在显示夹0前比较，重复时间保持文件序，译文直接附到已验证行，不按最近时间猜归属。空白、`//`及复制原文不显示；全量兼容且没有真实译文才返回NONE/NOT_PROVIDED。此规则独立于网易云/QQ的80%译文时间配对规则。

LyricsRepository将平台状态映射为INVALID_DATA、UNSUPPORTED_FORMAT、NETWORK、TIMEOUT、PLATFORM_ERROR或VERSION_MISMATCH，前端仍只消费 `LyricLine.translation` 和同一个activeIndex。账号代数、实际sourceKey、原队列与8份内存文档缓存沿用既有隔离。调试探针只输出固定分类/行数/比较布尔，不保存原始歌词、曲目标识、accesskey、响应或URL。

KRC是同一个后台逻辑操作里的额外顺序请求；原文等待可选请求完成后整份返回，**没有原文先显示或独立译文刷新**。`app.lyrics.refresh()`仍为整份重新加载，不能保证刷新失败保留旧文档。未新增逐字API、QRC、机器翻译、生产依赖或业务服务器。NOTICE追加同一固定KuGouMusicApi module/lyric.js及本项目PC参考来源，许可不变。

## 构建、同步与产物

最终 `./build.ps1 -Test -Lint` 成功，**350项JUnit、0 failures/errors/skipped**：core194、app11、playback45、酷狗49、QQ29、网易云22。相比QQ阶段新增32项：KRC核心兼容11项、容器/元数据18项、平台可选请求3项。六模块Lint **0 errors/12 warnings**，数量同基线：app2/accounts1/playback2/酷狗1/QQ1/网易云5。

10个文件同步主工作区：酷狗API/测试、KRC解码器/测试、核心KRC校验器/测试、LyricsRepository、固定debug runner、accounts debug探针、酷狗NOTICE。同步前核对5个已有文件SHA-256未变、5个新路径不存在，复制后逐项散列一致。主工作区 `:app:compileDebugKotlin :app:compileDebugUnitTestKotlin` 通过。未运行正式前端截图或交互验收。

最终隔离后端debug APK SHA-256：

`c14485f2ff5379300bfcfa4e0022dc8f00a2115e5c2ff325b1dd8d9039957d24`

APK v2签名通过，已覆盖安装并从手机回拉核对散列相等。手机安装的是隔离后端验收包，**不包含主工作区DS/cc正在开发的正式前端**。本轮未生成或发布正式release包。

本机脱敏证据目录：`<project-root>\.local\artifacts\musxi-translation-kugou-20261022`。含最终APK、build-final.log、main-compile.log、source-parity.json、junit-summary.json、lint-summary.json及各次固定探针XML；均不作为生产资源加入Git。

DS独立上下文完成PC规则核查、核心兼容器/11项测试、解码器17项合成测试和只读交叉审查。主代理审查修改、补BOM回归/严格JSON与资源边界、修正测试深度计数和中途写入语法问题，统一构建并完成设备操作。子代理结果不当作设备证据。

## 真机结果与实际来源

设备：OPPO PJF110 / Android16，ADB序列<device-serial>，包 `tech.musxi.player.dev`。三平台账号保持CONNECTED，未换号、退出或清数据。

| 酷狗固定只读搜索样本 | 原文行 | 译文行 | 结果 |
| --- | ---: | ---: | --- |
| Lemon | 57 | 54 | AVAILABLE/SUCCEEDED |
| Hello | 47 | 41 | AVAILABLE/SUCCEEDED |
| My Love | 61 | 55 | AVAILABLE/SUCCEEDED |
| 晴天 | 63 | 0 | NONE/SUCCEEDED/NOT_PROVIDED |

每次probe检查实际step、probePlatform=KUGOU、probeStatus=SUCCESS及时间有序；搜索取首个标题相等项，不宣称已核定录音、歌手语义或听感。KRC可读不证明音频有权限。Lemon的额外固定诊断确认PRESENT、57个KRC行、diagnostic=NONE；初版BOM拒绝、账号REVALIDATING时过早探针失败均为排查记录，不算最终成功。

Lemon、Hello、晴天、My Love的本轮点播实际sourcePlatform均为NETEASE，原队列平台仍KUGOU，分别显示实际来源46/42、53/51、16/0、59/55。无注入故障；只记录实际换源结果，没有把失败原因猜成会员/版权或模拟拒绝。换源后使用网易云对应歌词，来源与activeIndex匹配；不能把这些播放证据写为“酷狗音频及双语播放通过”。额外旧P0固定酷狗搜索首项点播同样换到NETEASE16/0。

本轮暂停seek30s/5s的快照只证明实际替代来源同步，不是酷狗来源双语seek验收。**实际KUGOU source的播放/双语seek仍待可播放样本补验**；目前已有酷狗直接歌词请求/解码/原文兼容/状态的真实设备证据。

最终c14485…安装包重新读取酷狗Lemon57/54、Hello47/41、晴天63/0，记录为final-probe-lemon/hello/qing.xml；My Love61/55的probe-mylove.xml同属该最终包。final-probe-netease.xml回归网易云Lemon46/42，final-probe-qq.xml回归QQ Lemon57/54。seek-30.xml及seek-5.xml记录实际NETEASE16/0、30000/5000ms、activeIndex5/0、暂停且来源/索引一致，期间resolveCount保持4，无中间重置；seek-5.xml复用当时已完成的final-probe-lemon快照，probe与播放状态是不同对象，不将probe译文数当当前播放译文数。

收尾再清故障配置/计数，device-final.xml记录：playing/wanted=false、位置5000ms、原队列KUGOU1项、实际NETEASE16/0、NONE/NOT_PROVIDED，来源/索引相符，三平台CONNECTED、注入计数0。此清除会重置resolveCount，不能跨重置比较取流次数。没有新增听音、后台、账号或收藏实测。

## 后续与收尾要求

下一步独立译文刷新与原文先显示：以实际sourceKey、双账号代数、原文版本/内容兼容校验拒绝旧结果；译文失败保留同份原文及已验证译文，增加明确重试和有界等待。更多录音、正式页面布局/滚动、真实听音、后台/Binder/收藏/B3专项继续按[待补清单](backend-device-followup.md)执行，不把本轮测试数量当专项通过。

本轮播放更新了本机队列/位置/recent，未写平台收藏或歌单。结束保持暂停、清除故障配置、保留三平台账号。未提交、推送、更新PR #24、合并或发布。
