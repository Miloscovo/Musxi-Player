# 网易云取流定位与整机重启补验

2026-11-19，接续[后台播放实机补验](backend-background-device-20261119.md)，设备 OPPO PJF110／Android 16。

同一已连接的网易云账号，三首歌曲详情均成功；播放 URL 接口对每首的极高和标准两档全部返回业务码 `-460`，早于 URL 交给播放器。原实现把这个码归为通用 `platform`，播放层最终显示 `SOURCE_FAILED / UNKNOWN`。[协议来源项目的同类报告](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/issues/300)也记录了该播放接口的多档拒绝。该证据确定拒绝发生的位置和编码，不能确定平台为何对这部设备或账号触发限制。

只在网易云播放 URL 接口把 `-460` 分类为风控，经现有播放链路成为 `RATE_LIMIT`。音质探测遇到这个错误立即停止，不降档重试，不动登录或其他接口。只读定位探针仅用于诊断包，已从最终源码移除；没有导出曲目 ID、响应正文、音频 URL 或账号信息。

最终调试包 `a96154ac…` 保留数据覆盖安装后点播一次，网易云仍拒绝发放播放地址；实机记录为 `SOURCE_FAILED / RATE_LIMIT`，没有 `SOURCE_RESOLVED`，并退出此次播放请求。全量回归 3,051 项单元测试通过，Lint 无错误；本机与手机上的 APK 散列一致。点播验证结束后，原库、授权和队列已核对恢复。

整机重启在原 `2253f243…` 包上独立通过。关闭播放并保存本轮自有单槽队列在 `40000 ms`；ADB 重启后 boot ID 变化，系统已完成启动且用户仍为 `RUNNING_LOCKED`，Musxi 无进程和活跃音轨。用户首次解锁后打开，仍是同一临时曲目、`40000 ms`、暂停，没有准备音频或请求焦点；明确播放从 `40002 ms` 开始。测试临时条目已移除，原本地库业务表、授权、受管文件及三平台队列已核对，原队列逐字节恢复。

证据：`.local/artifacts/background-netease-reboot-20261119/`。网易云后台连续播放仍需在平台恢复正常发放音源后复验；真实来电与自然深度 Doze 单列。

## 当日继续定位

- **历史对照。** [10-22 的《Lemon》起播记录](lyrics-translation-acceptance-20261022.md)确认当时使用网易云实际来源。Git 追溯 `f4626ae`、v0.3 的 `35f5850` 到 `6bfa8fa`，播放 URL 地址、标准档数据和通用传输的关键字段没有与这次故障对应的近期变更；11-17 的账号仓库变更只涉及酷狗歌单续页。历史可播不证明现在的取流请求一定符合平台当前要求。
- **本轮实机。** 同一 `a96154ac…` 包，用原有固定入口搜索 `Lemon 米津玄師`。账号已连接，搜索命中《Lemon》，歌词及译文各 42 行。历史记录是 46/42；旧证据未留曲目 ID，因此只作同名样本对照。唯一一次点播有 1 次真实逻辑取流失败、0 次故障注入，`SOURCE_FAILED / RATE_LIMIT`，`playing=false`、`wanted=false`，无 `SOURCE_RESOLVED`。没有再换挡或连试。原数据已恢复；结果和保全见 `.local/artifacts/netease-stream-control-20261119/`。
- **仍待区分。** [上游 #300](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/issues/300)于 11-06 报告同一播放接口、同一码及跨音质拒绝，11-16 有独立复现；截至本轮仍开放，没有平台方对触发规则的说明。`RATE_LIMIT` 是 Musxi 归类后的停止策略，不能据此说用户点得太快，或认定某个账号、设备标识或网络是原因。

## 免费曲目对照

用户指出《Lemon》是会员曲目，改用新收藏的《崩坏3》免费曲目，并确认官方 App 能放、Musxi 仍不能放。同一台手机的系统媒体会话及活跃音轨确认官方 App 正在播放 Hanbaka 的《Regression（崩坏三〈2022音乐会〉）（Remix）》。随后在 Musxi 网易云「我喜欢」实测阿云嘎／HOYO-MiX 版《Regression》：歌单读出 22 首，点播只触发 1 次逻辑取流，0 次故障注入；再次记录 `SOURCE_FAILED / RATE_LIMIT`，未收到 `SOURCE_RESOLVED`，未起播。两边是不同录音，不能写成同一曲目 ID 的严格对照；这次也没有单独记录阿云嘎版本的原始业务码，`-460` 仍以此前的直接探针证据为准。

此前的对照不足以判断平台具体规则，但已不能用《Lemon》的会员限制解释整起故障。Musxi 当前将平台风控和频率限制统一显示为「请求太频繁」，此文案不能证明点播过频。

脱敏证据见 `.local/artifacts/netease-free-control-20261119/`。已恢复原播放队列和搜索历史，本地库及来源授权通过保全核对；未修改收藏、登录状态或产品源码。

## Da Capo 同曲同 ID 对照

用户分享其确认在官方 App 可播的 HOYO-MiX《Da Capo (崩坏3《毕业旅行》动画短片印象曲)》。分享链接跳转至网易云官方曲页 `id=2026565329`。同一台手机上，Musxi 的既有网易云会话为 `CONNECTED`；用歌曲 ID 请求详情，校验回传 ID、曲名和歌手匹配。沿用当前取流实现，只请求一次标准档，接口返回业务码 `-460`，返回信息含「网络环境」和「风险」，Musxi 分类为 `risk`，没有收到可用播放地址。官方侧是否播放以用户此前的确认记录；诊断未二次操作官方 App。

这次排除了同名录音差异和《Lemon》单曲会员权益作为完整解释，确认阻断发生在网易云对 Musxi 取流请求的风控响应。提示不能证明某个 IP、账号或客户端字段就是触发条件，也不能据此断言所有用户或所有网易云歌曲均受影响。优先确认是否存在受支持的第三方播放接入与可用性说明；平台允许当前取流后，再验真实音频与锁屏。

证据：`.local/artifacts/netease-da-capo-20261119/`。单曲固定探针未写平台数据或变更请求参数；只记录成功标志、业务码和提示分类。原调试包 `a96154ac…` 已保留数据恢复并核对散列；原曲库、队列与授权通过校验，临时探针从源码移除。

## 上游核对与后续方案

用户补充：在 Musxi 重新登录网易云后点播仍显示「请求太频繁」。这是用户操作结果，未另测重登后业务码；与前述一次取流即被拒相符，暂不再把反复扫码当成恢复步骤。旧包将 `risk` 和 `rate` 显示为同一原因；`-460` 沿用原有停止策略，保留选中项、进度及队列，不自动切档或重试。

| 来源 | 2026-11-19 核对结论 |
| --- | --- |
| [MeiloX 主分支 `9db5aeb`](https://github.com/MeiloX/MeiloX/tree/9db5aebf57da716f9372113d515f3e3f9b9b96d3) | 本地 `bc038c9` 快照遇取流业务失败会停止；最新版本改用内置音源包，但网易云包仍调用同一类播放操作，未找到针对 `-460` 的明确修复。[`4eb0f46`](https://github.com/MeiloX/MeiloX/commit/4eb0f469f3178cfb59daa13621280b19d96f8f2f)处理插件运行时的临时连接失败与跳歌，可参考其失败分类和队列保留；它没有验证这次服务端拒绝。 |
| [协议上游 #300](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/issues/300) 与 [Musxi PC `9ddf21d`](https://github.com/Miloscovo/Musxi-Player/blob/9ddf21d9/services/netease.cjs) | 同类报告仍未结案，[v4.41.1](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/releases/tag/v4.41.1)没有对应修复说明；桌面最新的网易云适配仍使用 `song_url_v1`，没有可移植的同问题修复证据。 |
| [网易云开放平台](https://developer.music.163.com/st/developer/) | 提供经授权的[获取歌曲播放 URL](https://developer.music.163.com/st/developer/document?docId=3d2c9f695ff24f4ea37611614b7f7856)接口；个人使用与厂商接入的边界见下文。 |

## 播放失败与手动换源收尾

- 取流明确返回 `risk` 时，播放理由为 `PLATFORM_RISK`，播放器、迷你栏和换源说明共用「平台暂时限制播放」。真正的 `rate` 或 HTTP 429 仍显示「请求太频繁」。两者共用既有 `RATE_LIMIT` 停止策略，不自动换源、切档、重试或跳曲。账号、曲库和歌单请求不变。
- 复用「换个音源」，按已有保守规则在已登录的 QQ／酷狗中搜索。搜索、取消和无匹配都保留原来的停止原因；显式选择通过校验的候选后才更换当前来源并清除旧提示。保留原队列条目及进度，已暂停的曲目继续保持暂停，按播放从新来源继续。候选匹配与选中不证明平台会发放播放地址。
- 本轮未改网易云取流方式，也未增加打开官方曲页的入口。原生网易云恢复需单独验证；此前实机只确定网易云拒绝发生在取流阶段。

本轮统一验证 3,053 项 JVM 测试全过，Lint 0 错误、154 警告，debug 主包和设备测试包构建通过。新增服务回归通过模拟取流拒绝验证了单次请求停原位、取消与无匹配保留理由，以及明确手选后更换来源并保留队列和位置；它没有访问真实 QQ／酷狗音频。证据：`.local/artifacts/verification/20261119T114613133Z-16876/`，debug 主包 SHA-256 `cec9c71f7e98af7d5576aa1590a0d65cefe88a1ee1ba583d84c6bcdbacf3a74f`。这次未安装或进行新的实机取流。

## 官方接入条件

[厂商流程](https://developer.music.163.com/st/developer/document?docId=4313d6b51e6f42e0824a3c56a628a785)要求提交企业、产品、终端和合作诉求，并在评估通过后创建应用且通过审核。虽有[成年实名个人入驻](https://developer.music.163.com/st/developer/document?docId=46be8cfe3f8b476bb1e8b98e765b75ab)，但[平台 FAQ](https://developer.music.163.com/st/developer/document?docId=3b75ab8e475d41ca93d91ebd4dfd383f)说明，当前个人场景仅支持 `ncm-cli`，不支持自行上传密钥；不能把个人入驻视为 Musxi Android 的接入资格。

[访问方式](https://developer.music.163.com/st/developer/document?docId=d5ab035d4b9d4a088ff9f4ef3ea5005a)允许客户端或服务端发起签名请求。[移动端授权登录](https://developer.music.163.com/st/developer/document?docId=0adf20d426564597b9219d4c12bf00f7)和[刷新令牌](https://developer.music.163.com/st/developer/document?docId=2066d57bbed2445baeb18429e44b8689)推荐在服务端完成。后续只有在确认 Musxi 的应用资格、接口授权和兼容现有架构的密钥托管方式后才实现；不在 APK 里分发开发者私钥或应用密钥。播放 URL 仍受终端版权、会员权益与试听范围限制；实际授权、曲库、配额以平台批准为准。本轮只查公开资料，未申请或进行官方取流。

若获得接入条件，先用同一《Da Capo》从冷启动、单次取流验证实际起播，再补暂停恢复、锁屏播放与无权益曲目的失败解释。

## 11-20 MeiloX 正式版对照

用户确认手机上的 MeiloX 登录网易云后可播放歌曲。ADB 读取到 `com.neoruaa.meilox` 版本 `1.55.1`（versionCode 16）；安装包 SHA-256 `bddbcbbff95a644fb28d2d37b743458069b9ea2b433737d141675abe57245221` 与 [GitHub 正式发布包](https://github.com/MeiloX/MeiloX/releases/tag/1.55.1) 一致。对照其标签源码 `58d3c35dbf8ccd7571e43ec05515556d28256866`，不再用不同版本的主分支推断手机行为。

- [播放来源选择](https://github.com/MeiloX/MeiloX/blob/58d3c35dbf8ccd7571e43ec05515556d28256866/app/src/main/java/com/ljyh/mei/playback/MediaUriProvider.kt#L86-L156) 先查本地文件和仍有效的来源缓存。在线分支调用与 Musxi 同一类取流操作，并检查返回曲目身份和完整来源；非成功业务码直接终止探测。该正式版在线分支未发现自动切第三方源。用户报告说明它可以播放，但不能单独证明该次与 Musxi 使用同一曲目 ID、同类登录会话或全新取流。
- [正式版回归](https://github.com/MeiloX/MeiloX/blob/58d3c35dbf8ccd7571e43ec05515556d28256866/app/src/androidTest/java/com/ljyh/mei/data/network/NeteasePlaybackIdentityInstrumentedTest.kt#L49) 明确区分移动端登录来源和普通网页登录，并验证播放继承其移动端身份。该实现还涉及向登录 SDK 呈现替换后的应用签名；它不等同于用 Musxi 自身应用身份取得播放授权。不能只凭这组差异确定 `-460` 的内部触发规则。本轮没有移植身份实现、导入凭据或改变网易云请求。
- 用户明确优先恢复 Musxi 内原生播放；单纯打开官方曲页不能满足日常使用，因此未保留该入口。README 已补个人学习、无官方隶属／合作与播放授权声明。这是对照研究，未移植 MeiloX 播放实现。

Musxi 会在登录后校验账号身份，并将同一个 `NeteaseApi.Session` 加密保存；曲库和取流均从 `withNeteaseSession` 读取该快照，调用前后检查账号状态与代次。现有检查未发现取流切换到另一账号会话、进入解码后失败，或点击次数触发本机限频。当前已存会话是扫码/网页流程建立的账号会话，没有单独可验证的 Android 应用播放授权。重登可更新原会话，但不改变它的授权来源。设备版本和安装包只读核对；未更换手机上的 App，也未重复发出实机取流请求。

可借鉴的是让「账号已连接」和「当前会话可取流」独立表达，并把不同授权来源与其允许的能力配对。只有确认一种以 Musxi 自身身份取得的可用取流方式后，再按其实际合同改会话边界，保留原有代次隔离与账号数据。实现验收以《Da Capo》同 ID 冷启动单次实际起播为先，再测其他免费曲、权益限制、暂停原位恢复和锁屏；官方曲页或替代平台起播不计入网易云恢复。

外跳草案期间启动的统一验证在 `MainShell.kt` 的两处 `SuspiciousIndentation` 报错，证据 `.local/artifacts/verification/20261120T063126320Z-13752/`，不作为本轮通过记录。撤回草案后，780 个源码与构建配置文件均与 11-19 通过构建的散列一致，本轮仅保留文档修改。文档链接和 `git diff --check` 通过。

## 11-20 登录会话续签修正

网易云账号确认响应原先只消费账号资料，丢弃了响应头里的凭据更新。现在成功验证资料和账号 ID 后，只采纳适用于现有官方请求域及根路径的认证 Cookie 更新；域外、主机专用和其他路径 Cookie 不交给跨主机取流。平台明确撤回登录凭据会报告过期；只撤回校验字段时移除该字段。扫码和网页登录以及冷启动恢复都走现有验证入口，继续使用原加密存储及代次检查。未修改取流地址、客户端标识、失败重试规则或前端页面。

统一验证 3,056 项 JVM 单测全过、Lint 0 错误／149 警告，debug 主包和设备测试包构建通过，见 `.local/artifacts/verification/20261120T071037257Z-20736/`。新增三项用例用本地模拟响应覆盖续签落盘后的取流、Cookie 作用域与撤回、身份不匹配及冲突更新；并不证明实机响应确实发送了续签。

实机保留数据安装 `97337d593cbf8f63664aa0addd8d12d133308961321149482ebf0084252d0916`，冷启动网易云恢复 `CONNECTED`，在正式搜索页点播 HOYO-MiX《Da Capo》一次（核对歌名与歌手，本轮未独立核对 ID）。记录 1 次逻辑取流、0 次故障注入，仍是 `SOURCE_FAILED / RATE_LIMIT`，页面显示「平台暂时限制播放」，没有 `SOURCE_RESOLVED`、未起播。原始 `-460` 的证据仍以 11-19 同 ID 测试为准。原四份队列和搜索历史已逐字节恢复，手机保留新包；本轮未重新登录或写平台数据。脱敏报告与回退 APK 见 `.local/artifacts/netease-session-renewal-20261120/`。续签遗漏已修，原生播放未恢复，尚无证据表明会话续签遗漏是本次平台拦截的原因。

## 11-20 真实账号确认响应观察

保留现有网易云登录，按正常冷启动恢复。该次账号校验成功并进入 `CONNECTED`；响应有 `Set-Cookie`，但按既有作用域规则没有可采纳的认证或校验凭据更新，两项已用值均未变化。未点播、未重新登录，取流调用数为 `0`。这说明在本次冷启动的账号确认流程里，补上的续签逻辑没有可用于改善取流的更新；不外推首次扫码、网页完成登录或其他账号和时段。

临时观测只记录布尔值，未导出 Cookie 值、原始响应或账号标识，且未改请求。原修订版 `97337d59…` 已保留数据恢复；加密会话文件、四份队列及搜索历史共六份文件逐字节核对通过，临时观测代码和手机备份已撤除。证据见 `.local/artifacts/netease-session-renewal-20261120/renewal-observation.json` 与 `observation-preservation.json`。不再把常规冷启动续签作为 `-460` 的恢复手段；原生播放状态以此前取流复测为准。
