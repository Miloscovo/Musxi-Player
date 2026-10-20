# Musxi Android 开发约定

- 当前安卓工作根目录为 `C:\text\Musxi-Player安卓`。旧目录 `C:\text\Musxi-Player\Musxi-Player\android\player` 是迁移前副本，不同步开发两份。
- 当前 Git 分支为 `android/development`，origin 为 `qwt1011/Musxi-Player`；本分支根目录仅承载安卓工程。PC 上游同步在 fork 的 `main` 进行，两分支目录结构不同，安卓按需移植相关变更，不直接合并整套 PC 根目录。
- 七个 Gradle 模块：app、core、accounts、playback、platform-kugou、platform-qq、platform-netease。后端基础与临时 DeveloperActivity 已实现，正式前端交由 cc 接线。
- 开工先阅读 README.md、安卓端适配规划.md 的最新章节和 docs/frontend-contract.md；历史 `android/player/` 路径现对应本目录。保留既有账号、曲库和播放接口，避免为视觉需求改协议或队列语义。
- 平台请求由手机本地直连，不新增业务服务器。不记录或展示凭证、Cookie、原始私有响应、签名音频 URL。保留三平台账号、缓存及用户歌单，不自行退出、换号或改平台歌单。
- 跨平台换源保留原歌单 mediaId/索引/顺序；候选就绪后 5 秒尝试首个同名项，允许手选，最多 3 个替代来源；同名不等于同录音。服务拥有计时、取流与恢复，前端只订阅状态并提交命令。
- 设备验收使用固定 debug 入口，不用坐标点按。需要验证码、官方登录确认或真实听音时请用户亲自完成。不把模拟错误、状态增长、旧包听音或未执行的检查当作本轮实际证据。
- 构建使用 JDK 17 / SDK 36，运行 `./build.ps1 -Test -Lint`。中文路径使用 android.overridePathCheck=true；Gradle daemon 保持系统默认编码以正确写入 JDK 17 Windows 参数文件，JavaCompile 与 Test 内容单独固定 UTF-8，勿恢复原强制 daemon UTF-8 导致测试 classpath 误读。以实际构建为准。
- 保留 LICENSE、THIRD_PARTY_NOTICES.md、模块 assets 中原始声明/许可与 test-licenses。新增生产依赖须核对许可，保留必要声明，不改项目许可。
- 不覆盖他人改动；完成相应验证并更新本目录规划记录。未经授权不提交、推送、发布或发送外部消息。
- 用户已长期授权按需自主调用子代理；任务边界清楚、文件所有权不重叠，主代理负责整合与验证。指定 DS 时使用 deepseek_flash 对应强度角色、独立上下文并传递完整子任务；不可用须报告真实错误，不静默换模型。按进展证据监督，避免空转重复调用。
- 若获授权创建包含实质 Codex 协助的提交，按项目所有者约定只追加一次 `Co-authored-by: Codex <codex@openai.com>`，保留真实作者与提交者身份。提交前通过 `git interpret-trailers --parse` 核对该 trailer 恰好一次，提交后检查作者、提交者与消息；不改写既有历史。
