# 歌词翻译后端路线与当前进度

2026-10-22 B1-08 最新：前台资格门禁已实现并同步主工程，先由 Media3 建立前台服务，再单次请求音频焦点；等待最多3秒，暂停/停止/换队列使旧请求失效，系统拒绝前台启动时返回 `BACKGROUND_RESTRICTED`，须前台明确重试。隔离后端392项、主工程515项测试全通过，Lint均无错误（分别9/76条警告，范围不同）。最终后端包酷狗实际播放、受控超时/暂停取消/显式重试通过；最终包两次真实QQ→网易云换源均因NETWORK停止，未将完整闭环记为通过。前一候选包后台换源及用户听音证据保留其包归属。见[B1-08修复与验收](backend-b108-acceptance-20261022.md)。

2026-10-22 晚间：原文先显示、译文随后补入与 `app.lyrics.refreshTranslation()` 已实现，`LyricsState.canRefreshTranslation` 已可接线。酷狗实际 KUGOU 来源 Amazing Grace 搜索样本18/17行、播放及seek已补；三平台真实收藏切换均完整恢复（5/7/746）。控制器恢复与服务丢队列等注入边界、369项测试/Lint 0 errors/12 warnings及主工程编译见 [今晚验收](backend-evening-acceptance-20261022.md)。锁屏/长Doze/真实系统Binder回收/独立应用焦点和正式页面仍待补；前端继续由DS主导、cc辅助。

2026-10-22。网易云、QQ与酷狗KRC行级译文均已实现；酷狗新增有界解码、同候选复用及LRC原文全量兼容校验，350项测试通过，实际Lemon57/54、Hello47/41、My Love61/55、晴天63/0。详见[当前契约](frontend-backend-contract-lyrics-translation.md)及[酷狗验收](lyrics-translation-kugou-acceptance-20261022.md)，此前见[网易云](lyrics-translation-acceptance-20261022.md)/[QQ验收](lyrics-translation-qq-acceptance-20261022.md)。今晚已实现独立刷新及原文先显示；下列相关待办为历史背景，以顶部进度及最新契约为准。后端待补实测见[登记](backend-device-followup.md)。前端由DS主导、cc辅助，本轮不改页面。

## 已实现范围与样本边界（2026-10-22 校准）

第一阶段追加行级translation和文档状态；三平台现均使用结构化LyricsDocument。网易云/QQ使用通用LRC对齐；酷狗单独做KRC文件索引绑定、signed time/全量原文校验，再按已验证索引附加。异常译文保留原文，前端统一消费已对齐的LyricLine.translation。

| 平台 | 已读代码事实 | 待核对 |
| --- | --- | --- |
| 网易云 | 同次 song/lyric 请求取得 lrc.lyric 与 tlyric.lyric，tv/lv/rv/kv=-1；Android 已实现类型/限长/Unicode校验和 LRC 对齐、独立译文刷新及同份原文兼容保留，真机有译文/无译文样本通过 | 更多录音/部分译文的设备样本；罗马音不算译文 |
| QQ | GetPlayLyricInfo 带 crypt=0、qrc=0、trans=1、trans_t=0，同响应 lyric/trans，明文或标准Base64 LRC；实际Lemon57/54、Hello47/41、晴天63/0 | 更多版本/账号/网络样本；译文成功不代表音频有权限；无QRC解密 |
| 酷狗 | 实际hash+album_audio_id只搜索一次，同候选LRC/KRC；KRC1严格Base64/XOR/有界zlib/UTF-8/JSON，v1 type1/language0；原文全量文本/时间兼容后附译文；原文先显示、独立刷新及真机有/无译文已验证 | 更多录音/异常网络；早期固定外语样本实际换到网易云，晚间另有实际 KUGOU Amazing Grace 18/17行及播放/seek，按各包记录判断 |

PC参考已只读核对：`<pc-source-root>\docs\lyrics-translation.md`、`docs/lyrics-translation-reliability.md`、`services/netease.cjs`、`services/qq_bridge.py`、`services/krc.cjs`、`src/lyrics/krc.cpp`及`src/lyrics/lyrics.cpp`。Android当前实现及设备结果以验收为准，本轮没有重新验证PC线上结果，不修改迁移前安卓副本。

优先做平台已有译文读取；平台没有译文时原文照常可用。自动机器翻译可作为后续独立功能讨论，需要选服务/模型、传输与缓存策略、费用和译文来源标识，不是当前双语显示的前置条件。

## 设计背景与已落实边界

1. 已实现的平台适配层返回结构化歌词包：原文、可选译文及译文可用状态；原文和译文分别解码、限长、解析。原文成功而译文格式错误/缺失时，保留原文，不将整份歌词置 ERROR。第一阶段的 LyricsTranslation/TranslationLookup 已确定显示与查询状态，平台结构化歌词包已在后续适配阶段实现。
2. core 已增加按时间轴对齐的纯函数。原文作为唯一播放时间轴；同时间戳优先精确配对，可在测试确认后添加小范围、单调且不复用译行的容差匹配。不按行号 zip，不把上一句译文硬贴下一句；缺行、额外行、重复时间戳、offset、复唱多时间戳需专门测试。一个原文行可能对应多个译文片段，无法可靠对齐的留作独立文本或不显示行内翻译。
3. 第一阶段已选用 `LyricLine.translation: String? = null`，并在 ParsedLyrics/LyricsState 追加文档 translation 状态，类型与语义见接线契约。前端只用同一个 activeIndex/position 高亮原文与译文，不另跑译文播放时钟。纯文本原文/纯文本译文不能宣称逐行同步。

   PC 还将“已有可显示译文”和“最近一次查询结果”分开保存：已有译文、刷新失败时继续显示旧译文，只提示查询失败。安卓现已沿用此语义，且仅对同一份原文保留译文；刷新拿到另一版原文时先做文本/时序兼容核验，不能原样套用旧翻译。
4. 保留 LyricsBrowser 的实际 sourceKey、原队列及双账号代数隔离和最多 8 份内存缓存。切歌/换源/账号变化一并清掉旧译文；禁止从原队列录音取译文覆盖当前替代录音。扩展后需限定原文+译文的总量，避免缓存内存翻倍无上限。
5. 优先单次平台响应同时取原文与译文；译文需额外请求时让原文先显示，按请求代数拒绝旧译文，失败不影响音频。UI 显示翻译开关只控制展示，不应在每次播放进度更新时重复请求。

酷狗单独作为扩展：参考 PC 的 krc1 校验、XOR+有界 zlib 解压和严格 UTF-8/JSON 限制；仅识别已验证的 v1 content、type=1/language=0 译文块，type=0 罗马音不冒充翻译。KRC 行与 LRC 原文需逐行核对文本/时序，匹配后再附译文；未知结构、歧义块、行数不符均保留“无法确定”，原 LRC 继续可用。解压限制不能只在解压完成后检查。

## 分步交付建议

字段/状态、通用对齐、三平台译文、原文先显示/译文后补及独立译文刷新均已落地。`app.lyrics.refreshTranslation()` 在 sourceKey/账号代数/原文兼容门禁后合入译文，失败保留同份原文已有译文；`canRefreshTranslation` 供前端判断入口。`app.lyrics.refresh()` 仍是整份重载。正式前端已接原文首版，双语渲染/开关/刷新入口由用户与 DS、cc 继续讨论；后端本轮优先修 B1-08。逐字、QRC、罗马音和外部翻译服务不纳入当前交付。

酷狗阶段350项测试包含KRC容器损坏/解压限量/BOM/严格JSON、行号绑定/空行/罗马音、全量文本/时间/数量核验及失败保留原文；精确设备范围以验收为准。更多样本、正式页面滚动/布局、真实听音和未执行的B1/B2/B3项仍需补验。上述“设计背景与已落实边界”保留设计背景；KRC已实现，独立刷新与原文先显示已完成；接下来补正式页面、多轮性能、剩余设备专项与B4。
