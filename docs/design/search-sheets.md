# 搜索页样张与实现（2026-10-21）

搜索页。样张生成自 `SearchSheetTest`（Robolectric + Roborazzi，JVM 渲染，未上真机），九张覆盖九个状态。

实现：`ui/search/SearchViewModel.kt`（累积与状态折叠）、`ui/search/SearchScreen.kt`（页面）、`ui/design/SearchSheet.kt`（样张，直接复用 `SearchScreen`）。

## 样张索引

| 状态 | 图 |
| --- | --- |
| 未搜索（初始） | [search_idle_mist_light.png](search_idle_mist_light.png) |
| 有结果 + 可加载更多 | [search_results_mist_light.png](search_results_mist_light.png) |
| 有结果（深色） | [search_results_mist_dark.png](search_results_mist_dark.png) |
| 成功但无结果 | [search_empty_mist_light.png](search_empty_mist_light.png) |
| 首屏加载中 | [search_loading_mist_light.png](search_loading_mist_light.png) |
| 失败 | [search_error_mist_light.png](search_error_mist_light.png) |
| 列表底部加载下一页 | [search_loading_more_mist_light.png](search_loading_more_mist_light.png) |
| 已全部加载完 | [search_all_loaded_mist_light.png](search_all_loaded_mist_light.png) |
| 超长歌名/多歌手/长专辑/90 分钟时长 | [search_long_text_mist_light.png](search_long_text_mist_light.png) |

## 设计约定

- **搜索是单平台**：三个 chip（网易云 / QQ音乐 / 酷狗）单选，结果不跨平台合并，也不做"全部"。
- **强调色只出现在四处**：输入框聚焦、选中 chip、列表底部「加载更多」、失败态「重试」按钮。选中 chip 用的是中性填充（`FilterChip` 默认），不强行刷强调色。
- **五个状态各自的轮廓必须不同**：
  - 未搜索 —— 一行中性提示，并写明当前平台（"搜索网易云"），因为平台已经默认选中，提示不应再要求用户"选一个平台"。
  - 有结果 —— 列表本身即轮廓。
  - 无结果 —— **两层结构**：`未找到相关歌曲`（正文色 titleSmall）+ `试试其他关键词，或切换平台`（次要色 bodySmall）。**不是错误**：没有红色、没有警告图标、没有重试按钮（重试同一个词毫无意义）。
  - 加载中 —— 静止圆弧 + 文字。
  - 失败 —— 一句说明 + 唯一主操作「重试」。
- **行结构**：44dp 封面 + 歌名 + 「歌手 · 专辑」+ 右侧时长，两行文字都单行省略，时长永远保留自己的空间。

## 数据流

`SearchBrowser` 每次投递都**替换**自己的 page，不做累积，所以累积在 `SearchViewModel` 里，形状与 `LibraryViewModel` 一致：

- 累积键是 `(platform, keywords)`；键变了就清空重来，不同查询的页绝不能混在一起。
- `offset == 0` 替换列表，否则追加。
- 折叠逻辑抽成纯函数 `reduce()`，测试不需要 dispatcher 或 Android 运行时（`SearchReducerTest` 10 项）。
- 账号门禁、陈旧结果拒绝、`nextOffset` 校验都在 `SearchBrowser` 里，页面不重复判断。

## 本轮修掉的两个真 bug

1. **`formatDuration` 不支持小时**。90 分钟的曲子显示成 `90:12`，看起来像 1 分 48 秒的曲子。已改为超过一小时加小时段：`1:30:12`。加了 `FormatDurationTest`（4 项）锁住边界，包括负数钳零。
2. **无限动画**：搜索页的加载指示从一开始就用静止圆弧（`ui/components/ProgressArc.kt`），没有再引入 `CircularProgressIndicator`。这是账号页踩过的坑，见 [account-sheets.md](account-sheets.md)。

## 视觉复审记录（三轮，`penguinapi / gpt-6.1-sol`）

**第一轮**：

| 问题 | 处理 |
| --- | --- |
| 初始态提示"选一个平台开始"与平台已默认选中矛盾 | 改为"输入歌名、歌手或专辑，搜索网易云"，提示里带当前平台名 |
| 无结果与未搜索都是一行灰字，快速扫视难区分 | 无结果改为两层结构（标题 + 建议），仍是中性色 |
| 失败文案"暂时搜不到结果"与空态语义混淆 | 改为"网络连接异常，请稍后重试" |
| 输入框没清除入口 | 加右侧清除图标，次要色 |
| 证据缺口：尾部加载态/全部加载完/长文本未覆盖 | 补三张样张 |
| 有结果、加载、失败、深色四张 | 直接 pass |

**第二轮**：前 4 条确认修好；第 5 条判 `partial` —— 补的三张都在，但发现**长时长显示成 `90:12`**，暴露了 `formatDuration` 的真 bug。

**第三轮**（定点只审长文本那张）：`pass`。确认时长已变为 `1:30:12`、完整可见、与歌名留有空隙，封面未变形。

## 复现命令

```powershell
# 在项目根目录执行
& "$env:JAVA_HOME\bin\java.exe" -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain `
  :app:testDebugUnitTest --tests "tech.musxi.player.design.SearchSheetTest" `
  "-Proborazzi.test.record=true" --console=plain
```
