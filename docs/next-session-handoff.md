# 当前开发接续

更新：2026-11-26。本文维护当前任务和验证基线；能力完成度统一看[状态矩阵](feature-status.md)，接口看[总览](frontend-contract.md)，旧交接和日期证据看[历史索引](history/README.md)。旧记录中的“当前”“下一步”和队列快照不构成新任务或授权。

## 用户安排与工作范围

- 11-27用户授权整理累计前后端成果，提交安卓仓库、向PC上游android/development提交PR并发布v0.3.1（0.3.1/code7），可更新README。按[本版说明](android-v0.3.1-release.md)重新统一验证与正式签名，沿用原证书；不自动合并PR。用户已反馈网易云网页登录大部分自行实测正常，扫码常驻入口建议未实现；旧专项断开原因未确认，发布记录保留边界。

- v0.3.1发布准备：统一验证`20261127T064902391Z-27496`通过3,178项、Lint0错误165警告、1,093条路径冻结；正式签名包8c29dacb与原证书一致，APK清单／固定DEX调试类隔离通过，PJF110首次安装启动及设备APK散列核对通过，不等于覆盖升级或正式包账号／播放全验。release Lint0错误54警告；[安卓PR #1](https://github.com/qwt1011/Musxi-Player-Android/pull/1)与[PC上游PR #31](https://github.com/Miloscovo/Musxi-Player/pull/31)保持OPEN，源码树一致，Release附件核对后发布，本地证据`.local/artifacts/release-v0.3.1-preparation/`。


- 11-26晚：用户授权Codex暂代CC调整网易云网页登录，指定wbapi K3.1前端主实现，主代理审查验证；底部按钮明确随当前主题Accent切换。仅改登录外壳与测试，保留官方网页、标准Sheet／GlassHost及CC其余前端；会话槽按session隔离、FLAG_SECURE保留原标记并覆盖退出动画。原“和gimini交流.md”提炼后已删除，未留全文备份。[本轮记录](frontend-netease-web-login-refinement-20261126.md)：11-27凌晨完整verify 3178项全过、Lint0错误165警告、编译及冻结通过，包3322f7d0；15项登录专项与7张新图检查通过。11-27已装机且用户认可外观；专项基础关闭／重开／刷新／拖拽／安全标记恢复通过，条款第二窗口返回通过。主包仍3322f7d0；新增androidTest仅单独编译，不包含在凌晨冻结中。官方确认等待超时已关闭，键盘／验证码／提交／扫码未验；收尾发现网易云DISCONNECTED且原凭据文件不存在，测试无退出／clear／submit调用，需用户确认是否主动退出，暂不继续账号操作；QQ酷狗仍连接。

- 11-26继续：[第三批内容一致性](backend-search-lyrics-consistency-20261126.md)修补三平台搜索重复完成、搜索／歌词候选完整不可变快照、歌词匹配搜索的仓库取消门禁。最终a58f4ce1包统一3,171项、Lint0错误166警告、冻结检查通过并安装；三平台真实搜索／取消／歌词预览及推荐读取通过，酷狗推荐来自缓存。第一批网易云实际等待1201542ms自然到期，同票据续取一次、新流206，QQ／酷狗同包也通过；旧CDN全部仍206，自然签名拒绝后的服务恢复继续保留。第一轮REMOVE TASK中断不计通过。原942首／383／145622ms暂停及四个保护散列一致，私有baseline和原本不存在的QQ／网易云空日志已清理。不动CC前端、登录界面及游戏，无提交推送发布。

- 最新：用户在第二批收尾后授权补第一批。[11-26补验与酷狗无损修复](backend-playback-remaining-device-20261126.md)：基础变体hash差异走独立官方授权，授权无损沿用现有两域名原始HTTP兼容；同一首标准→无损、三平台真实取消／STOP、QQ／酷狗本地能力自然到期重新取流通过。最终4a4cbdde包统一3,168项、Lint0错误166警告、冻结检查通过，已装机。原942首／383／145622ms及本地库设置恢复一致并暂停，私有基线清理。自然CDN拒绝、网易云20分钟自然到期／更多设备保留，未改前端／网易云登录。

- 第二批[实现](backend-pagination-queue-consistency-20261124.md)与[11-26同包实机](backend-pagination-queue-device-20261126.md)：最终37d7d389包统一3,165项全过、Lint0错误164警告、冻结检查通过。酷狗942首补全逐项同序／30首时STOP、网易云22首／QQ7首生产队列、三平台关闭失效重开、跨进程暂停恢复与受控迟到确认竞争通过。原942首日志／索引383／145622ms及本地库设置已恢复，保持暂停；私有临时基线清理。网易云／QQ千首及大量歌单目录、远端并发编辑和真实改名尚无本轮实机；不要外推已全覆盖。

- 范围：保留CC前端；近期网易云登录前端被用户指出问题较大，单独处理。第二批修补已包括QQ／酷狗从第一页核对前缀、关闭目录展示快照、artistIds不可变副本，以及网易云／酷狗改名丢响应只读确认；平台真实写操作未获本轮具体授权，未执行。第一批剩余已按最新安排补验，当前整包基线见首条；下方11-24旧包与旧待办只代表对应阶段。

- 17:47酷狗可见授权状态条版本实机verify／save同账号并自动返回Musxi通过；shortService试验导致17:35 ANR，已完整撤回服务及清单声明。当前仅已有overlay许可时临时可见状态条维持授权检测，成功／取消／超时移除；主动返回还需状态条代次仍有效。没有许可保持官方登录及手动返回，不新增权限。精确包／最终检查见本轮summary，旧失败试验不可当最终结论。

- 最新：用户实测QQ 5b60aa48任务隔离后登录正常返回，不再退桌面。酷狗新增本次用户发起授权的有界后台轮询，verify／save成功后单次主动返回MainActivity；保留官方入口、不托管酷狗Activity、不加权限，系统拒绝时仍可手动返回。实际设备结果／统一检查见`.local/artifacts/kugou-active-return-20261124/summary.json`，不要把实现当已实测通过。

- 11-24 17:01用户确认QQ仍回桌面：抓到QQ授权与Musxi同task、Musxi先恢复后整个task转后台，进程未退出。现补QQ授权NEW_TASK隔离并加Intent回归测试，待本次新包官方确认后验收；不是已修复结论。酷狗主动返回仍可另行设计有界后台检测及系统允许的返回路径，上轮同任务方案失败不代表主动返回不可能。见[专项追加](backend-playback-request-audit-20261124.md)。

- 11-24下午续验：[播放专项](backend-playback-request-audit-20261124.md#16331635-新包播放专项2026-11-24)三平台真实起播、注入过期单次刷新、延迟STOP通过；网易云／QQ真实音质切换通过，酷狗样本单档，天然CDN过期待验。原941队列与11个文件散列恢复一致。酷狗同任务返回试验失败并撤回，自动返回仍未实现；QQ新官方确认未完成，回桌面长期问题未结案。最新全量验证以专项材料最新索引为准，不沿用失败试验包。

- 最新完成：[播放请求与取消首批、账号授权实机](backend-playback-request-audit-20261124.md)。三平台协作取消传递与回退门禁已补，QQ新callback按包名隔离；统一3,123项全过，Lint0错误160警告，已装主包82814a54…。三平台真实原位授权verify／save且同账号通过；网易云明确退出Cookie为空／pending=false，重新登录后冷进程恢复，播放日志还原及11个保全散列一致。酷狗官方页无Musxi回调，仍需确认后手动返回；QQ本次成功后主页面仍在，但未捕获VIEW回跳，正式旧重新登录流程及长期回桌面尚需专项复验。新包播放取消／过期／音质听音另验。前端布局暂缓，无提交推送发布。

- 当前安排：[账号第三批](backend-account-reauth-web-20261124.md)已实现三平台原位重认证与网易云网页关闭／退出生命周期，三轮共同覆盖网易云、QQ音乐、酷狗。[同包实机补验](backend-account-device-20261124.md)已完成现有账号冷启动校验、三个重叠刷新回调、歌单读取、候选取消保留原身份及断网后约60秒自动恢复；主包 SHA-256 `cd5fdc0a…`，保留账号与数据。第三批实现阶段统一3,119项／Lint0错误150警告通过，本次补验新增设备测试并重新运行完整入口，精确证据见设备材料索引。真实授权替换、明确退出与Cookie跨进程／更多系统仍待。新前端入口未接，现有重新登录仍先退出；三处浮卡、账号层级和锁屏歌词C暂缓。无提交、推送或发布。

- 最新开发与实机：[网易云 XEAPI 取流适配](backend-netease-xeapi-20261124.md)。固定上游当前 `song_url_v1` 选择 XEAPI，本地已补独立取钥、签名校验、加密请求和有界响应解码；沿用账号与风险停止策略。统一 3,061 项测试全过，Lint 0 错误／150 警告。同包 OPPO PJF110／Android 16 现有账号《Da Capo》ID `2026565329` 标准与原极高档分别冷进程单次取流起播成功；通知暂停 32,908 ms／恢复 32,931 ms、约 58 秒已确认锁定采样跨度的持续播放通过。手机留修复包 `bc55b817…`；13 个原私有文件散列恢复一致，未平台写入。真人听感、更多曲目／设备、长时与深度 Doze 待验。

- 最新诊断：[网易云真实账号确认响应](backend-netease-stream-reboot-20261119.md#11-20-真实账号确认响应观察)。现有账号冷启动校验成功，响应虽有 `Set-Cookie`，但没有可采纳的认证或校验凭据更新；0 次取流。临时观测已移除，恢复原包及测试前数据，不把常规冷启动续签当成取流修复。

- 最新开发与实机：[网易云登录会话续签修正](backend-netease-stream-reboot-20261119.md#11-20-登录会话续签修正)。同一账号验证通过才接收平台凭据更新；3,056 项测试通过、Lint 0 错误。新包装机后网易云恢复已连接，但一次《Da Capo》取流仍显示平台限制，未起播；原队列及搜索历史已恢复。

- 11-20 对照：[MeiloX 1.55.1 正式版对照](backend-netease-stream-reboot-20261119.md#11-20-meilox-正式版对照)。安装包散列匹配发布资产；当次在线取流判断及移动端会话差异仅属于该次研究，当前协议适配以 11-24 记录为准。用户要求优先恢复 Musxi 内播放，未保留外跳入口；未移植参考端身份实现。

- 当前开发：[网易云风控提示与手动换源](backend-netease-stream-reboot-20261119.md#播放失败与手动换源收尾)。`risk` 与 `rate` 分开显示，原有无自动重试策略保留；手动换源搜索未选定前保留旧原因，换源后保留队列身份和暂停进度。官方个人准入当前仅支持 `ncm-cli`；Musxi 没有已获批的 Android 播放授权，网易云原生取流仍待解决。

- 当前排查：[网易云 Da Capo 同曲对照](backend-netease-stream-reboot-20261119.md#da-capo-同曲同-id-对照)。用户确认官方 App 可播；分享 ID `2026565329` 的 Musxi 详情核对通过，单次标准取流确认为 `-460` 和网络环境风险提示。未定位平台内部触发条件；未改正式取流协议，原包及原数据已核对恢复。

- 最新实机：[网易云取流定位与整机重启](backend-netease-stream-reboot-20261119.md)。三首详情通过、极高和标准取流均被业务码 `-460` 拒绝；只补播放 URL 接口的 `RATE_LIMIT` 分类，不修改访问策略。整机重启前后同一临时单槽及 40.000 秒暂停态，显式续播通过，原库与队列已恢复。网易云后台播放等正常取流再验，真实来电和自然深度 Doze 待验。

- 最新实机：[11-19 后台播放补验](backend-background-device-20261119.md)。同包蓝牙重连保持暂停且手动原位继续；CUE 12 秒分段锁屏循环、DSD128 引用锁屏帧计数、酷狗和 QQ 真实取流锁屏通过。网易云三首在来源阶段 `SOURCE_FAILED / UNKNOWN`，需先查提供方错误分类。真实来电、整机重启和自然深度 Doze 尚待。原库、授权及本地／三平台队列已核对恢复，临时样本移除；无源码变更或发布。

- 最新开发：[第三优先后台播放可靠性与诊断](backend-background-reliability-20261118.md)。现有服务加入有界准备／缓冲、系统迟到拒绝处理和进程内诊断；原队列不自动重播，定时策略不变。统一验证 3,050 项全过、Lint 0 错误，主包 `2253f243…` 已保留数据装机。通知动作、独立应用焦点、进程重启暂停恢复、有声蓝牙锁屏约 6 分 21 秒与到点暂停、原位续播、耳机断连暂停通过。静音扬声器观察定位到 ColorOS 冻结与音轨欠载；重连、真实来电及长 Doze 另验。原库、授权和队列已核对，临时测试材料已删除，手机已交还。

- 最新完成：[第一、第二部分实机验收](backend-local-versions-lifecycle-device-20261118.md)。原库直接从 schema 5 保留数据升级到 7；真实系统存储目录刷新、撤权和授权恢复通过。两个自然播放保存点不打断普通续页或整理，进度相关查询和真实整理冲突正确失效。原业务数据、文件、授权与队列已核对；临时材料已清除。JVM 3,040 项全过，Lint 0 错误、152 警告，主包 `8b2fb58e…` 与设备一致，设备专项通过。只新增定向测试及文档，未提交推送、发布或同步 PC。

- 当前后端范围：[本地库双版本](backend-local-versions-20261117.md)和[目录刷新与任务生命周期](backend-local-lifecycle-20261117.md)保留小而精的接口与现有串行任务。不可覆盖安装旧 schema 5／6 包。正式查询页面、第三方云提供方和正式 Activity 长 COPY 取消不计入本轮实机结论；前端继续暂缓。

- 最新授权：保留今天歌单相关成果，连同近期 CUE／DSF 等后端源码、测试及文档提交并推送 **安卓独立仓库 origin/main**；暂不提交 PC 仓库，不发布新版或更新 v0.3 安装包。下列每轮“不提交推送”等仅描述当时范围，不撤销这次明确授权。曲库右上角 `+` 新建歌单浮卡不符合当前美术风格，已记入[前端待办](frontend-playlist-review-20261117.md#后续前端待办2026-11-17-用户补充)，今天不改 UI。接下来几天重心放后端，所有前端接线／视觉待办暂缓；[后端下一步建议](backend-next-priorities-20261117.md)是讨论与推荐顺序，尚未开工。原六批计划第五／第六批仍暂缓。

- 最新为[曲库主页最近播放稳定性修复](backend-library-home-stability-20261117.md)：同账号目录重开先发空items的回归修前失败，现保留纯展示快照直至首页替换；过渡不授权分页／排序，换号／清缓存立即清除。目录24项通过；实机三平台各3轮无临时空态，酷狗／网易云推荐与喜欢页进出、静置和下拉回弹后最近播放y=1606稳定。QQ由用户重登后CONNECTED，目录与推荐进出通过，当前无喜欢模块／最近播放样本，不能声称卡片已验。真实目录详情、本地ready、酷狗权限及分页复验通过；一次手势测试测到回弹中间42px，补等待后通过，失败证据保留。最终同包5项实机全过，统一3006项／Lint0错误152警告，冻结与手机包e22f8947匹配，证据见 `.local/artifacts/library-home-jump-20261117/final-summary.json`。录屏无有效输出，手机screenrecord 22890被OEM拒绝清理，本机管道和空文件已清；不播放、不平台写入、不提交推送发布。

- 最新为[长按反馈与酷狗权限查询修复](backend-playlist-menu-permissions-20261117.md)：网格取消整块 indication，沿用一次 LONG_PRESS 与现有浮卡。酷狗实机确认两个协议兼容点：无 total 的短页之后为版本稳定的 info:null；普通自建行省略 is_def，须独立验证 list_create_userid 与 session 规范 ID 匹配。严格终页条件、系统／未知标志拒绝、写前完整重验保留。重连已装归属修复包并核对散列70a5f95c…：3项只读设备测试全过，两条普通行可改名／删除，默认收藏受保护；三平台目录／详情、本地 ready、四轮刷新均通过。大／紧凑网格封面与标题四个长按入口均通过，按住态无整块蒙层，恢复原大网格；真人触感仍待用户反馈。本轮最终统一检查引用 `.local/artifacts/playlist-permissions-20261117/reconnected/final-summary.json`，旧2997项与1e8366ef包为阶段记录。一次误触游戏悬浮窗已停止并说明。不真实改名删除、不提交推送发布。

- 最新为[酷狗下拉刷新与自动分页循环修复](backend-playlist-refresh-20261117.md)：已实机复现近期成功记录与被首页写入清除的尾页缓存不一致，导致同页 loading/idle 循环。Browser 仅在有效缓存存在时免重复请求；缓存只读语义保留。修前核心回归失败，修后三轮立即刷新与正式页面自动续页、三平台目录／详情和本地 ready 通过；本轮统一检查及安装包散列以独立验证材料为准。旧报告的酷狗“完整目录”已更正为首页读取。仍不执行平台写入、不提交推送发布。

- 最新为[歌单前端接入审查修复](frontend-playlist-review-20261117.md)：用户报告 Kimi 隔离包导致本地库打不开、酷狗读歌单失败。主目录已整合前端与当前 CUE／DSF 后端；保留原工作树／改前快照，修复 schema 5 库安装旧 schema 4 包、酷狗短页 continuation、歌单详情订阅缺失、旧操作弹窗及懒加载问题。最终统一2,986项全过、Lint0错误152警告、主包及测试包编译／源码冻结通过；手机最终APK散列fee8056c…匹配，三平台目录首页／非空详情及本地ready通过。启动runner等待需保留观察，进度+995ms已记录，未手动回写。真实页面写入及拖动体验仍待。当前修复与验收以主目录及本轮报告为准，不再直接装旧工作树；不执行平台写入、不提交推送发布。

- 当前完成[DSF正常生产启用验收](backend-dsf-enablement-20261117.md)：正式应用能力为true，fresh debug继承；64／128复制／引用与两轮新进程四条来源控制／曲末通过。初次曲末失败是文件夹队列断言错误；后续单条队列曾首条启动等待25秒超时，原因未定，增加失败状态诊断后两轮独立重启未复现，保留观察项及全部失败报告。原库七表、文件、授权保全，原inactive本地journal字节恢复、平台journals未改，owned材料清理、恢复无进程。正式UI及第五六批暂缓，不提交推送发布。release编译／Lint／隔离及签名门禁通过；缺本机安全签名密码，正式签名APK／正式包实机尚未完成。已发布v0.3附件不变，不能当成已包含本轮功能。

- v0.3已正式发布：[Release与安装包](https://github.com/qwt1011/Musxi-Player-Android/releases/tag/v0.3.0)、[对应源码](https://github.com/qwt1011/Musxi-Player-Android/tree/v0.3.0)、[完整更新说明](android-v0.3-release.md)。安卓发布提交adb4c0b；PC上游[PR #30](https://github.com/Miloscovo/Musxi-Player/pull/30)使用fork分支`qwt1011:android/v0.3`，基于`android/development`的PR #28合并点c8b1ab3，保持与安卓main源码树一致；不合并PC main或自动合并PR。三附件公开回下载的大小与散列均匹配。首次云端检查暴露两项宿主测试时序问题，main和PR已补虚拟时钟及数据库测试初始化／收尾，不改已发布APK或移动标签；实际提交、CI与校验结果保留在项目 `.local/artifacts/release-v0.3-preparation/`。用户已更新GitHub授权，先前403仅为历史失败，不打印令牌。

- 已完成的前轮任务为Android v0.3发布：用户明确授权核对安卓独立仓库及PC上游基线、提交全部现有开发、向上游android/development提交新PR，并在安卓仓库发布正式签名APK与更新说明；不合并PC主线。版本升为0.3.0／code6，沿用v0.2密钥。README参考LoveACE结构，使用自有图标与合成页面样张。该发布授权已执行完毕，本轮CUE开发不自动沿用提交／推送／发布权限。

- 最新任务为[锁屏歌词 C 后端](backend-lockscreen-lyrics-c-20261113.md)：用户确认方案并明确只做后端，正式UI不接。三态互斥／旧设置迁移、共享歌词和已应用播放页壁纸、触发状态与抽象宿主、debug最小弹出探针已实现；最终统一2,784项全过、Lint0错误149警告／947条源码冻结通过，保留数据装机，手机hash一致。PJF110／Android16／ColorOS16仅开悬浮窗时2次BAL_BLOCK；用户再手动开启“应用详情→权限管理→特殊应用权限→后台弹出界面”后，同包17:25:33后台创建／绘制／获得焦点，用户确认看到。触摸退出先露出仍锁定的系统锁屏，约2秒后另行解锁。之后进程被划掉并重启，debug恢复CARD／armed=false，计数清零；阳性依据系统事件和用户观察。后台弹出机制已通过本机样本，正式页面／四方向手势／视觉后续接线验收。不授予特殊权限、不解锁手机、不改平台内容。

- 前轮为[QQ／网易云歌单创建及删除实机补验](backend-playlist-lifecycle-device-20261113.md)：用户要求趁手机连接补验，现已通过正式facade完成各1次创建／删除，报告均 `PASSED`；精确返回ID、本人才可删及实际空内容核实，QQ列表3→4→3、网易云2→3→2，全部原行指纹一致。旧QQ不确定创建marker及酷狗marker前后散列不变，本轮永久意图保留；不加歌、不改红心、不改生产或CC源码、不提交推送。统一2,754项全过、Lint0错误139警告／935条源码冻结，保留数据装机。后续以页面接线和同包验收为主，不自动再跑写入。

- 前轮为[酷狗普通自建歌单删除后端接入](backend-kugou-playlist-delete-20261113.md)：用户明确授权把已验证请求接入正式后端，补齐权限／异常／完整读回回归后开放给前端，页面由用户后续安排。独立Base64文本profile、严格本人普通行权限、完整分页核实和 `delete=true/canDelete` 已交付；最终统一2,754项全过、Lint0错误139警告、主包／测试包编译与932条源码冻结通过。该阶段不改CC页面或测试、不安装、不再真实删单、不提交推送。

- 前轮为[酷狗本人歌单删除实机验证](backend-kugou-playlist-delete-probe-20261113.md)：用户授权唯一目标 `Musxi创建验证` 即使非空也允许整单删除，实机确认含1首歌。固定Base64文本profile只发送1次请求，报告 `OBSERVED_DELETED`，完整本人列表4→3、精确目标缺席、其余行ID／名称／数量／权限指纹一致；没有先清歌或其他修改请求。删除与历史创建marker均保留，单次写入额度已用完。最终统一2,736项全过、Lint0错误139警告／930条源码一致性／主包及测试包编译通过，保留数据装机并执行两个单独设备方法。当次生产delete=false；后续生产接入以本轮交付为准，不重跑设备删除。

- 前轮接续原会话最后授权：曲库页直接新建／删除／改名及改变位置；用户确认排序只在Musxi记住，暂不连接手机。沿用已落盘实现，补统一目录分页／持久排序、行权限、同平台写互斥及QQ／网易云删单后完整核实；酷狗自建删除证据不足保持关闭。CC负责正式页面，不改UI或CC测试、不提交推送。接口与本轮验证见[曲库管理交付](backend-playlist-library-management-20261113.md)。

- 前轮任务为[继续解决酷狗新建歌单](backend-kugou-playlist-create-20261113.md)：用户明确授权的一次空创建探针已成功，`data.info.listid`与新鲜本人可写普通空歌单精确一致；生产create=true已接入，现有前端能力开关自然启用，不改CC界面、不提交推送。真实只写一次、不加歌；最终生产“新建并加入”留用户实测。
- 前轮接续 CC“Musxi-Player 安卓开发261111”中用户11-13凌晨02:07的交接，重验任务2（可打断的封面／歌词转场、底部三工具按压反馈）和已完成的任务9（长按爱心“收藏到”），并按明确授权保留数据装机。CC任务2第二次构建实际已在后台成功，断在中转汇报；未改任何源码或CC测试，只重验、安装和补交接。两任务已合在同一`tech.musxi.player.dev`调试包，11-13 10:36:49覆盖安装至PJF110；用户随后明确任务2／9前端均验收通过。任务4不在本轮范围，不提交、不推送。下列各前轮“不安装”等说明是其历史边界，前轮安装授权适用于任务2／9验收，不自动扩成平台写入授权。
- 前轮为[壁纸强度放宽到150](backend-requests-20261112-5.md#交付说明)：两份入口统一0～150、默认35，新增`WallpaperPreferences.MAX_STRENGTH`；值对象允许构造/copy，命令返回`INVALID_ARGUMENT`且不写盘不钳制，存储边界显式校验。沿用v1/v2格式、不处理旧版降级读取大于100的新值。专项56项、主目录统一2,622项通过，Lint0错误/135警告；CC可接常量。不改`ui/`或CC测试、不安装、不提交推送。
- 前轮为[推荐总开关](backend-requests-20261112-4.md#交付说明)：`recommendationsEnabled`默认true、旧设置兼容、专用setter/通用update同步通知已实现；关闭三平台空闲/清快照、逻辑取消、串行清推荐元数据，`play`拒绝`recommendations_disabled`，点播校验/提交与关闭共用同步边界。共享封面/已接受队列和30秒限流保留，重开不自动联网。专项84项、主目录统一2,604项通过，Lint0错误/135警告；不改`ui/`或CC测试、不安装、不提交推送，CC接线后验收正式页面。
- 前轮为[独立界面壁纸](backend-requests-20261112-3.md#交付说明)：保留wallpaper播放页入口，新增interfaceWallpaper；独立状态/草稿/清单、共用图片池/串行队列、全引用回收、chooseSaved对称复用已交付。P1及P2均做完，专项57项、主目录统一2,575项通过，Lint0错误/135警告。本轮不改`ui/`或CC测试、不安装、不提交推送，接线及同包实机由CC负责。
- 前轮为[壁纸取景位置](backend-requests-20261112-2.md#交付说明)：保留受管整图，新增归一化中心与1～4倍zoom，沿用强度的草稿/应用/取消；换图重置、已有图直接调整、v1清单默认兼容及非法参数错误已交付，专项41项通过；当轮统一验证受CC界面Lint错误阻挡。
- 前轮为[手动封面裁切与选图EXIF](backend-requests-20261112.md#交付说明)：摆正后归一化crop、区域解码、一次开流及暂存清理；null保持整图语义并修复方向。两项P1已交付。
- 前轮为[三平台歌曲推荐](backend-recommendations-20261112.md)：网易云/酷狗每日歌曲，QQ可证实为猜你喜欢个性电台，不标成每日；缓存/刷新/取消和完整快照点播已实现，统一入口`recommendations`，按kind区分。真实账号响应和同包推荐点播待CC接线后验收。
- 前轮为[本地条目倍速、封面偏好和书签改名](backend-requests-20261111-5.md#交付说明)：第1/2/3项和P2书签改名已实现。倍速按条目保存且pitch1，切平台恢复1；OFF保留自动图但隐藏，手动图优先且OFF仍显示。用户明确OFF→AUTO不自动补旧图，只由设置按钮调用分批fillArtwork。不改 `ui/`，不安装手机、不提交、不推送，CC继续持有安装窗口。
- 前轮[本地视频声音与自动封面](backend-requests-20261111-4.md#交付说明)已完成视频音轨检查、VIDEO/schema v3、显式禁视频轨、内嵌图/有界截帧、共享图片清理和旧条目补图；观看扩展未实现画面。
- 前轮[歌单搜索缓存补全](backend-requests-20261111-3.md#交付说明)已增加三平台 `LibraryBrowser.fillPlaylist/fillState/cancelFill`、进度、共享限速/退避与容量保护；第 2 项只核对，QQ/酷狗队列仍要求 offset 0 连续前缀。

- 用户随后选择先做[第 4 项媒体卡片标题歌词方案 B](backend-media-card-lyrics-20261111.md)，默认关闭；`mediaCardLyrics` 与 setter、现有歌词桥接、服务按行调度和平台标题恢复已实现。CC 接开关并继续持有手机安装窗口；本包不自动安装，不改 `ui/`，不提交、不推送。第 2 项 ColorOS 原生发布器继续暂缓；原生探针结果独立保留。
- 用户 11-11 后续明确按[后端需求单](backend-requests-20261111.md)优先完成主题色持久化与逐字播放取样接口；本地音频七项按风险排期。当前交付还包括 reorder 过期错误统一和单选选择器；没有修改 `app/src/main/kotlin/tech/musxi/player/ui/`，CC 继续负责前端。本轮不提交、不推送，不覆盖安装手机主包。
- 同日早一轮手机补验仅安装测试包并修正构建锁，见[设备记录](backend-local-device-20261111.md)。11-10 首轮及扩展格式结果分别保留在[首轮记录](backend-local-device-20261110.md)和[追加记录](backend-local-followup-device-20261110.md)，不将旧包、单台样本扩成当前接口／真人听音通过。
- 本地范围为音乐、音声、有声书、录音等；COPY 默认，REFERENCE 可选。保留稳定 ID、别名、整理、进度和书签，不自动删除来源文件。
- 产品“小而精”：优先系统 SAF、SQLite、现有 Media3/协程，控制运行时依赖、包体、解码和后台扫描开销。
- 唯一主目录 `C:\text\Musxi-Player安卓`；保留既有未提交后端与 CC 前端。未获新授权不提交、推送、发布，不操作平台收藏、创建或恢复历史队列。

## 当前验证基线

2026-11-17最终统一2,971项全部通过，0失败／错误／跳过，debug Lint0错误152警告，主／设备测试包编译及源码冻结通过。证据 `.local/artifacts/verification/20261117T005526811Z-23676/`。实装主包 `8afd0259fd2791d067e56d7d066f0a0963c24c8bae7c32bbbe691980d5ead454`、测试包 `d6d963cc3f14e4eb84432b91a6c8ce10847a0a1cfad471c39638b49da5b489c0`；最终代码／构建与验证manifest匹配，随后仅更新文档并单独检查。release app Lint0错误55警告，签名门禁与清单／编译类隔离通过，不代替正式签名APK／正式包实机。详情见[DSF正常生产启用验收](backend-dsf-enablement-20261117.md)；既有各轮证据保留历史，不混用。

2026-11-14 第三批格式与输出信息后端完成：新增24项，官方统一2,901项全过，0失败／错误／跳过；Lint0错误150警告，debug主包／设备测试包编译及2,221条路径冻结一致（含朋友材料）。证据 `.local/artifacts/verification/20261114T104413922Z-27340/`，索引 `.local/artifacts/audio-info-20261114/final-summary.json`。FLAC／WAV显式只读查询、Media3输入／解码器与应用AudioTrack配置观察及平台偏好回归完成；decoded和deviceOutput保持未知。格式／路由事件与第一、二批SAF／实际分段播放仍待实机；未接UI、未安装、未提交推送发布。另记录既有手动换源失败未恢复旧来源的独立待办，未扩大修改。

2026-11-14 CUE第二批后端完成：新增37项，官方统一2,877项全过、0失败／错误／跳过，Lint0错误150警告，debug主包／设备测试包编译与2,213条路径冻结一致（含朋友材料）。证据 `.local/artifacts/verification/20261114T100528949Z-28080/`；索引 `.local/artifacts/cue-import-20261114/final-summary.json`。schema5无损迁移、共享来源原子入库、分段Media3队列／进度恢复与备份v2已实现。原导出／单条relink对分曲明确阻断；正式UI、SAF与权限、真实音频分段解码／衔接与定时仍待同包实机。未安装、未提交推送发布。

2026-11-14 CUE第一批已完成：新增56项，官方统一2,840项通过、0失败／错误／跳过，Lint0错误150警告，主包／设备测试包编译及2,207条文件路径冻结一致（含未跟踪朋友材料）。证据 `.local/artifacts/verification/20261114T090557342Z-3128/`；最终core.jar实样解析24轨、GB18030、手动关联和边界计算通过，材料 `.local/artifacts/cue-preview-20261114/`。仅读取文件头，不代表完整音频或实机播放通过。本轮只读预览，未入库／播放、未改UI、未安装、未提交推送发布。

发布后CI稳定性补丁已在本地重新统一验证：2,784项通过，0失败／错误／跳过，Lint0错误150警告，主包／设备测试包编译及954条路径冻结通过；证据 `.local/artifacts/verification/20261113T151805094Z-24544/`。相较发布构建新增的1条警告仅为协程测试依赖的版本提示，测试依赖按现有运行库保持1.10.2。首次PC CI的5／40ms真实延时顺序不稳定改为虚拟时钟；安卓CI的LocalMedia测试准备与清理补主线程预开库、主线程读取回调Result、cancelAndJoin后再关数据库。仅测试／测试依赖和文档变化，无生产行为修改。云端最终结果以本轮材料中的android-ci-result／upstream-ci-result和最新运行记录为准，不覆盖首次失败证据。

v0.3发布轮已完成本次0.3.0／code6源码的统一验证：2,784项全过、0失败／错误／跳过，Lint0错误149警告，主包／设备测试包编译及954条源码路径冻结一致；证据 `.local/artifacts/verification/20261113T140810256Z-31376/`，debug APK `08c8e10e6e093024bed50f17867fb39eb5aed6650e49861d96013464a1d8cba9`。正式签名APK为19,250,861字节，SHA-256 `a3729ca554fdefab47ea95a184a84b71690474cff01959e59b37faa8e8f1d461`，沿用v0.2证书，v2签名／版本／release清单检查与DEX调试类抽查通过，Release Lint0错误55警告；包位于 `.local/artifacts/release-0.3.0/`。发布详情见[v0.3说明](android-v0.3-release.md)；本次没有安装手机或执行真实平台写入，覆盖升级待用户实际执行。以下各轮保留各自历史包与实机范围。

前轮为 **锁屏歌词 C 后端**：最终统一 **2,784项通过，0失败／错误／跳过，Lint0错误149警告**，主包／测试包编译及947条源码一致性通过，证据 `.local/artifacts/verification/20261113T085230464Z-31876/`。新主包 `5558360aa0ed8d97b478ade24f35114201bb110aafe1ba6c3edfc23db37da5e8`已保留数据安装，手机base.apk散列一致；负向NEEDS_PERMISSION／BAL_BLOCK及双权限允许后本机后台显示的阳性结果均保留。最新设备证据 `.local/artifacts/lockscreen-lyrics-backend-20261113/acceptance-20261113T092402Z/acceptance-summary.json`，显示／焦点／触摸退出及仍锁定事件已核实，用户确认看到。最终CARD／armed=false；进程重启后计数0不是显示失败。正式ui未变，release无具体宿主；后续前端接线再验正式页面，不将机制探针视作视觉或四方向均通过。设备轮次未改源码、未重新构建，收尾仅补文档。

前轮为 **QQ／网易云创建及删单实机补验**：实机执行阶段统一 **2,754项通过，0失败／错误／跳过，Lint0错误139警告**，主包／测试包编译与935条源码冻结通过，证据 `.local/artifacts/verification/20261113T072746605Z-31848/`，本轮索引 `.local/artifacts/playlist-lifecycle-device-20261113/final-summary.json`。主包仍为 `69364d911ddb989720b2b22d88dc7dfca5851a3002f68f00dd9c6c0efa40e362`；执行测试包 `730c48517f6c189218b596e1af059f2a7624b8efa4077515aa1abf15daa394e8`，均锁内复核后安装到PJF110，安装后散列一致、首次安装仍10-19。四个单独设备方法runner各 `OK (1 test)`，两前检READY／0写，两循环业务均PASSED：每平台1次创建＋1次删除，正式生产APPLIED／缺席true，QQ3→4→3、网易云2→3→2、原行指纹一致。交叉审查后仅加固测试路由白名单和报告收尾，再次统一2,754项全过、Lint0错误139警告／935条源码冻结通过，最终证据 `.local/artifacts/verification/20261113T074248552Z-28112/`；测试包 `af8dc9db24b857ee434ff2342e04269eddac31c8de868a9e8d2ddcc22f453ab6`已覆盖安装，仅跑两次只读核实：保存状态PASSED、目标仍缺席、0次写入。所有历史防重marker散列不变，新marker保留。实际写入的旧源码／包另行保留，不把加固测试包冒充写入执行包。生产源码未改；旧QQ创建请求仍UNKNOWN，但当前独立创建样本已成功，不能重写历史结论。

前轮为 **酷狗普通自建歌单删除正式后端接入**：最终主目录官方统一 **2,754项通过，0失败／错误／跳过，Lint0错误139警告**，主包／设备测试包编译与932条源码冻结通过。证据 `.local/artifacts/verification/20261113T070820262Z-27544/`，索引 `.local/artifacts/kugou-playlist-delete-production-20261113/final-summary.json`。净增18项，删除适配器24项／密码9项／协调器15项／真实适配器＋协调器集成5项全过；独立profile审查无阻塞。主包为上述 `69364d91…`。首轮旧rename测试依赖guard调用次数而失败，已改按实际写请求模拟切号并完整重跑。生产 `delete=true/canDelete` 已开放，前端接口不变；该生产接入阶段未安装、未真实删单，后续装机与QQ／网易云补验见当前轮，酷狗生产入口未重复删单。

前轮为 **酷狗本人非空歌单单次删除实机验证**：最终分页修正版主目录官方统一2,736项全过、Lint0错误139警告、debug主包／androidTest包编译与930条源码冻结通过，证据 `.local/artifacts/verification/20261113T063746326Z-30128/`。测试APK锁内复核保留并安装，SHA-256 `e73190b0aeaddbc792506ccfe0a1c51f7989ea43f0e342dd2d75d4a414673ecf`；主APK仍为 `99d3fbf6…`。PJF110保留数据装机后，准备 `READY`、删除 `OBSERVED_DELETED`；唯一目标实为1首歌、只发1次写请求、完整列表4→3且其余行指纹一致。首次只读准备因探针强制total失败且0写入，已修正为未知total按页号读到终页。删除意图及创建marker保留，当次生产delete=false。完整安全报告与阶段包区别见[当次记录](backend-kugou-playlist-delete-probe-20261113.md)，本地索引 `.local/artifacts/kugou-playlist-delete-20261113/final-summary.json`。

前轮为 **曲库歌单管理与Musxi内排序**。最终主目录官方统一 **2,736项通过，0失败／错误／跳过，Lint0错误／139警告**，主包／设备测试包编译与928条源码冻结通过；新增8类专项75项。证据 `.local/artifacts/verification/20261113T053202355Z-23928/`，索引 `.local/artifacts/playlist-library-management-20261113/final-summary.json`；共享锁内复核源码／HEAD／状态／APK并保留最终包，SHA-256 `99d3fbf67a846a5b4c3c7ae18647017c90ac3f0f50142697ec77421a24d245e5`。QQ／网易云删单实现、完整列表核实与Musxi内排序已交付，酷狗delete=false；[本轮合同与CC接线](backend-playlist-library-management-20261113.md)。不改UI或CC测试、不连接手机、不安装、不执行真实平台写入、不提交推送；真实删单及正式页面待后续验收。较早2,733项和测试预期失败尝试分别保留，最终源码以上述为准。

前轮为 **酷狗普通歌单创建接入**。一次真实授权探针已成功，只发1次创建、不加歌、不删除，业务报告`OBSERVED`并确认`data.info.listid`及回显与本人列表新ID一致；安全证据`.local/artifacts/kugou-playlist-create-20261113/device/`。本轮官方统一验证2,661项通过、Lint0错误139警告，证据`.local/artifacts/verification/20261113T041114920Z-1744/`；915条源码清单锁内复核一致。新主包SHA-256 `ff8b7d6278a8da45af81612cbd2199f605de97249ae9245358d2b640da77231f`已覆盖安装到`c201e9f0`，保留数据、启动成功且无FATAL EXCEPTION，安装证据`.local/artifacts/kugou-playlist-create-20261113/device/backend-installation.json`。新增14项创建单测、接口和不确定结果语义见[交付](backend-kugou-playlist-create-20261113.md)。探针准备阶段旧数字仅作历史证据。

前轮为 **CC任务2／9接续验证及装机**，主目录官方统一 **2,647项通过，0失败/错误/跳过，Lint0错误/139警告**，debug主包／设备测试包编译及全树源码一致性检查通过。官方证据`.local/artifacts/verification/20261113T022729644Z-13288/`，装机证据与保留APK在`.local/artifacts/cc-task2-task9-20261113/`。保留APK SHA-256为`470c5decdda16d4bda4bb6f6059584a6578f2b4eb31dc829876c1c5dc6913adb`，与CC任务2第二次成功构建相同；保留副本前在共享构建锁内复核验证清单、HEAD、工作区状态和APK，手机安装后base.apk散列再次一致。包名`tech.musxi.player.dev`、0.2.0／5，Android16／SDK36，PJF110；`adb install -r`成功，首次安装时间仍为10-19，MainActivity冷启动成功，采样时应用进程存活且安装后该进程AndroidRuntime日志未见FATAL EXCEPTION。Codex只做启动检查，未运行设备测试套件，未自动播放、写平台收藏或新建歌单；用户随后明确任务2／9前端均验收通过，该结论不扩成酷狗创建或历史协议缺口全部通过。

前轮为 **壁纸强度放宽到150**，专项 **56项**、主目录官方统一 **2,622项通过，0失败/错误/跳过，Lint0错误/135警告**，debug主包/设备测试包编译及全树源码一致性检查通过。验证含两份壁纸合法端点、越界拒绝/不写盘、120预览/取消/应用/冷启动、v1/v2兼容及非法清单。5份本轮后端源码/测试与专项、官方清单及交付时磁盘一致，回填后另查5份文档。官方证据 `.local/artifacts/verification/20261112T135826818Z-21828/`，索引 `.local/artifacts/wallpaper-strength-20261112/final-summary.json`，保留APK SHA-256 `f91aa9f710a80f6ee7e4391a00e95573b1ec04849c9970bad9a9ebbf71b74045`。本轮不改UI、不安装手机、不提交推送；100以上实际画面待CC接线后验。

前轮为 **推荐总开关**，最终专项 **84项**、主目录官方统一 **2,604项通过，0失败/错误/跳过，Lint0错误/135警告**，主包/设备测试包编译与全树源码冻结通过。专项覆盖设置持久化/失败、同步off/on通知、三平台loaded/inflight清理、排队read/write及迟到响应、真实缓存文件冷启动清理、限流、点播拒绝/原子提交及已接受队列保留。11份后端源码/测试与验证清单及交付时磁盘一致，回填后另查6份文档。最终证据 `.local/artifacts/verification/20261112T125749866Z-12740/`，索引 `.local/artifacts/recommendations-toggle-20261112/final-summary.json`；保留APK SHA-256 `ce356758148c7bfffb51e5df604048880882f63f63880485e8b2f70ebe7786ea`。本轮没有手机安装/真实平台请求，不改CC页面或测试、不提交推送；接口/清理时刻见[交付](backend-requests-20261112-4.md#交付说明)。

前轮为 **独立界面壁纸**，专项 **57项**、主目录官方统一 **2,575项通过，0失败/错误/跳过**，Lint0错误/135警告，debug主包/设备测试包编译及全树源码一致性检查通过。9份后端源码/测试与最终专项、官方清单及交付时磁盘一致，结果回填后另查6份文档。本轮结果见[交付](backend-requests-20261112-3.md#验证范围)，证据 `.local/artifacts/verification/20261112T113204525Z-24500/`，索引 `.local/artifacts/interface-wallpaper-20261112/final-summary.json`。保留APK SHA-256 `70ff5f057a805b953207d6a0263ffd92f00ad629e883dec95ad37b819b88a5b0`，没有安装手机、提交推送或创建工作树。两份正式页面范围/曲库预览路由、手势/对比度及同包设备性能待CC接线后验。

前轮为 **壁纸取景位置**，专项 **41项通过，0失败/错误/跳过**。官方统一验证 **未通过**：`:verifyUnitTests` 及主包/设备测试包编译已完成，app Lint 的 `ui/MainShell.kt:1102` 报 `SuspiciousIndentation`（前一语句1093行），该文件本轮未改。失败证据 `.local/artifacts/verification/20261112T074944556Z-21800/`，索引 `.local/artifacts/wallpaper-frame-20261112/final-summary.json`。未执行官方最终报告汇总/全树冻结检查，不报告完整测试数或整包成功；尝试保留报告时其它构建持有共享锁，未复制可能被替换的报告/APK。5份后端源码/测试与专项、官方开始时及最终磁盘一致；详见[交付](backend-requests-20261112-2.md#验证范围)。没有新建隔离树，不安装手机、不提交推送。

前轮为 **手动封面裁切与选图EXIF**，定向70项、主目录官方统一 **2,510项通过**，0失败/错误/跳过；Lint0错误/132警告，debug主包/设备测试包编译及全树源码冻结通过。8份后端源码/测试与验证清单及交付时磁盘一致；该轮没有新建隔离树。证据`.local/artifacts/verification/20261111T175423080Z-27080/`，索引`.local/artifacts/artwork-crop-20261112/final-summary.json`。接口、失败尝试修正和设备边界见[交付](backend-requests-20261112.md#交付说明)，保留APK SHA-256 `545fdad1d0f73df6f655c2436645997d48ca6b18a6ad73fa0c4f265bcb1eebeb`，未安装手机、不提交推送。HEIC实样、Coil坐标/手势、真实权限撤回、峰值内存和同包锁屏待CC接线后验，不用JVM图形测试替代设备结果。

前轮为 **三平台歌曲推荐**，定向369项通过；最终将主目录866条源码路径原样冻结到已登记的隔离工作树，官方统一验证 **2,484项测试全过，0失败/错误/跳过，Lint0错误/132警告**，debug主包、设备测试包编译及源码冻结通过。证据已移回`.local/artifacts/verification/20261111T164024180Z-10896/`。26份当轮后端源码/测试/NOTICE与隔离快照、三次主目录尝试及交付时磁盘一致；此前主目录的并行修改冻结失败及CC已修复的书签页编译失败仍独立保留。通过结论属于隔离组合快照，不替CC之后的前端修改背书。详见[交付](backend-recommendations-20261112.md)及`.local/artifacts/daily-recommendations-20261111/final-summary.json`（目录为开始日，跨日完成）。保留最终快照APK，SHA-256 `10c861f78404752334939c6b16313ce39444ae04bfe883d74efba8ba7a8d6215`，未使用真实账号请求推荐、未安装手机；不把协议fixture当成平台真实成功。

前轮为 **本地条目倍速、封面偏好和书签改名**，接口、模式/手动优先级、schema v4迁移与验证见[第五份需求交付](backend-requests-20261111-5.md#交付说明)。定向271项、最终统一2,436项全过，均0失败/错误/跳过；Lint0错误/130警告，主包和设备测试包编译通过，21份交付源码/测试散列一致。统一证据 `.local/artifacts/verification/20261111T141915790Z-26780/`，索引 `.local/artifacts/local-preferences-20261111/final-summary.json`。保留APK的SHA-256为 `1e664e1ade1e609394acd3005fddd19700273d5836d35a4fbde9c6aa14c442d7`，没有手机安装或执行，听音不变调/系统卡片与锁屏仍待同包实机。原日志的无堆栈SDK35线程提示见该次交付，不当作设备结论。

前轮为 **本地视频声音与自动封面**，见[交付](backend-requests-20261111-4.md#交付说明)。当时定向205项、统一2,394项全过、Lint0错误/130警告，证据 `.local/artifacts/verification/20261111T125236604Z-13992/` 与 `.local/artifacts/local-media-20261111/final-summary.json`；没有手机安装或执行，设备解码与截帧/OEM选择器/锁屏仍待验。

前轮为 **歌单搜索缓存补全**，接口、取消/刷新归属、按平台共享限速和队列入参约束见[需求单交付](backend-requests-20261111-3.md#交付说明)。当轮证据索引 `.local/artifacts/playlist-fill-20261111/final-summary.json`，没有手机安装/真实歌单网络/搜索听音验收。

此前轮为 **媒体卡片标题歌词方案 B**，接口与恢复语义见[交付](backend-media-card-lyrics-20261111.md)。证据索引 `.local/artifacts/media-card-lyrics-20261111/final-summary.json` 区分其后端检查、统一验证与历史探针设备结果。方案 B 专项 24 项通过，accounts／playback／app Lint 均 0 错误，release 编译与 debug 探针隔离通过，18 份源码保持不变；当时全量入口因前端 `SheetPhysicsTest.releaseVelocityUsesTheBandsLocalSlope` 第 129 行期望 0.55／实际 1.0 失败。保留 B APK 的 SHA-256 为 `0eec0b70d03e7d668afd9217aa04608e7225831faf6f8c35ae679f05971589c8`，没有安装，真实锁屏／蓝牙／车机仍待验收；CC 继续持有安装窗口。

此前原生探针轮为 **ColorOS 锁屏歌词 debug 实机探针**：四个固定步骤、字段读回、封面重写后重新附加与清理在 debug sourceSet。16:36 已在 PJF110 保留数据覆盖安装固定 APK，安装前后散列一致。A1／A2 的触发和用户观察后平台读回均成功，用户两组反馈均只有音乐卡片、没有歌词相关；A1 metadata 回调后重新附加一次。**16:50 已保存两组日志并关闭探针，确认测试字段清除；安装窗口已释放给 CC。16:59 用户明确酷狗显示的是其自有整页锁屏界面，原生歌词页阳性对照未完成。** 原生页是否在本机可用、root／Bridge 状态及未显示原因仍不能确定，不将结果归因为单一系统白名单原因。[设备验收记录](backend-lockscreen-lyrics-probe-20261111.md)记录环境、结果和判断边界，现场索引为 `.local/artifacts/lockscreen-lyrics-probe-20261111/device/device-summary.json`。准备阶段原 `.local/artifacts/lockscreen-lyrics-probe-20261111/final-summary.json` 继续索引专项、release 隔离、统一验证及保留 APK；这些自动验证不替代系统歌词页观察。

前一接口轮为主题色、逐字播放时钟和两项本地音频修正：接口、线程／时钟语义及本地七项排期在[交付说明](backend-requests-20261111.md#交付说明codex-填写)。下表是该轮及设备历史的独立证据，不计作本轮探针结果。

| 证据 | 路径或结论 |
| --- | --- |
| 阶段索引 | `.local/artifacts/backend-requests-20261111/final-summary.json`，分别指向定向测试与本轮统一验证；统一验证冻结的源码清单为准 |
| 当前交付 | [主题色、播放取样与本地音频补缺](backend-requests-20261111.md#交付说明codex-填写) |
| 前轮交付 | [同日手机补验](backend-local-device-20261111.md)、[上午首轮设备补验](backend-local-device-20261110.md)、[主动检查来源](backend-local-source-check-delivery-20261109.md)、[检索与批量整理](backend-local-organization-delivery-20261109.md) |
| 文档阶段 | `.local/artifacts/docs-cleanup-20261108/summary.json`；当时 101 份 Markdown 链接/可达性与归档保全通过 |
| 源码基准 | HEAD `70778e9` 加未提交修改，以本轮统一验证的 source-files.json 和最终冻结结果为准；构建锁已修正中文绝对路径跨编码导致的锁分叉 |
| 设备边界 | 本接口轮没有实机执行或安装；同日设备轮的主包 SHA-256 为 `62df3088b1a068912e92ce73c4b000ab0f34d55d3978748f3399599afa788426`，结果与清理保留在 `.local/artifacts/local-device-20261111/final-summary.json`。当时通知、OPPO 两项导入／取消、5项 SQLite、2项壁纸通过；约416秒熄屏采样停更、定时未启动，仍不能判定长熄屏通过 |

修改前对照为上午首轮 2079 项 JVM、Lint 0 错误/128 警告、debug 28,121,736 bytes，证据在 `.local/artifacts/local-device-20261110/final-summary.json`。这些数字只属于旧轮；本轮实际结果和失败尝试见新的阶段索引。设备测试包编译不代替执行，设备结果也不代替源码统一验证。

## 下一步

第四批正常生产能力与debug同生产路径实机已收尾。release剩正式签名APK生成、最终包内隔离／证书及正式包设备验证；需所有者在本机安全输入长期发布密钥密码，不在聊天发送，实际发布前递增版本，不能覆盖旧0.3附件充作新发布。云端、更多设备、单声道和数小时Doze按需补兼容；现有验收不外推所有倍率耗电或物理原样输出。第五六批继续暂缓，正式前端由用户安排。不要重跑历史平台写入、旧marker或旧journal。

锁屏歌词C后端及本机后台弹出机制已通过，可以交给前端；按[合同](frontend-backend-contract-lockscreen-lyrics.md)接三态选择、具体宿主、歌词页已应用背景、歌词／播放控制及四方向退出，再做同包交互验收。ColorOS权限引导覆盖悬浮窗、独立的“后台弹出界面”，以及用户补充的“隐私→权限管理→权限→锁屏显示”应用权限；最后一项尚未单独切换／读回验收，独立必要性待验。公开overlayGranted只含悬浮窗，refreshPermissions也不检测后两项，不承诺三项均已授权。这项补充不阻塞接线。当前探针已关并恢复CARD，无需继续自动arm或改变播放；正式前端仍由用户后续安排。

酷狗删除实样已成功，`Musxi创建验证`（1首歌）已在唯一一次请求后确认删除，防重marker保留。生产接入与回归已交付，前端沿用 `app.playlists.deletePlaylist`／`managementInfo.canDelete`，检查返回值内的status／confirmedAbsent／error，UNKNOWN先刷新核对、不自动重发；[具体接线](backend-kugou-playlist-delete-20261113.md#前端接线)。页面由用户后续安排。无需另删歌单；此前实机平台写入授权已用完，不试其他type、不删除marker或执行历史写入套件。

本批曲库管理后端已交付，QQ／网易云生产创建与删单实机样本已补齐；正式页面后续按[新合同](backend-playlist-library-management-20261113.md)接统一目录／菜单／拖动，不将旧browser累积页与新catalog混用。正式菜单、跨账号和非空复杂样本仍待后续验收，不自动再执行平台写入或重跑marker。酷狗生产自建删除已接，用户最终“新建并加入”亦留后续实测。以下既有接线任务继续保留。

任务2／9前端已获用户验收通过。酷狗一次空创建实样已确认，创建的 `Musxi创建验证` 后经用户本轮授权完成整单删除，创建及删除防重marker保留不重跑。生产已接精确ID读回和create=true；最终用户在酷狗歌曲长按爱心→收藏到→新建歌单→新建并加入，核实列表及歌曲，后续增补跨账号样本。不自动再写、不按同名或列表差集关联，不修改CC页面。前端验收不扩成三平台创建／历史遗留问题全部通过。

强度放宽交付后，CC将两份滑条的上限从100改读`WallpaperPreferences.MAX_STRENGTH`；0～100画面映射保留，100以上的刻度/提示/文字颜色和对比度映射由CC处理，再同包检查两份壁纸的120/150、取消/应用/重启及文字可读性。后端不修改滑条或提前安装。

CC按[双壁纸合同](frontend-backend-contract-wallpaper.md#入口与状态)把界面背景接到 `interfaceWallpaper`，播放页继续 `wallpaper`，不再读写 scope；两个host各收集本份状态，禁用播放页不沿用界面图。缩略图菜单可双向 chooseSaved，随后走相同取景/预览/应用流程。接好后同包验曲库/搜索/我的/二级页背景、曲库首页预览、播放页与歌词排除、关闭/取消/重启及同图复用。

CC按[壁纸取景合同](frontend-backend-contract-wallpaper.md#取景位置与调整已有图片)接 `WallpaperPreferences.frame`：选图取景先留本地值，preview成功后写draft；已应用图直接preview原ID即可调整，不需重新选图。应用/取消与强度共用，前端按当前窗口比例算显示矩形。接好后验证贴边/缩放、换图回中、已有图调整及取消、重启持久化、横竖屏/分屏适配；后端本轮不安装。

上轮 `ui/MainShell.kt` 的 Lint 失败保留为历史证据，本轮重新运行统一验证已通过；前端后续接线改动仍需重验，不复用旧包或修改Lint基线绕过。

CC按[裁切交付](backend-requests-20261112.md#交付说明)把摆正后预览坐标传给`setCustomArtwork(..., crop = LocalArtworkCrop(...))`；保留null和取消选图路径。统一新包后验EXIF竖拍/镜像、四角及贴边、取消/恢复默认/换封面竞争、HEIC实际支持与封面/锁屏更新；后端不安装或代改裁切页。

推荐由CC按[合同](frontend-backend-contract-recommendations.md)接`app.recommendations`：观察独立平台状态、按kind命名、进入/返回时open、离页cancel、点播传显示快照revision。总开关读`settings.recommendationsEnabled`，IO调用`settingsRepository.setRecommendationsEnabled`；开启成功后主线程open当前平台。统一新包后验证开关/重启/缓存清理与三平台真实账号只读推荐、QQ权限、换号/断网/取消/日期新鲜度和整组/所选位置点播，协调一次安装窗口；不操作平台收藏或不喜欢。

0. CC 按第五份交付接本地倍速/书签和手动封面菜单；Coil改用 `localAudio.displayArtworkFile(item)`，收集设置与库状态，确保OFF隐藏自动图但手动仍显示。OFF→AUTO不自动补旧图；补取按钮每批1～500条、最新revision、进度与取消。统一新包验按条目记速度/不变调/切平台1倍、AUTO/OFF旧图保留、手动图/恢复默认/锁屏即时变化及书签改名；原视频音轨/截帧/复制引用重关联等设备待验继续保留。观看页面与Surface模式本轮未做。

1. CC 将“载入全部再搜”接到所属平台 browser 的补全接口，按 `pagesRevision` 重搜、离页取消，展示限额/容量/账号及平台错误。不要把搜索去重或跨空洞结果传为队列前缀；选择真实连续前缀或空列表使用既有回退。待同一新包验收三平台真实大歌单、取消/断网/限速和搜索点播。

2. 方案B现纳入锁屏歌词三态，CC按[C合同](frontend-backend-contract-lockscreen-lyrics.md)读取 `effectiveLockScreenLyricsMode` 并调用 `setLockScreenLyricsMode`，保持新安装默认OFF及CARD／PAGE互斥；旧布尔setter仅作兼容。正式页面接线后验证开启／关闭、暂停／seek／切歌／换源、歌词空缺、封面更新及真人锁屏显示；不要复用旧探针APK代替正式页面。第2项原生路线仍需有效原生页阳性对照，酷狗自有锁屏页不能替代。
3. CC 接主题色保存与逐字扫光时，沿用需求单交付的同步保存／IO 调用及 elapsedRealtime 时钟语义，并使用 `canExtrapolatePosition`。正式页面接好后实机验收重启持久化、暂停／缓冲／seek／换曲与听音对齐；本轮没有验收 CC 的新页面。
4. 本地后续排期先完整拆分整理／进度 revision，连同进度相关检索分页 token、迁移和过期回归；随后同批补目录显示名与目录错误／逐项失败数，再独立完善 imports/sourceChecks 的 runId、完成与清除生命周期。不能只取消 saveProgress 的版本递增；理由及前端过渡接法见[排期](backend-requests-20261111.md#本地音频七项状态与排期)。
5. 配合 CC 接正式导入、文件夹、搜索、多选整理及“检查来源／重试／重新关联”，并专项验证正式 MainActivity 离开／关闭／重建时长批 COPY 的临时读权限。接口仍以[本地音频](frontend-backend-contract-local-audio.md)、[壁纸](frontend-backend-contract-wallpaper.md)、[定时](frontend-backend-contract-sleep-timer.md)、[歌词](frontend-backend-contract-lyrics-translation.md)、[歌单](playlist-management-contract.md)合同为准。本轮不发送对外消息。
6. OPPO 文件取消已补过，目录仍待验。先调查熄屏采样停更与系统唤醒锁代理现象，再补长熄屏实际定时、真人听音／真实音声、整机重启、数小时后台／device-idle Doze、耳机蓝牙与真实电话；不重放已清理 marker，不运行整个历史写入套件，不擅自修改电池设置。
7. 倍速设置、自动/手动封面与书签改名后端已交付，待正式页面/实机；AB、章节、本地 LRC 仍为可选增强。整理备份不含音视频与封面，也不含新增倍速/手动图偏好；包含音频的一键迁移、批量导出及覆盖冲突策略另评估。持续遵守[文档维护规则](README.md#维护规则)，不恢复追加式状态时间线。

## 保留的边界

- 本地库、壁纸、书签和恢复日志不属于普通缓存；平台退出不能清它们。暂停恢复不 prepare、不请求焦点、不恢复旧定时器。
- 网易云整单订阅缺兼容 SDK v2 提供者，当前写前 `verification_required`；酷狗整单订阅未开放；旧 QQ 创建不确定 marker 保留，禁止自动重试或按标题猜 ID。QQ／网易云普通自建创建及删除已于11-13补验通过，不将旧 marker 等同于当前创建未实现。QQ 普通改名已单设备往返，仍需复杂元数据及跨账号验证。
- 三平台 KRC/QRC/YRC 后端已有 11-07 单台 Android 16 实际歌词与缓存证据，逐字页面和听音对齐待接；旧包结果不证明本地音频解码或当前包听音。
- v0.2与上游PR #28为本次发布前基线；当前用户已授权发布v0.3，结果见[v0.3说明](android-v0.3-release.md)。旧基线与后续提交细节在[整理前交接快照](history/next-session-handoff-through-20261108.md)，不因此自动合并上游PR或操作其他项目。
