# CUE 第一批：解析、文件关联与导入预览

2026-11-14。六批范围见[推进计划](audio-expansion-roadmap-20261114.md)。本批只读预览；虚拟曲目持久化与播放在第二批实现。

同日恢复设备验证后，系统SAF实际24轨预览、显式关联及取消不入库已补验；COPY／REFERENCE、分段播放与权限结果见[合并实机记录](backend-audio-expansion-device-20261114.md)。本文后面的2,840项为第一批初次交付基线，不替代最终2,954项与对应包证据。

## 入口与生命周期

`MusxiApplication.localAudio.cuePreview`为`CuePreviewRepository`，提供`state: StateFlow<CuePreviewState>`。

```kotlin
localAudio.cuePreview.preview(cueUri, selectedAudioUris, bindings) { result -> /* 主线程 */ }
localAudio.cuePreview.cancel()
localAudio.cuePreview.clear()
```

主线程调用，返回可取消Job；IO读取，正常回调返回应用主线程scope。新预览取消前一个，revision阻止迟到覆盖；取消不回报普通成功／失败。cancel立即清结果并标记cancelled，clear清空。直接取消Job也停止发布。阻塞提供方可能延迟返回，但不会发布迟到结果。

`LocalAudioPickers.cue()`为系统单选、全部类型、临时只读；候选音频沿用files。取消选择器不发命令，使用系统实际授予的读取权限。首批只接受显式选取的content URI，不从CUE文本推断兄弟文件权限；不获取或释放持久授权。预览不保留URI、不复制、不入库，进程结束需重新选择。

## 文件关联

候选最多100个，不得重复或包含CUE本身。candidateIndex对应本次输入位置；`bindings: Map<Int,Int>`从fileIndex映射候选index，`mapOf(0 to 1)`明确将第一来源关联到第二候选。改关联时携原选择重新preview，index不跨revision复用。

默认先精确名称，再唯一忽略大小写名称；多匹配为AMBIGUOUS，无匹配为MISSING。不猜`(2)`，不因匹配文件损坏擅自选另一份。手动关联为MANUAL，仍校验格式与范围。非法绑定／非content为INVALID_ARGUMENT，超量LIMIT_REACHED。

公开files仅有叶名称、fileIndex、candidateIndex和关联状态；candidates仅有名称、格式与枚举错误，无URI和外部异常正文。UI不得读取原始CueSheet。

## 解析与范围

纯`CueSheetParser.parse(bytes)`：最多256KiB、4096行、单行2048字符、99文件／99轨。严格UTF-8含BOM、带BOM UTF-16LE／BE；无BOM UTF-8失败后严格GB18030。拒绝非法字节／控制字符。

数量按TRACK AUDIO动态生成；INDEX01必需、75fps，时分秒帧与顺序校验。首版单FLAC，FILE类型WAVE／FLAC均可。曲目表演者优先，缺省回退专辑；缺标题用曲目编号。REM可忽略；多FILE、INDEX00、额外索引、间隙和未支持语义给parseIssues并阻断，不静默丢弃后当成功。

引用限叶文件名，拒绝目录、绝对路径和URI。手动绑定不消除解析阻断。源引用不进入错误文本或公开状态。

候选最多读取42字节FLAC magic和首个STREAMINFO，确认采样率、样本数、声道、位深及精确时长，不解码／全量哈希。零／未知样本数不能确认末轨，给INVALID_FLAC；首批不支持前置ID3的FLAC。**文件头通过不等于音频完整**，朋友原名坏FLAC仍可能得到可预览结果，不能宣称完整性通过。

轨道有startFrame、下轨起点endFrameExclusive（末轨null）、durationUs。非末轨按75fps，末轨按样本终点计算；起点小于总时长、下一边界不得越界。Long计算，过长索引阻断。集合防御复制且不可修改。

## 状态与错误

completed表示本次结束，**canProceed仅表示解析／关联／范围通过，不表示已入库或可播放**。缺失／歧义／越界返回成功预览但canProceed=false；CUE无法读取等整个操作失败写state.error并回PreviewFailure.reason。未引用候选的错误不阻塞已选有效来源。

解析失败不打开候选音频；CUE最多上限+1判超量。读取／权限／超量／格式分别映射，取消不吞成读失败。

## 验证与后续

第一批已完成。新增56项自动化（解析22、规划17、Android文档读取与生命周期17），覆盖动态轨数、编码、关联、范围、权限、有界读取、取消与迟到结果。

2026-11-14官方 `scripts/verify.ps1` 全量2,840项通过，0失败／错误／跳过，Lint 0错误／150警告；debug主包与设备测试包编译通过，2,207条路径冻结一致（清单包含未跟踪的朋友材料，不全是源码）。证据 `.local/artifacts/verification/20261114T090557342Z-3128/`。本批8份实现／测试文件散列与官方清单一致；文档收尾另做链接及差异检查。

最终core.jar的真实样本冒烟通过：GB18030、24轨；仅选带“(2)”音频时MISSING，明确手动关联后MANUAL／canProceed=true；首轨43.493333秒、末轨62.466666秒。证据 `.local/artifacts/cue-preview-20261114/`，不修改来源。该冒烟只读取CUE与FLAC文件头，不代表完整解码或安卓播放通过。

音频／完整曲目表不提交；正式UI、SAF实际提供方、听音／无缝和实机播放留后续批次。本轮不安装、不提交推送、不发布。第二批从稳定来源／分段身份、入库事务、重复导入与引用回收开始，再接分段播放及恢复。
