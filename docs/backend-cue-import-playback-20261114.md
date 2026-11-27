# CUE 第二批：虚拟曲目入库与分段播放

2026-11-14，后端实现与本地统一验证完成，同日手机补验已覆盖主要导入／分段／恢复／撤权／共享删除链路，见[合并实机记录](backend-audio-expansion-device-20261114.md)；正式页面、无缝听音与长后台仍待验。范围来自[六批计划](audio-expansion-roadmap-20261114.md)，接续[第一批预览](backend-cue-preview-20261114.md)。只做后端，正式页面后续由CC接入。

## 导入入口

先完成 `localAudio.cuePreview.preview(...)`，取得可继续的预览及其revision；提交时仍传同一CUE URI、候选顺序和bindings。UI只保存选择器输入用于调用，不把URI写入页面状态、日志或整理备份。

```kotlin
localAudio.importCue(
    folderId, cueUri, selectedAudioUris,
    expectedPreviewRevision = localAudio.cuePreview.state.value.revision,
    expectedRevision = localAudio.state.value.revision,
    bindings = bindings,
    mode = LocalImportMode.COPY,
    grantedFlags = audioPickerResultFlags,
) { result -> /* 整批成功条目或明确失败 */ }
localAudio.cancelImport()
```

主线程命令；读文件与复制在IO执行。使用现有 `imports` 进度，不与普通导入／导出并行。首版沿用单FLAC、有效AUDIO索引；不自动推断兄弟文件权限。COPY为默认，音频只复制一份；REFERENCE必须取得系统持久读授权，不因临时可读就声称重启后仍能读。CUE仅用于索引，导入后不再依赖CUE授权。

提交时重读CUE、候选文件头及可用的大小／修改时间，与后端保留的预览指纹核对；预览已清除／重做或曲库revision变化返回STALE_STATE，输入改变返回SOURCE_CHANGED。不接受UI自行构造的曲目表作为入库依据。复制期间再次核对来源，取消在事务提交前回滚整批并回收未引用副本／授权；提交完成后的取消不撤销已提交资料。

## 身份与持久化

schema5新增nullable分段字段，旧条目默认无分段；旧库通过增列保留ID、文件夹、进度、书签、封面和倍速。单次事务保存全部曲目和文件夹成员，不额外生成整轨条目。

`LocalAudioItem.segment`提供 `startUs/endUs/trackNumber`；end为源时间中的独占边界，段内durationMs可供页面显示。metadata.sizeBytes仍是底层整文件大小，用于来源校验，不能求和当实际占用；多个曲目共用一份COPY location或REFERENCE grant。进度／书签按曲目ID独立，位置从分段起点算0。

身份包含完整CUE字节摘要、底层来源版本和分段边界。同版本重复导入复用ID、保留用户数据；CUE内容或来源版本改变产生新ID，不按编号套旧进度。旧分曲保留，由用户后续整理，不自动替换或删除。

删除单曲只移除该条目及其依赖；最后一个引用消失后才回收受管副本或Musxi持有的授权。删除文件夹只删成员，不删曲库条目；不修改外部原文件。

## 播放、恢复及备份

分曲进入原本地队列、同一Media3服务和匿名来源ticket。MediaItem使用微秒剪辑范围；播放进度、seek、书签和保存队列均为段内位置，不再加源偏移。上／下一首、循环、随机、队列编辑和定时沿用同一服务策略。恢复仍核对稳定曲目ID与sourceVersion，变化时阻断；段内位置有界。

整理备份写v2，保留分段且可读v1；同一COPY的不同分曲使用不同来源匹配key，不把旧整轨备份猜配到分曲。恢复还核对当前与备份的分段范围一致，避免修改备份边界后套入越界进度。备份仅记录整理元数据，不包含音频、URI、授权或原始来源identity。

现有整文件导出及单条重新关联对分曲返回INVALID_TARGET，避免把整张专辑导出为一首或把旧边界用于另一文件。后续可另做显式分曲导出与整组重新关联，不在本批静默改变语义。

## 验证与实机窗口

新增37项回归：导入facade 10、SQLite 13、备份6、MediaItem配置4、播放服务4。官方 `scripts/verify.ps1` 全量2,877项通过，0失败／错误／跳过，Lint0错误150警告，debug主包与设备测试包编译、2,213条路径冻结一致（含未跟踪朋友材料）。最终证据 `.local/artifacts/verification/20261114T100528949Z-28080/`；本批索引 `.local/artifacts/cue-import-20261114/final-summary.json`。过程失败及修正日志分别保留，不与最终通过混用。文档回填后另跑链接与差异检查。

实样解析／关联冒烟再次得到GB18030、24轨与明确手动绑定通过；本轮没有用真实音乐执行入库或播放。导入使用合成FLAC头，播放专项验证Media3队列与裁剪配置，未完成真实音频解码边界、持续播放或听音，不代表朋友原FLAC完整性或物理无缝通过。

第一批仍需手机上验证SAF文件选择、临时授权和实际提供方；可以合并第二批一次验证：

1. 系统选择CUE和音频，名称不符手动关联，取消后不写入；拒绝／撤销权限有明确反馈。
2. COPY入库24轨且只占一份底层音频，重复导入无重复；REFERENCE重启后可读，撤销后保留条目并提示授权。
3. 首／中／末轨从各自起点播放，拖动和书签是段内时间，下一首、上一首和单曲循环无越段。
4. 后台／锁屏控制、曲末定时、切平台后恢复、进程重启恢复，删除某轨不破坏其余分曲。
5. 同一文件相邻曲目的实际听音衔接另验；剪辑范围通过不等于已证明无缝或设备原样输出。

第二批初次交付没有安装或运行实机探针；随后用户重新连接手机，已按[实机记录](backend-audio-expansion-device-20261114.md)补验。朋友源文件未改，正式页面接线与真人听音仍独立记录。
