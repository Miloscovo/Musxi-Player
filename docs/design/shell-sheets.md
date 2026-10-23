# 应用外壳样张（2026-10-21）

内容区 + 迷你播放器 + 底部 tab 栏。样张生成自 `ShellSheetTest`（Robolectric + Roborazzi，JVM 渲染，未上真机）。

实现：`ui/player/MiniPlayer.kt`（迷你播放器 + 纯映射 `miniPlayerUi`）、`ui/shell/ShellBottomBar.kt`（底栏）、`ui/MainShell.kt`（三段式布局）、`ui/design/ShellSheet.kt`（样张）。

## 为什么有这份样张

之前 `MainShell` 里 `tab` 是个状态变量，但**全项目没有任何地方改它**，也没有底栏和迷你播放器。也就是说：F1 做完的账号页和搜索页，在真机上根本点不进去。单测全绿、样张全绿，但用户路径是断的。

## 定稿尺寸

| | 值 | 说明 |
| --- | --- | --- |
| 迷你播放器高度 | **64dp** | 比第一版多 0dp、比中间版 72dp 少 8dp |
| 卡片圆角 | **20dp**（`shapes.large`） | 64dp 高配 28dp 会成药丸，且与下方胶囊导航混淆 |
| 封面 | **48dp** | 64dp 卡片里给足面积，"封面是主角" |
| 播放按钮 | **36dp，容器色**（`primaryContainer`），非强调色实心 | 见下 |
| 进度 | **2.5dp 强调色圆环**，环绕播放按钮 | 不占额外高度 |
| 上一个/下一个 | 32dp 槽位、20dp 图标 | |
| 底栏高度 | **64dp** | 上 64 下 64，与播放器等高 |
| 底栏形态 | **略内收胶囊**（每侧内收 24dp，约 272dp，居中） | |
| 底栏图标 | 24dp，**选中填实 / 未选中描边** | 之前只有颜色差异，选中态太弱 |
| 底栏标签 | **12sp Medium**，选中 **SemiBold** | 之前 11sp Normal，整条看起来是空的 |
| 整个 chrome | 64 + 8 + 64 + 8 = **144dp**（原 152dp） | |

## 三次重构的原因（都是量出来的）

**第一版 64dp**：进度条用 2dp 线。实测 44dp 封面底边落在 y≈1411.5，进度条在 1412–1415 —— **零间距相切**。原因是内部 Row `fillMaxSize()` 按整张卡居中，而进度条又单独钉在离底 8dp 处，两套定位互不知道对方。用户的原话是"蓝色时间轴有问题"。

**第二版 72dp**：给进度条留了 12dp 独立条带，间距变成 10.5dp。拥挤解决了，但用户仍认为"圆角矩形部分有问题，需要继续重构，希望更紧凑"——问题变成：**为了一条 2dp 线付 8dp 高度不划算，而且那条线横穿卡片，本质是被当分割线在用。**

**第三版 64dp + 圆环**（定稿）：不再把横向进度硬塞进卡片底部，**让进度归属于播放控件**。卡片只承载一排内容，边缘完整安静。

同时试过但被否掉的两个：
- **贴底线性进度**（2dp 贴齐底边、被圆角裁切）：进度与容器轮廓混淆，左端更像底边着色而非进度起点。
- **28dp 圆角**：不是"太胖"，而是端头更接近药丸，与下方胶囊导航撞形态，削弱层级区分。

## 播放按钮为什么用容器色

视觉复审的 major 意见：强调色实心蓝钮**比封面更抢眼**，与"封面是主角"冲突。加上圆环后，同一位置出现"强调色圆面 + 强调色圆环"两重强调。

改成容器色（浅蓝底 + 深蓝图标）后，**强调色只留给进度环**，按钮仍是明确的圆形容器，主操作层级没丢。深色版同样成立。复审结论：`overallVerdict = pass`。

## 样张索引

| 状态 | 图 |
| --- | --- |
| 播放中（跨平台换源） | [shell_playing_mist_light.png](shell_playing_mist_light.png) |
| 被其他音频打断 | [shell_interrupted_mist_light.png](shell_interrupted_mist_light.png) |
| 缓冲中（按钮里是静止圆弧） | [shell_buffering_mist_light.png](shell_buffering_mist_light.png) |
| 没有在播（迷你播放器整个隐藏） | [shell_hidden_mist_light.png](shell_hidden_mist_light.png) |
| 播放中（深色） | [shell_playing_mist_dark.png](shell_playing_mist_dark.png) |
| 选中「搜索」tab | [shell_tab_search_mist_light.png](shell_tab_search_mist_light.png) |
| 定稿圆形进度 | [variant_d_ring_64.png](variant_d_ring_64.png) |
| 定稿容器色按钮 | [variant_g_tonal_64.png](variant_g_tonal_64.png) |
| 定稿深色 | [variant_g_tonal_64_dark.png](variant_g_tonal_64_dark.png) |

## 副标题规则：原因优先

迷你播放器只有一行副标题，规则是**先回答"为什么没在播"**：`被其他音频暂时打断` / `没有可用的音频输出` / `播放暂时被暂停` / `缓冲中` / `播放失败` / `已播完`。只有一切正常时才显示歌手；跨平台换源时追加 `· 网易云音源`。

第一版写成"歌手 · 原因"，在窄条里被省略成 `周杰伦 · 被其他音频暂…`，**把最重要的信息截掉了** —— 这是第二轮复审抓出来的，`MiniPlayerUiTest` 现在有 10 项锁住这个优先级。

平台色小点已移除：副标题只有两行字的条里，红点会成为第二种强调色，而"网易云音源"文字已经说清楚了。

## 主题样张也统一了

一份容易漏掉的地方：`ThemeSheet.kt` 里原本**自己画了一套迷你播放器和底栏**（旧尺寸、旧字重、`MoreHoriz` 占位图标）。外壳重构后它没跟着变，九张主题样张会一直展示旧界面。

现在 `ThemeSheet` 直接复用 `MiniPlayer` + `ShellBottomBar`，和真实外壳同一个组件。这是第二次踩同一个坑（第一次是账号页样张自己画卡片、留着已删除的"更多"菜单），所以规则固定下来：**任何样张都只能复用真实组件，不允许私有副本。**

## 两处被独立指出的可测量缺陷（已修）

两位前端模型各自提出，我量了一下，都成立：

| 问题 | 修前 | 修后 |
| --- | --- | --- |
| 深色下底栏与页面背景几乎同色 | `#121212` vs `#131313`，对比度 **1.01:1**，等于没有边界 | 底栏改为往**亮**走：`#202020`，形成 背景 `#121212` → 底栏 `#202020` → 迷你播放器 `#262626` 三级阶梯 |
| 浅色下进度环的未播放轨道看不见 | 轨道 `#CFCFCB` vs 卡片 `#E4E4E0`，**1.2:1**，环看起来像一段孤零零的弧 | 轨道透明度 10% → 20%，`#BCBCB8` |

教训：**深色模式下"降一级"不等于"变暗"**，背景已经很暗时只能往亮走。

## 复现命令

```powershell
# 在项目根目录执行
& "$env:JAVA_HOME\bin\java.exe" -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain `
  :app:testDebugUnitTest --tests "tech.musxi.player.design.ShellSheetTest" `
  "-Proborazzi.test.record=true" --console=plain
```

样张断言除背景占比外，还会检查**底部 14% 区域必须画了东西**——防止底栏再次被挤没。
