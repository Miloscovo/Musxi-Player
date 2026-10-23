# 安卓后端增量 PR 记录（2026-10-21）

> 历史专项记录：以下只对应文中日期、包和测试范围；当前状态以 [0.1 验收](android-0.1-acceptance.md) 为准。本机证据路径仅用于本地复查，不随仓库分发。

已创建：[上游 PR #24](https://github.com/Miloscovo/Musxi-Player/pull/24)，已于 2026-10-21 合并（上游提交 c5821a7）；提交 `ac3ac80f4259244a50fb5269aa0fdf5bd1c59afd`，70 个文件，目标 `android/development`。本地开发目录继续保留未提交副本，PR 提交在隔离 worktree 中。

## 上游旧 PR 核对

- [PR #23](https://github.com/Miloscovo/Musxi-Player/pull/23) 已由 Miloscovo 于 2026-10-21 12:48:21（北京时间）合并；原临时 base 为 `codex/pr23-archive-merge`，合并结果保存在上游 `android/development`，桌面 `main` 保持独立。
- 原提交为 `1b86ed5f38c99f17077b23c0c49c776f11813529`，上游合并提交为 `e847e0204aa7bed1caea7314ab5c6ceda553a8c2`。合并提交的两个父提交为原基线 `80743c6` 和原安卓提交 `1b86ed5`。
- GitHub compare 与本地 Git 均确认合并后的源码树和 `1b86ed5` 一致，没有维护者额外改动文件。旧 PR 无普通评论、评审提交或行内评论，也没有要求修改的事项。
- 截至本次核对，上游安卓分支头仍为该合并提交；PC `main` 头为 `84a96e4`（壁纸设置及 UI 行为），不将其整树合并到独立安卓工程。

## 本次范围

用户授权提交目前新增内容并创建新 PR，明确排除 cc 未完成的前端。本次从上游 `android/development` 创建 `android/backend-p0-p2`，在独立 worktree `<project-root>\.local\worktrees\Musxi-Player-android-backend-pr` 整理，原工作区 `<project-root>` 的进行中成果保留。

包含：

- P0：三平台歌曲公开封面元数据、兼容 v1 的分页缓存 v2、当前播放专辑及双平台字段、分页搜索、搜索单曲播放。
- P1：循环/随机、每平台队列/位置/模式 journal、显式暂停恢复、最近播放、跟随实际来源的标准歌词；包含酷狗歌词 `/search` 兼容修复。
- P2：三平台本人喜欢集合与单曲写后核实，Media3 服务拥有的队列编辑、revision 校验及 journal 联动。
- 相应单元测试、仅 debug 的固定真机验收入口、接线约定、验收记录、第三方来源声明和后续接续计划。

排除：

- `app/src/main/kotlin/tech/musxi/player/ui/`。
- `app/src/test/kotlin/tech/musxi/player/design/`。
- 两份 Gradle 文件中 Compose、Robolectric、Roborazzi 的插件、依赖与截图测试配置；本次两份文件与上游安卓基线相同。
- `$null` 临时文件、`local.properties`、构建产物、签名材料及任何账号/设备私有数据。

排除前端后未发现后端对这些 UI 类型或依赖的引用；本次构建验证的正是上述独立后端范围。文档内历史提到的 cc 依赖、截图与 UI 文件指本地开发工作区，不代表它们包含在本 PR 中。B1 连接恢复、B2 收藏刷新联动、B3 请求耗时测量、B4 换源匹配仍为后续计划，本次没有新增实现。

## 本轮重新验证

在隔离 worktree 执行 `./build.ps1 -Test -Lint` 成功，289 个任务实际执行：

| 模块 | JUnit 数量 | failures/errors/skipped | Lint errors | Lint warnings |
| --- | --- | --- | --- | --- |
| app | 11 | 0/0/0 | 0 | 2 |
| core | 144 | 0/0/0 | 不适用（纯 JVM） | 不适用 |
| playback | 35 | 0/0/0 | 0 | 2 |
| platform-kugou | 28 | 0/0/0 | 0 | 1 |
| platform-qq | 22 | 0/0/0 | 0 | 1 |
| platform-netease | 17 | 0/0/0 | 0 | 5 |
| accounts | 无独立测试任务；业务规则在 core 等测试中覆盖 | 不适用 | 0 | 1 |
| 合计 | 257 | 0/0/0 | 0 | 12 |

此前 P2 的 258 项报告包含 1 项 cc 截图测试，本次将其排除；历史报告的 49 条 Lint warnings 对应包含前端依赖的工作区，本次不沿用该数字。没有把历史设备或听音证据算作本轮重新执行。

本轮 debug APK SHA-256：`f4a07ebe7e06fb37d8a1ed7552db1acb64f5f806c2f0ed28950e3b48ccee459f`；apksigner 验证通过，v2=true。另生成 release 合并清单，检查正式 Application、debuggable 及 debug 组件隔离；未生成或发布 release APK。

本轮未安装手机、重做听音或读写真实收藏；既有单设备验收仍见 [P0](p0-device-acceptance.md)、[P1](p1-data-acceptance.md)、[P2](p2-acceptance.md)。长时后台/Doze、真实换号/过期、多设备和正式前端流程仍待专项验证。

## 工程接续

新 PR 目标为 `Miloscovo/Musxi-Player:android/development`，来源为 `qwt1011/Musxi-Player:android/backend-p0-p2`；不向 PC `main` 提交安卓根目录替换，不更新旧 PR，不强推原开发分支。提交保留 qwt1011 的实际作者/提交者，按项目约定核对一次 Codex co-author trailer。

后续功能顺序仍见 [development-handoff.md](development-handoff.md)。原本地工作区保留未提交的前端和后端副本；不能用 reset/clean 清理它们，也不能把新 PR 已归档状态误认为 cc 前端已完成。合并及发布不在此次授权范围内。
