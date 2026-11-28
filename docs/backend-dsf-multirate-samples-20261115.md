# DSF 第四批：蓝牙听感确认与 DSD64／128 工程样本

2026-11-15，Asia/Shanghai。用户反馈[上一轮](backend-dsf-listening-20261115.md)使用蓝牙耳机，持续播放、暂停恢复及定位后主观“听起来还是非常流畅的”；同时说明没有其他倍率的文件，授权自行准备样本。

## 当前交付

后续用户授权继续手机验收：[DSD64／128短样实机与暂停读回诊断](backend-dsf-multirate-device-20261115.md)已完成两个倍率的最终同包完整播放与控制复验，用户整体反馈“应该”没有杂音、爆音、非预定断续或声道异常，按有保留的主观反馈记录。以下“未手机播放／未构建”等描述保留为本次制样阶段的范围，不代表后续实机尚未执行。

DSD256 本次窗口已补用户主观听感确认。自行准备两份原创合成的 45 秒双声道 DSF，具有实际对应倍率的一位音频载荷，均经独立 FFmpeg 和现有 Musxi 内核完整解码、信号核对。**它们是工程测试音频，不是原生 DSD 音乐录音**；DSD64／128 手机播放、真人听音以及原生录音兼容性仍待验。

没有修改应用源码、前端或正式 DSF 开关，没有重新构建、安装或向手机写入／播放新文件，不提交、推送或发布。DSD256 的蓝牙结果也不代表 USB／原生 DSD 输出能力。

## 为什么自行生成

查找了公开厂商／唱片公司来源：

- [2L free Test Bench](https://www.2l.no/hires/) 明确公告目前不可用。
- [OPPO／David Elias](https://www.oppodigital.com/hra/dsd-by-davidelias.aspx) 页面有原生 DSD64 试听链接，但其 `download.oppodigital.com` 下载域名在本机无法解析，没有取得音频。
- [Blue Coast 免费测试](https://bluecoastmusic.com/free-downloads) 通过购物车／结算提供免费文件，没有直接匿名 DSF 下载。
- [Sound Liaison](https://soundliaison.com/search?q=free&type=product) 查到免费格式比较产品，[NativeDSD](https://www.nativedsd.com/) 查到 starter pack，但对应产品页返回 HTTP 429。未绕过访问限制、登录或代为结算。

并行只读检索的 DeepSeek 子代理返回路由分组权限 403，未产出研究结果；主代理完成以上调查与制样。精确来源和访问结果保存在本地 `source-search.json`。没有下载公开音乐或第三方程序。

## 样本与内容

文件保存在项目 `.local/artifacts/dsf-multirate-samples-20261115/samples/`，不纳入 Git。

| 文件 | DSF 源格式 | 时长／声道 | 字节／SHA-256 |
| --- | --- | --- | --- |
| `musxi-synthetic-dsd64-stereo-45s.dsf` | 2,822,400 Hz／1bit，LSB-first | 45 秒／2 | 31,752,472；`f3dc1f6813402e4d1a382cafa82ab1446782bbe7a6bf1ab03c2629a95d59fa5b` |
| `musxi-synthetic-dsd128-stereo-45s.dsf` | 5,644,800 Hz／1bit，LSB-first | 45 秒／2 | 63,504,665；`e7f1261e7cdcdfdc9d87c988c3b9c8e03029cb4e7b8fd73d6bc9784f47bd997b` |

参考波形为自行编写的 176.4kHz PCM：0～1 秒静音，1～4 秒左声道 440Hz，4～7 秒右声道 660Hz，7～10 秒双声道提示，10～44 秒原创和弦／琶音，最后约 1 秒收尾。没有朋友音乐或商业歌曲内容，没有语音提示。参考峰值约 0.192，淡入淡出。

通过线性插值和二阶一位 sigma-delta 调制分别生成真实速率的位流，再按 DSF 4096 字节／声道分块封装，声明准确样本数、填充和文件大小；不是改头或把 DSD256 位流冒充其他倍率。固定随机种子用于很小的调制抖动。两个文件均带小型 ID3v2.3 文本标签，标题明确包含 `SYNTHETIC`。它们适用于识别、解码、定位、声道和生命周期验收，不作为音质参考母带或调制器品质评测。

## 已完成检查

FFmpeg 9.0 对两个文件均以 `-xerror` 完整解码成功；现有编译的 Musxi `DsfFileParser`、`DsfId3Tags`、`DsdPcmDecoder` 也完整读取，头信息和标签正确。每个结果为 176,400Hz／双声道、7,938,000 帧，正好 45 秒；没有非有限值，最大绝对幅度小于 0.20，无削波。

在低于 20kHz 的分析频带，左／右提示的主频为 440／660Hz，声道隔离均超过 58dB；原始全带宽噪声测量一并保留，不把 DSD 噪声整形的超声能量误算为声道串扰。音乐段与生成参考的可听频带信噪比：FFmpeg DSD64／128 约 38.46／43.46dB，Musxi 约 53.13／61.48dB。不同滤波器存在幅相／延迟差异，使用整数帧对齐，这些分数只作本样本一致性核对，不能用于比较解码器音质。

首次生成的标签误用了 ID3v2.3 不支持的 UTF-8 编码值，Musxi 未读出标题；已修正为适合 ASCII 文本的 encoding 0，重新生成、重算散列并完整复验。生成器首次以全带宽做声道隔离判定，随后明确区分低于 20kHz 的声道检查与全带宽噪声记录，原始宽带数值仍在分析 JSON，不将检查口径变化当作应用优化。

以上为桌面完整解码和信号检查，没有替代手机实机或真人听感。应用源码未变，仍沿用之前统一 2,954 项测试／Lint 通过基线；本轮只重查源码一致性和文档链接，不冒称重新跑过统一回归。

## 证据与接续

本地材料：`.local/artifacts/dsf-multirate-samples-20261115/`，`README.md` 为复现与使用说明；`samples-manifest.json`、逐文件 validation／musxi JSON、`signal-analysis.json` 记录格式、散列及验证。`reproduce/` 保留原创生成器、双解码和分析脚本；已计算散列的中间 float PCM 在检查完成后清理，只保留两份 DSF 和参考 WAV。原 pending 听感及文档快照在 `.local/checkpoints/dsf-multirate-samples-20261115/`，不覆盖私人数据库或旧设备证据。

制样结束时的接续任务现已由[后续设备专项](backend-dsf-multirate-device-20261115.md)执行：新增倍率适用的固定短样步骤，没有套用写死DSD256／大于五分钟或含超出45秒目标的旧runner。完整播放、暂停恢复、10／30／5秒定位及曲末通过；首轮失败、诊断与复验独立保留，正式开关继续关闭。64／128整体主观反馈已补，原生音乐录音与长时验证另补，不以合成结果取消该边界。
