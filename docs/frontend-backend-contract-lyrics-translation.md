# 歌词翻译接线契约：三平台已接入（2026-10-22 本轮状态校准）

2026-10-22 B1-08 最新：前台资格门禁已实现并同步主工程，先由 Media3 建立前台服务，再单次请求音频焦点；等待最多3秒，暂停/停止/换队列使旧请求失效，系统拒绝前台启动时返回 `BACKGROUND_RESTRICTED`，须前台明确重试。隔离后端392项、主工程515项测试全通过，Lint均无错误（分别9/76条警告，范围不同）。最终后端包酷狗实际播放、受控超时/暂停取消/显式重试通过；最终包两次真实QQ→网易云换源均因NETWORK停止，未将完整闭环记为通过。前一候选包后台换源及用户听音证据保留其包归属。见[B1-08修复与验收](backend-b108-acceptance-20261022.md)。

2026-10-22。网易云 `tlyric.lyric`、QQ 同响应 `trans` 和酷狗 KRC `language` 均已接入，三平台可返回行级译文。前端继续使用同一套 LyricLine.translation、translation 状态和 activeIndex。已实现原文先显示、译文随后补入及独立刷新；新增入口见下文，今晚记录见 [后端补验](backend-evening-acceptance-20261022.md)。原文接口与来源规则沿用 [P1](frontend-backend-contract-p1.md)，酷狗阶段设备及构建证据见 [酷狗阶段验收](lyrics-translation-kugou-acceptance-20261022.md)，此前见 [网易云验收](lyrics-translation-acceptance-20261022.md)及 [QQ验收](lyrics-translation-qq-acceptance-20261022.md)，后续见 [翻译计划](lyrics-translation-plan.md)。

**2026-10-23 前端接线更新**：正式页面现已接行级双语、翻译开关和独立 `refreshTranslation`，沿用后端验证对齐的译文，不新增机器翻译服务。默认显示按原文脚本判断：中文主体关闭，外语主体开启；当前实际来源的手动覆盖优先，延迟译文和刷新不重置。`TranslationUi.hasText`区分失败时有无旧译文；`lookupFailed`保留查询失败事实。确认无译文与获取失败仍分别处理：前者禁用，后者可重试并保留已有译文。字标状态、短按/长按语义及视觉验证限制见[播放页设计记录](design/player-sheets.md)。2026-10-22“前端仅原文第一版”的说明已是历史阶段；文中既有真机数字保持原包归属，未据本轮JVM渲染宣告正式前端整体验收。

## 已落地 API

包名均为 `tech.musxi.core.lyrics`：

```kotlin
data class LyricLine(
    val timeMs: Long,
    val text: String,
    val translation: String? = null,
)

data class TranslationLookup(
    val status: TranslationLookupStatus = TranslationLookupStatus.UNSUPPORTED,
    val reason: TranslationReason? = if (status == TranslationLookupStatus.UNSUPPORTED)
        TranslationReason.NOT_IMPLEMENTED else null,
)

data class LyricsTranslation(
    val availability: TranslationAvailability = TranslationAvailability.UNKNOWN,
    val lookup: TranslationLookup = TranslationLookup(),
)
```

`ParsedLyrics(lines, plainText, translation = LyricsTranslation())` 和 `LyricsState` 均含 `translation: LyricsTranslation`；`LyricsState` 另在末尾追加 `canRefreshTranslation: Boolean = false`。`app.lyrics.state` 中 `lines` 是已经对齐的展示行；后端负责对齐，页面不能自行按行号拼原译文。`plainText` 仍只表示原文。原构造器实参、已有字段顺序、status/activeIndex 语义不变；原文 `LrcParser.parse(text)` 不猜测或制造翻译，仍可直接使用。

每行 `translation` 为可选展示字符串：null 表示该行没有可显示译文。当前一对一配对，不合并多片段。整首 AVAILABLE 不代表每一行都有翻译。原文与译文共用 timeMs 和 activeIndex，译文没有第二套定位/滚动时钟。不提供独立译文纯文本块，未同步文本不按行号硬贴。

## 状态定义

`TranslationAvailability` 表示当前可显示内容：

| 枚举 | 含义 |
| --- | --- |
| UNKNOWN | 尚不能确定是否有可用翻译，包括未实现、未请求或查询失败 |
| NONE | 后端已确认当前这份原文没有可用译文；不能用网络失败或未实现推导 |
| AVAILABLE | 至少有一行已经验证并对齐的译文，可按行展示；可能只有部分行 |

`TranslationLookupStatus` 表示最近一次查询，与当前可显示内容独立：

| 枚举 | 含义 |
| --- | --- |
| IDLE | 已具备查询路线，但此次尚未查询；不代表没有译文 |
| PENDING | 正在查询；已有原文/译文应可继续展示 |
| SUCCEEDED | 本次得到已验证结果；可能 AVAILABLE，也可能 NONE |
| FAILED | 本次查询失败；如果已有同份原文的验证译文，允许 AVAILABLE + FAILED |
| UNSUPPORTED | 当前客户端/格式不具备查询能力；结合 reason 区分原因 |

`TranslationReason` 为固定枚举：`NOT_IMPLEMENTED`、`NOT_SYNCED`、`NO_LYRICS`、`NOT_PROVIDED`、`NETWORK`、`TIMEOUT`、`PLATFORM_ERROR`、`INVALID_DATA`、`VERSION_MISMATCH`、`UNSUPPORTED_FORMAT`。没有补充原因时为 null，不透传异常文本、平台原始字段或凭证。单独使用原文 LrcParser 的默认值仍为 UNKNOWN + UNSUPPORTED/NOT_IMPLEMENTED；三平台 LyricsRepository 可实际返回 AVAILABLE/SUCCEEDED、NONE/SUCCEEDED/NOT_PROVIDED，以及译文异常时 UNKNOWN/FAILED。分阶段原文首先为 IDLE，随后译文查询为 PENDING；独立刷新失败时可真实出现 AVAILABLE+FAILED。

## 前端展示建议

```kotlin
val state = app.lyrics.state.value // 示例快照；Compose 按既有生命周期收集此 flow
// 放在每行渲染中；showTranslation 是页面展示偏好。
val translatedText = line.translation?.takeIf {
    showTranslation &&
        state.translation.availability == TranslationAvailability.AVAILABLE &&
        it.isNotBlank()
}
// 原文与可选副行组成同一个歌词行，用 state.activeIndex 定位与高亮。
```

| 组合 | 页面含义 |
| --- | --- |
| UNKNOWN + UNSUPPORTED/NOT_IMPLEMENTED | 该来源/格式当前没有翻译查询路线（例如仅使用原文 `LrcParser` 的默认值）；原文页继续工作，可隐藏翻译开关或显示不可用说明 |
| AVAILABLE + SUCCEEDED | 展示已有译文；缺译文的行仅显示原文 |
| AVAILABLE + PENDING | 保留双语，提示正在检查，不让整个歌词页重新进入加载态 |
| AVAILABLE + FAILED/TIMEOUT | 保留双语，仅提示翻译更新超时，不能改成“暂无翻译” |
| NONE + SUCCEEDED/NOT_PROVIDED | 平台本次未提供可用翻译 |
| UNKNOWN + FAILED | 翻译查询失败，不能断言平台没有翻译；原文继续显示 |

翻译开关只改变展示，不重新请求歌词、不改变音频，不随进度 tick 发请求。无译文的行不预留空副行；具体布局由 DS/cc 决定。前端可在 `state.canRefreshTranslation` 时调用 `app.lyrics.refreshTranslation()`；PENDING 时可禁用重复点击。后端仍用独立 revision 拒绝旧刷新结果。此动作不提交播放器命令，不重取原文、不进入整页 LOADING；原文/既有译文/activeIndex 保持可用。没有逐行同步原文时不可刷新译文。

仅用于设计样张/测试的双语示例（不替换生产订阅结果）：

```kotlin
val sample = LyricsState(
    status = LyricsStatus.TIMED,
    lines = listOf(
        LyricLine(1_000, "First line", "第一行"),
        LyricLine(3_000, "Second line"), // 部分缺译文
    ),
    plainText = "First line\nSecond line",
    activeIndex = 0,
    translation = LyricsTranslation(
        TranslationAvailability.AVAILABLE,
        TranslationLookup(TranslationLookupStatus.SUCCEEDED),
    ),
)
```

`app.lyrics.refresh()` 仍是既有的**整份原文歌词刷新**：进入 LOADING，失败进入 ERROR，当前不承诺保留此前内容。独立译文查询使用 PENDING/FAILED，与整份刷新分开；不能把整份 fetch 的失败伪装成“仅译文失败”。整份原文成功后自动启动一次译文查询，平台异常只影响可选译文。

## 隔离和降级边界

`LyricsBrowser` 透传每行译文及文档元数据，缓存最多 8 份完整文档。保持原 sourceKey、原队列及双账号代数校验；换源/切歌/取消/账号变化时新状态不能携带旧文档译文，旧回调不能复活。缓存命中时原文/译文一并命中；若离开前译文仍 PENDING，返回后重新启动可选查询并拒绝上次回调。seek 只更新同一个 activeIndex，不触发翻译请求。

三平台适配层独立校验译文：译文类型、Unicode、控制字符、长度或时间格式异常返回原文及 FAILED 元数据，不抛成整份原文错误。酷狗额外下载失败同样保留成功的 LRC 原文。原文请求/解析失败仍走原有 ERROR。AVAILABLE + FAILED 仅允许保留同份原文的已验证译文；独立刷新已实现；成功的 NONE 会清除此前译文，失败/不支持/版本不符只更新 lookup 并保留同份原文的已验证译文。网易云/QQ刷新返回的原文须与初始原始文本全等，采取保守校验；不跨版本复用。


## 原文与译文请求的阶段边界

生产 `LyricsRepository.fetchOriginal` 先交付原文；`fetchTranslation(key, original, refresh, callback)` 在账号串行 worker 上处理可选译文。原有 `fetch` 完整文档接口供兼容/debug 使用。网易云/QQ第一次译文使用同一次响应，不重复 HTTP，但对齐在原文发布后执行；显式译文刷新才重新获取平台同份响应并严格核验原文。酷狗先 search+LRC，随后用内存 `LyricsOriginal` 句柄的同 id/accesskey 下载 KRC；句柄绑定当前 API/session，刷新不重新搜索候选，也不重新下载 LRC。

Browser 原文请求携带当前目标/双账号代数/revision guard，失效结果不得注册仓库上下文；文档整份刷新、淘汰及账号变化会同步释放对应上下文。两层最多保留8份，避免迟到原文挤掉仍可显示文档的译文上下文。上下文、候选票据和原始响应只在 accounts/platform 内存，不进入 UI、日志或磁盘；未增加业务服务器、依赖或机器翻译服务。二参数 `LyricsBrowser(identity, fetch)` 保留，含旧 trailing lambda 调用。

## 当前平台与对齐规则

网易云在原有同一次歌词请求中读取 `lrc.lyric` 和 `tlyric.lyric`，不增加端点或业务服务器，不把罗马音当译文。`LyricsTranslationAligner.parse(original, translation)` 保持原文 plainText、显示行、timeMs 与索引；分别应用 offset，并在负时间归零前配对。先预留精确时间戳，重复精确时间按文件顺序一一配对；再在精确锚点之间尝试 ±100ms，存在多个可匹配原文或多条译文争用时不附加容差译文。不跨锚点、不复用行、不按行号拼接。

QQ 沿用 `GetPlayLyricInfo`，`crypt=0/qrc=0/roma=0/trans=1/trans_t=0`，同响应分别读取 `lyric`、`trans`。原文解码保持既有规则；译文支持标准明文或 Base64 LRC，原始表示最多 350KiB、解码文本最多 256KiB。短且无 padding 的 ASCII 可能是普通文字，解码不能确认 LRC 时交给 core 按未同步格式处理；明确编码失败、hex 密文、非法 Unicode/控制字符、超限均标记 FAILED/INVALID_DATA。无 QRC 解密、罗马音或 AI 翻译。译文可读取不代表该曲音频有权限；换源后依据实际 sourceKey 显示对应平台原译文。

网易云/QQ排除 `//` 占位后，至少 80% 的译文时间行须能配对，否则整份译文为 FAILED/VERSION_MISMATCH、原文继续显示。这是译文时间轴的配对率，不是要求 80% 原文都有翻译；复制原文的行参与时间核验但不展示，只要剩余一条可显示译文即可 AVAILABLE。时间相容仍不能证明语义或录音相同，生产输入必须来自同一平台响应；不接受跨来源同名歌词硬贴。

酷狗 `lyricsDocument()` 只搜索一次实际 hash+album_audio_id，复用首个候选的 id/accesskey 先下载 fmt=lrc，再可选 fmt=krc。KRC1 严格 Base64、XOR、有界 zlib、严格 UTF-8，允许文本开头一个 BOM；只读取整数 version=1、type=1/language=0 翻译，type=0 罗马音忽略。先按 KRC 原文件行号绑定译文（含空行），再稳定排序非空原文行，与 LRC 的 signed time 逐行验证：数量完全相等、文本 trim/折叠连续空白后相等、时间差不超过100ms。任一不符整份为 UNKNOWN/FAILED/VERSION_MISMATCH；不套用网易云/QQ的80%规则，不用最近时间猜译文归属。附加后仍精确保留原 LRC 的文字、plainText、timeMs 与索引。

KRC容器及解压各1MiB，解压过程中限量；language JSON 256KiB、深度8、最多16块，拒绝重复键、宽松JSON和歧义翻译块。KRC最多10000源行、每行原文/译文4096码点，原文总UTF-8 512KiB、译文256KiB。已验证兼容但无翻译块/仅罗马音/全占位为 NONE；未知数值版本/翻译类型为 FAILED/UNSUPPORTED_FORMAT，损坏数据为 FAILED/INVALID_DATA，网络/超时/平台失败分别保留固定reason。空损坏下载不能当无译文。

原文 UTF-8 上限 512KiB，译文 256KiB；LRC沿用单行4096码点、最多10000个分割后源行（含末尾空行）及20000时间行等边界。最多8份内存文档，不落盘歌词。纯文本/无原文时间行沿用 NOT_SYNCED 或 NO_LYRICS。**历史阶段（独立译文刷新落地前）**，酷狗在一个后台逻辑操作内顺序完成 LRC/KRC，原文等可选请求结束后一起返回；当时没有原文先发布/译文稍后补发，也没有 `refreshTranslation()`。当前流程已改为原文先显示、译文后补并支持独立刷新，以顶部状态和“原文与译文请求的阶段边界”为准。翻译开关与 seek 不发请求。

## 第一阶段历史验证

隔离后端工作区 `./build.ps1 -Test -Lint` 成功：292 项 JUnit 全通过，0 failures/errors/skipped（app 11、core 169、playback 45、酷狗 28、QQ 22、网易云 17）。本阶段新增 6 项契约测试；六个 Android 模块 Lint 0 errors/12 warnings，与前轮数量一致。测试用合成文档验证兼容性、部分译文/同索引 seek、失败状态表示、换源与账号隔离、迟到结果和缓存；不构造线上成功结论。第一阶段当时独立译文刷新尚未实现，后续阶段已补做并通过对应测试。

4 个源码/测试文件在确认主工作区原文件散列未变、无并行冲突后同步；主工作区 `:app:compileDebugKotlin :app:compileDebugUnitTestKotlin` 成功，覆盖当前前端及其样张测试的源码编译兼容性，未运行前端截图/交互测试。未操作账号或设备，未改前端文件、平台协议、依赖或原词解析算法；未提交、推送或更新 PR。

隔离 debug APK SHA-256：`750defaa7988c34b48b87854828edcf0fd60195f935f1f9c7f306f55381cf350`，v2 签名校验通过，未安装设备。这个产物不包含主工作区正在开发的正式前端。

## 第二阶段历史验证（网易云译文）

通用对齐及网易云译文已实现，最终 311 项测试全通过，Lint 0 errors/12 warnings；主工作区前端/单测源码编译通过。Android 16 实际请求得到 Lemon 46 行原文/42 行译文、Hello 53/51，另一个“晴天”标题命中 16/0、NONE/NOT_PROVIDED。播放 seek、换源后移除旧译文、进程重开不自播/显式暂停恢复及部分逻辑计时已留证。具体 APK 归属、注入边界、最终复测与未覆盖项见[验收记录](lyrics-translation-acceptance-20261022.md)，页面视觉、真实听音与后台/Binder 专项不在本次通过范围。

## 第三阶段历史验证（QQ 译文）

QQ阶段318项测试全通过，Lint 0 errors/12 warnings；主工作区源码编译兼容。QQ实际Lemon57/54、Hello47/41，晴天标题命中63/0、NONE/NOT_PROVIDED；实际QQ替代来源57/54及暂停seek30s/5s有状态证据。最终包复测、版本与注入边界见[QQ验收记录](lyrics-translation-qq-acceptance-20261022.md)。该阶段尚无酷狗KRC，现由下一节更新；独立译文刷新在第三阶段当时尚未实现，后续已落地，见顶部当前状态。

## 第四阶段历史验证（酷狗 KRC 译文）

350项测试通过，Lint 0 errors/12 warnings，10个后端/测试/debug/NOTICE文件同步主工作区且编译通过。酷狗实际Lemon57/54、Hello47/41、My Love61/55，晴天63/0。歌词读取成功不代表音频有权限，实际点播发生的换源及最终包复测以[酷狗验收](lyrics-translation-kugou-acceptance-20261022.md)为准。该阶段之后已补原文先显示、译文后补和独立刷新；当前待办为正式前端译文 UI 与整体验收、逐字/QRC和机器翻译。
