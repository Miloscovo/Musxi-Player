# 歌单操作前端 Codex／DeepSeek 隔离交付

> 后续审查发现本地 schema 降级安装、酷狗分页、详情订阅和弹窗／懒加载等回归；修复已经整合到主目录，详见[审查修复记录](frontend-playlist-review-20261117.md)。下文为最初隔离交付的历史范围与验证，不能代表修复版，也不要再安装旧工作树 APK 覆盖 schema 5 数据库。

2026-11-17。基于 main `e898332`，独立分支 `codex/playlist-management-frontend` 与工作树 `.local/worktrees/playlist-management-frontend/`；主目录未提交的 DSF／本地音频工作未纳入本任务。DeepSeek 使用独立上下文只读审查正式页面接线，主代理完成实现、修正和验证。未提交、推送或发布。

## 实现范围

- 三平台曲库目录统一观察 `app.playlists.catalog(platform).state`，进入／切平台 `open()`，离开 `cancel()`；详情仍使用原曲目浏览器，不与目录页混拼。目录显示保持 catalog 的跨创建／收藏顺序，不再按 kind 二次分组。
- 曲库标题提供新建入口；列表每行提供独立“拖动排序”和“更多”区域，网格长按打开同一菜单。“我喜欢”保持独立固定模块，不暴露管理菜单。
- 菜单打开读取 `managementInfo`，权限返回前不提供远端写入；改名、删除使用各自权限。创建与改名使用名称校验、提交互斥、平台真实标题和输入保留；失败／未确认不会自动重放。
- 整单确认写明平台、歌单名、歌曲关系、其他歌曲／歌单与已接受播放队列不受影响。只有 `confirmedAbsent=true` 的 APPLIED／UNCHANGED 才按删除完成；UNKNOWN 刷新核对，FAILED 保留明确失败提示。当前详情为已删目标时退出。
- 移到最前／最后、恢复默认与列表拖动都固定 revision；refresh／分页／保存 revision 变化或忙碌时取消，不自动重试旧手势。移到最后要求完整目录。排序存储错误在页面可见。
- 旧红心、歌曲“收藏到”、`MembershipEditor` 与播放队列接口未改。未新增协议、凭证、后端请求或运行时依赖。

## 验证

最终 `pwsh -NoProfile -File scripts/verify.ps1` 通过：2,793 项 JVM 测试，0 失败／错误／跳过；六个 Lint 模块 0 错误、151 警告；debug 主包与 androidTest 包编译通过，冻结前后源码一致。证据：`.local/artifacts/verification/20261117T021812216Z-20736/`。APK SHA-256 `b99a833cfb99e406790c127ff2f5c9566fa30eb9b2d38d3c3556d7c22bcaf7a1`。

新增控制器测试覆盖行权限与旧账号回调、真实创建标题、重复提交、失败输入、UNKNOWN 删除、确认缺席、名称边界；拖动纯函数覆盖向下／向上／尾锚点／非法几何；Compose 测试覆盖权限尚未返回、独立删改权限和删除确认文案。原分组测试随新 catalog 跨类别排序语义移除，页面测试改为断言不再分组。

手机 PJF110／Android 16 已保留数据安装同散列 debug 包；QQ 页面可见新建、把手及更多按钮，行触摸区分离。酷狗首轮目录显示网络读取失败，未把它当作空目录。随后其他应用窗口／悬浮窗改变了前台，停止自动触摸；未完成三平台菜单、实际拖动／重启保留、弹窗及正式远端写入的人工确认。没有执行真实创建、改名、删除，没有退出／切换账号或清数据。此前旧版分组与测试配置失败已修正，最终通过结果不混用旧包。

## 接手与回退

CC 直接在此工作树审查即可，无需复制到主目录。核心文件为 `ui/library/PlaylistManagementController.kt`、`PlaylistManagementDialogs.kt`、`PlaylistReorder.kt`，以及 `LibraryViewModel.kt`、`LibraryContent.kt` 与 `ui/MainShell.kt` 接线。未提交状态下可用 `git diff` 逐文件审查；用户决定保留后再授权提交。回退整个隔离任务使用 `git worktree remove` 前先确认不再需要本次未提交成果，不能把它当成主目录 DSF 的回退。

后续真实平台验收需要用户当轮明确指定临时本人普通歌单与写入窗口；本交付不授权消耗现有歌单，也不使用历史探针 marker。推荐人工验证顺序：只读菜单／我喜欢保护 → 本地排序及重启／恢复默认 → 空歌单创建、改名 → 指定临时歌单删除；网络不确定时只刷新核对，不重发。
