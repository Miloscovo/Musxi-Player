# 安卓播放器基础使用验收

2026-10-19 完成本轮高频场景验收。本记录区分代码配置、固定调试入口、真实系统事件和用户本人操作；未执行的项目不写成通过。保留本人三平台登录及缓存，使用现有 Musxi 开发版，不操作其他应用私有文件或凭证。

## 本阶段范围

| 场景 | 验收标准 | 当前证据 |
| --- | --- | --- |
| 前后台与页面重建 | 播放由同一服务持有，返回页面状态一致，无重复播放器 | 本轮 HOME 和 Activity.recreate 后 queue=7、原索引/进度保留且继续播放；前轮曲尾接续通过 |
| 锁屏后台 | 锁屏后继续播放，解锁返回状态一致 | 本轮 KEYCODE_SLEEP 后 interactive=false、keyguardLocked=true，短时进度继续增长；WAKEUP 后正常 |
| 系统媒体控制 | 系统会话暂停/继续、前后切歌与应用一致 | 用户本人确认锁屏卡片可见、暂停/继续/下一首有效，切歌后有声音；脚本未代替本人操作 |
| 短暂音频焦点丢失 | 暂停/抑制播放，焦点归还后按系统规则恢复 | debug 前台 Activity 请求真实 AUDIOFOCUS_GAIN_TRANSIENT 获批；suppression=AUDIO_FOCUS，归还后继续播放 |
| 主动暂停与焦点恢复 | 用户在被打断期间主动暂停，归还焦点不能擅自播放 | 两次真实短暂焦点请求中，第二次主动 pause 后 release 保持 playing=false / playWhenReady=false，显式 resume 成功 |
| 长期音频焦点丢失 | 不自动恢复，用户可显式继续 | 真实 AUDIOFOCUS_GAIN 获批后主播放器暂停，release 后仍暂停，显式 resume 成功 |
| 耳机断开 | 真正断开后暂停，重连不擅自播放 | 用户确认起初耳机可听，断开后无声；固定诊断 playing=false / playWhenReady=false / 无错误。重连用户确认没有自动恢复；未记录耳机型号和连接类型 |
| 真实断网及重试 | 缓冲耗尽后明确失败，不连跳整单；恢复网络后显式重试 | 本轮真实飞行模式并关闭 Wi-Fi/移动数据，跳到未缓冲段触发 NETWORK，原曲/队列保留且不自动跳歌；恢复网络后保持停止，显式 resume 从原位置继续 |
| 前端状态 | 区分播放意图、实际播放、缓冲、系统抑制、错误；重试可发现 | 新增 PlaybackState.suppression 及开发页文案，区分系统焦点抑制、输出问题和主动暂停；统一接口文档已补充 |

三平台已通过部分歌曲登录、缓存、队列起播和切歌，不能代表所有歌曲可播。当前队列不持久化；强停/系统回收后的账号恢复与队列恢复分别记录。长时间待机、整机重启、多设备和真实来电不属于这轮高频场景的完成前提。

## 顺序与交接

先完成有界的基础验收和修复，随后实现跨平台候选选择与原歌单接续，再稳定接口交 cc 正式接线。跨平台方案记录见 [cross-platform-sources.md](cross-platform-sources.md)。本阶段不冻结未来换源接口；已存在的账号和曲库接口继续可用。


## 本轮进行记录

- 用户确认耳机断开无声、重连不自动播放；仅记录本人反馈及自己应用状态，不读取其他应用。
- 真实焦点请求由 debug 非导出 ValidationFocusActivity 发起，API36 下前台 Activity 满足申请资格，focusProbeResult=1 才计获批。30 秒自动释放；launch/release 使用 revision 避免尚未创建就取消的竞态。此页与固定入口不在 release。
- 焦点测试没有模拟来电、通知提示音或其他真实应用；只证明 Android 系统焦点请求路径。当前验收同一应用不同焦点 listener/clientId；独立应用/真实通话留后续专项。
- 用户本人已反馈锁屏/通知卡片可见、暂停/继续/下一首有效，切歌后有声音。脚本 KEYCODE_SLEEP 短时锁屏播放与本人卡片操作分别通过。
- QQ 本人歌单当前总数为 7，真实队列=7；前轮 6 是当时快照。没有修改平台歌单，验收脚本改为使用本次实际 total。
- 初始旧进程固定广播一度未及时反映到诊断，更新包并 force-stop/reopen 后恢复；不将其称为歌曲权限失败，也不将当时超时算作验收成功。


## 最终包与结果

最终 debug APK SHA-256：`71b01adc5c7a7acfeb7992a9e7af60cc73780655e4574d126e43d6630f5a53ec`，已覆盖安装。APK v2 签名通过；123 项 JUnit 全通过，app/accounts/playback Lint 0 errors。release 合并清单使用正式 MusxiApplication，无 ValidationFocusActivity、DebugMusxiApplication、ValidationReceiver、DUMP；未构建 release APK。

真实音频断网验收使用 QQ 7 项队列索引 2：先正常播放，临时 airplane=1、关闭 Wi-Fi/移动数据，seek_late 到约 140649 ms 后发生 NETWORK，playing=false/playWhenReady=false，skip=0，观察 3 秒无跳歌。finally 恢复原 airplane=0、wifi=0、mobile_data=1 并核对；网络回来不自动播放，显式 resume 后同索引从约 140682 ms 继续、无错误，再暂停。此项是真实音频加载失败，不是元数据缓存离线或 debug 注入。

用户实际耳机和系统媒体卡片的反馈均来自本轮已安装的同一最终包。焦点、短时锁屏及页面重建使用固定入口/系统按键；它们不代替真实来电、长时待机或进程死亡测试。快速 focus launch/release 额外验证结束后无活跃测试请求，主播放保持暂停；只读复审发现的取消竞态已修复并复核。

当前限制：本轮单设备 Android16，耳机型号/连接类型未记录；未专项测试真实来电、独立第三方应用的焦点、提示音 ducking、长时间后台/Doze、多设备、整机重启、实际换号、自然地址过期。账号和元数据缓存恢复不等于持久播放队列；后续换源仍须单独实现和验收。


最终三平台回归中，网易云5项队列起播通过；随后设备进入锁屏，酷狗746项队列已建立但position=0、playing=false、playWhenReady=false、无播放器错误，固定MusxiQueue日志为ready=false reason=2（音频焦点原因）。未把等待超时记为成功或音频权限错误。该情形涉及暂停后的后台新建队列与API35+焦点资格，已请本人解锁保持开发页前台后再复测；实际锁屏媒体控制前面已由本人验证成功。是否需要进一步支持后台重新发起新队列待具体定位，本轮不把该场景宣称已通过。


解锁保持开发页前台后：酷狗同一746项队列索引1显式resume，进度超过1500ms、无错误；QQ重新读取7首并指定第2首起播，进度超过1000ms、无错误。网易云5项、酷狗746项、QQ7项三平台回归完成。前述锁屏暂停后新建/恢复被焦点拒绝的特殊场景仍保留为未解决的后台启动边界，不能因为前台恢复成功就抹去；尚未对该边界做独立根因实验。

收尾：QQ队列7项索引1暂停，playing=false/playWhenReady=false/playbackError=false，三家CONNECTED。原airplane=0、wifi=0、mobile_data=1均已恢复；故障模拟未开启，焦点测试页已释放，账号和元数据缓存保留。下一阶段按已授权方向实现跨平台手动选源最小闭环。

## 跨平台来源阶段增量（2026-10-19）

本节更新上文“换源待实现”的阶段状态；基础验收旧包摘要和用户反馈仍对应原阶段。

- QQ AIZO 明确不可播后真实搜索 20 候选，候选就绪后 5 秒自动尝试网易云；原 QQ 7 项队列/索引 0 保留。用户本人确认声音正常、歌曲符合预期。手选第 2 项也成功，不把 SELECTED 状态本身当作已发声。
- 实际替代音频 seek 至约 162488/215954ms 继续无错，再到曲尾前 1 秒等待真实结束事件，进入原 QQ 索引 1、实际 QQ 来源播放、automaticTransitions=1、候选状态 NONE；不是将候选列表当新队列。
- 候选中取消、暂停、停止，以及下一首后超过旧截止不被迟到计时改变。延迟 2 秒的模拟权限错误在主动暂停后到达，也不进入自动选择。成功替代来源标识在暂停后保留，显式继续成功。
- 最终包模拟 4 次权限拒绝（原曲+3候选），FAILED 后原队列 7 项保留；超过 5 秒 resolveCount 仍为 4。无同名候选时手选推进，未自动播相关项；显式重试先取 QQ 原来源。故障模拟与真实平台拒绝分别记录。
- 连续拒绝曾重复收到 legacy 媒体会话 STOP。仅清除加载/隐藏错误均不能解决；服务拥有的待选期统一表达为 BUFFERING/播放意图保留，之后该复现不再出现。未确认系统发送 STOP 的根因。普通控制器直接 play（未 prepare）和真实 STOP 都复验，主动停止不被过滤。
- HOME 后服务按原截止选择网易云，但该次新取流 NETWORK，保留原队列停止；前台 resume 成功。已起播替代音频经 recreate_page 和 HOME 短时继续；后台倒计时提交与后台音频成功分开，不将失败尝试记为通过。

最终 debug APK SHA-256：`083542d04d7859b63698f177c156f21e2750111e191335062f2c06081e98b016`，已覆盖安装。142 项 JUnit、0 failures/errors；app/accounts/playback/三平台模块 Lint 均 0 errors；v2 签名与 release 清单隔离通过，无调试 Receiver/Application/焦点 Activity/DUMP，未构建 release APK。仍为单设备有界验收，实际换号/自然过期由测试及授权校验覆盖，未动本人会话；长时 Doze、划掉任务、多设备及全部歌曲未专项验收。正式前端按 frontend-contract.md 接入。

最终包三平台代表曲：网易云 5 项/索引 0、酷狗 746 项/索引 0、QQ 7 项/索引 1，均实际进度超过 1 秒、无播放器错误。酷狗索引 1 这次 PERMISSION、替代搜索 NETWORK 后 FAILED，未记通过，首曲成功不推翻该结果。最后 QQ 索引 1 暂停、playing=false/playWhenReady=false/playbackError=false；三家 CONNECTED，模拟关闭、injectedFailures=0，网络仍为原 0/0/1。此轮未新增本人听音或改平台歌单。
