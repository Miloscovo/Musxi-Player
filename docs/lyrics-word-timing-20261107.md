# 逐字歌词：参考核查与酷狗后端首轮

状态：2026-11-07 开发增量。用户暂缓歌单前端，允许参考 PC、本地资料与 GitHub 推进歌词后端。本轮完成统一字词模型、酷狗 KRC 接线、播放时钟定位及缓存；QQ QRC、网易云 YRC、罗马音和逐字渲染仍待接续。验证结果在本文末尾登记。

同日后续 QQ QRC 接入见 [独立接续记录](lyrics-qq-qrc-20261107.md)。本文保留酷狗阶段的原始范围、包和设备证据，不把后续代码/验证合并为当时已完成。

## 参考结论

核对本地 PC `C:\text\Musxi-Player\Musxi-Player` 的 `e72fba9feb6da22a1b8b207ba2fa07b29b96d36c`，并通过 GitHub API 核对 [上游 main 固定提交](https://github.com/Miloscovo/Musxi-Player/tree/824a1dfd8089f2cd9699f6da490e05727d3fe335)（2026-11-04）和个人 fork 的 main/歌词分支。未切换、合并或修改 PC 工作树。

- PC [lyrics.hpp](https://github.com/Miloscovo/Musxi-Player/blob/824a1dfd8089f2cd9699f6da490e05727d3fe335/src/lyrics/lyrics.hpp) 明确把 KRC 字词时间降为行时间；`Line` 只有行时间、原文和译文。`lyrics.cpp#stripWordTags` 清除字标记，QQ `qq_bridge.py` 明确 `qrc=False`，网易云只消费 `lrc/tlyric`。已检查的代码中没有可直接搬入安卓的完整逐字链路。
- 沿用 PC 的 KRC 容器、有界解压、同候选 LRC/KRC 校验、失败保留原文和测试输入。Android 现有解码器已落实其中大部分，本轮在其上扩展。[PC parser 测试](https://github.com/Miloscovo/Musxi-Player/blob/824a1dfd8089f2cd9699f6da490e05727d3fe335/tests/lyrics-parser.cpp) 中 `[1200,2000]<0,400,0>Hel<400,600,0>lo` 用作新测试的格式依据；PC 只断言整行，Android 新增字词断言。
- 本地 MeiloX 固定 [bc038c9e9a01f37484a13295b2c3a8c3e44d8804](https://github.com/NEORUAA/MeiloX/tree/bc038c9e9a01f37484a13295b2c3a8c3e44d8804) 的 `QRCParser.kt`、`YRCParser.kt`、`SystemLyricTimeline.kt`、`LyricTiming.kt` 及测试可参考。借鉴音节起止、暂停/seek共用播放时钟、行字同偏移的设计，未复制其源码或引入依赖。MeiloX 主体 GPLv3，Musxi 继续 GPL-3.0-or-later。
- 没有采用参考项目的绝对/相对时间启发式、3秒翻译最近邻或为坏数据制造时间等规则。KRC 按明确相对行首时间解析，保留现有全量原文校验和100ms行级容差。LoveACE、Glasmio 未发现可用歌词后端。

Git HTTPS 曾超时，GitHub API/raw 随后成功；结论固定到以上提交。少量远端文件保留在项目 `.local/tmp/pc-lyrics-824a1df/` 供本轮对照，不含账号或平台私有响应。

## 接口与时间语义

`LyricLine` 末尾追加 `words: List<LyricWord> = emptyList()`，原调用兼容：

```kotlin
data class LyricWord(
    val textStart: Int,
    val textEnd: Int,
    val startTimeMs: Long,
    val endTimeMs: Long,
)
```

文字范围是该行展示文字的 UTF-16 半开区间，不拆代理对。一段可以是字、词或音节，不假定等长等时。时间是曲目绝对毫秒，未施加手动偏移。KRC 字标签相对行首，解码时加行首和文件 offset；沿用 Android 既有文件 offset 加法，未照搬 PC 减法。字词负时间最终归零，对齐继续使用 signed 行时间。

`LyricsState` 追加 `activeWordIndex`（默认-1）与 `activeWordProgress`（0..1）。当前行沿用 `activeIndex`，字词定位使用播放位置减 `offsetMs`；暂停不另跑时钟，seek双向重算，不发请求。间隙保留最近已开始段及进度1，零时长到点完成，重叠段选择最后开始的一段。无字词时为-1/0。

`LyricsWordTimeline.sample(words, positionMs)` 是纯采样函数。现有播放器 tick 为500ms，新增状态不是逐帧动画接口；前端平滑渲染需据播放时钟采样并校正暂停/seek。正式页面仍按行渲染，本轮未实现逐字视觉。

## 校验、失败与缓存

- 复用同 hash/candidate 的 LRC/KRC 请求，全量行数、规范化文本、signed时间校验仍先于附加。
- 翻译可使用已有空白规范化校验，但字范围要求 KRC/LRC 展示文字精确相同。仅空白不同仍可显示有效译文，字词降级为空，不修改原 LRC。
- 新增字词约束失败仅丢字词，保留行级原文/有效译文；原有损坏容器/语法失败语义不放宽。每行最多4096段、全文100000段、时间上限24小时；原有容器、解压和文字上限不变。
- 可选查询失败保留同份原文已有字词，原文/来源/账号不同不得复用；明确成功无字词可清除旧字词。
- 磁盘格式2保存有界字范围与时间，读取再次校验并兼容格式1。旧词仍可离线显示，不生成虚假字时序；整份刷新可重新取词。owner、TTL、大小限制及手动绑定隔离不变。
- KRC 仍在可选轨道阶段下载，字词与译文渐进补入；无译文的成功 KRC 同样可有字词。QQ/网易云本轮仍只有行级歌词。

## 验证与剩余事项

本轮统一入口 `pwsh -NoProfile -File scripts/verify.ps1` 通过，证据为 `.local/artifacts/verification/20261107T065354466Z-16216/summary.json`：1747项JVM测试，0失败/错误/跳过，六Android模块Lint为0错误/118警告，debug主包及设备测试包编译通过，源码冻结核对通过。本轮新增19项字词相关测试，1747总数包含共享树既有后端及CC新增测试。主APK SHA-256：`ace539015d72316cfff17b3e8276e80d597161352efcc21d09622a75aec4c1ba`。

新测试覆盖PC格式、UTF-16、offset、空段、异常降级、缓存往返/旧格式、暂停/seek、迟到结果和失败保留。`KugouWordLyricsDeviceTest` 是固定只读专项，读取两首真实样本、校验范围及临时缓存往返，不播放、不写收藏或绑定；首轮电脑阶段仅编译，后续手机执行结果见下节。

另在电脑JDK17上，以本轮Gradle产出的生产 `KrcLyricsDecoder`、`KrcTranslationAligner`、`LyricsWordTimeline`、`FileLyricsCacheStore`，核对官方公开keyword搜索、同候选下载的实际LRC/KRC：

| 样本 | 原文行 | 带字词行 | 字词段 | 带译文行 | 原文/行时间不变及缓存冷读 |
| --- | --- | --- | --- | --- | --- |
| Lemon / 米津玄師 | 55 | 52 | 528 | 0 | 通过 |
| Hello / Adele | 25 | 25 | 149 | 25 | 通过 |

这两份是本轮公开歌词候选，未与播放音频核对，也不等同于11-03手机样本。证据、源码/输入散列及一次性探针保留在 `.local/artifacts/krc-word-20261107/`；公开歌词正文、候选accesskey和临时缓存已清理。主包和设备测试包各保留一份，便于后续按本轮包补手机专项。完整验证后仅追加本文和开发接续中的结果，生产/测试源码未再修改，追加后的文档链接另行检查。

首轮电脑阶段 ADB 未检测到设备，上述桌面证据不代表手机通过；用户随后连接手机，补验记录如下。后续分别接 QQ QRC 解密/请求与网易云 YRC；MeiloX 解析器可参考，仍需核实实际响应。

## 手机追加验收与缓存修复

用户连接 PJF110 / Android 16，并说明分屏游戏挂机。只操作 Musxi 开发包，未操作其他应用、未播放音频、未改账号/收藏/手动绑定或队列。覆盖安装保留应用数据，始终只运行 `KugouWordLyricsDeviceTest`，没有执行历史平台写入套件。

首次以 `ace53901…c4c1ba` 主包读取到有效字词后，在临时目录的 `FileLyricsCacheStore.write` 失败：父目录采用 `toRealPath()`，子目录采用 `toRealPath(NOFOLLOW_LINKS)`，Android 的父路径别名使两者比较不一致。修复为父子使用一致的 canonical 解析；仍在解析前后拒绝子目录符号链接，并要求解析结果精确属于根目录下的目标 owner。新增手机回归验证合法父路径别名往返、缓存根/owner链接拒绝和外部哨兵不变，实际记录 `legacyCanonicalComparisonMismatch=true`。

修复后统一入口 `.local/artifacts/verification/20261107T071805056Z-14244/summary.json` 完整通过：1747项JVM、0失败/错误/跳过、Lint 0错误/118警告及主包/测试包编译。主包 SHA-256：`c67d89eb7d38af8143ec8ef42dc20b221331117bb8bba891c76b2cb49ade8c21`，已安装并核对设备上的包散列。

第二轮路径回归和两首歌词/缓存均通过其对应断言，但最终账号一致性断言失败：用例只等待酷狗连接，取基线时QQ/网易云尚在自动恢复，随后由 `verified=false` 变为 `true`。修正用例为等待自动恢复阶段结束、播放器连接完成后再取基线，保留最终完整身份/队列相等断言。生产源码未再修改；最终用例在 `build.ps1 -AfterBuild` 的共享构建锁内重新编译设备测试包并运行 `:app:lintDebug`，均通过。最终测试包 SHA-256：`5d228968198390bd7bba5b2bbe246f480491096a1b212287849ccbe2b7d6cc20`。没有将这次仅设备用例的调整宣称为再次完整重跑1747项。

最终同一主包上的2项设备测试全部通过：

| 手机实际来源样本 | 原文行 | 带字词行 | 字词段 | 带译文行 | 生产缓存冷读 |
| --- | --- | --- | --- | --- | --- |
| Lemon | 57 | 57 | 538 | 54 | 通过 |
| Hello | 47 | 47 | 334 | 41 | 通过 |

测试结束时账号完整身份与活动队列同基线一致；两份原有播放日志SHA-256不变，QQ日志原先不存在且仍不存在。三轮均未恢复或覆盖历史日志。临时UUID测试目录已清理，设备仅保留固定的无凭证计数报告。证据索引为 `.local/artifacts/krc-word-device-20261107/final-summary.json`，包含失败轮次日志、最终报告、安装包散列和保留原因的本地README。

此结论覆盖单台Android16上真实歌词获取/解析、字范围、译文、临时生产存储往返和路径保护；没有进行音频与歌词逐字听音对齐、页面逐字渲染、长后台或跨系统验收。暂停/seek/手动偏移继续以对应JVM测试为依据。
