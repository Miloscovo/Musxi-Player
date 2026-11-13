# 后端需求单 · 2026-11-12（四）：每日推荐可以关闭（前端 → Codex）

> **状态：后端已交付，等待CC接线。** 用户希望「每日推荐 / 猜你喜欢」能在设置里关掉，默认开启。关掉后，曲库首页不再显示推荐卡片，也不再为推荐联网。需要一个应用级设置字段。另外，关闭时应清掉推荐缓存，具体见第 2 节。
>
> 完成后由 Codex 在末尾“交付说明”写清接口与验证边界。

读者：负责 core / app 后端层的 Codex。约定同前几批：不改 `app/src/main/kotlin/tech/musxi/player/ui/`（CC 负责）。行号以 2026-11-12 工作区为准，开工前复核。

## 现状（CC 已核对）

- `core/.../settings/AppSettings.kt` 中的设置项都是布尔或枚举字段，`AppSettingsRepository` 每项各有一个 `setXxx`（例如 `setMediaCardLyrics`），持久化在 `AppSettingsPersistenceFormat`。
- 推荐入口是 `MusxiApplication.recommendations`，提供 `open / refresh / cancel / play`。按合同，前端在进入平台分段或回到前台时调用 `open`；没有后台计时器。

## 1 · 设置字段（P1）

1. `AppSettings.recommendationsEnabled: Boolean = true`（名称可调整），`AppSettingsRepository.setRecommendationsEnabled(value)`。
2. 持久化：旧设置里没有这个字段时读成 `true`，不能判成损坏。
3. 范围：**一个总开关**，三个平台一起开关；用户没有要求按平台分别设置。

## 2 · 关闭后的后端行为（P1）

前端在开关关闭时不会调用 `open / refresh / play`。但为了防御，请让后端在关闭时做到以下几点：

1. `open / refresh` 不发请求，不读写缓存，状态保持空闲，也不发布错误。`play` 返回失败，错误码请写明。
2. 关闭时取消三个平台进行中的推荐请求，迟到的结果不更新状态、不写入缓存。
3. **关闭时清空三个平台的推荐缓存和内存快照。**理由是用户关掉功能，通常是不想再看到这些内容。重新打开后，前端调用 `open` 即可重新取回。仍然受 30 秒间隔限制，节流按现有规则处理。
4. 重新打开不需要后端做任何事：不自动联网，等前端调用 `open`。

如果 Codex 认为关闭时保留缓存更合理（例如为了少一次联网），可以提出来，写进交付说明，由用户决定。

## 3 · 不需要改的

推荐协议、缓存格式、节流规则、账号隔离、`play` 的 revision 语义，这些都不变。

## 前端这边的配合（CC 负责，供参考）

- 「我的」页新增一行开关「每日推荐」，副标题为「在曲库首页显示每日推荐和猜你喜欢」。位置在「播放」组或单独一组，待用户看设计稿后再定。
- 关闭后，曲库首页不显示推荐卡片，「继续播放」下面直接接「我喜欢」。打开时立即调用当前平台的 `open`。
- 后端交付前，前端先用本地默认值 `true` 把卡片接好，交付后改读设置字段。

## 验证建议

- 默认值为 true，旧设置文件兼容。
- 关闭后 `open / refresh` 不发请求、不写缓存，`play` 被拒绝。
- 关闭时进行中的请求被取消，迟到的结果被丢弃。
- 关闭时三个平台的缓存和快照都被清除。
- 重新打开后 `open` 能正常取回结果。

不需要安装手机。

## 交付说明

### 接口与语义

- core/accounts 均提供 `setRecommendationsEnabled(Boolean): AppSettings`，字段 `AppSettings.recommendationsEnabled` 默认 `true`。存储键 `settings.recommendations-enabled`；缺键、畸形值、旧设置兼容为 `true`，其他字段保留。设置失败抛异常，不提前改变状态或取消推荐。通用 `update { copy(recommendationsEnabled = ...) }` 也会同步通知；快速 off/on 不依赖 Flow 合并后的值。
- `MusxiApplication.recommendations` 的 `open/refresh/cancel/play/state` 调用形状不变。关闭后 `open/refresh` 无操作，无推荐请求或推荐缓存读写，三平台状态空闲、快照空、`error == null`。`play` 返回 `Result.failure(LibraryPageFailure("recommendations_disabled"))`，优先于账号/revision 校验；不提交新队列。关闭前已接受的队列和当前歌曲继续播放。
- 关闭会立即增加三平台请求代次与快照 revision，迟到成功/失败与旧排队写入不能发布或回填。点播校验与队列提交调用共用关闭的同步边界，避免校验通过后被设置线程插入关闭、再交付一次新队列。逻辑取消不会承诺中断已发出的底层 HTTP。
- 用户授权后，后端决定只清三平台推荐列表内存/磁盘元数据，保留共享封面、普通曲库、歌词、本地文件、壁纸、账号和设置。磁盘清理排入现有串行缓存队列，命令返回表示已失效并排入清理；新开启的读取在清理后执行。逐平台清理，一处异常不阻断另外两处，失败由 `storageError` 表示；下一次关闭或关闭状态冷启动再次清理。UI 未初始化推荐 facade 时也能清理。
- 开启不自动联网，前端显示当前平台卡片后调用 `open` 即可。本进程既有30秒限流记录保留；开关不绕过限流。推荐协议、缓存v1、账号隔离及点播revision语义保留。
- CC 接线：观察 `app.settings` 显示/隐藏卡片；在 IO dispatcher 调用 `app.settingsRepository.setRecommendationsEnabled(value)` 并处理保存异常，开启成功后在主线程调用当前平台 `open`。完整合同见[应用级总开关](frontend-backend-contract-recommendations.md#应用级总开关)。

### 验证范围

最终专项 **84项通过，0失败/错误/跳过**（core设置/推荐、accounts设置/缓存及app推荐facade），证据 `.local/artifacts/recommendations-toggle-20261112/targeted-atomic/`。覆盖默认/旧数据、false重建、保存失败状态不变、快速off/on同步通知、三平台已载入/进行中清理、旧排队read/write和迟到成功/失败、真实三平台缓存文件冷启动清理、限流保留、清理异常和点播拒绝/已接受队列保留。初轮81项通过后补loaded及queued-read回归为83项；统一初轮2,603项/Lint0错误135警告通过后，再加点播提交与关闭的原子性保护及并发回归，最终专项84项已重跑。初轮统一证据 `.local/artifacts/verification/20261112T123622065Z-28848/`，该结果不替代最终源码验证。

最终主目录官方 `scripts/verify.ps1` **2,604项通过，0失败/错误/跳过，Lint0错误/135警告**；debug主包/设备测试包编译与全树源码一致性检查通过。11份本轮后端源码/测试与验证清单及交付时磁盘散列一致。最终证据 `.local/artifacts/verification/20261112T125749866Z-12740/`，索引 `.local/artifacts/recommendations-toggle-20261112/final-summary.json`，回填后另查6份文档链接/散列，不将文档回填误记成全树未变。

对应debug APK保留在 `.local/artifacts/recommendations-toggle-20261112/app-debug.apk`，SHA-256 `ce356758148c7bfffb51e5df604048880882f63f63880485e8b2f70ebe7786ea`。精确修改前快照 `.local/checkpoints/recommendations-toggle-20261112/`；本轮没有创建工作树，没有改 `ui/` 或CC测试，没有安装手机、请求真实平台推荐、提交或推送。只读审查子任务未返回结论后已停止，不报告独立审查通过；最终检查由主代理完成。页面开关、真实账号网络及同包实机由CC接线后验收。
