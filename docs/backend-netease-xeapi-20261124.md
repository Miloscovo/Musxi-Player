# 网易云取流 XEAPI 适配

2026-11-24。接续 [11-19／20 的取流定位](backend-netease-stream-reboot-20261119.md)。

## 判断与修复范围

已记录的失败发生在播放地址接口，早于 Media3 收到来源：账号确认与歌曲详情成功，`song/enhance/player/url/v1` 返回业务码 `-460`。会话续签修正后仍未恢复；不能把“已连接”视为可以取流，也不能仅凭风险提示推导为会员、IP 或应用授权的具体问题。

本轮重新核对 [api-enhanced 固定修订 `23d9e9d7ee5dfeccfc3b15916ccd9da1f76d3ea4`](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/tree/23d9e9d7ee5dfeccfc3b15916ccd9da1f76d3ea4)：`module/song_url_v1.js` 明确选择 `createOption(query, 'xeapi')`，而 Musxi 此前沿用 `interfacepc.music.163.com/eapi/...`。这是可以确认的协议差异，尚不能单独证明历史 `-460` 的具体触发原因。GitHub API 请求因共享出口配额返回 403，后续使用公开源码固定提交和 `git ls-remote` 核对。

取流现在单独适配 `https://interface3.music.163.com/xeapi/song/enhance/player/url/v1`：

- 先直连官方 `/api/bsr/sk/get`，校验响应签名，解出平台公钥、版本和 `sk`。取钥不带账号凭据，失败立即停止。
- 依据上游规则构造 `C/S/R`，保留原有歌曲 ID、音质和 `encodeType`。每次生成动态密钥、X25519 临时密钥与随机量；不保存或跨账号共享响应中的加密会话密钥。
- 当前账号的 Cookie 与 `x-music-u` 只在此次取流请求中使用，继续沿用仓库原有账号代次检查和加密会话存储。
- 原始响应先按 AES 解密，再按 gzip 标记有界解压与解析；HTTP 400 的加密业务错误也可读取。`-460` 保持风险分类并停止，不回退旧协议、不自动降档或重复取流。
- 曲库、登录、搜索与其他平台请求保持各自协议。新增 Android 8 兼容的纯 Java Curve25519 依赖，来源与许可见 [第三方声明](../THIRD_PARTY_NOTICES.md)。

## 本轮验证与边界

网易云模块专项 90 项单测通过。独立测试端使用 JDK X25519 解开实际生产代码构造的请求，检查注册签名、`C/S/R`、歌曲和音质参数；覆盖跨账号凭据隔离、续签后的取流、风险停止、加密／gzip 响应和解压大小上限。这些都是离线协议验证，不是实际音乐播放。

另发起一次不带账号的真实公钥请求：HTTP 200、业务码 200、响应签名验证通过，解得 32 字节公钥，版本和 `sk` 类型符合实现。首次 PowerShell 调用在发送前被 User-Agent 格式检查拒绝，调整工具的头部校验后完成上述请求；未重复真实取流，音频请求数为 0。该结果只确认公钥接口及其响应格式。

完整工程验证 `scripts/verify.ps1` 通过：3,061 项 JVM 测试，0 失败／错误／跳过；Lint 0 错误、150 警告；debug 主包和设备测试包构建通过，源码一致性检查通过。摘要为 `.local/artifacts/verification/20261124T011736361Z-10636/summary.json`，debug APK SHA-256 为 `bc55b8175a561261bd54a5880e1fc35990da76cdad5fb26d0366f5ac652dd782`。该摘要中的 `deviceTestsExecuted=false` 保留原义，随后单独执行的设备验证见下节。

## 同日实机验证

用户随后连接手机并授权继续。设备 OPPO PJF110／Android 16／API 36，保留数据覆盖安装上述冻结主包，手机安装包 SHA-256 一致；首次安装时间仍为 10-19，现有三平台账号恢复 `CONNECTED`，没有重新登录或更换账号。

- **同曲标准档**：正式搜索页搜索 `Da Capo`，最佳匹配为 HOYO-MiX；通过现有固定 debug 点播入口调用正式 `playSong`。当前单槽队列逐字段解析确认网易云歌曲 ID 为 `2026565329`；详情元数据核对通过。记录 1 次逻辑取流、0 次故障注入，依次出现 `SOURCE_RESOLVED → DECODER_READY → MEDIA_READY → PLAYING → OUTPUT_READY`，实际来源为 NETEASE，时长 134,640 ms，进度持续增加，系统同 UID 的 AudioTrack 为 `started`。
- **通知暂停与恢复**：调用 Musxi 自身通知的真实 PendingIntent。暂停在 32,908 ms，等待后位置相同；恢复采样为 32,931 ms，随后继续前进，无失败、无需新取流。
- **熄屏锁屏**：持续观察 85,448 ms，最早已确认锁定的采样为 60,183 ms，最后采样为 117,902 ms；这两个采样间隔 57,713 ms，均为 `interactive=false / keyguardLocked=true / playing=true`，中间也有锁定样本。相同来源、相同队列、无自动跳曲或失败；蓝牙输出音轨保持 started。观察窗口起点的 keyguard 尚未锁定，因此不将全部 85 秒都算成已锁定时长；未进入深度 Doze。
- **原极高档**：暂停后停止进程，将测试临时标准设置恢复为用户原 `netease:exhigh`，冷启动再搜索并点播同一首。再次确认该 ID，1 次逻辑取流、0 次故障注入；成功取流与解码起播，采样进度 15,108 ms，实际音质为“极高音质”，来源与元数据一致。

两个独立进程的这首歌曲均走新协议实时取流，不将播放器旧来源缓存或其他平台换源当作网易云恢复。结果证明本设备现有账号的同曲标准／极高播放已恢复；不能外推所有歌曲、会员档位、其他账号或 API 26。当前输出为蓝牙，系统播放链路与进度已验，实际听感仍由设备使用者确认。

## 数据保全与收尾

测试前在设备私有目录保存当轮基线，未向本机导出原始凭据。正常冷启动曾重写三平台加密会话；结束暂停、停止进程后恢复测试前的三份会话密文、四份原队列、搜索历史及原设置。连同本地库数据库、两份受管音频及授权，共 13 个文件逐一 SHA-256 相同；QQ 原空队列目录也保留。没有写平台收藏或歌单。临时设备基线与 UI 中转文件已删除，手机保留修复包并停止测试播放。

设备证据与回退包在 `.local/artifacts/netease-xeapi-device-20261124/`，以 `final-summary.json` 为索引。真实来电、自然深度 Doze、长时间后台和更多曲目仍按需另验；后续正常使用不需要为本修复重新扫码。

## 本地材料

修改前快照位于 `.local/checkpoints/netease-xeapi-20261124/`，保留开工时已有未提交修改，非 Git HEAD 基线；该目录说明记录用途。本轮协议参考、专项构建失败／通过日志与脱敏公钥检查保留于 `.local/artifacts/netease-xeapi-20261124/`。未提交、推送或发布。
