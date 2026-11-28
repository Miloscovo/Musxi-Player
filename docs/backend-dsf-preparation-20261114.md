# 第四批：DSF识别与DSD解码准备

2026-11-14。第四批初稿在用户暂停验证期间编写；恢复验证后新增53项已进入统一2,954项回归并通过。首次持续播放失败经[热点优化](backend-dsf-optimization-20261114.md)后，现已进一步通过[整首及数分钟熄屏验收](backend-dsf-background-20261115.md)；正式入口继续关闭，真人听感、其他倍率及长时耗电仍待验。初次失败保留在[首次评估](backend-audio-expansion-device-20261114.md)。不接正式UI，不提交推送发布。

## 本轮已编写

- `DsfFileParser.parse(header, actualSize?)`：纯Kotlin读取固定92字节DSD／fmt／data头，严格核对版本、声道布局、采样率、位序、块大小、样本数、声明长度与元数据指针。偏移采用Long，拒绝unsigned64超界和算术溢出；音频负载长度按4096字节每声道块及末尾填充计算。头可读不代表音频负载完整。
- `DsfFileInfo.locate(positionMs)`：把时间换算为DSD样本，再定位对应声道块组的绝对偏移、块首样本与需丢弃的样本数。只做定位计算，不输出音频。负位置拒绝，超曲末夹至最后有效样本；未来解码器仍需滤波预热和样本级丢弃。
- `DsfId3Tags`：有界ID3v2.3／2.4文本标签，支持标题、作者、专辑、曲序。总标签最多1MiB，单文本帧最多4KiB，发布文本最多1024字符。封面/APIC跳过，不解码图片；扩展头、全局unsync和压缩等复杂标签保守不支持。标签失败不等于音频头失败。
- `localAudio.dsfPreview`：显式选择文档后的只读预览，与曲库独立；`preview(uri, callback): Job`、`state: StateFlow<DsfPreviewState>`、`cancel()`、`clear()`。主线程发命令，IO读数据，主线程回调；新请求取消旧请求，revision拦截迟到结果。输入URI仅在后端短期使用，不出现在公开状态、错误正文或持久数据。
- 既有 `localAudio.inspectFormat` 的文件头识别增加DSF分支，返回DSD原始采样率／1bit／声道，不推断PCM或物理设备输出。不会因扩展名猜测格式。

## 预览与权限

仅检查用户明确选择的content文档，使用已有临时读取权限，不获取或释放持久授权，不复制、不入库、不扫描相邻目录，不改正式选择器或扩展白名单。`DsfPreviewInfo`公开文件显示名、源采样率、声道、时长、DSD倍率、可选文本标签及标签状态；`playbackSupported=false`，识别不开放播放入口。

首次读取固定92字节；存在metadata pointer时直接随机读取10字节ID3头，再按有界长度读取标签。最多92+10+1MiB；禁止通过InputStream.skip顺序遍历音频负载。SAF提供者为不可seek管道时，标签状态为UNAVAILABLE，源格式仍可显示。若权限明确被撤销则返回PERMISSION_REQUIRED。文档大小／修改时间前后变化拒绝发布；提供者没有可靠版本字段时无法证明内容不变，预览不作为后续播放的授权票据。

固定错误：INVALID_ARGUMENT、NOT_FOUND、PERMISSION_REQUIRED、READ_FAILED、TOO_LARGE、INVALID_DSF、UNSUPPORTED_DSF、SOURCE_CHANGED。无路径、原始异常和URI。沿用64GiB单文件／366天时长上限。DSD64至1024仅表示头部格式识别范围，不是播放能力列表；本轮真实样本仅验证了DSD256，超过1MiB的真实ID3按上限返回UNAVAILABLE。

## 解码接入门槛

现有默认Media3链路不能凭增加dsf扩展名获得DSD解码。本轮不引入未经选择和验收的native依赖、不做整首转WAV缓存、不新增独立后台播放器。DSD→PCM需要明确的低通滤波和降采样、声道块解交织、位序、末尾填充与有效样本处理；不能把逐位平均直接当作最终音频方案。

接入方向为Media3同一服务内的Extractor与软件解码器：Extractor负责DSF头、有效样本、块读取和SeekMap；解码器负责低通降采样及PCM时间戳，沿用AudioSink、焦点、通知、队列、书签、倍速和定时。定位后要先回退足够的滤波历史，再丢弃预热和请求位置前样本；不可直接从目标块冷启动并宣称准确seek。每次只缓存固定数量的声道块和PCM帧，不把GB级音频装入内存。

候选实现需要查证DSD解码支持、Android ABI与minSdk、线程/取消和许可，明确包体与维护代价后再选；不因桌面ffmpeg解码成功推断Android已支持。正式开放必须通过输出对照、seek／曲末、持续播放、内存／耗电和依赖体积检查。原生DSD、DoP与USB独占仍属第六批。

当前选择专用Extractor直接发布raw PCM，继续走现有AudioSink，不做整首转码缓存。目标为独立DSF、DSD64／128／256到176.4kHz float32；这不是物理最终格式或正式支持列表。纯Kotlin移出内层getter访问后，本机同一DSD256的前台两分钟吞吐通过；JNI仍未引入，也没有已选用的第三方DSD解码库或新增运行许可声明。后续已补整首、后台受限报告及数分钟熄屏，下一步先补听感／倍率和长期验收，不依据最初失败立即切换实现。

## 流式转换原型与默认隔离

纯Kotlin `DsdPcmDecoder` 支持单／双声道DSD64、128、256到176.4kHz float32，按DSF声道块解交织和位序转换。采用257tap Blackman窗sinc低通先抽取8倍，再经1／2／3级65tap低通各抽取2倍；各级DC归一、环形历史缓冲，补偿24／28／30输出帧群延迟。系数为项目内按公式构造，无新增第三方库或native ABI。初版加入字节查表、整字节路径与镜像／对称滤波，数值回归通过；最初的PJF110持续播放失败已被后续热点优化和整首复验覆盖；有限网格分析、片段PCM对照与实际听感分别记录，不宣称音质或性能全面合格。

新增 `DsfPcmExtractor` 与 `DsfExtractorsFactory`，并接入同一服务的MediaSource工厂。内部 `PlaybackHost.experimentalDsfDecoding` 默认false，正式Application未启用；即使内部启用，也只将DSF探测器加入本地票据／content／file来源，不加入HTTP平台流。默认工厂行为保留，正式选择器、DSF导入与公开 `playbackSupported=false` 均保持当前状态。

Extractor输出raw PCM给现有渲染链，使用固定单声道块组与固定PCM缓冲。每次读重新检查中断，只解码有效样本、不播放末块填充或尾部ID3；曲末用滤波零值补尾并限制总输出帧为floor(sampleCount／decimation)。SeekMap按PCM帧向下定位、回读上一声道块组作为滤波预热，重置滤波历史，丢弃延迟及目标前帧；时间戳按源时长对应的PCM时间生成。不可seek来源仍需后续单独验收，当前没有承诺其可随机播放。

格式观察边界：该桥接路径的Media3输入是Extractor已经生成的PCM，因此 `playback.audioInfo.source` 在此表示渲染链输入PCM；原始DSD事实由只读文件查询／预览取得。不能把176.4kHz float32说成DSF源格式，也不能把它说成USB最终输出。

Extractor6项覆盖内部开关与网络隔离、PCM计数与尾填充、seek后与连续输出对照、64bit SeekMap、sniff及负载截断；内核8项覆盖立体声、位序、部分块与相位连续、reset、输出计数／延迟和幂等补尾。连同解析／标签／预览39项，第四批53项均已运行并通过。解码路径仍是关闭的原型，单元测试通过不解除持续播放门槛。

协作记录：流式内核与Extractor交叉审阅两个子任务均遇本地路由401 Unauthorized／Invalid token。主代理保留并修正内核已有文件，接管内核测试和最终静态审阅；交叉审阅没有完成，不将失败子任务计作独立审阅通过。没有切换模型重试，也没有以此恢复验证。

## 已验与剩余门槛

当前官方统一2,954项通过、Lint0错误150警告；真实DSD256识别、前20秒PCM逐字节对照、定位／曲末及两轮整首通过。最终包374次状态采样全部处于熄屏／锁定，缓冲跃迁为0，后续暂停／恢复／连续定位及取消通过。无页面冷触发的受限场景已明确读回BACKGROUND_RESTRICTED，不能将其记为允许后台任意自启。正式UI未接，DSF导入与播放未开放，封面提取未实现。

解析／预览39项为DSF头与定位14项、ID3文本18项、应用预览7项。覆盖大于4GiB的稀疏头、块填充、曲末／溢出、标签编码与边界、随机读取不可用、权限／版本变更和公开状态脱敏；已纳入上述统一结果。大于4GiB是稀疏模型回归，不是真实4GiB设备文件验收。

下一步先补真人听感和真实DSD64／128，再分别验证不同／不可seek提供方、标签／封面、数小时后台、长期内存与耗电。快速取消已读回暂停与开关复位，准备阶段取消的精确竞态不算全部通过。前三批关键SAF／CUE／格式设备链路沿用已有验收，无缝及物理路由仍独立保留。六批总览见[计划](audio-expansion-roadmap-20261114.md)，最新结果见[整首／熄屏验收](backend-dsf-background-20261115.md)，首次失败见[先前评估](backend-audio-expansion-device-20261114.md)。
