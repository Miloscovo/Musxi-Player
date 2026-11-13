# 三平台歌曲推荐后端交付 · 2026-11-12

用户11-11讨论后授权实现，跨日完成。范围为登录后的歌曲推荐、按平台/账号缓存、手动刷新与取消、整组/所选位置点播；只做后端，不改`app/src/main/kotlin/tech/musxi/player/ui/`。前端接口与状态语义统一见[推荐合同](frontend-backend-contract-recommendations.md)。不提交、不推送、不安装手机。

## 实现范围

- 网易云与酷狗新增`dailyRecommendations(session)`；QQ新增`personalRecommendations(session)`。三家复用原始凭证与传输，没有新增运行时依赖或业务服务器。空结果和结构故障分开、严格保序/限量/固定错误、迟到守卫。
- QQ已核对固定及当前上游recommend模块，仅找到可证实的猜你喜欢个性电台；返回`PERSONAL_RADIO`，不将它标为每日推荐。网易云/酷狗为`DAILY_SONGS`。
- core提供`RecommendationsBrowser`、快照与有界文件缓存；accounts提供`RecommendationRepository`与浏览器装配；app新增`recommendations`公开入口，`queuePlayback.playSnapshot`进入现有播放链路。
- 缓存按账号隔离、每日/小时新鲜度、30秒发起间隔、取消/换号/清理迟到防护；归入现有曲库元数据缓存维护，不清其他私人数据。不做收听上报、跳过/不喜欢反馈、自研推荐算法或合并排序。

## 协议依据与许可

| 平台 | 固定参考 | 请求合同 |
| --- | --- | --- |
| 网易云 | `NeteaseCloudMusicApiEnhanced/api-enhanced@2aab9957dfd5231e5b192aecdb177f93ace4c92e`，`module/recommend_songs.js`，MIT | WEAPI `/weapi/v3/discovery/recommend/songs`；不设afresh；读取`data.dailySongs` |
| 酷狗 | `MakcRe/KuGouMusicApi@a5a98013cce79fe0ae2ad65fc84b68176ebcfc1e`，`module/recommend_songs.js`/`util/helper.js`，MIT | 标准Android1005/20489签名，POST`/everyday_song_recommend`，router`everydayrec.service.kugou.com`；读取`data.song_list` |
| QQ | `L-1124/QQMusicApi@3fc57f02e4eddb68a069bba4e0e12a7893f2a814`，`recommend.py`/model，GPL-3.0-or-later | 已登录`music.radioProxy.MbTrackRadioSvr.get_radio_track`，id99/num30/from0/scene0/song_ids空；读取`data.tracks` |

保留各模块assets NOTICE与许可全文，新增端点闭集白名单及酷狗独立标准profile，不改既有登录/播放请求语义。字段交叉研究仅作为响应形态依据，未引入其他App代码或资源。公开参考文件留在本轮本地tmp；临时编译产物收尾清理。

## 验证与证据

定向 **369项通过**（core22、accounts3、app15、酷狗147、网易云70、QQ112），0失败/错误/跳过。随后补了读缓存期间手动刷新的守卫及3项时序测试，由下面第一次全量覆盖。

最终在原样隔离快照运行官方`scripts/verify.ps1`，**统一验证通过**：全部 **2,484项测试通过**（core578、accounts55、app1362、playback160、酷狗147、网易云70、QQ112），0失败/错误/跳过；Lint0错误/132警告，debug主包与设备测试包均编译通过，866条源码路径的冻结检查通过。证据已移回主项目`.local/artifacts/verification/20261111T164024180Z-10896/`，原console为本轮目录的`verify-isolated-console.log`。

隔离原因与此前三次主目录尝试保留如下，不把失败入口改记为成功：

1. `.local/artifacts/verification/20261111T161516723Z-8600/`：全部 **2,482项测试通过**（core578、accounts55、app1360、playback160、酷狗147、网易云70、QQ112），0失败/错误/跳过；Lint0错误/130警告，debug主包与设备测试包均编译通过。最终源码冻结检查失败，期间CC改了`ui/MainShell.kt`和`ui/player/LocalBookmarkSheet.kt`。
2. `.local/artifacts/verification/20261111T162415556Z-21448/`：基于最新主工作区重跑，因`ui/player/LocalBookmarkSheet.kt:62–63`的Compose `MutableState`属性委托无法解析`getValue/setValue`而在`:app:compileDebugKotlin`失败。该问题随后由CC补导入修复；本轮没有代改前端或放宽检查。
3. `.local/artifacts/verification/20261111T163057927Z-14124/`：书签页编译已通过，2,482项测试全部通过，Lint0错误/130警告，主包与设备测试包编译通过；但CC期间又修改`ui/localaudio/LocalAudioUiTest.kt`，全树源码冻结再次失败。稍后收尾比对还发现`ui/player/LocalPlayerActionsTest.kt`与启动清单不同，其写入时间晚于该次验证结束，不将它列为本次冻结失败的原因。

为避免主目录继续编辑打断验证，先在`.local/worktrees/README.md`登记，再用`git worktree add --detach`基于HEAD `70778e9`创建`.local/worktrees/recommendations-verify-20261112/`，仅叠加主目录Git枚举到的现有源码与删除状态。复制前/后及目标866条路径散列一致；CC的UI和测试均逐字节保留，不复制ignored账号、密钥、local.properties或构建目录。隔离构建沿用共享锁，不安装、不提交；工作树作为本次可复核现场保留，不作为新的主开发入口。

本轮 **26份后端生产源码/测试/模块NOTICE** 与三次主目录启动清单、最终通过的隔离快照及主目录交付时SHA-256一致。通过结论覆盖这份组合快照，不延伸到CC之后继续修改的前端。日期目录为任务开始日，索引`.local/artifacts/daily-recommendations-20261111/final-summary.json`区分失败尝试、最终通过范围及设备边界；`isolated-snapshot.json`/`isolated-source-files.json`记录原样快照，`delivery-hash-audit.json`记录交付散列核对。修改前快照在`.local/checkpoints/daily-recommendations-20261111/`。交付文档结果在验证后更新，另运行文档检查，不冒充业务源码重新验证。

保留最终通过快照的APK：`.local/artifacts/daily-recommendations-20261111/Musxi-debug-recommendations.apk`，27,999,981 bytes，SHA-256 `10c861f78404752334939c6b16313ce39444ae04bfe883d74efba8ba7a8d6215`。该路径已用最终包替换第一次未冻结包；未安装，三平台推荐NOTICE已核对打包。若CC接入正式页面后安装，应使用双方协调的最新统一包。主目录及隔离日志尾部均出现孤立的`SDK 35 Main Thread`提示，无异常正文/堆栈；Gradle任务和XML测试结果通过，原样保留日志，不据此推断设备行为。

三家的真实账号推荐响应、QQ凭证权限、酷狗字段兼容、与官方App推荐一致性及同包听音均未验证。没有读取电脑上的真实账号凭证、没有调用带真实身份的推荐请求、没有安装或操作手机；协议fixture通过不等于真实接口成功，设备测试包编译不等于执行。待CC接线后协调一次安装窗口，用三平台已登录账号逐家做只读请求及推荐点播。
