# 本地库整理与进度双版本

2026-11-17。用户明确授权推进后端第一项：解决边听边整理的版本与分页稳定性。不扩展目录任务生命周期、A／B、LRC、自定义音源或 USB；不改页面美术，不自动进行平台写入、PC 同步或发布。

## 设计与兼容

- 保留原 `revision` 全变化计数，现有 Long 参数的后端接口继续严格核对。新增独立 `organizationRevision` 与 `progressRevision`，不会因裸数字恰好相同而猜测调用者含义。
- `saveProgress` 的位置、完成状态与文件夹游标变化只推进 progressRevision 和 legacy revision；整理、元数据、来源可用性、封面、书签及目录写入推进 organizationRevision 和 legacy revision。备份恢复按实际写入分别推进，当前已有进度／游标优先，不改变原合并策略。
- SQLite schema 5 → 6 是添加 meta 键；两个新计数以旧 revision 初始化。旧版 1～4 先执行原迁移再初始化，损坏／缺失／负数计数拒绝读写；计数耗尽事务失败而非回绕，防止旧 token 重获有效性。
- 新 `LocalAudioVersionToken` 显式携带整理版本及可选进度版本。SQLite 在事务内检查再执行修改；批量、规则排序、重排、CUE 和恢复均有版本重载。普通命令用 organizationToken，LAST_PROGRESS 排序和恢复必须用完整双版本；旧接口保持兼容。

## 查询与状态发布

`LocalAudioQuery.expectedToken` 和 `LocalAudioSearchPage.token` 绑定筛选／排序／作用域，忽略 offset、limit；进度筛选与 LAST_PROGRESS 排序携带进度版本，其余只携带整理版本。换条件不能沿用旧 token，返回 INVALID_TARGET；版本过期返回 STALE_STATE。offset > 0 无 token 且无旧 expectedRevision 时拒绝，不能给旧续页换最新数字强行续接。

Repository 的计算完成、迟到发布及后续 publish 使用同一 token 判断；进度无关查询不因检查点失效，进度相关查询仍保留旧页并标记过期。显式选择 legacy expectedRevision 时仍检查全变化版本，并随结果 token 保留该约束。

来源检查与补图新增显式 token 重载，后续逐项来源代次／观察守卫保留。CUE 导入可用 expectedVersion 在复制前与提交时重验。备份预览带完整 expectedVersion，确认必须匹配该预览及当前双版本。

现有页面只改 controller 适配接线：读取 organizationRevision，转换为显式 token；名称输入、菜单、触感和浮卡未改。查询筛选正式新页面继续暂缓。

## 验证与边界

新增回归覆盖普通续页跨检查点、进度依赖过期、跨查询 token、无保护续页、旧接口严格性、未就绪发布、SQLite 计数持久化／迁移保全／耗尽回滚、真实整理冲突、进度排序双版本要求、备份恢复进度与游标冲突规则，以及 Repository 和现有 controller 接线。

最终统一验证 `.local/artifacts/verification/20261117T145842661Z-1848/` 通过：**3,029 项 JVM 测试，0 失败／错误／跳过；Lint 0 错误、152 警告**。主包及设备测试包编译、源码冻结通过，主 APK SHA-256 为 `c68ad21a99b0adc237ba8eb88834746f5a72f138be44f1ecc42b246c9016d719`。核心查询25项、SQLite版本专项7项均通过。汇总与首轮失败日志保留在 `.local/artifacts/local-versions-20261117/`。

首轮普通 `build.ps1 -Test` 因三个页面测试的旧单版本 fixture／断言失败；测试数据已显式区分真实组织变化与进度变化，并新增组织计数与 legacy 数值不同的接线回归，没有跳过断言。最终统一测试为上述修正后的完整结果。统一验证结束后的改动仅为文档结果更新，未再修改业务／测试源码。

本轮未安装手机、未触发实际数据库迁移或播放，也未提交推送／发布。自动化不替代当前包实机迁移、真实播放并行整理；不引用旧 DSF 包的听感作为新增验证。后续建议保留数据覆盖安装本轮包，先核对旧库和CUE／DSF条目保全，再正常播放跨多个检查点并整理／分页，确认普通操作稳定及真实冲突提示；不能再覆盖安装旧 schema5 工作树包。

后续实机结果见[2026-11-18 双版本与目录刷新验收](backend-local-versions-lifecycle-device-20261118.md)。整合第二部分后，手机原库由 schema 5 直接升至 7，播放跨自然保存点的分页和整理均通过。
