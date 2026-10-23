# 账号页样张与实现（2026-10-21）

「我的」账号页。样张生成自 `AccountSheetTest`（Robolectric + Roborazzi，JVM 渲染，未上真机），四张：雾蓝浅色、雾蓝深色、松石青浅色、暖琥珀浅色。

相关实现：`ui/account/AccountViewModel.kt`（`AccountState` → 展示态映射）、`ui/account/AccountRowCard.kt`（共用卡片与状态胶囊）、`ui/account/AccountScreen.kt`（正式页面）、`ui/design/AccountSheet.kt`（样张，复用同一套卡片与映射）。

## 样张索引

| 强调色 | 亮度 | 图 |
| --- | --- | --- |
| 雾蓝 MIST | 浅色 | [account_mist_light.png](account_mist_light.png) |
| 雾蓝 MIST | 深色 | [account_mist_dark.png](account_mist_dark.png) |
| 松石青 TEAL | 浅色 | [account_teal_light.png](account_teal_light.png) |
| 暖琥珀 AMBER | 浅色 | [account_amber_light.png](account_amber_light.png) |

样张用 `toRowUi()` 把合成的 `AccountState` 喂给同一张卡片，覆盖 `AccountPhase` 八个分支里的七个（`PREPARING` 与 `WAITING_CONFIRMATION` 展示完全相同，只画后者）。

## 设计约定（本页收紧后的用色规则）

- **平台色只做一个小点**：平台徽标是中性色圆底 + 中性色缩写，平台身份只由右下角 8dp 降饱和小点表达，带一圈卡片色描边与圆底分开。
- **强调色只给该行唯一的主操作**：`连接`、`重新登录` 是强调色实心按钮，「已连接」胶囊用强调色。管理／取消／重试都是中性色文字按钮，`取消` 不与 `连接` 争夺注意力。
- **一行一件事**：已知账号名就显示账号名，否则只显示一句说明；说明不再重复胶囊里的字，且统一单行，长文案会被换行成孤字孤词。
- **状态字形不靠颜色区分**：已连接＝勾，验证中＝静止三刻钟圆弧，等待确认＝时钟轮廓，未连接＝浅色空心点，网络异常／已过期＝实心点（配 `error` 色）。

## 两个接线陷阱（已在实现里避开）

1. **「重试」不能接 `checkNow`**。`AccountRepository.checkNow(platform)` 的实现是 `pending[platform]?.let { poll(...) }`，而账号走到 `RETRYABLE_ERROR` 时 `fail()` 已经把 `pending` 移除，所以接 `checkNow` 会静默什么都不做。实现里改接 `restore(platform)`。
2. **`PREPARING` 和 `VERIFYING` 都属于 `activePhases`**，此时用户唯一能做的事是取消。所以 `PREPARING` 与 `WAITING_CONFIRMATION` 一样，右侧直接给「取消」，不藏进菜单。

## 一个必须记住的坑：无限动画会挂死截图测试

第一版给「验证中」用了 `CircularProgressIndicator`。它是**无限动画**，永远到不了 idle 帧，Roborazzi 的 `captureRoboImage` 会一直等composition 静止 —— 表现为 Gradle 测试 worker 满负荷空转、永不结束。

它的连带症状很误导人：那个卡死的 worker 一直持有 `app/build/test-results/testDebugUnitTest/binary/output.bin`，于是**下一次**构建报 `Unable to delete directory ... binary`。很容易误判成「Gradle 文件锁／环境问题」，实际根因是上上次的测试挂住了。

> 排查时如果看到「测试 worker 持续 100% CPU 且长时间不结束」，先怀疑测试代码里有无限动画，而不是去杀进程。

修法：把不确定进度的转圈换成**静止图形**。现在「验证中」是 `Canvas` 画的三刻钟圆弧，无动画、可确定性渲染，也更符合「安静胶囊」的定位。

## 视觉复审记录（三轮，`penguinapi / gpt-6.1-sol`）

**第一轮**（对着 16:11 的旧样张审的，那版还没有字形区分）：

| 问题 | 处理 |
| --- | --- |
| 「验证中」那行右侧挂着竖向三点菜单，与「无操作」不符 | 移除；`AccountAction.NONE` 不渲染任何操作 |
| 网络异常行同时显示账号名和长说明，末尾「状态」单独换行 | 说明缩短为「暂时无法验证」，单行显示 |
| 已过期行说明「登录已过期」重复胶囊文字 | 改为「重新登录以恢复同步」 |
| 等待确认用双圈点、未连接用浅单点，都是小圆点类，扫读难区分 | 等待确认改为时钟轮廓 |
| 平台标识是大面积彩色圆底 + 彩色文字，超出「平台色只做小圆点」 | 改中性圆底 + 右下角 8dp 平台色小点 |
| 管理／取消／重试都用主题强调色，与「强调色只给主操作」冲突 | 三者改中性色文字按钮 |
| 页脚末行孤立成「平台。」 | 拆成两句独立文本行 |
| （未处理）琥珀色下「已连接」随主题变棕橙，易误读为警告 | 复审第二轮确认：勾 + 明确文字「已连接」已足够补偿，不必强引入跨主题绿色。若将来隐藏文字或缩成纯色点，需重新评估 |
| （未处理）深色下中性胶囊与卡片底色接近 | 复审第二轮确认：文字可读，不构成阅读阻断；弱的是小点图形，已由形状区分解决 |

**第二轮**：前六项确认修好，无回归；页脚仅改了文案、实际仍折行成孤行，判 `partial`。改为两句各自独立的 `Text` 后重录。

**第三轮**（定点只审页脚）：`pass`。两行完整、行距正常、底部留白合理，页脚之外无新问题。

## 复现命令

```powershell
# 在项目根目录执行
& "$env:JAVA_HOME\bin\java.exe" -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain `
  :app:testDebugUnitTest --tests "tech.musxi.player.design.AccountSheetTest" `
  "-Proborazzi.test.record=true" --console=plain
```

录制与验证双模式。不加 `-Proborazzi.test.record=true` 时跑的是像素断言（背景占比 > 30%、强调色渲染过、卡片真的存在）。
