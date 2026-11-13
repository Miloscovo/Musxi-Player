# 当前开发接续

更新：2026-11-13。本文维护当前任务和验证基线；能力完成度统一看[状态矩阵](feature-status.md)，接口看[总览](frontend-contract.md)，旧交接和日期证据看[历史索引](history/README.md)。旧记录中的“当前”“下一步”和队列快照不构成新任务或授权。

## 用户安排与工作范围

- v0.3已正式发布：[Release与安装包](https://github.com/qwt1011/Musxi-Player-Android/releases/tag/v0.3.0)、[对应源码](https://github.com/qwt1011/Musxi-Player-Android/tree/v0.3.0)、[完整更新说明](android-v0.3-release.md)。安卓发布提交adb4c0b；PC上游[PR #30](https://github.com/Miloscovo/Musxi-Player/pull/30)使用fork分支`qwt1011:android/v0.3`，基于`android/development`的PR #28合并点c8b1ab3，保持与安卓main源码树一致；不合并PC main或自动合并PR。三附件公开回下载的大小与散列均匹配。首次云端检查暴露两项宿主测试时序问题，main和PR已补虚拟时钟及数据库测试初始化／收尾，不改已发布APK或移动标签；实际提交、CI与校验结果保留在项目 `.local/artifacts/release-v0.3-preparation/`。用户已更新GitHub授权，先前403仅为历史失败，不打印令牌。

- 当前任务为Android v0.3发布：用户明确授权核对安卓独立仓库及PC上游基线、提交全部现有开发、向上游android/development提交新PR，并在安卓仓库发布正式签名APK与更新说明；不合并PC主线。版本升为0.3.0／code6，沿用v0.2密钥。README参考LoveACE结构，使用自有图标与合成页面样张。前述历史“不提交／不推送”仅约束对应旧轮，不撤销本轮明确授权；真实账号／平台写入仍不在发布操作范围。

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

发布后CI稳定性补丁已在本地重新统一验证：2,784项通过，0失败／错误／跳过，Lint0错误150警告，主包／设备测试包编译及954条路径冻结通过；证据 `.local/artifacts/verification/20261113T151805094Z-24544/`。相较发布构建新增的1条警告仅为协程测试依赖的版本提示，测试依赖按现有运行库保持1.10.2。首次PC CI的5／40ms真实延时顺序不稳定改为虚拟时钟；安卓CI的LocalMedia测试准备与清理补主线程预开库、主线程读取回调Result、cancelAndJoin后再关数据库。仅测试／测试依赖和文档变化，无生产行为修改。云端最终结果以本轮材料中的android-ci-result／upstream-ci-result和最新运行记录为准，不覆盖首次失败证据。

v0.3发布轮已完成本次0.3.0／code6源码的统一验证：2,784项全过、0失败／错误／跳过，Lint0错误149警告，主包／设备测试包编译及954条源码路径冻结一致；证据 `.local/artifacts/verification/20261113T140810256Z-31376/`，debug APK `08c8e10e6e093024bed50f17867fb39eb5aed6650e49861d96013464a1d8cba9`。正式签名APK为19,250,861字节，SHA-256 `a3729ca554fdefab47ea95a184a84b71690474cff01959e59b37faa8e8f1d461`，沿用v0.2证书，v2签名／版本／release清单检查与DEX调试类抽查通过，Release Lint0错误55警告；包位于 `.local/artifacts/release-0.3.0/`。发布详情见[v0.3说明](android-v0.3-release.md)；本次没有安装手机或执行真实平台写入，覆盖升级待用户实际执行。以下各轮保留各自历史包与实机范围。

当前轮为 **锁屏歌词 C 后端**：最终统一 **2,784项通过，0失败／错误／跳过，Lint0错误149警告**，主包／测试包编译及947条源码一致性通过，证据 `.local/artifacts/verification/20261113T085230464Z-31876/`。新主包 `5558360aa0ed8d97b478ade24f35114201bb110aafe1ba6c3edfc23db37da5e8`已保留数据安装，手机base.apk散列一致；负向NEEDS_PERMISSION／BAL_BLOCK及双权限允许后本机后台显示的阳性结果均保留。最新设备证据 `.local/artifacts/lockscreen-lyrics-backend-20261113/acceptance-20261113T092402Z/acceptance-summary.json`，显示／焦点／触摸退出及仍锁定事件已核实，用户确认看到。最终CARD／armed=false；进程重启后计数0不是显示失败。正式ui未变，release无具体宿主；后续前端接线再验正式页面，不将机制探针视作视觉或四方向均通过。设备轮次未改源码、未重新构建，收尾仅补文档。

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
- 网易云整单订阅缺兼容 SDK v2 提供者，当前写前 `verification_required`；酷狗整单订阅未开放；QQ 创建结果不确定，禁止自动重试或按标题猜 ID。QQ 普通改名已单设备往返，仍需复杂元数据及跨账号验证。
- 三平台 KRC/QRC/YRC 后端已有 11-07 单台 Android 16 实际歌词与缓存证据，逐字页面和听音对齐待接；旧包结果不证明本地音频解码或当前包听音。
- v0.2与上游PR #28为本次发布前基线；当前用户已授权发布v0.3，结果见[v0.3说明](android-v0.3-release.md)。旧基线与后续提交细节在[整理前交接快照](history/next-session-handoff-through-20261108.md)，不因此自动合并上游PR或操作其他项目。
