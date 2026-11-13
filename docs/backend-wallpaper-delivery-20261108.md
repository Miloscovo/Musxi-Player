# 自定义静态壁纸后端

状态：2026-11-08 本地后端首版完成并通过统一验证。用户同意先做播放器与歌词页，底层兼容全局；CC 前端暂缓，不改现有产品页面。

## 实现范围

- `core.wallpaper`：图片格式与大小约束、受管副本、原子清单、候选/预览/应用/取消、关闭保留、恢复默认、内容哈希与冷读校验、孤儿回收。
- `app.wallpaper`：系统选择结果的本地导入，流式限量、采样解码、EXIF 方向、输出归一；异步串行 facade 暴露 `MusxiApplication.wallpaper`。
- 不新增依赖，不增加业务服务器；壁纸独立于账号、音乐缓存与玻璃开关，未改 CC 页面和播放行为。

接线、字段和错误见 [壁纸合同](frontend-backend-contract-wallpaper.md)。界面尚未展示新能力，底层 GLOBAL 不等于全局前端已实现。

## 参考与采用边界

PC 本地 `e72fba9feb6da22a1b8b207ba2fa07b29b96d36c` 的壁纸文件与此前固定 `87c473f93e13a4628dacfc8feb5586fe423469d7` 无差异。本轮只参考产品流程和边界，Windows WIC/CEF 桥代码不移植。PC 实际挂在整个应用外壳，设置文案写播放器；安卓明确区分作用范围。

- [PC 候选与预览流程](https://github.com/Miloscovo/Musxi-Player/blob/87c473f93e13a4628dacfc8feb5586fe423469d7/frontend/src/composables/useWallpaper.ts)：候选、确认、取消、默认与失败回退。
- [Glasmio 背景层约定](https://github.com/NEORUAA/Glasmio/blob/c4c1b68efc4b94c3f4dcf04e9d01fca178480d57/docs/components.md)：壁纸放独立背景槽，避免随前景滚动/遮罩；仅机制参考，不复制 Apple 壁纸资源。
- 本地 LoveACE 的头像导入提供内部副本的参考，未复制其代码；所查 MeiloX 为封面取色流体背景，没有完整自选壁纸链路。不因参考增加任何第三方代码或资源。

## 本轮验证

最终统一入口 `.local/artifacts/verification/20261108T011923671Z-31940/summary.json` 为 passed：**1818 项 JVM 测试，0 失败/错误/跳过；六 Android 模块 Lint 0 错误 / 121 警告；主包和 androidTest 包编译通过，源码冻结核对通过**。相对上轮1788项增加30项壁纸测试。Lint增加的3项为平台ExifInterface建议2项及Bitmap.scale风格建议1项；未为消除建议新增依赖。

| 专项 | 数量 | 覆盖 |
| --- | --- | --- |
| 静态容器检查 | 4 | 实际PNG/JPEG、静态WebP容器、动画拒绝、截断、像素/长度限制 |
| 受管存储 | 7 | PNG真实落盘与冷读、篡改、清单损坏、路径链接拒绝、孤儿回收、关闭保留与失败不改清单 |
| 事务状态 | 11 | 候选、草稿、应用/取消、失败保留、过期ID、缩略图/展示失败、关闭与默认 |
| Android图像处理 | 8 | SDK35原生图像解码、透明PNG、JPEG/WebP、大图采样、8种EXIF方向的实际像素、GPS去除、取消/超量读取与临时清理 |

主 APK SHA-256：`a60b85a0ebde523aa1273d71a6260bb9c32d35b04201a5e8c0004b08418bd02c`。

测试 APK SHA-256：`8036b20fd6a6661716ba8e306fe8faf9d25b69e4011ad8b34ca159890f8a8ef6`。

新增 `WallpaperDeviceTest` 两项隔离本地专项已编译，使用生成图片和UUID临时目录，不操作真实壁纸、账号、队列或播放。起止两次 `adb devices -l` 均无连接设备，本轮没有覆盖安装或实机验收；系统Photo Picker往返、API26实际设备、内存与视觉/歌词对比度仍待接线后验证。

首轮 `.local/artifacts/verification/20261108T005755990Z-21744/` 为 failed，不能算通过：新图像测试2项失败。一项揭示Windows原生图像测试中通过文件名解码残留文件句柄，现改显式流并关闭；另一项是输出EXIF可能为0（未定义）而非1（正常），检查改为接受两种不旋转值，同时逐一检查8种方向实际像素和GPS清除。修正后先在共用构建锁内运行壁纸27项定向检查通过，再追加3项边界回归并完成上面的最终全量验证。

定向脚本、日志与清理状态说明保存在 `.local/artifacts/wallpaper-backend-20261108/`。首轮遗留的4个系统JUnit测试临时目录只含合成图片；递归清理被自动审批返回 `blocked by policy`，未提供更具体原因，本轮保留并记录，未改称已清理。项目临时脚本归档后清理。

未提交、推送或发布；保留原有歌单、歌词后端和CC未提交页面改动。
