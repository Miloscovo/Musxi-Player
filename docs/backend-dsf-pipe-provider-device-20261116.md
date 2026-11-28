# DSF受控Android管道来源实机验收

2026-11-16，Asia/Shanghai。PJF110／Android16。用户连接手机授权继续第四批，并明确忽略后台及右上角游戏；未关闭、调整游戏或修改蓝牙／音量／系统策略。第五、六批暂缓，正式DSF开关关闭，不接正式UI、不提交推送发布。

## 结果与范围

使用debug专用DocumentsProvider及真实系统ACTION_OPEN_DOCUMENT选择，取得实际结果flags，调用正式AndroidLocalAudioDocuments、AppLocalAudioRepository、SQLite及内容寻址复制实现。测试使用独立数据库、文件目录、CoroutineScope及带前缀的owned授权偏好；ContentResolver和授权API仍是实际Android实现。没有把测试条目放入用户曲库，没有启动音乐或请求音频焦点。

| 检查 | 最终同包实机结果 |
| --- | --- |
| 读取能力 | 实际ParcelFileDescriptor管道，lseek返回ESPIPE |
| 只读预览 | 正确识别DSD64，playbackSupported=false |
| REFERENCE | READ_FAILED／COPY_REQUIRED，无条目和持久授权残留 |
| 已知大小COPY | 成功，私有副本整文件SHA-256匹配，尾部ID3标题正确 |
| 未知大小COPY | 成功，副本与同一完整原件匹配，尾部标签正确 |
| 慢速COPY在途取消 | 观察到已复制8,192字节时取消，无条目／受管或临时文件残留 |
| 未知大小截断COPY | SOURCE_CHANGED，无条目／文件残留 |
| 管道线程与授权 | 所有写入线程退出，持久授权集合与开始时一致 |

完整成功步骤约7.2秒，原始JSON及JUnit均passed。复制后通过正式resolveForPlayback解析私有file URI并核对文件字节；本轮不执行播放器，不宣称新增听音或播放通过。实际云盘账号、网络、远端延迟和文件版本变化没有在本轮覆盖；这是真机受控管道证据，不是某网盘兼容性证明。

样本为此前[原创DSD64双声道45秒工程样本](backend-dsf-multirate-samples-20261115.md)，31,752,472字节，SHA-256 `f3dc1f6813402e4d1a382cafa82ab1446782bbe7a6bf1ab03c2629a95d59fa5b`。只复制固定样本到本轮私有目录，不读取任意路径、不操作用户原件。管道截断及慢速是明确的受控条件，不冒充实际网络失败。

## 实现和隔离

新增DsfPipeValidationProvider只位于debug sourceSet，MANAGE_DOCUMENTS保护、grantUriPermissions；固定normal／unknown／slow／truncated四种文档，不接受路径、URL或外部配置。仅当本轮固定cycle marker与指定长度sample.dsf同时存在时显示root和读取文件，结束后删除marker／样本即不可用。写入端遇到短读关闭／取消时退出，最终活动writer数为0。

LocalAudioPickerValidationActivity新增固定管道选择操作，使用系统DocumentsUI和固定root URI，保留临时授权到导入结束。设备测试按包名过滤可访问节点，启用交互窗口检索，每次选择前结束旧桥接页；不按游戏位置点击。测试没有修改正式main源码、生产gate或解码器，也没有新增依赖。

## 原失败和纠正

1. 首次设备启动被JUnit初始化拒绝：runBlocking表达式推断的返回类型不是void。尚未执行导入；显式Unit修正。
2. 初次选择器定位超时，尚未导入。改用root URI及交互窗口节点检索。
3. 旧DocumentsUI任务复用，点击后结果没有返回新桥接页；保留失败，重建Musxi自己的任务。
4. 同包已实际确认ESPIPE和REFERENCE拒绝，但下一次选择复用尚存桥接页，没有再次onCreate；整段仍failed。每次选择前finish旧桥接页，随后编译／Lint检查并覆盖测试包，最后完整通过。

没有把部分通过合并成整段成功；attempt1～4原日志／JSON保留，最终为独立完整步骤。DeepSeek只读审查路由127.0.0.1:8790连接被拒，502，无交付；主代理审查，不计独立审查通过。

## 构建、包与保全

官方统一验证`.local/artifacts/verification/20261116T131544578Z-23744/`，2,971项全过、0失败／错误／跳过，Lint0错误152警告；主包／设备测试包编译和源码冻结通过。其后仅一个设备测试文件补桥接页生命周期处理，经build.ps1 -Test -Lint再次通过（bridge-recheck-build.log），并用对应测试包完成实机整段。791份src／构建／scripts文件中790份与官方manifest完全一致，唯一变化的测试文件另记散列；不宣称最终测试包就是官方冻结时的测试包。

- 实装主包：`32e4bc1622453902152d25a26b51735f680089bc306c5b952e52c4699d8d626e`。
- 最终实装测试包：`45d1d543cc710e725bc35a0697598d827e8776b2b1b3bba4310eb90755392e2e`。

两包保留数据覆盖安装并核对实装SHA-256。基线在`.local/checkpoints/dsf-provider-device-20261116/`，原库七表逐字段相等（progress按惯例忽略更新时间）、integrity_check=ok，原受管文件、授权偏好及本地／两个平台journal字节均未改变；无需回写原journal。测试持久授权与开始集合一致，隔离库／目录、固定样本和marker清理，Musxi恢复初始无进程状态；生产gate未启用。

证据`.local/artifacts/dsf-provider-device-20261116/`含所有失败、最终原始报告、包、源码比对、基线和保全汇总；电脑原音频及游戏不操作。USB供电，没有耗电结论。剩实际文件提供方样本、独立不充电功耗、正常生产gate及release路径；[一小时播放与切换证据](backend-dsf-switch-soak-20261116.md)继续独立保留。
