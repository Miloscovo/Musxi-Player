# 歌词翻译查询：接口与界面行为

本文件描述歌词翻译检查的接口、界面行为与验证结果。主歌词页支持三平台和有效本地绑定的主动检查，匹配预览显示同一套原因；保留旧字段以兼容既有客户端。

## 请求

沿用 `library.lyrics`，旧请求不变：

```json
{"source":"queue","id":"netease:123"}
```

用户点击一次重新检查时增加 `retryTranslation: true`。该标志仅随这一次请求发送，后续轮询恢复普通请求。

```json
{"source":"queue","id":"netease:123","retryTranslation":true}
```

主动检查适用于网易云、QQ、酷狗在线歌曲及已指定平台歌词的本地歌曲，当前译文为 unknown、none、available 都可检查。跳过译文新鲜期，不清缓存、不重置已有原文。已有相同查询时合并，期间返回 ready 的原内容和 pending 的翻译查询状态。

整份歌词尚未加载时，响应仍可为 `status: "pending"`；翻译的 pending 状态不等于整份歌词 pending。主窗口可以主动检查；桌面页继续只读当前播放歌曲，保持原有禁止强制重试的权限。

## 结果

继续保留现有 `translation`、`translationPending`、`translationRetryable` 和行内 `translation`。新增可选对象：

```ts
type TranslationLookup = {
  status: 'idle' | 'pending' | 'succeeded' | 'failed' | 'unsupported';
  reason?:
    | 'not_provided'
    | 'network'
    | 'timeout'
    | 'platform_error'
    | 'invalid_data'
    | 'unsupported_format'
    | 'version_mismatch'
    | 'not_bound'
    | 'not_synced'
    | 'no_lyrics'
    | 'internal_error';
  canRefresh: boolean;
};
// Lyrics.translationLookup?: TranslationLookup
```

translation 是当前可用内容的状态；translationLookup 是最近一次检查的状态。canRefresh 是查询能力，pending 时仍可为 true，前端应另行禁用正在查询的按钮。translationRetryable 在主歌词结果中与 canRefresh 一致，兼容旧前端的酷狗重试判断。

| status | 含义 |
| --- | --- |
| idle | 正在使用已存结果，此次尚未检查；不能单凭 idle 认定平台没有译文 |
| pending | 正在检查或等待已取消的旧 worker 结束；原内容可继续显示 |
| succeeded | 已取得可验证结果；reason=not_provided 表示本次平台未提供可用译文 |
| failed | 本次检查失败；原文与旧译文保留，错误原因不写为无译文 |
| unsupported | 当前原文没有可信在线绑定、不是同步歌词，或整首无歌词 |

| reason | 前端语义建议 |
| --- | --- |
| not_provided | 该版本本次未提供翻译 |
| network | 网络连接失败 |
| timeout | 本次查询超时 |
| platform_error | 平台请求失败；不能进一步假定是账号权限或网络问题 |
| invalid_data | 返回内容无法使用，或缺少可靠译文结果 |
| unsupported_format | 已知但尚不支持的译文格式/语言结构 |
| version_mismatch | 返回版本与当前原文不兼容 |
| not_bound | 当前本地歌词尚未关联在线版本，可使用现有选择歌词入口 |
| not_synced | 当前原文不是同步歌词，首版不按行号拼接翻译 |
| no_lyrics | 当前整首无歌词，或刷新时平台已不返回这份原文 |
| internal_error | 服务启动、IPC、写入等无法进一步归类的失败 |

reason 是固定枚举，不返回第三方原始错误、账号凭据、accesskey 或私有响应。服务按结构化错误代码分类，未知错误不根据错误文本猜原因。

## 必须支持的界面组合

已有译文，更新失败：

```json
{"translation":"available","translationRetryable":true,
 "translationLookup":{"status":"failed","reason":"timeout","canRefresh":true}}
```

旧双语继续展示，提示更新失败并允许重试，不显示“暂无翻译”。

无旧译文，平台明确没有：

```json
{"translation":"none","translationRetryable":true,
 "translationLookup":{"status":"succeeded","reason":"not_provided","canRefresh":true}}
```

有旧译文，但本次平台未提供新版：translation 仍为 available，lookup 为 succeeded/not_provided；继续显示保存译文，不自动删除。

普通本地 LRC 未绑定在线版本：

```json
{"translation":"none","translationRetryable":false,
 "translationLookup":{"status":"unsupported","reason":"not_bound","canRefresh":false}}
```

这里旧 translation 字段的 none 不意味着平台确认无译文，新 UI 应优先解释 unsupported 原因。

同理，not_synced/no_lyrics 返回 unsupported 且 canRefresh=false；译文查询不应使整份可用歌词变成加载失败。缓存命中时 lookup 可为 idle，结合 translation 展示已有结果。

## 保存与版本规则

- 自动读取先使用原文/缓存；未知译文后台补查，明确无译文七天后复查。本地旧 none 没有检查时间时允许一次补查。失败不会因桌面轮询持续重新联网；运行期最多记住 256 首的自动尝试/诊断，用户操作可以主动重试。
- 在线缓存仍写 version 2 并兼容 version 1。新增 translationCheckedAt，与 savedAt 分开；只补查译文时保留 savedAt。坏的可选时间字段不丢弃原文。
- 本地快照仍兼容 version 1/2，selection 中可选 translationCheckedAt；保留用户原文和 offset。未知状态或无效检查时间不当作永久无译文。
- 三平台补查均检查原文行数、文字与各自 offset 后的时间，使用已有 sameOriginal（100ms 容差），再判断译文对齐。酷狗保留 KRC 到 LRC 的重定时。署名或文本编辑仍可能导致保守拒绝，不放宽 80% 对齐门槛。
- 更新失败、版本不兼容、损坏元数据不修改持久译文；平台本次 none 也保留已有 available 内容。更新快照和在线缓存均先比较读取时的身份/选择或缓存内容，拒绝旧任务覆盖更新的记录。
- 网易云成功原文响应中缺失/空 tlyric 沿用已验证的无译文语义；存在但损坏的字段为 unknown。QQ 空字符串译文为 none，None 或残余密文为 unknown，不宣称 SDK 精确解密错误。酷狗未知 schema 为 unknown/unsupported_format，损坏 metadata 为 unknown/invalid_data。

## 匹配预览与桌面

library.lyricsMatch 的 preview 同样带 translationLookup，保留当前 session/job。预览 canRefresh=false，重试操作是重新 preview 同一候选，不应向 library.lyrics 请求当前本地文件来重试候选。没有可用译文仍可保存原文；预览状态不会作为私有平台响应持久保存。

桌面保持现有查询权限和独立双语开关。主窗口触发检查后，桌面消费共享结果和旧译文，无需另建服务请求。

## 前端接入

frontend 下的 TypeScript 类型/校验、native client、请求 hook、主歌词页与匹配预览、demo/fixtures 和前端测试均已接入。前端兼容旧后端缺少 translationLookup：缺字段不报错，保留旧行为；轮询只发送一次 retryTranslation，等待超时保留原文/旧译文并结束加载提示。

前端测试覆盖 available+pending、available+failed、none+succeeded、unknown+failed、unsupported 本地、切歌取消和后端缺字段，接入后完成主窗/桌面 CEF 联调；具体结果见下方记录。

## 后端验证记录（2026-10-15）

- 分支 `feat/lyrics-translation-reliability`，基于 `upstream/main` 的 `a1aff34`；本轮未修改 frontend 文件。
- Node 服务测试 78/78、QQ Python 测试 7/7；既有前端测试 49/49，TypeScript/Vite production build 通过。
- 最终 MSVC Release 原生及 CEF 构建通过；歌词 parser/krc/cache/match-store/online/match 与 player-interface/library-interface 共 8/8 原生测试通过。覆盖三平台主动检查、负缓存、保留旧译文/原文/偏移、同时间不同词拒绝、旧任务不能恢复已删除绑定、重复点击合并和私有上下文不外泄。
- 最终桌面 CEF 冒烟通过（1.94 秒）。主窗该轮在隐藏歌曲元数据/保持播放控件位置的布局断言失败；单独重跑通过（15.23 秒），前一轮同样通过（16.43 秒）。保留这一偶发失败记录，不将重跑通过视作已修复布局时序问题。
- 未登录的公开样本抽查：网易云原文 2520 bytes、译文 2410 bytes；QQ 原文 4043 bytes、译文 4402 bytes；酷狗原文 4162 bytes、KRC 20438 bytes，最终 C++ 提取/兼容校验得到 92 行原文中的 88 行译文。这些是本次样本结果，不代表全部曲库或账户权限。
- 验证在 ignored `build/translation-reliability` 独立源码/构建树完成；复用内测包重建的 compile-only FFmpeg SDK 和经固定 manifest 哈希核对的运行库，替代 SDK 校验仅存在于验证副本，正式仓库审计规则未改。首次原生测试缺少 FFmpeg DLL，补齐已核对 DLL 后通过；首次公开接口请求受沙箱网络限制，提升权限后通过。

本轮未制作安装包、未更新朋友已有内测包。当前 CEF 验证使用既有前端，只验证后端兼容性；CC 完成新状态、提示和按钮后仍需联调验收。AI 翻译、音译、多目标语言、跨平台拼词和更宽松的版本对齐不在本 PR 范围。

### 前端集成及 demo 修复（2026-10-16）

CC 已接入查询状态、主页面重新检查、预览失败重试和旧后端兼容，并完成 14/14 原生及 CEF 集成检查。用户随后反馈 demo 其余流程和真实环境初步走查大致正常，未将其视为所有专项全部验收。

Codex 按用户授权修复两处 demo 差异：在线歌曲再次检查保留最近取得的译文；保存的本地绑定实际处理主动检查，补充译文且保留原文/偏移，重开调整仍保留译文，替换/删除绑定丢弃旧任务。新增三项回归后前端 61/61、类型检查及生产构建通过；实际浏览器确认在线重复检查保留双语、本地无译文保存后可补查成功。模拟任务约 1.5 秒完成，重复点击不重新计时。本轮 demo 修复不改变真实后端。
