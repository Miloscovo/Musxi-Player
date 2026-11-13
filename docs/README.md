# Musxi Android 文档导航

更新：2026-11-13。按“当前任务 → 能力状态 → 专门合同 → 当次证据”阅读。入口职责固定；日期记录中的当前状态、测试数和设备结果都属于当次范围。

## 先读这些

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
