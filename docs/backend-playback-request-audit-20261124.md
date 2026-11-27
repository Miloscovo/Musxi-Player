# 播放请求、资源失效、音质回退与取消审查

2026-11-24。用户授权第一批后端审查，同时安排真实官方账号授权及网易云退出清理；前端布局、账号按钮接线继续暂缓。不提交、推送或发布。

## 已确认的播放取消缺口与修补

11-26接续：[剩余项同包补验](backend-playback-remaining-device-20261126.md)已修复酷狗无损变体及CDN兼容，标准→无损、三平台真实取消、QQ／酷狗自然本地到期通过；最终4a4cbdde包3,168项全过。自然CDN拒绝、网易云长有效期与更多设备仍待，以下11-24记录保持对应当时包的范围。

三个 `PlaybackProvider.resolve` 原来各自用 latch 等待异步仓库回调。加载线程被中断后只停止等待，已排入 AUDIO 通道的仓库工作没有接收到取消信号；同一次工作在高音质被拒绝后仍可能继续请求低档位。账号代次和 QueueSources 保护了最终发布，但不能避免这些无效请求占用共享音频解析通道。

三平台统一使用 `awaitPlaybackResult`，等待完成、超时或中断时撤销本次活跃标志。`resolveAudio` 追加可选 `isCurrent` 参数，兼容现有调用；在出队执行前、网易云歌曲资料请求后、每个音质请求之前及结果返回后检查。取消用固定 `cancelled` 类别传递，仓库安全映射识别其公共错误父类。QQ 额外将该条件送入既有平台适配器的请求间检查。迟到回调不能重新激活请求。

边界：这是一层协作取消，不能强制终止已经发出的底层 HTTP；它仍受既有传输超时约束。音质菜单目前仍只在查询时加载，没有因本批调整而在起播时枚举全档位。用户队列、偏好档位和现有失败分类保持既有语义。

## 资源与回退审查结论

- QueueSources 缓存最多3个能力，每次使用重新核验账号代次和有效期；失效缓存重新解析，新队列或来源变化拒绝迟到结果。
- CDN 的401／403／404／410映射为资源失效，服务的恢复预算仅允许一次自动刷新；同槽重复播放不重置该预算。网络／超时／风控／账号错误停止，不进入自动刷新循环。
- 默认音质只对 permission／membership／copyright／preview／unavailable 尝试较低档；明确指定档位失败不自动降级。音质切换先准备后提交，失败可回滚原能力，队列身份不变。
- 本批不扩大重试范围，不自动为网络或风控切档，也不引入预取所有音质。实际起播耗时、真实资源过期／快速切歌和多曲目仍须设备专项；旧包听音不计入新包验收。

新增测试覆盖三平台取消后禁止降档、排队取消时禁止首次解析、风控／限流／账号／超时停止回退，以及加载线程中断后撤销工作并拒绝迟到回调。第一版新增 Robolectric 测试未指定SDK，JDK17不能运行SDK36，已固定为现有项目使用的SDK35；该次失败与修正后通过分别保留。

## QQ 登录回桌面排查

用户报告长期存在QQ重新登录后回桌面。现场未发现对应Java／native崩溃；本轮旧 instrumentation 断言失败会由测试框架结束目标进程，不能将该行为归因于长期产品缺陷。首次启动验证也出现 Activity 未恢复，失败记录保留。

已确认手机同时安装的开发包和旧glassdemo包都匹配 `musxiplayer://login-return`。新的QQ授权请求改用 `context.packageName + ".login"` scheme，清单用 `${applicationId}.login` 注册，旧scheme保留兼容在途请求。实机查询新地址仅匹配开发包一个Activity；MainActivity接收匹配的新回跳后只触发一次已有账号轮询，不解析回调凭据或绕过平台验证。是否完整解决长期回桌面，须以新包用户授权返回复验为准。

## 官方授权与清理验证入口

初版 opt-in instrumentation 的 QQ／酷狗候选均未得到新成功验证，不记通过，原账号回滚保留。为避免框架结束进程干扰回跳，改为固定 `account_authorization_open` debug步骤，沿用 ValidationReceiver 的 DUMP 权限；页面不可外部导出，独立任务栈避免主页面 singleTask 回跳销毁观察。官方确认、验证码由用户完成，报告只记录枚举、时间及身份比较，不导出Cookie／凭据。网易云明确退出后检查原生DISCONNECTED、Cookie jar为空及持久清理待办；随后由用户恢复原账号。

开始前11个文件保全散列及网易云播放日志私有副本已留存。退出会按既有规则清该平台缓存／日志；只有原账号恢复并核对后才还原播放日志，不恢复已退出凭据，不伪造网页登录状态。实际授权／清理未完成前不宣称通过。

材料：`.local/artifacts/account-authorization-playback-20261124/`。精确全量验证、已装包散列及设备最终结果以该目录README和最终摘要为准。

## 最终实机与完整验证

完整入口 `20261124T073547866Z-29712` passed：3,123项全部通过，无失败／错误／跳过，Lint0错误160警告；新增告警主要属于debug验证页文字资源。主包SHA-256 `82814a5414d573191ba7be35985a0665cd3970fc0e27912f27837266b3d3ea02`，与实机一致。源码冻结检查一致；本段设备结果为验证完成后补充文档，不改写历史源码清单。

- 网易云：15:41:11真实网页会话原位验证并保存，sameOwner=true；15:41:21明确退出完成，原生DISCONNECTED、Cookie jar为空、pending=false。随后用户重新登录，冷进程校验CONNECTED。WebStorage删除无完成回调，未逐项检查DOM存储，不扩大为全部网页存储独立验证。
- 酷狗：用户确认卡顿后重复授权，15:49:12和15:50:30观察到新验证成功且原账号一致；后一次原始JSON已保存。早一份共享报告被下一次操作覆盖，其结论仅作为直接ADB输出观察另注明，不冒充保留下来的原始文件。当前官方授权网页没有配置Musxi回调，用户确认后手动返回，Musxi回前台继续轮询；没有自动回跳是既有交互缺口，不代表授权失败。不凭空新增未确认的酷狗callback参数。
- QQ：15:51:29真实官方授权完成，新凭据验证并保存，sameOwner=true。官方页关闭后Musxi主页面仍在，无对应崩溃记录；该次系统事件未捕获VIEW回跳，不能断言新scheme是恢复的唯一原因。正式前端的旧先退出再登录流程与长期回桌面仍需独立复验；多版本回调歧义已修复。

网易云恢复原账号后还原测试前播放日志，11个保全文件逐行散列一致。三平台退出／换号及真实歌单写入未执行（网易云明确退出为本次明确授权例外）；没有音频播放。此次账号通过不替代新包播放取消／过期／音质的真实听音验收。前端未集中接线，无提交推送发布。


## 16:33～16:35 新包播放专项（2026-11-24）

实机 OPPO PJF110／Android16。网易云与酷狗运行包 `d31e43be…`，QQ运行包 `865e4c23…`；后者仅补debug选曲／授权触发，三平台生产播放实现相同。本次材料 `.local/artifacts/login-return-playback-device-20261124/`，每平台独立JSON；后续构建包不能自动继承这次同包结论。

| 项目 | 网易云 | QQ | 酷狗 |
| --- | --- | --- | --- |
| 官方资源真实起播、进度增长 | 通过 | 通过 | 通过 |
| 暂停时切音质、槽位和进度保持、恢复起播 | standard→higher通过 | standard→high通过 | 样本仅128，未覆盖 |
| 注入一次SOURCE_EXPIRED后真实重新取流播放 | 2次resolve／1次刷新 | 2次resolve／1次刷新 | 2次resolve／1次刷新 |
| 注入延迟解析时STOP、3.5秒后无迟到起播且队列为空 | 通过 | 通过 | 通过 |

明确边界：过期是debug注入的来源错误，未等待自然CDN有效期到期；取消是延迟错误期间STOP，未证明每个实际HTTP阶段都被中断；档位回退由既有单元测试覆盖，本轮没有人为更改账号权益。真人听感未反馈。本轮不改网络／音量，不操作挂机游戏，实际播放会短暂请求音频焦点。

原三平台播放日志、941首活动队列／索引7／111286ms暂停进度／循环和随机设置已恢复；11个原文件SHA-256与本次前基线一致。恢复曾因授权未结束与服务内旧代次失败，取消候选并重启Musxi后成功；失败报告不作为通过。账号均恢复原保存身份，无平台收藏或歌单写入。

## 本轮登录返回调查边界

QQ已再次打开官方QuickLoginAuthorityActivity，但未获得这次完成确认／回调；Musxi进程仍存活，不能据此宣称长期回桌面已修复。官方确认由使用者操作，未自动点击。

酷狗20.5.6的官方loginQRCode页面成功分支只允许内部HTTPS回址，否则closeFrame，无Musxi回调。尝试显式MediaActivity同任务托管，实机出现旧主页复用及授权页未打开；已完整撤回托管Activity及后台轮询例外，不交付失效方案、不后台强拉Musxi打断游戏。自动返回仍待实现，现有官方深链授权仍保持。需继续研究官方第三方分发入口或可用官方回调，而非臆造redirect_url。临时APK／反编译工具不进入源码。


最终统一检查：`.local/artifacts/verification/20261124T085026402Z-9564/summary.json`，3,123项／失败错误跳过均0，Lint0错误162警告（比上一轮增加2条debug诊断入口相关警告），主包及androidTest包编译、源码冻结通过。最终主包 `2b2778a9e0490b3031914d7bf464d463bc3ffdafc4298f21bb29bfd69ed53200` 已保留数据装机。同包QQ／网易云重复上述起播、音质、注入过期和取消通过；酷狗结果及最终保全以本轮 `final-summary.json` 为准。仅debug验收新增，正式页面另修正启动异常向账号仓库传递：不再吞掉startActivity异常，沿用OPEN_FAILED失败处理。文档此段在冻结检查后追加，未改生产源码。


## 用户复现QQ回桌面与任务隔离（2026-11-24 17:01）

用户确认最终2b2778a9包QQ重新登录仍回桌面。设备events捕获QQ QuickLoginAuthorityActivity与AgentActivity进入Musxi同一task4146，17:01:03.981 Musxi MainActivity恢复，17:01:04.057同一任务moveTaskToBackInner，17:01:04.078 Launcher恢复；17:01:05.500用户再次打开同一MainActivity。Musxi pid22324未在该窗口退出。由此该次是任务被后台化，并非进程崩溃；尚未从系统日志直接确定moveTaskToBack的调用方。

修补：QQ官方授权Intent加入FLAG_ACTIVITY_NEW_TASK，使官方Activity不再借用Musxi任务；不改认证参数或保存校验。新增QqOfficialLoginIntentTest验证独立任务标记、正式／开发回调及保留特殊字符的参数往返。必须继续实机确认新授权任务ID与Musxi不同，并由用户完成官方授权，不能只用单元测试认定问题解决。

酷狗可研究“有界后台轮询成功后主动返回”，上一轮失败仅针对同任务托管。当前轮询在Musxi后台暂停，直接加startActivity不足以形成可靠返回：Android限制后台Activity启动，现有悬浮窗许可不能当成所有设备的前提；需明确处理用户切去其他应用时不夺回前台，以及系统拒绝时的返回入口。暂不添加未知官方回调或依赖系统全局权限，自动返回仍未实现。

本次材料：`.local/artifacts/qq-task-return-20261124/`。授权完成由用户操作；不操作游戏、不自动退出账号、不改网络与音量、不提交推送发布。精确最终验证及安装包见该目录summary.json。

任务隔离最终检查：`.local/artifacts/verification/20261124T090647412Z-28616/summary.json`，3124项全过，Lint0错误／162警告；主包5b60aa48…已装机。新固定原位授权未开始新attempt，观察QQ为DISCONNECTED，故必须从正式连接入口验收，未改已退出凭据。该段为检查后追加文档，源码冻结通过。


## 酷狗授权完成主动返回（2026-11-24）

用户确认QQ任务隔离新包5b60aa48实际登录后正常返回Musxi、不再退桌面；此为用户正式流程验收，更多设备／QQ版本不外推。

酷狗保留官方深链，不再托管MediaActivity。AccountRepository只有KUGOU且官方Activity成功发起的Pending尝试允许后台轮询，沿用2.5秒间隔、180秒总deadline、平台串行工作队列、取消／过期／错误停止。候选verify及save完成且代次仍相同，删除Pending后仅投递一次平台／代次完成通知。Application收到后再次核验CONNECTED与代次，向MainActivity发一次NEW_TASK返回请求；不反复拉前台、不预设延时即成功、不操作游戏、不新增权限。系统后台启动限制可能静默拒绝，账号保存与导航解耦，仍可手动回Musxi；不保证全设备自动返回。

debug仅记录REQUESTED／RETURNED／OPEN_FAILED与时间，不含账号、凭据或URL。新增仓库测试覆盖后台校验保存后单次通知、取消与失败不返回、QQ后台仍暂停。设备材料`.local/artifacts/kugou-active-return-20261124/`，实际返回与同账号结果以其summary.json为准。无提交推送发布。


第一版仅后台轮询包993ce400统一检查3,127项全过（精确证据verification/20261124T092319036Z-25904），但实机酷狗入口停首页，使用者连续点击后未收到该尝试完成；后台Musxi被ColorOS标记virtualFreeze，返回前台后TIMEOUT回滚，未替换原凭据／未触发返回，不计实机通过。补KugouAuthorizationService，用户可见发起时启动shortService前台通知，等待期间防止普通cached进程冻结，完成／取消／错误停止，175秒最大存活且START_NOT_STICKY；不申请电池白名单、不改系统设置、不借用播放服务。现有FOREGROUND_SERVICE权限足够shortService，无新增运行时权限。仍不声称所有OEM允许后台打开Activity。


短时服务第一版42870776虽统一检查通过，实机17:35:23发生shortService超时ANR，系统杀pid8346，不计通过。DropBox主线程位于Service.stopSelf→stopServiceToken同步调用；已修为先stopForeground移除前台类型，再stopSelf，并将最大时长提前为150秒；加入debug轮询阶段标签用于定位，仍须实机确认，不能把编译通过当修复通过。五项原文件散列在失败后保持一致。失败材料short-service-main-trace.txt等保留，未修改游戏或系统设置。


shortService修正版fc107a02仍未持续执行查询，已从最终源码完整移除服务和清单声明，不留不可靠后台服务。当前改为KugouAuthorizationReturn：仅已有悬浮窗许可时显示可见“等待酷狗授权·返回Musxi”状态条，使应用保持可见窗口；点击可手动返回并移除，完成／取消／错误／175秒移除。未授予overlay仍维持原官方登录与手动返回，不主动申请权限。该实现引入的是登录期间功能性小窗，不是此前已暂缓的浮卡视觉重做。后台主动返回需在小窗仍有效时执行；设备验证结果另记，不把此前失败服务包全量检查套用新包。


17:47:41 可见overlay版本2af7f5e4实机通过：新的酷狗原位授权verify／save成功且sameOwner=true，MainActivity收到NEW_TASK并resume，debug记录RETURNED，小窗移除。该次后台回跳事件与报告已分别保存overlay-reauth-passed.json、overlay-return-events.txt、overlay-return-passed.xml。后补一次性门禁：必须该代次小窗仍存在才主动返回，点击小窗手动回Musxi或175秒移除后不再自动抢回；CONNECTED短暂保留小窗给已排队完成回调，再移除。未授权overlay不会后台强拉页面。最终包需门禁补丁同包补验。


最终包848ad01c实机17:56:01再次verify／save同账号并自动回MainActivity通过，状态条移除，五项原文件散列一致。最终统一证据`.local/artifacts/verification/20261124T094841767Z-5244/summary.json`：3,127项全部通过／失败错误跳过0，Lint0错误164警告；主包和设备测试包编译、源码冻结通过。最终主包SHA256 `848ad01c577e097db3383c21b721d9cc4373b18606731429378f1b38698affe8`已保留数据装机。精确设备材料final-reauth-passed.json／final-return-passed.xml／final-return-events.txt及summary.json。无overlay、其他系统与长时间切走行为未实机覆盖；没有把短时服务失败包当成成功。此段是验证后追加文档，无后续源码变更。
