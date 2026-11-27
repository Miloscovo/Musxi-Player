# DSF实际系统文件提供方与跨来源重关联

2026-11-16，Asia/Shanghai。PJF110／Android16，用户授权继续第四批的实际文件提供方开发。保持游戏及其他应用不操作，不改正式UI／生产DSF开关，不提交推送发布；第五、六批暂缓。

## 实际提供方结果

新增设备步骤通过MediaStore创建本轮独占样本，再实际使用系统ACTION_OPEN_DOCUMENT选择文件并核对返回authority；测试使用正式AndroidLocalAudioDocuments、AppLocalAudioRepository、SQLite和复制实现，独立数据库／目录／owned授权偏好。预览／导入能力只在这个独立文档适配器中启用，应用正常gate未开启。

最终一次完整设备执行passed，约5.2秒，无失败重试。两个入口实际返回不同提供方，未将同一来源按入口重复计数。

| 检查 | 内部存储 | 下载内容 |
| --- | --- | --- |
| 实际authority | com.android.externalstorage.documents | com.android.providers.downloads.documents |
| 实际provider包 | com.android.externalstorage | com.android.providers.downloads |
| 返回读授权／可持久授权flags | 均有 | 均有 |
| 实际文件描述符定位 | lseek到100成功 | lseek到100成功 |
| DSF预览 | DSD64、标签正确、独立gate下playbackSupported=true | 同左 |
| 引用读取 | 导入后持久授权存在；关闭并重开隔离SQLite仓库后正式resolveForPlayback成功 | 通过跨来源relink后正式resolveForPlayback成功 |
| COPY | 副本完整SHA-256与固定样本相同，删除条目后副本回收 | 同左 |
| 跨提供方重关联 | 作为原来源 | 作为新来源，原条目ID、自定义名称、7秒进度及8秒书签保留 |

仓库重开属于同一应用进程内的SQLite／scope重建，不冒充应用进程重启、整机重启或长时授权验证。resolveForPlayback只确认来源解析，本轮没有启动播放器或新增听感结论；[此前真实音乐](backend-dsf-real-music-device-20261116.md)和[切换／一小时播放](backend-dsf-switch-soak-20261116.md)继续独立保留。

样本为[原创DSD64双声道45秒工程样本](backend-dsf-multirate-samples-20261115.md)，31,752,472字节，SHA-256 `f3dc1f6813402e4d1a382cafa82ab1446782bbe7a6bf1ab03c2629a95d59fa5b`。主机固定副本经完整散列核实；设备通过MediaStore写入Download/Musxi-Actual-20261116，在打开选择器前再次整文件核对。样本行URI只记录在私有owned marker，不写公共结果；结束只删除该精确MediaStore行及本轮文件，未枚举／改动其他用户下载。

## 云端提供方发现与边界

只读检查当前安装清单：百度网盘com.baidu.netdisk、移动云盘com.chinamobile.mcloud、系统云服务com.heytap.cloud和夸克com.quark.browser，均未发现标准android.content.action.DOCUMENTS_PROVIDER注册。全局provider表包含外部存储、下载、媒体、归档、MTP及少数工具类provider，但没有上述云盘入口。安装了云盘、可在应用内下载或分享文件，不代表它向系统文件选择器提供可引用文档。

本轮没有登录或打开云盘、上传样本、安装新应用、读取其内部数据或把工具类provider当云盘。也不尝试把云盘文件下载到本地后宣称测过远端来源。结论仅为当前系统两个实际本地provider通过；[受控Android管道](backend-dsf-pipe-provider-device-20261116.md)覆盖顺序读取行为，两者不能合并成真实云盘网络兼容性通过。

后续云端样本应先确定一个实际暴露在系统“打开文件”位置列表中的提供方，再由用户在该服务准备可用DSF并完成必要登录／选取。可使用上述原创工程样本，避免要求上传私人音乐；选择前先明确服务及上传意愿。若应用只提供分享临时URI而无持久授权，应作为独立的临时导入需求评估，不能伪造持久flags或用任意路径绕过SAF。

## 实现、验证和保全

仅新增DsfActualProviderDeviceTest及debug选择桥的两个固定operation：dsf_actual_external／dsf_actual_downloads；正式main、解码器和接口未修改，无新增依赖。设备测试核固定cycle marker／样本长度，在独立库操作；MediaStore使用pending行写入后发布，marker立即记录owned URI，finally按精确URI删除。系统选择器节点按包过滤，旧桥接页先结束，未按悬浮游戏位置点击；本轮未出现此前选择器生命周期失败。

统一验证`.local/artifacts/verification/20261116T134947893Z-27516/`passed：2,971项全部通过，0失败／错误／跳过；Lint0错误152警告，主／测试包编译与源码冻结通过。设备测试单独执行，JUnit及JSON均passed。最终792份src／构建／scripts文件与该manifest完全一致，之后仅补文档及本地主机材料。

- 实装主包SHA-256：`873f29855df61a9321c5b31b905eed1a150f9e780e1f19318b44e30cac81193a`。
- 实装测试包SHA-256：`621b872f77215d920e453912398974da3649f81ab2ad476fa482736cdb1cd828`。

保留数据覆盖安装并读回核实身份，安装前后及设备结束后原七表一致（progress按惯例仅忽略updated_at_ms），integrity_check=ok，原文件／授权偏好一致；本地和两个平台journal字节未变，无需回写。设备持久授权集合与开始相同；测试MediaStore行删除、独占目录清空后移除，独立SQLite及私有样本／marker已清理，Musxi恢复初始无进程。

证据`.local/artifacts/dsf-actual-provider-device-20261116/`含发现、原始设备日志／JSON、包、安装／来源冻结和preservation。私有基线`.local/checkpoints/dsf-actual-provider-device-20261116/`仅供本轮比对，不恢复整库或覆盖后续用户变化。手机USB供电，未测耗电；正式DSF入口仍关闭，剩真实云端样本、独立耗电、正常生产gate／release路径，未启动第五六批。
