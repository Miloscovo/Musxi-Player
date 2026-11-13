# QQ QRC 逐字后端接续

状态：2026-11-07。沿用已有酷狗字词模型和缓存，接入 QQ 在线 QRC；产品前端仍按行显示。验证与手机结果在本文末尾登记，不能用历史酷狗包的结果代替。

## 来源与协议核对

固定来源为 [QQMusicApi 3fc57f02](https://github.com/L-1124/QQMusicApi/tree/3fc57f02e4eddb68a069bba4e0e12a7893f2a814)：`modules/lyric.py`、`models/lyric.py`、`algorithms/__init__.py` 和 `algorithms/tripledes.py`。请求沿用 `music.musichallSong.PlayLyricInfo/GetPlayLyricInfo`，可选 QRC 使用 `crypt=1/qrc=1`，从 `lyric` 字段取得十六进制密文，经自定义 TripleDES、zlib 与 UTF-8 解码取得 QRC/XML。原有 `crypt=0/qrc=0/trans=1` 请求保持独立。

`tripledes.py` 明确标注 Copyright (c) 2024 沉默の金、GPL-3.0-only，并引用 WXRIW/QQMusicDecoder 固定提交 `0837a3a1281e58f6db3e3e1dcb1d5441fb0ac268` 的 `DESHelper.cs`/`Decrypter.cs`。本轮移植解密核心，保留该文件的声明与现有 GPLv3 全文；未把文件许可改为项目的 GPL-3.0-or-later，未增加运行时依赖。MeiloX 的 `QRCUtils.kt`/`QRCParser.kt` 只作交叉参考。

用匿名官方公开 songId=100 响应核对：QRC 与普通 LRC 的76行文字全部一致，行时间差最多9ms；字时间为曲目绝对毫秒。实际有字词结束晚于行结束的情况，因此保留独立字词时钟，不按行时长裁剪，也不猜绝对/相对时间。此公开样本仅作协议交叉验证，不等同于手机/音频验收；歌词正文不进入源码。

## 接入与降级

- `QrcCipher` 只负责有界、完整8字节块解密，不调用标准 JCE DESede 代替该变体。
- `QrcLyricsDecoder` 限制密文512KiB、解压文本512KiB、10000源行、每行4096段、全文100000段和24小时字词时钟。拒绝异常UTF-8、破损压缩流、非零尾随数据、未覆盖文字、倒退词时和断开的UTF-16代理对。
- XML只读取唯一的字面 `LyricContent` 属性并解码有限实体，保留其中换行；拒绝声明/任意实体，不启动XML解析器或外部资源解析。
- QRC行与原LRC复用完整行数、规范化文字、signed行时钟和100ms容差校验。词范围必须属于精确相同的展示文字，保留平台词时，不用行时钟代替词时。
- `LyricsRepository` 的渐进加载、显式刷新和匹配预览都接 QRC，平台文档不进入UI或磁盘。账号代次、来源、原文版本和绑定偏移守卫保持有效。
- QRC失败只影响字词；译文成功仍可更新。仅在原文文字/行时间完全相同时保留已有字词，明确无QRC时允许清除。译文损坏也不丢有效新字词。
- 沿用歌词缓存格式2和格式1兼容、owner隔离及过期策略；不清除账号、原缓存、队列或手动绑定。

## 验证范围

新增独立SDK生成的自制密文golden、容器/实体/Unicode/时序/上限、请求参数和网络降级测试，以及原文对齐、不同版本拒绝、独立字词失败保留和译文失败仍带字词测试。

新增只读 `QqWordLyricsDeviceTest`：等待账号自动恢复及播放器连接稳定后取身份/队列基线，读取两首实际歌词，核对预览与刷新、词范围和临时生产缓存冷读。只运行本专项，不播放音频、不修改账号/收藏/队列/绑定，不操作分屏游戏。设备结论以实际执行记录为准。

网易云 YRC、逐字视觉、逐帧动画及真人听音对齐仍待后续；播放器500ms tick和接口使用方式见 [统一字词模型](lyrics-word-timing-20261107.md) 与 [前端合同](frontend-backend-contract-lyrics-translation.md)。

## 本轮最终验证与手机结果

最终统一入口 `.local/artifacts/verification/20261107T080914927Z-16340/summary.json` **passed**：1768项JVM测试，0失败/错误/跳过；六Android模块Lint 0错误/118警告；debug主包与设备测试包编译通过，源码冻结核对通过。相对酷狗阶段新增21项相关JVM测试。主APK SHA-256为 `29c05ede7bd27aeace40332e799559a9f7620dffcacb95ce21b51de1c353294f`，设备测试APK为 `8d4f172e9d80283520ed093825d4c97c178d918b929fca6d44fc11a2c7046b9d`；均已覆盖安装并核对设备包散列。

首轮完整验证曾在回调标签漏改处编译失败，修正后中间包通过1767项。手机首轮与诊断轮均发现日文QRC的 `[kana:...]` 注音元数据未跳过，返回UNSUPPORTED而安全保留行级原文，不能记为通过。诊断只导出固定参数、长度、状态和行/词数量，不导出原始响应或会话。修复为跳过该明确元数据并新增回归，随后才得到上述最终1768项完整验证及新包手机成功结果。此前失败轮次、诊断测试包和中间主包均保留，不覆盖历史证据。

在 PJF110 / Android16 上只运行 `QqWordLyricsDeviceTest`，最终 **OK (1 test)**：

| 实际QQ样本 | 原文行 | 带字词行 | 字词段 | 带译文行 | 首次补入 / 预览 / 刷新 / 缓存冷读 |
| --- | --- | --- | --- | --- | --- |
| Lemon | 57 | 57 | 546 | 54 | 全部通过 |
| Hello | 47 | 47 | 334 | 41 | 全部通过 |

两首QRC与原LRC逐行文字精确一致，行时钟差最多9ms，未放宽100ms守卫；保留实际词时。临时缓存由生产 `FileLyricsCacheStore` 写入，以新实例冷读后整份相等，所有词范围有效。账号完整身份与活动队列前后相等；实时读取的两份既有播放日志SHA-256不变，QQ日志原先不存在且仍不存在。UUID临时缓存已清理，没有恢复旧日志、写收藏或绑定、播放音频、操作分屏游戏。

最终证据 `.local/artifacts/qq-qrc-device-20261107/final-summary.json`，同目录README记录包、失败轮次、固定报告、日志散列及保留用途。完成后只补文档和本地证据，产品/测试源码未再修改；这些结果不覆盖正式包、其他设备/账号、所有QRC方言、逐字听音对齐或前端渲染。
