# Musxi Android 文档导航

更新：2026-11-26。按“当前任务 → 能力状态 → 专门合同 → 当次证据”阅读。入口职责固定；日期记录中的当前状态、测试数和设备结果都属于当次范围。

## 先读这些

[v0.3.1 累计更新与发布范围](android-v0.3.1-release.md)：三平台账号／播放、分页队列、本地音频和统一浮卡／锁屏歌词界面；区分已开放入口与待验边界，正式验证结果在发布时回填。

[网易云网页登录外壳调整](frontend-netease-web-login-refinement-20261126.md)：按用户与Gemini讨论稿收敛到Musxi既有Sheet／GlassHost，底部验证按钮随主题Accent切换，官方网页不自绘；补会话容器隔离、防截屏标记恢复及窄屏／大字／横屏检查。最终验证与设备边界见该记录。

[第三批：搜索、推荐、歌词与换源一致性](backend-search-lyrics-consistency-20261126.md)：三平台搜索完成门闩、元数据不可变快照与歌词匹配搜索取消接线；最终统一3,171项全过，三平台只读实机通过。第一批网易云长期限补验按该记录更新，不改CC前端／网易云登录。

[第二批：分页、队列与一致性](backend-pagination-queue-consistency-20261124.md)：已补QQ／酷狗队列前缀校验、关闭目录展示快照、歌手ID缓存快照及网易云／酷狗改名丢响应读回。[11-26同包实机](backend-pagination-queue-device-20261126.md)完成酷狗942首补全／途中停止、三平台目录重开、跨进程暂停恢复及受控迟到确认竞争；整包3,165项通过，冻结检查通过。网易云／QQ仅小歌单样本，真实改名与远端并发变更等边界另验；未动登录前端。

[播放请求与取消审查、QQ回跳排查](backend-playback-request-audit-20261124.md)：[11-26剩余补验](backend-playback-remaining-device-20261126.md)修复酷狗无损变体身份及既有两域名HTTP兼容；标准→无损真实播放、三平台真实取消、QQ／酷狗本地能力自然到期通过，最终3,168项全过。网易云实际20分钟到期已在[后续补验](backend-search-lyrics-consistency-20261126.md#第一批长期限补验)通过；自然CDN签名拒绝及更多系统仍待，不改登录前端。

[账号后端第三批](backend-account-reauth-web-20261124.md)：三平台原位重认证后端、候选失败／取消回滚；网易云现有网页登录关闭保留与明确退出清理。[实机补验](backend-account-device-20261124.md)已覆盖三平台校验、歌单读取、取消保留原账号和断网后自动恢复；真实授权替换／退出及更多系统仍待。新前端入口未接；沿用[第二批执行隔离](backend-account-execution-20261124.md)和[首批状态与恢复](backend-account-status-recovery-20261124.md)。MeiloX 参考、账号页层级和三处浮卡待办见[账号讨论](backend-account-planning-20261124.md)。

[网易云 XEAPI 取流适配](backend-netease-xeapi-20261124.md)：上游协议差异、新请求实现、离线互通与真实公钥检查；11-24 同包《Da Capo》标准／极高取流起播、通知暂停恢复及锁屏播放通过。

[后台播放可靠性与诊断](backend-background-reliability-20261118.md)：前台、焦点、准备、缓冲、来源和解码诊断，以及有界等待和原位重试规则。

[网易云取流定位与整机重启补验](backend-netease-stream-reboot-20261119.md)：网易云播放 URL 接口返回 `-460`，整机重启后恢复原位并保持暂停。

[后台播放剩余场景实机补验](backend-background-device-20261119.md)：蓝牙重连、CUE／DSD128 和酷狗／QQ 锁屏通过，网易云来源阶段失败及设备保全。

[本地库双版本与目录刷新实机验收](backend-local-versions-lifecycle-device-20261118.md)：旧库保留数据升级至 schema 7；Android 系统文件提供方刷新、撤权恢复，以及真实播放期间普通续页和整理通过。

[目录刷新与任务生命周期](backend-local-lifecycle-20261117.md)：系统目录名、整目录错误与单项失败分离，以及导入／来源检查结果清除与迟到保护；按小而精限制范围。

[本地库整理与进度双版本](backend-local-versions-20261117.md)：边听边整理版本拆分、查询 token 与 schema 6 兼容迁移；旧接口保持严格保护。

[后端下一步建议](backend-next-priorities-20261117.md)：用户安排近期以后端为主、前端暂缓；优先版本与分页稳定性、目录与任务生命周期、后台可靠性，再选择 A／B 或本地 LRC。新建歌单浮卡仅记录风格待办。

[曲库主页最近播放稳定性修复](backend-library-home-stability-20261117.md)：修复同账号重开目录时先发布空态引发上方模块消失；三平台重开刷新、产品推荐／喜欢页进出与下拉回弹后位置验证，QQ 最近播放卡片缺样本。

[歌单长按反馈与酷狗操作权限查询修复](backend-playlist-menu-permissions-20261117.md)：去掉网格整块按压蒙层、沿用统一长按触感；实机确认稳定版本 null 终页和普通行省略 is_def 两个兼容点，补严格终页及本人创建者验证。重连同包权限、大小网格长按、三平台目录详情及刷新回归通过，未真实改名删除。

[酷狗下拉刷新与自动分页循环修复](backend-playlist-refresh-20261117.md)：首页刷新清尾页后，近期成功记录误阻止缺缓存页请求；补齐完整目录立即刷新及真实页面自动续页回归。

[歌单前端接入审查与回归修复](frontend-playlist-review-20261117.md)：主目录整合当前 DSF 与隔离前端，修复旧库版本安装、酷狗分页、详情订阅、弹窗及列表问题；真实平台写入不在本轮。

[DSF正常生产启用验收](backend-dsf-enablement-20261117.md)：正式源码已启用，64／128复制／引用和新进程四条来源控制／曲末通过；release编译／Lint／清单隔离及签名门禁通过，签名APK和正式包实机尚待。前端及第五六批暂缓。

[DSD64断电半小时观察](backend-dsf-battery-device-20261116.md)通过：22:33:06～23:03:12，断电播放30分5秒、120次全部熄屏锁定／放电采样、8次回绕、0缓冲跃迁，电量79%→77%，charge counter报告减少52mAh，温度24.5→21.6°C、thermal始终0。单次整机数据，不能外推其他倍率／续航；数据保全完成。云端按需；该记录属于11-16观察，正常启用以11-17专项为准。

[实际系统提供方验收](backend-dsf-actual-provider-device-20261116.md)已通过内部存储／下载两个不同authority：实际随机定位、引用持久授权／重开隔离仓库解析、COPY整文件匹配与跨来源relink保留ID／名称／进度／书签。当前已装云盘未发现标准provider，云端仍待；原库不变。

[受控Android管道实机](backend-dsf-pipe-provider-device-20261116.md)已完整通过：真实ESPIPE、REFERENCE返回COPY_REQUIRED且无授权残留；已知／未知大小COPY整文件与尾标签匹配，8,192字节在途取消及未知大小截断拒绝均无残留。使用隔离库，原数据不变；实际网盘／耗电仍待。

[DSF切换与一小时持续播放](backend-dsf-switch-soak-20261116.md)：DSF／普通FLAC／酷狗切换与进度恢复，DSD64连续60分4秒／14次循环／0缓冲跃迁；USB下热与内存、完整数据保全及剩余边界。

[统一外部音频测试材料](audio-test-materials-20261116.md)：用户指定独立目录、真实DSD64／128及高采样FLAC、截断样本与本地探测范围；[真实音乐DSD64／128与在途复制取消实机](backend-dsf-real-music-device-20261116.md)。

新增：[本地音频与扩展音源六批计划](audio-expansion-roadmap-20261114.md)、[CUE第一批接口与交付](backend-cue-preview-20261114.md)、[CUE第二批入库与播放](backend-cue-import-playback-20261114.md)、[第三批格式与输出信息](backend-audio-format-info-20261114.md)、[第四批DSF准备](backend-dsf-preparation-20261114.md)、[首次实机与性能评估](backend-audio-expansion-device-20261114.md)、[DSF应用内优化及持续播放复验](backend-dsf-optimization-20261114.md)、[后台受限定位与整首熄屏验收](backend-dsf-background-20261115.md)、[有界听音窗口与音频焦点观察](backend-dsf-listening-20261115.md)、[蓝牙听感确认与64／128工程样本](backend-dsf-multirate-samples-20261115.md)、[64／128短样实机与暂停读回诊断](backend-dsf-multirate-device-20261115.md)、[DSF正式导入与来源收尾](backend-dsf-production-prep-20261115.md)、[DSF正式导入新包实机](backend-dsf-production-device-20261115.md)（生产开关关闭，更多提供方与长时仍待）。

| 要解决什么 | 文档 |
| --- | --- |
| 继续开发、查看基线和下一步 | [当前开发接续](next-session-handoff.md) |
| 判断后端、界面和实机各完成到哪 | [当前能力状态](feature-status.md) |
| 找应用入口、状态与调用约定 | [前端接口总览](frontend-contract.md) |
| 找某轮实现、失败、包与设备证据 | [历史与日期记录索引](history/README.md) |

## 给 CC 的前端需求单

- [锁屏歌词 C](frontend-lockscreen-lyrics-c-brief-20261113.md)：正式设置、宿主、歌词页背景复用、播放控制、四方向退出与权限引导。
- [歌单操作](frontend-playlist-management-brief-20261113.md)：曲库创建、改名、删除整单及Musxi本地排序；包含当前接口与验收范围。

## 现行接口合同

| 能力 | 详细合同 |
| --- | --- |
| 账号、恢复与验证、原位重认证 | [首批状态](backend-account-status-recovery-20261124.md)、[执行与合并](backend-account-execution-20261124.md)、[三平台重认证及网易云网页](backend-account-reauth-web-20261124.md) |
| 本地音频导入、检索／批量整理、播放、目录刷新／来源检查、导出与备份恢复 | [本地音频](frontend-backend-contract-local-audio.md) |
| 三平台歌曲推荐、按账号缓存、刷新/取消与推荐点播 | [推荐合同](frontend-backend-contract-recommendations.md) |
| 定时停止 | [服务策略与状态](frontend-backend-contract-sleep-timer.md) |
| 锁屏歌词 C | [三态互斥、内容流、权限与宿主](frontend-backend-contract-lockscreen-lyrics.md) |
| 静态壁纸 | [导入、候选、应用与界面接线](frontend-backend-contract-wallpaper.md) |
| 红心、歌单与队列 | [P2](frontend-backend-contract-p2.md)、[歌单管理](playlist-management-contract.md)、[曲库管理与排序](backend-playlist-library-management-20261113.md)、[给 CC 的首版范围](frontend-playlist-brief-20261106.md) |
| 歌词、译文和字词模型 | [歌词合同](frontend-backend-contract-lyrics-translation.md) |
| 换源与音质 | [保守匹配](frontend-backend-contract-b4.md)、[音质与输出](frontend-backend-contract-quality.md) |
| 数据恢复、最近播放、歌词基础 | [P1](frontend-backend-contract-p1.md)、[早期 v2 增量](frontend-backend-contract-v2.md)；新能力以对应专门合同覆盖旧阶段备注 |
| 设置、会员、缓存和实体歌曲 | [第四批合同](backend-batch4-contract.md) |
| 应用内更新 | [更新状态与安装](android-app-updates.md) |

## 构建、发布与规则

- [工程规则](../AGENTS.md)、[统一验证工作流](validation-workflow.md)。
- [Android v0.3 发布说明](android-v0.3-release.md)、[v0.2 历史发布](android-v0.2-release.md)、[正式构建](release-build.md)、[发布签名与私钥](signing-security.md)。
- 远端 Actions 首跑、旧包验收和发布后的每轮结果在[历史索引](history/README.md)，不代表当前源码重新验收。

## 设计与参考

- [本地音频方案与固定 GitHub 参考](local-music-design-20261108.md)。
- [PC 能力对照与壁纸设计依据](pc-capability-roadmap.md)、[MeiloX/Glasmio 固定快照阅读](reference-review-20261103.md)；排期以当前交接为准。
- [交互与材质规范](design/interaction-spec.md)、[播放器/歌词](design/player-sheets.md)。
- 历史样张：[账号页](design/account-sheets.md)、[搜索页](design/search-sheets.md)、[应用外壳](design/shell-sheets.md)、[主题](design/theme-sheets.md)。设计存在不等于界面或性能已验收。

## 维护规则

1. README 只介绍项目、版本和入口；`next-session-handoff.md` 只维护当前任务/基线/下一步；`feature-status.md` 只维护能力矩阵。更新已有段落，不在当前入口尾部追加“上文是上一阶段”。
2. 参数、状态、线程和失败语义写对应合同，完成度写状态矩阵；交付与实机结果按日期独立记录并加入历史索引。多份合同冲突时先核对源码，不能按旧包数量猜当前能力。
3. 归档保留原文与证据，只修相对链接并注明来源；不把历史账号、队列、临时授权当成当前指令。已有记录路径保持稳定，避免外部引用失效。
4. 临时脚本/日志/散列/APK/快照使用项目 `.local/`。文档整理用 `scripts/check-docs.ps1` 检查链接，并核对历史保全；业务修改交付再跑 `scripts/verify.ps1`。文档检查不冒充重新完成业务测试。
5. 本次整理前原始字节在 `.local/checkpoints/docs-cleanup-20261108/`，整理清单在 `.local/artifacts/docs-cleanup-20261108/summary.json`；README 与交接全文另有可追溯快照，见历史索引。
