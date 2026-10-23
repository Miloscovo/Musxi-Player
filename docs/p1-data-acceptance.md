# P1 队列恢复、最近播放与歌词验收（2026-10-20）

> 历史专项记录：以下只对应文中日期、包和测试范围；当前状态以 [0.1 验收](android-0.1-acceptance.md) 为准。本机证据路径仅用于本地复查，不随仓库分发。

接口见 [P1 后端接线](frontend-backend-contract-p1.md)。队列持久化/显式暂停恢复、最近播放、三平台标准歌词已接入并完成后端真机检查。酷狗初轮空响应已在同日补验中修复，最新证据见文末；下方213项测试及旧APK属于初轮记录。正式前端的接线、视觉与交互验收另行完成。

## 构建与边界

工作区 `<project-root>`，JDK17 / SDK36，执行 `./build.ps1 -Test -Lint` 成功。213 项 JUnit，0 failures/errors/skipped，较播放模式阶段新增40项。六模块 Lint 均0 errors；warnings：app37、accounts1、playback2、酷狗1、QQ1、网易云5。新增警告为 debug SharedPreferences 的 UseKtx 建议；没有新增生产依赖，cc 的 Compose/Gradle/截图文件保留。

最终 debug APK SHA-256：`5828d33279dc0c2d46f03e9983e919eb4a325e228ebcd5281d21af994deffc32`。APK v2 签名通过，覆盖安装至 OPPO PJF110 / Android16。重新执行 `:app:processReleaseMainManifest` 成功，release 使用正式 MusxiApplication、无 ValidationReceiver/ValidationFocusActivity；P1DataValidationRunner 仅在 debug 源集。本轮未生成发布版、提交、推送或发布。

存储采用 noBackup 下每平台独立的有版本/owner/CRC/原子替换 journal，最多8MiB、20,000首、100条recent。只保存公开元数据、原队列索引/位置/模式。源票据、账号会话与音频地址不进入文件。账号清理与journal IO共享串行执行器，owner/generation/verified均参与结果隔离。系统强杀可能丢失上次成功保存后约15秒内的进度，不保证系统重启/长时间后台的完整覆盖。

## 手机检查

所有操作经 DUMP 权限保护的固定 debug 入口，不用坐标点按。诊断只含枚举、计数、时间/位置及内存比较布尔，不输出歌曲 key、歌词、用户身份、Cookie、原响应或媒体地址。

| 检查 | 实际证据 |
| --- | --- |
| 冷启动默认 | 播放器空队列、OFF/false、不播放；保存的本人快照和最近播放可见 |
| QQ 7首快照 | 最终包原索引1、32,522ms、ALL+shuffle均保存，内存比较完整原顺序相等 |
| 强停重开与显式恢复 | 重开后实际queueSize=0；显式恢复QQ后queueSize=7、原索引/顺序/位置/模式恢复，playing/wanted/buffering均false，音频resolveCount=0 |
| 显式继续 | 点击resume后才重新取流；QQ来源实际playing=true，无failure；暂停后可继续保存/恢复 |
| QQ歌词 | 真实CGI的crypt=0,qrc=0返回标准LRC，本轮歌单曲32行；歌词sourceKey与实际来源相等 |
| seek同步 | 最终包约32.5秒时activeIndex=8；暂停seek到5秒时activeIndex=1，均与按实际position计算的行相等；不重新取歌词 |
| 跨平台歌词与最近播放 | QQ搜索原曲不可播后自动选网易云，原QQ队列身份/顺序保留；网易云歌词16行，sourceKey及高亮匹配；recent.original仍为QQ、recent.source为网易云 |
| 网易云直接播放 | 独立NETEASE队列实际playing=true，标准歌词35行，NETEASE平台recent增加1；QQ记录保持 |
| 酷狗音频与记录 | KUGOU搜索单曲实际取流并有约2秒进度后暂停，recent.original/source均为KUGOU；其歌词失败未阻塞音频。仅播放器证据，无本轮听音确认 |
| 酷狗暂停恢复 | 保存并显式恢复其原单曲与约1.7秒位置，仍暂停，取流次数未增加，历史时间未变化 |
| 连续同平台恢复 | 活跃队列内连续restore，restoring最终false，无player/storage错误；自身checkpoint不吞掉恢复ack；没有因恢复产生新recent |
| 模拟取流失败不记录播放 | 最终包在暂停恢复QQ后注入一次本地NETWORK错误并resume；failure=NETWORK、injectedFailures=1，QQ recent的条数和原时间均未变；属于模拟错误，不代表真实断网 |
| 平台隔离 | QQ/NETEASE/KUGOU分别保留最后队列与本人recent，切换原队列平台不覆盖其他平台记录 |

无新增用户听音确认；playing/进度只代表播放器实际状态。换号/退出、未验证身份、损坏文件、大队列上限及迟到回调以自动测试覆盖，未为验收清除本人账号。当前酷狗歌词的失败是实际服务返回空响应，不是故障注入；模拟故障和真实网络行为不混记。

## 酷狗歌词初轮遗留（已在文末补验修复）

固定 vendor 的 `search_lyric.js` 请求 `https://lyrics.kugou.com/v1/search`，以实际source hash+album_audio_id查询，再用`fmt=lrc`下载。复核了实际标准app profile（appid1005/clientver20489），以及该版本wrapper的`notSignature`与模块`notSign`差异，按实际wrapper补搜索签名；保留2MiB传输上限、严格Base64/UTF-8和512KiB文本上限。

本机最后实际请求通过HTTP状态检查，但歌词搜索响应体为空，固定诊断`lyrics_search_json_empty`，没有进入下载/解析成功分支。因此只把适配及fixture测试记为完成，三平台歌词全部可用尚未成立。没有将空响应当作“该歌曲无歌词”，也没有退回明文HTTP、猜测其他歌曲歌词或加入QRC/KRC解密。QQ、网易云歌词和音频不受这项失败影响。

## 自动回归与DS协作

新增测试覆盖存储冷实例读写/原顺序/字段和封面过滤/账号归属/损坏CRC及版本/限额保留旧文件/只清理本模块文件；浏览器身份验证变化/旧回调/恢复ack与保存交错/recent去重和上限/清空recent保留queue；LRC多时间戳/全局offset/排序/输入上限/24小时边界；歌词跟随实际source、原/源账号隔离、旧请求、seek正反向同步、空/纯文本/错误和显式重试；恢复使用当前generation及独立暂停提交；平台歌词请求参数/解析/限额fixture。

使用deepseek_flash（low，独立上下文）完成存储、LRC解析器、网易云/QQ歌词和journal浏览器测试的小补丁；主代理审查并修复夹具编译/错误key和边界断言后统一跑完整验证。酷狗协议只读任务返回定位事实，后续实现任务没有交付补丁，经一次纠偏后关闭，主代理接手，未静默更换代理模型。最终只读DS复审确实找出“restore被自身checkpoint的journal revision淘汰”的状态竞态，主代理修复并加入回归；未把DS静态审查当作设备证据。

本机忽略产物：`build/p1-data-evidence.jsonl`（只含固定诊断）、`build/p1_device_probe.py`；构建日志另保留于本机临时目录。三平台账号、原有缓存、平台歌单及其他人的改动均保留。

最终收尾：手机QQ7项队列停在原索引1、5,000ms，playing/wanted/buffering均false、无failure，repeat=OFF、shuffle=false、injectedFailures=0；QQ歌词32行、activeIndex=1且source/位置匹配。QQ/网易云/酷狗均已验证连接，保存队列与recent分别保持；没有清数据、断开、换号、修改手机网络或平台歌单。

## 同日酷狗歌词兼容修复与补验

按用户要求继续定位。电脑公开请求与手机均重现官方 `/v1/search` 返回HTTP200、空响应体；电脑以签名开/关及设备头有/无的小范围对照仍为空，未将这些失败解释成无歌词。同一官方HTTPS主机的 `/search` 返回正常JSON候选及 `/download?fmt=lrc` 内容。生产仅切换搜索路径为 `/search`，原hash、album_audio_id、空keyword、签名及下载参数保持，不增加关键词猜配、备用歌曲、自动重试或新依赖。白名单只允许精确 `/search`、`/download`，移除旧歌词搜索路径；HTTP/其他主机/路径/端口/片段仍拒绝。

手机覆盖安装后，当前酷狗搜索首项仅读歌词即得到TIMED、63行，无音频取流；实际起播首三项依次取得63/46/53行，均sourceKey相等、无歌词/播放器错误、injectedFailures=0。首项暂停seek到30,000ms时activeIndex=13，再到5,000ms时=2，均与实际position计算一致。NETEASE仅读歌词35行、QQ仅读歌词46行均成功，未改这两平台协议。没有新增用户听音确认，歌词内容及歌曲标识只在进程内比较，不落盘或输出。

最终 `./build.ps1 -Test -Lint` 成功：216项JUnit（新增3项），0 failures/errors/skipped；六模块Lint0errors，warnings保持app37/accounts1/playback2/酷狗1/QQ1/网易云5。新增回归覆盖精确HTTPS路径白名单、成功空候选与异常响应的区分、空体错误传播且不重试，以及实际来源标识/无账号凭证/标准LRC请求。APK SHA-256 `f9bce16cd2d2ac5ba3e547fc019d6bb62b07d39596d275b4270768d99ae6100a`，v2签名通过，已覆盖安装；重新生成release合并清单，正式Application且无debug组件，未生成发布版。

DS deepseek_flash low独立上下文完成只读PC调用差异检查，实际交付文件行号和profile/clienttime/duration候选原因。主代理的HTTP对照及真机验证证实路径切换可用，不把DS候选推断写成已证实根因；未让DS运行adb或修改文件，交付后关闭。保留账号、缓存、平台歌单及cc改动；近期播放因本轮真实起播新增/更新时间属于预期行为。此修复只证明本机已测歌曲的标准歌词可用，不保证酷狗所有曲目均有歌词或完成正式前端验收。

最终包安装后再次验证首项63行及30秒/5秒同步，并再次仅读NETEASE35行、QQ46行；安装APK散列与本地相等，`git diff --check`通过。收尾KUGOU单项队列暂停在5,000ms，歌词TIMED、activeIndex=2，source/位置匹配，failure=NONE、OFF/false、故障注入0；三平台均已验证连接。
