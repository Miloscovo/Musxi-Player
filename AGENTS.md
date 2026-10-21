# Musxi Android 开发约定

- 当前安卓工作根目录为 `C:\text\Musxi-Player安卓`。旧目录 `C:\text\Musxi-Player\Musxi-Player\android\player` 是迁移前副本，不同步开发两份。
- 当前 Git 分支为 `android/development`，origin 为 `qwt1011/Musxi-Player`；本分支根目录仅承载安卓工程。PC 上游同步在 fork 的 `main` 进行，两分支目录结构不同，安卓按需移植相关变更，不直接合并整套 PC 根目录。
- 七个 Gradle 模块：app、core、accounts、playback、platform-kugou、platform-qq、platform-netease。后端基础与临时 DeveloperActivity 已实现，正式前端交由 cc 接线。
- 开工先阅读 README.md、安卓端适配规划.md 的最新章节和 docs/frontend-contract.md；历史 `android/player/` 路径现对应本目录。保留既有账号、曲库和播放接口，避免为视觉需求改协议或队列语义。
- 2026-10-21 当前进度与接续顺序见 [docs/development-handoff.md](docs/development-handoff.md)：前端 F1～F5；后端建议先 B1 播放连接恢复，再 B2 收藏列表联动、B3 请求耗时测量，之后 B4 换源匹配。均需按文档区分已有能力、待办和历史验收；后续交付更新该文与规划，Git/正式包收尾见其第 6 节。
- 2026-10-20 P0 新接口见 docs/frontend-backend-contract-v2.md：公开图片封面、PlaybackState 双平台与专辑、app.searchBrowser、queuePlayback.playSong。缓存写 v2/读 v1，保留旧目录与账号隔离。搜索是单平台当前页；取消拒绝旧结果，不承诺立即中断正在执行的 HTTP。
- 2026-10-20 P1 见 docs/frontend-backend-contract-p1.md：原生 OFF/ONE/ALL+shuffle，原队列身份不变；每平台本人队列/位置/模式及最多100条最近播放存 noBackup journal（8MiB）。冷启动只展示快照，显式恢复保持暂停、不 prepare，用户播放才重取流。歌词跟随实际 PlaybackState.sourceKey，标准LRC/纯文本、内存8份有界缓存，无QRC/KRC解密。账号清理与journal IO共用串行执行器，禁止保存音频URL/票据。真机结果与限制见 docs/p1-data-acceptance.md。
- 平台请求由手机本地直连，不新增业务服务器。不记录或展示凭证、Cookie、原始私有响应、签名音频 URL。保留三平台账号、缓存及用户歌单，不自行退出、换号或改平台歌单。
- P1 酷狗歌词兼容已修复：官方 `/v1/search` 在电脑/手机返回空体，改用同一 HTTPS 主机的 `/search`，保留实际hash+album_audio_id及签名，下载仍为fmt=lrc。三首本机歌词63/46/53行，source/seek同步通过；空体仍为ERROR/protocol，不记为无歌词。详见 docs/p1-data-acceptance.md 补验。
- 2026-10-20 P2 见 docs/frontend-backend-contract-p2.md / docs/p2-acceptance.md：app.favorites 按平台显式读取完整喜欢集合，单曲写后再读核实、不乐观更新、不自动重试；QQ仅201目录的单曲写使用Android公参，其他读取保留WEB；酷狗添加路径自带网关路由、不加x-router，取消使用实际fileid。app.playback.queueState提供原队列与revision，编辑携带revision；移动/插入/删其他保留当前票据/已选来源，删当前暂停从0，清空/删唯一项同时清journal并保留recent。真实收藏测试需明确授权并恢复完整集合；本轮已获授权的三平台各一首已恢复核实。
- 跨平台换源保留原歌单 mediaId/索引/顺序；候选就绪后 5 秒尝试首个同名项，允许手选，最多 3 个替代来源；同名不等于同录音。服务拥有计时、取流与恢复，前端只订阅状态并提交命令。
- 设备验收使用固定 debug 入口，不用坐标点按。需要验证码、官方登录确认或真实听音时请用户亲自完成。不把模拟错误、状态增长、旧包听音或未执行的检查当作本轮实际证据。
- 构建使用 JDK 17 / SDK 36，运行 `./build.ps1 -Test -Lint`。中文路径使用 android.overridePathCheck=true；Gradle daemon 保持系统默认编码以正确写入 JDK 17 Windows 参数文件，JavaCompile 与 Test 内容单独固定 UTF-8，勿恢复原强制 daemon UTF-8 导致测试 classpath 误读。以实际构建为准。
- 保留 LICENSE、THIRD_PARTY_NOTICES.md、模块 assets 中原始声明/许可与 test-licenses。新增生产依赖须核对许可，保留必要声明，不改项目许可。
- 不覆盖他人改动；完成相应验证并更新本目录规划记录。未经授权不提交、推送、发布或发送外部消息。
- 用户已长期授权按需自主调用子代理；任务边界清楚、文件所有权不重叠，主代理负责整合与验证。指定 DS 时使用 deepseek_flash 对应强度角色、独立上下文并传递完整子任务；不可用须报告真实错误，不静默换模型。按进展证据监督，避免空转重复调用。
- 若获授权创建包含实质 Codex 协助的提交，按项目所有者约定只追加一次 `Co-authored-by: Codex <codex@openai.com>`，保留真实作者与提交者身份。提交前通过 `git interpret-trailers --parse` 核对该 trailer 恰好一次，提交后检查作者、提交者与消息；不改写既有历史。

- 2026-10-21 本次后端增量 PR 记录见 docs/backend-pr-20261021.md；用户已授权新 PR 的提交/推送。隔离分支 android/backend-p0-p2 基于上游 android/development，排除 cc UI、design 截图测试及其 Gradle 插件/依赖；原工作区保留前端。B1～B4 仍为后续待办，未授权自动合并或发布。
