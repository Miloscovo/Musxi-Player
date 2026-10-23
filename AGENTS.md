# Musxi Android 开发约定

## 当前入口

当前源码版本为 `0.1.0-alpha02`，属于很不完善的 v0.1 开发基础，不是稳定版。开工先读 README.md、docs/v0.1-foundation.md、docs/android-0.1-acceptance.md 与 docs/next-session-handoff.md。带日期文档记录历史包，不能当作最新结论。

安卓独立仓库为 https://github.com/qwt1011/Musxi-Player-Android ，默认分支 main；PC 来源为 https://github.com/Miloscovo/Musxi-Player 。向 PC 上游提交安卓代码时目标是 android/development，遵守 fork 网络和上次已合并 PR 的基线，不直接合并 PC 根目录到安卓工程。

## 实现与验证

- 七个 Gradle 模块；平台协议由手机直连，不新增业务服务器。保留既有账号、缓存、收藏、用户队列和前后端接口。
- UI 不持有 Session、Cookie、私有响应或签名音频 URL。debug 验收入口保持固定步骤及 DUMP 权限隔离，release 不包含入口和故障注入。
- 队列点击使用 jumpToQueueIndex 与 expectedRevision，不用 Replace 重建队列。拖动固定开始 revision，外部变化取消；后端仍验证账号代次、索引和 revision。
- 换源保持原队列身份，候选必须通过标题/完整歌手/时长/版本保守匹配；有限重试，网络错误不可视为焦点错误。先取得前台资格，再请求音频焦点；后台受限需明确重试。
- 歌词与译文跟随实际来源。中文主体默认关闭译文，外语主体默认开启，当前来源的手动选择优先；无译文和获取失败分开表达。短歌词整组居中，长歌词按实测行组跟随；浏览时不夺回滚动。
- 使用 JDK 17 / SDK 36 和 build.ps1 -Test -Lint。Windows 中文路径不要强制 Gradle daemon UTF-8。按改动运行必要检查，不用离线 fixture、模拟错误或旧包听音代替本包实机结果。
- 设备检查优先使用正式页面语义和固定 debug 入口，不操作其他应用；验证码、官方登录确认和真实听音由设备使用者完成。覆盖安装保留数据，未经明确要求不退出、换号、清数据或写平台收藏。
- 不覆盖他人改动。修改未跟踪文件前也要检查是否存在；快照与恢复先比较，禁止用 reset/clean 丢弃成果。子代理任务限定文件归属，主代理审查并验证交付。

## 工作区整洁

主工程只有一个开发入口。辅助工作树放 .local/worktrees/，创建或迁移前记录位置、分支、目的和保留原因，使用 git worktree 管理。

临时脚本放 .local/tmp/，修改前快照放 .local/checkpoints/，验收日志/截图/APK 放 .local/artifacts/，历史工作日志放 .local/archive/；这些不提交、不放工作区根目录。收尾清理本轮临时文件，保留必要证据与回退点，不递归清空 .local/。源码扫描/快照默认排除 .local/、.git/、.gradle/、.kotlin/ 和模块 build/。

## 提交与许可

保留 LICENSE、THIRD_PARTY_NOTICES.md、模块 assets 原始声明和 test-licenses。PC 图标只作品牌素材，不当作音乐封面。新增依赖核对许可，不改原许可。

提交、推送、PR、仓库创建和发布按用户明确授权执行；授权范围内连续完成，不反复征求同一许可。创建包含实质 Codex 协助的新提交时，保留真实作者/提交者，仅追加一次 `Co-authored-by: Codex <codex@openai.com>`，并用 git interpret-trailers --parse 核实。不改写既有上游历史，不自动合并 PR 或发布 Release。
