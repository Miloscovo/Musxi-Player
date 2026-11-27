# 第三批：音频格式事实与输出边界

2026-11-14。后端实现与本地统一验证完成，接续[六批计划](audio-expansion-roadmap-20261114.md)。初次交付时手机未连；同日重新连接后已补FLAC／标准96k24 WAV／48k MP3输入与AudioTrack事件、停止清理，以及第一、二批的主要SAF／分段／恢复／撤权链路，见[实机记录](backend-audio-expansion-device-20261114.md)。正式UI、听音及蓝牙／USB实际路由另验，不提交推送或发布。

## 本地来源查询

`localAudio.inspectFormat(audioId) { Result<LocalAudioFormatInfo> }`：显式主线程调用、IO只读、主线程回调，返回可取消Job。支持FLAC STREAMINFO及普通RIFF WAV PCM／IEEE float；其他格式、复杂扩展或未知字段保持null，不按文件名、平台标签或设备能力猜测。

查询按现有来源权限读取，不获取持久授权、不修改曲库；校验来源版本与取消，失败返回本地音频枚举错误。最多读取256KiB的头／前置块，FLAC只42字节，大WAV不读取data负载。CUE分曲返回底层源格式，条目时长仍是分曲时长。不把读取文件头视为完整文件校验或支持DSF播放。

## 播放观察

`playback.audioInfo: StateFlow<PlaybackAudioInfo>`由服务持有，观察当前时间线；mediaId用于与当前条目核对。首次无数据、切曲／换源／换档新period时先清空，旧period与预加载事件不覆盖当前。停止清活动解码器和AudioTrack，服务销毁清全部；路由变化清旧AudioTrack值，等待新观察。

- `source: AudioFormatFacts?`：Media3选中音轨的输入格式，字段codec／sampleRateHz／bitsPerSample／channelCount／bitrate。仅固定codec映射与数值，不发布Format标签、私有响应、URL或文件路径。压缩输入若未给位深则null，不能因FLAC标签填16／24。
- `decoderName`：已观察的当前解码器名字；仅有限字符与长度，非错误正文。
- `decoded`：当前接口未直接观察codec输出，保持null。
- `appOutput: AudioTrackFacts?`：Media3实际创建AudioTrack时的encoding／采样率／channel mask配置，模式区分PCM／offload／encoded／tunneling。这是应用提交边界，32bit float不代表源是32bit音乐。
- `deviceOutput`：当前系统链路无法确认物理最终格式，保持null；不从AudioDeviceInfo支持能力、输出设备名称或源格式推断。

`null`显示“未知／尚未观察”，不能显示0kHz／0bit或统一套用48kHz。页面先核对mediaId与当前播放器，再展示事实边界；暂停可保留已观察配置，停止不能当作仍在输出。该信息不改变播放器采样率、音量、音频焦点或路由。

## 音质偏好与回退复核

全局STANDARD／HIGH／LOSSLESS／BEST仅在没有有效平台显式偏好时生效；各平台显式偏好独立。自动取流从该实际平台的偏好向下选择，权限／会员／版权／试听／不可用允许降档，网络失败不降档；手动明确选档不静默降档。音质切换准备保留旧lease，失败或超时回滚旧流。`qualityName`是平台实际取流档位标签，不是编码格式或物理输出。

新增偏好覆盖／清除／非法值与平台隔离测试；既有候选顺序、降级和质量切换回滚测试继续统一运行。没有真实账号档位或输出硬件验证。

独立待办：静态审查发现手动换源会先替换来源，候选首次播放失败后未恢复旧来源；这是现有换源策略，与本轮音质切换回滚不同，未扩大修改。后续应单独设计prepare／commit／rollback及设备失败恢复验收。

## 验证与待验

新增24项回归：文件头10项、播放事实8项、平台偏好4项、服务生命周期与CUE共享来源各1项。官方 `scripts/verify.ps1` 统一2,901项全过，0失败／错误／跳过，Lint0错误150警告；debug主包与设备测试包编译通过，2,221条路径冻结一致（含未跟踪朋友材料）。证据 `.local/artifacts/verification/20261114T104413922Z-27340/`，索引 `.local/artifacts/audio-info-20261114/final-summary.json`；APK SHA-256 `09661ee78ff832f9943aa14deb48fd25172307e1169b96b832f287c959039bdc`。早期隐藏API编译失败及WAV截断测试失败均修正，保留在本批材料，不作为通过证据。正式UI未接，未安装、未提交推送发布。需要手机后再核对实际FLAC／WAV／MP3输入、解码器与AudioTrack事件、切曲／换档／换源后的旧值清理、蓝牙／USB插拔及重建；物理输出、原样输出和USB协商属于第六批，不以本地测试代替。
