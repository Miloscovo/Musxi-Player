# GitHub Actions 远端首跑记录

状态：2026-11-03。本记录对应下列提交和运行，不代表后续提交自动通过。使用入口见 [验证工作流](validation-workflow.md)，现状见 [开发接续](next-session-handoff.md)。

## 实际运行

| 运行 | 提交 | 结果 |
| --- | --- | --- |
| [41558725811 / attempt 1](https://github.com/qwt1011/Musxi-Player-Android/actions/runs/41558725811) | `4a3458e2d2863ab547670874d44cd057205fa657` | SDK 与工具准备成功；accounts 的缓存测试失败；失败摘要、构建日志与已有模块报告正常上传 |
| [41559846988 / attempt 1](https://github.com/qwt1011/Musxi-Player-Android/actions/runs/41559846988) | `d2e00180a7b0ed1b57b82429a4b79907040a911e` | 完整验证与报告上传通过；已下载核对摘要、源码清单和模块报告 |

通过轮次使用 Windows 2025 / Microsoft JDK 17.0.19 / Android SDK 36；验证步骤于北京时间 09:28:11 开始、09:38:50 结束。Git 工作区干净，源码清单覆盖 637 个文件，开始和结束时源码身份一致。

## 首次失败与修复

`ContentMetadataCacheTest.countsAndClearsRealStoresWithoutTouchingAccountsBindingsJournalsOrOtherCaches` 写入歌词缓存失败。`FileLyricsCacheStore.prepareOwnerDirectory` 将展开短路径别名后的真实目录与仍含别名的期望目录比较，误报 `Lyrics cache unavailable`。

修复提交为 `8632d74`：以真实根目录计算期望 owner 路径，保留 owner 格式、目录边界及符号链接检查。本机通过 Windows 8.3 短路径临时目录复现原始失败，修复后在同种条件下执行 core 355 项、accounts 31 项，全部通过，无跳过；随后才进行完整远端复验。没有跳过失败测试或弱化验证入口。

## 通过轮次结果

| 模块 | JVM 测试 | Lint 错误 | Lint 警告 |
| --- | ---: | ---: | ---: |
| accounts | 31 | 0 | 3 |
| app | 918 | 0 | 104 |
| core | 355 | 不适用 | 不适用 |
| platform-kugou | 110 | 0 | 3 |
| platform-netease | 40 | 0 | 5 |
| platform-qq | 58 | 0 | 6 |
| playback | 87 | 0 | 5 |
| 合计 | **1599** | **0** | **126** |

所有 JVM 测试的失败、错误和跳过均为零。debug 主包与 androidTest 包编译通过，`deviceTestsExecuted=false`。

相对之前本地 119 条 Lint 警告，远端 app 多 4 条 `GradleDependency` 和 1 条 `OldTargetApi`，playback 多 2 条 `GradleDependency`。这些为工具环境下的依赖更新和目标版本提示；本轮未升级依赖或修改 targetSdk，不将警告差异隐去或直接解释成业务回归。

debug APK SHA-256：`0ce6e3e4e7e900a5c8c506b81232d60a87332aff5cf9794f35895056284d6ba9`。该哈希用于标识本轮输出，不能替代正式 v0.2 Release 的包或签名信息。本轮未发布新 APK、变更正式签名或执行实机验收。

## 证据保留

通过轮次 Artifact 为 `android-verification-41559846988-1`，ID `13196473969`，815019 字节；内含 `.local/artifacts/verification/20261103T012811952Z-8244/` 的摘要、源码清单和模块 XML，以及各模块构建报告。已确认隐藏目录中的摘要确实上传。

本机保留 `.local/artifacts/actions/41558725811/` 与 `41559846988/`，分别保存原始运行元数据、Artifact 元数据、日志和解压后的报告；失败轮次另保留短路径复现实验的前后日志与修复后 XML。这些材料排除 Git，不是公开下载链接。GitHub 报告保留 14 天，长期追溯使用本地证据及本文运行 URL。
