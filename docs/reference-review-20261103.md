# MeiloX 与 Glasmio 参考阅读

2026-11-03 保存官方源码归档并定点阅读；未构建参考工程，未引入其代码、资源或依赖。副本在 `C:\text\参考资料\MeiloX\source`、`C:\text\参考资料\Glasmio\source`；上层 README 保存来源和归档校验值。本快照不等同于 CC 开发 v0.2 时参考的版本。

| 项目 | 固定提交 | 主体许可 |
| --- | --- | --- |
| [MeiloX](https://github.com/NEORUAA/MeiloX) | `bc038c9e9a01f37484a13295b2c3a8c3e44d8804` | GPLv3，第三方资源另核对 |
| [Glasmio](https://github.com/NEORUAA/Glasmio) | `c4c1b68efc4b94c3f4dcf04e9d01fca178480d57` | Apache-2.0，部分 Apple 资源例外 |

## MeiloX

- [SystemLyricTimeline.kt](https://github.com/NEORUAA/MeiloX/blob/bc038c9e9a01f37484a13295b2c3a8c3e44d8804/app/src/main/java/com/ljyh/mei/playback/SystemLyricTimeline.kt) 将行级/逐字歌词变成统一时间轴，处理负时间制作信息、缺失结束时间、音节越界、翻译与对唱；旁边的测试明确覆盖边界。Musxi 可先定义独立时间轴，再接逐字解析和锁屏共享。
- `SystemLyricsBridge.kt`、`SuperLyricPublisher.kt` 在播放服务中监听切歌、seek、速度和暂停，只在行变化时发布，并隔离 Provider/SDK 异常。适合借鉴页面关闭也同步、共享故障不影响音频的生命周期；Lyricon、SuperLyricApi、ColorOS 仍需独立开关、许可与真机验证。
- `CachedLyric.kt`、`LyricManager.kt` 保存来源、解析类型和更新时间，先显示缓存再在线渐进更新。Musxi 要保留平台、账号、录音身份和手动绑定隔离，不能照抄单歌曲 ID 主键。该快照 TTML/YRC 原文提取存在空值后不落盘路径，格式支持不等于全部格式可靠持久化。
- 固定 Glasmio 提交、签名值通过 Secrets 输入、构建与手动发布分开值得参考。它以网易为核心，推荐/FM 与整套服务不适合作为 Musxi 三平台架构直接移植；Musxi 普通验证不需要正式签名。

## Glasmio

- `AdaptiveGlass.kt` 的局部亮度采样和双阈值滞回能避免滚动背景让前景色反复跳变；`SamplingPolicy.kt` 把效果纹理分辨率与最终文字/形状分开。适合研究浮层可读性，显式品牌色应保留。
- 材质分别表达折射、模糊和减少透明度，实色模式跳过无用采样。Musxi 已有三档与透明开关，下一步应测一致性、点击语义及耗电，而非重复新增设置。
- [interaction-performance.md](https://github.com/NEORUAA/Glasmio/blob/c4c1b68efc4b94c3f4dcf04e9d01fca178480d57/docs/interaction-performance.md) 区分窗口 FPS 与 Perfetto FrameTimeline 验收，要求包/进程/窗口归因、p95/p99 和 deadline miss。`DemoFpsCounter.kt` 被动观察，不为计数主动请求动画帧，适合补 Musxi 玻璃拖动与长列表测量。
- [构建工作流](https://github.com/NEORUAA/Glasmio/blob/c4c1b68efc4b94c3f4dcf04e9d01fca178480d57/.github/workflows/build.yml) 包含 JVM/Lint、设备包编译、模拟器和独立消费者验证。Musxi 本轮先落实无账号验证，模拟器不能替代真实音频和 OEM 后台验收。

该快照要求 API 33 起、JDK 21、SDK 37 与较新 AGP/Kotlin，玻璃模块还有 NDK/CMake。Musxi 为 API 26 起、JDK 17、SDK 36，整体替换会影响兼容范围。上游“1:1”是目标，不是本轮核实的跨设备性能保证。

## 采用顺序与许可

先做验证和文档治理，随后建立性能基线，再研究逐字时间轴/共享，最后按具体可读性问题评估材质自适应。上述均为后续候选，本轮未接入产品。

Musxi 的 GPL-3.0-or-later 可选择适用 GPLv3 条款后评估 MeiloX 代码复用；Glasmio 的 Apache-2.0 代码也要保留版权与第三方声明。SF Pro、Apple 壁纸、Tapback、部分图标不自动属于主体许可，不随代码复制。
