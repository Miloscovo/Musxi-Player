# 后端需求单 · 第二批（2026-11-01，前端 -> Codex）

> **状态：历史需求单（2026-11-01，第二批）。** 本文保留当次需求和优先级；测试数、工作区与提交前提只属于当时阶段。当前状态与下一步见 [当前开发接续](next-session-handoff.md)，文档分类见 [文档入口](README.md)。

读者：负责 accounts / core / playback / platform-* 的后端开发。约定同 [第一批](backend-requests-20261101.md)：不改 `app/src/main/kotlin/tech/musxi/player/ui/`，接口变化写进本文末尾"交付说明"。行号以 2026-11-01 工作区为准，开工前复核。

来源：前端交互重构的需要，以及对照 PC（`Miloscovo/Musxi-Player` main = v0.6.0，本地 `C:\text\Musxi-Player\Musxi-Player`，读 main 请用 `git show main:<path>`，工作区在别的分支）的全功能核对。账号会话、收藏、加歌单、搜索、歌词翻译、换源规则安卓已持平或更严，不在本单。

**开工前提**：主代理会先把当前整个工作区（含你上一轮全部改动）提交为存档点并推送。请在那之后的 main 上继续，避免两轮改动混在一起。

## P0 · 换源与播放状态（前端正在重做换源与播放页，直接依赖）

### 1. 失败原因透出
- 现状：`PlaybackFailure` 内部有十类，但界面只拿到"失败"，只能显示"播放失败，请检查网络"（`UiModels.kt:28`）或"暂无可用音源"（`FullPlayerUi.kt:116`）。换源候选的 `playbackFailures` 已按平台记录，但原因粒度停在 `PERMISSION`。
- 目标：用户能区分"需要会员 / 无版权 / 仅试听 / 网络 / 账号失效 / 平台不可用"。平台无法区分时如实给"权限受限（可能需会员或无版权）"，不臆断。
- PC 参考：酷狗 status 2/3 且无 URL 记为 rights（`services/bridge.cjs` L40-43）；网易 `freeTrialInfo` 视为无权限（`services/netease.cjs` L53）；QQ 校验 filename/ekey（`services/qq_bridge.py` L127-130）；全部档位被拒汇总为权限问题（`services/multi-platform.cjs` L65-66）。
- 建议接口：`PlaybackState.failureReason: FailureReason?`（枚举 + 可选平台），换源 `playbackFailures` 与候选同用此枚举。
- 验收：三平台各一个夹具映射到正确枚举；未知原因不得被归为"会员"。

### 2. 原曲与实际音源的元数据分开
- 现状：换源后播放状态的 artist/album 被替换为音源录音的信息。真机样例：酷狗《晴天》换到网易云后显示"Jay · 晴天"，用户误以为原曲信息变了。
- 目标：`PlaybackState` 同时提供原曲（队列原始项）与实际音源录音的 标题/歌手/专辑/封面/平台。前端主显示原曲，第三行写"由网易云 · Jay 的版本提供"。
- 约束：不改变队列身份与 ticket 规则；通知栏/系统媒体会话显示哪一份，请说明并保持与前端一致（建议原曲标题 + 实际歌手，或沿用现状，二选一写明理由）。
- 验收：换源前后原曲字段不变；音源字段随选中候选变化；单测覆盖。

### 3. 核查自动换源是否过宽（待产品负责人确认这一首是否手动所选）
- 现象：上面的《晴天》，音源歌手为"Jay"、专辑为"晴天"，封面不是《叶惠美》，疑为他人上传的翻唱版。按 B4 规则（完整歌手集合一致）不应自动选中。
- 请查：这次是 AUTO 还是 MANUAL；若 AUTO，换源提速改动是否放宽了 `SourceMatchPolicy`；"Jay" 与"周杰伦"是否被别名规则视为同一歌手。确认后在交付说明写结论；若规则被放宽，恢复严格匹配并补夹具（同名翻唱、上传者昵称、版本标记）。

## P1 · 前端重构需要的数据

### 4. 在线歌曲手动选歌词
- 目标：自动匹配错时，用户搜索并选定另一份歌词，可调整整体偏移，可恢复自动。
- PC 参考（仅本地歌曲）：`src/application/lyrics_match.inc` L60-172（begin/search/preview/adjust/save/reset/cancel/poll），三平台搜索每页 30、页码 1-10（`services/lyrics.cjs` L8-17），偏移 ±600 秒；前端 `frontend/src/native/lyricsMatch.ts`。
- 安卓现状：无；`LrcParser.kt` 只解析 `[offset:]` 标签。
- 建议接口：`lyricsMatch.search(original, platform, keywords, page)`、`preview(candidate)`（不落盘）、`bind(original, candidate, offsetMs)`、`reset(original)`；绑定按 MediaKey + 账号 owner 本地保存；`fetchOriginal` 先查绑定。歌词状态带 `origin = AUTO | MATCHED` 与 `offsetMs`。
- 验收：预览不落盘；切歌、换号取消在途请求；绑定在冷启动后生效；换号/登出清除。

### 5. 随机播放后的真实"接下来"顺序
- 现状：`PlaybackQueueState` 只有原始 `songs`（`PlaybackQueueState.kt:7-11`），开启随机后队列面板仍按原顺序显示，用户看不出下一首是谁。
- 建议接口：`PlaybackQueueState.upcoming: List<Int>`（按实际播放顺序排列的槽位索引，从当前项之后开始，取 Media3 timeline 的 shuffle order），以及 `currentIndex`。拖动/删除在随机开启时的语义请说明（建议：编辑作用于原始顺序，upcoming 随之重算）。
- 验收：随机开/关、切歌、编辑后 upcoming 正确；单测覆盖。

### 6. 歌单类型字段（第一批 §4b，仍未做）
- `PlaylistSummary`（`LibraryModels.kt:10`）补 `kind: LIKED | CREATED | COLLECTED | OTHER`。依据见第一批 §4b。前端拿到后删除按名称猜测的临时规则。

### 7. 最近播放：单条删除与完整页
- 现状：`clearRecent` 已有但无界面调用；无单条删除；每个平台最多 100 条（`FilePlaybackJournalStore.kt:343`）。
- 建议接口：`playbackJournal.removeRecent(platform, key)`；评估上限是否提到 300（PC 为 500，同曲多源合并）。
- 验收：删除后持久化；换号隔离。

### 8. 歌词磁盘缓存
- 现状：歌词只在内存保存约 8 份，冷启动必重取，离线无歌词。
- PC 参考：命中 30 天、无词 24 小时、失败回退旧缓存、上限 256 条 / 16 MiB（`src/application/lyrics.inc` L280-326，`docs/online-lyrics.md`）。
- 建议：`LyricsCacheStore`，键为 实际音源 MediaKey + 账号 owner，含译文与第 4 项的绑定信息；不存任何凭证或签名 URL。
- 验收：断网冷启动可显示已缓存歌词；换号/登出清除；超限淘汰。

## P2 · 设置页需要的能力

### 9. 会员状态只读识别
- 目标："我的"页账号行显示会员徽标与到期时间；播放失败时可提示"需要会员"。
- PC 参考：酷狗 `services/bridge.cjs` L430-443（概念版 VIP / 畅听 VIP，到期按东八区）；网易 `services/netease.cjs` L90-95；QQ `services/qq_bridge.py` L40-77；汇总 `services/multi-platform.cjs` L139-157（三态，含"会员状态暂不可用"）；测试 `services/vip.test.cjs` L75-89。
- 建议接口：`AccountState.membership: Membership(level: YES | NO | UNKNOWN, name, expiresAtEpochMs)`。
- 约束：只读。**不移植 PC 的酷狗"自动领取当日 VIP"**（`vip_auto`，改写账号状态、依赖私有接口）。UNKNOWN 不得显示为"非会员"。

### 10. 音质补全与全局默认
- 现状：`PlaybackQuality.kt` 酷狗 3 档、QQ 3 档、网易 4 档，无 Hi-Res；酷狗只走 `/v5/url`，无授权取流；偏好按平台记忆，无"默认音质"入口。
- PC 参考：酷狗 Hi-Res 用 `privilege_lite` 取变体并校验 FLAC 头（`services/bridge.cjs` L224-245、`services/kugou-audio.cjs`）；网易 hires（`services/netease.cjs` L5）；VIP 基础档回落到授权取流（`services/bridge.cjs` L182-223）；默认音质 128/320/flac/best 逐级回落（`services/multi-platform.cjs` L55-67）。
- 建议：设置项"默认音质：标准 / 高品 / 无损 / 最高可用"，按平台映射到最近的已确认档位并逐级回落；补网易 hires、酷狗 Hi-Res 与授权取流。
- 验收：复用 PC `bridge.test.cjs` L233-321、`kugou-audio.test.cjs` 的场景；会员静默降级需真机确认后再声明。

### 11. 更新检查（精简版）
- 目标："关于"页可检查新版本并打开发布页。**不做应用内下载安装**（不申请 REQUEST_INSTALL_PACKAGES）。
- PC 参考：`src/updates/releases.cpp`、`manager.cpp`，`docs/app-updates.md`（GitHub Releases、含预发布、30 秒节流、限流退避、忽略此版本）。
- 建议接口：`UpdateRepository.check(includePrerelease)` → 无新版 / 有新版(版本、说明、发布页 URL) / 限流 / 失败；"忽略此版本"本地保存。仓库为 `qwt1011/Musxi-Player-Android`。
- 验收：四种结果各一个夹具；不在启动时阻塞。

## P3 · 可选优化

12. 酷狗无封面歌单的封面补全改为每 3 个并行（现 `KugouLibraryRepository.kt` 约 68-83 行串行，最多 6 次）。先确认 KugouApi 会话并发安全；单项失败回退占位，顺序不变。
13. `LibraryBrowser` 30 秒内重复打开同一页时跳过联网（PC `services/multi-platform.cjs` L26-34）。只记成功联网时间；`refresh()`、重试、换号、invalidate 必须联网；不影响"先显示磁盘缓存"。

## 不做

酷狗自动领 VIP；合并多平台搜索与"云端收藏整合"（安卓刻意单平台）；本地音乐与本地歌词匹配（需 MediaStore/SAF，另立项）；桌面歌词、快捷键、托盘（安卓由 Media3 会话覆盖）；壁纸（纯前端，Photo Picker）；新建/删除歌单与移出歌曲（PC 也没有，另评估）。

## 交付说明（Codex 填写）

2026-11-01 第二批后端已由接续对话整合，逐项状态、接口、验证范围见 [第二批交付](backend-batch2-delivery-20261101.md)。该记录独立于第一批的收藏/Doze 真机验收。

| 项目 | 后端状态 |
| --- | --- |
| 1–3 失败原因、原曲与音源、匹配核查 | 已实现并补回归；缺少历史身份数据，不能认定《晴天》那次为自动或手动选择。 |
| 4–8 歌词匹配、随机顺序、歌单类型、最近播放、歌词缓存 | 已实现；最近播放上限 300，完整页布局由前端负责。 |
| 9 只读会员 | 三平台查询、YES/NO/UNKNOWN、连接后自动查询与旧代次拒绝已接通；会员徽标/到期展示待前端接线。查询失败不改变登录状态。 |
| 10 音质与全局默认 | 网易 Hi-Res、酷狗授权取流与 Hi-Res 探测、默认偏好及回退已接通；默认音质设置入口待前端接线。真实会员取流/静默降级尚未验收。 |
| 11 更新检查 | 已实现手动检查、节流/限流、忽略版本及安全发布页 URL，前端已接入。 |
| 12–13 可选优化 | 酷狗封面复用 3 线程补全前 6 项；同页成功联网后 30 秒节流已实现，刷新/重试/失效/换号绕过。 |

当前设备未连接，第二批不追加真机通过结论。交互改版、设置页和第一批尚待补验的自然长后台/真实歌单写入不计为本批后端完成条件。
