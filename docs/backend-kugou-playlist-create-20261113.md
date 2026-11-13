# 酷狗新建歌单后端交付

2026-11-13。用户已在PJF110验收CC任务2（歌词转场／工具反馈）和任务9（长按爱心“收藏到”）前端，并要求继续解决酷狗新建歌单限制。本轮不改`ui/`或CC测试，不提交、不推送。

## 当前结论

用户明确授权后，独立设备探针只发送**一次**创建请求，成功创建名为`Musxi创建验证`的公开空歌单。响应`data.info.listid`与同一账号新鲜可写列表中的新普通空歌单精确一致，`data.info.list_create_listid`回显一致；请求前该ID不存在。原3个可写歌单变为4个。没有按名称或列表差集选择目标，没有加歌、删除、改名或修改红心；测试空歌单留给用户自行删除。

据此已实现生产后端创建，并设`capabilities(KUGOU).create=true, rename=true, follow=false, reason=partial_support`。CC现有页面读取该能力即可出现新建入口，无需修改前端。整单订阅仍不开放。本次真实成功只覆盖当前账号的一次空创建探针；最终生产调用和“新建并加入”未再自动发送真实写入，须由用户验收。

## 接口与语义

- 现有`app.playlists.createPlaylist(Platform.KUGOU, name, callback)`接入`KugouLibraryApi.createPlaylist(session, name, current)`，返回带当前账号代次的`EditablePlaylist`。网络仍由账号worker执行，页面不接触凭证或原始响应。
- 名称去首尾空格后1～80个Unicode code point，拒绝控制字符和孤立代理。响应列表沿用既有显示清洗；核对标题时使用相同清洗，避免组合表情中的ZWJ导致误判。返回服务端读回的实际显示名和条目数。
- 写前完整读取当前账号歌单，再固定一次`POST /cloudlist.service/v5/add_list`；`type=0, source=1, is_pri=0, total_ver=0, list_create_gid="", from_shupinmv=0`，query带`last_time/last_area=gztx/userid/token`，复用Android签名，不加`x-router`。
- 只接受根成功状态、零`error_code`（若存在）和`data.info.listid`正整数ID；可选`list_create_listid`若存在必须合法且一致。显式null等畸形回显保守拒绝。`info.status`是歌单资源字段，不当成操作成功码。不使用root/data其他ID、GID、重名或差集替代。
- ID不得出现在写前快照中。写后完整读回必须找到这个ID，且为本人可编辑、非我喜欢、非系统默认的普通歌单，标题一致。快照每页100、最多20,000个、总数稳定且不重复；全流程60秒预算。并发客户端新增歌曲不导致新歌单被误判，返回真实count。
- 全流程绑定账号owner/generation/连接状态。切号、分页不完整、读回失败、身份冲突、网络超时均返回固定错误，不重发创建请求。成功及不确定结果沿用曲库缓存失效；不会把旧账号结果交给新账号。
- `createPlaylistAndAdd(song, name, callback)`复用原协调流程：创建成功后添加歌曲；添加失败仍返回已创建目标和独立`songResult`，重试添加须用此目标，不能重新创建。生产没有持久的跨点击幂等键；网络错误或任何写后拒绝都可能已创建，须刷新列表人工核对，不能据错误文案当作未写入或自动重试。

## 已核对的公开依据

- [MakcRe/KuGouMusicApi固定创建模块](https://github.com/MakcRe/KuGouMusicApi/blob/7ac64e1cdd333ad9bfff644ded82a0ffeef002d1/module/playlist_add.js)：请求形状、Android签名及service路径路由。
- [同版本文档](https://github.com/MakcRe/KuGouMusicApi/blob/7ac64e1cdd333ad9bfff644ded82a0ffeef002d1/docs/README.md)只说明创建参数，没有可靠响应示例；[上游issue #102](https://github.com/MakcRe/KuGouMusicApi/issues/102)说明创建不需`list_create_listid`，后续列表操作使用本人列表ID。本轮以真实响应和本人列表相等核对补齐此缺口。
- 本人列表为`POST /v7/get_all_list`，本地ID为`row.listid`。现行错误与前端合同见[歌单管理合同](playlist-management-contract.md)。沿用MakcRe MIT许可，NOTICE已更新，无新运行时依赖。

## 单次探针与设备证据

入口：[KugouPlaylistCreationProbeDeviceTest](../app/src/androidTest/kotlin/tech/musxi/player/KugouPlaylistCreationProbeDeviceTest.kt)的`observeOneAuthorizedEmptyPlaylistCreation`，只存在于`androidTest`，不进入debug主包或release。只运行这一个方法，必须同时带`allowPlatformWrites=true`和`allowKugouPlaylistCreateProbe=true`，并有用户本轮明确许可。

写前应用私有marker原子create-new，发送前持久保留；`files/kugou-create-probe-once-v1.json`现已存在，**不得删除以重跑**。已有marker、断网、超时或账号切换均不重试。原始响应只留测试进程，导出报告只含白名单结构类型、布尔匹配与计数，不导出凭证、真实ID、原始响应或请求URL。

设备为PJF110，Android16／SDK36，serial `c201e9f0`。探针阶段仅覆盖安装测试包，主包当时仍为任务2／9已验收包。instrumentation为`OK (1 test)`；业务报告为`OBSERVED`、`writeRequests=1`、两个响应ID路径均`matchesCurrentWritableList=true, matchedOrdinary=true, alreadyPresentBefore=false, matchedEmptyCount=true`。安全证据在`.local/artifacts/kugou-playlist-create-20261113/device/`，含授权/防重前检、测试包安装日志、runner输出和`probe-report.json`。runner成功本身不代替业务报告。

## 验证与交付

生产实现已完成统一验证并安装新主包。新增14项创建单测覆盖精确ID、同名、固定签名载荷、名称/组合表情、资源status、畸形/歧义ID、权限、账号变化、丢响应、读回失败及单次写入；这些fixture不替代真实平台结果。只读交叉审查已完成，写后不确定语义及跨点击非幂等边界已记录。

本轮官方统一验证为2,661项通过，0失败／错误／跳过，Lint 0错误／139警告，证据`.local/artifacts/verification/20261113T041114920Z-1744/`；915条源码清单、debug主包和androidTest包在锁内复核一致。新主包SHA-256为`ff8b7d6278a8da45af81612cbd2199f605de97249ae9245358d2b640da77231f`，保留于`.local/artifacts/kugou-playlist-create-20261113/Musxi-kugou-create-backend-debug.apk`。已用`adb install -r`覆盖安装到`c201e9f0`，版本0.2.0／5，首次安装时间保留，启动成功，进程日志无FATAL EXCEPTION；安装证据见`.local/artifacts/kugou-playlist-create-20261113/device/backend-installation.json`。探针准备阶段2,647项的旧数字和测试包仍保留作历史证据，不冒充本轮生产验证。首次生产验证`.local/artifacts/verification/20261113T040749038Z-3240/`因新增测试夹具遗漏Android JSON的checked异常声明失败，修正夹具后完整重跑通过；失败记录单独保留。最终回填4份文档后另查链接与差异，业务源码未再修改。

用户最终实测入口：酷狗歌曲播放页长按爱心→收藏到→新建歌单→命名→新建并加入；检查新歌单出现、曲目实际加入及后续刷新仍在。账号跨样本、平台风控/延迟、并发客户端及真实“创建后添加失败”仍待验，不自动扩大本轮写入授权。
