# 三平台账号接入

可同时登录酷狗概念版、网易云音乐和 QQ 音乐，歌单合并展示并标注平台。头像中的账号窗口提供三个入口；QQ 使用手机 QQ 扫码确认。退出一个平台只清理该平台的歌单和队列；若当前播放属于该平台，则停止该歌曲。

在线歌曲的搜索、播放、音质、收藏和添加到歌单由所属平台处理，不能跨平台添加。本地歌曲沿用原有限制。仅接受官方完整音频，不使用替代音源，不将试听或降级音质当成完整权限。会员、版权及隐私设置可能限制可用功能。

凭证沿用 Windows DPAPI 加密的 `kugou-session.dat`，兼容旧账号。React 只接收平台、登录状态和名称。Python 的 `qq-device.json` 仅保存设备身份。服务使用私有标准输入/输出，不监听 HTTP 端口；原生 Job Object 在退出时回收整个进程树。

## 构建与固定材料

运行 `./setup-cloud.ps1` 后重建 `cef_host` 或运行现有 `build.ps1`。服务和运行时部署到 `build/services`、`build/runtime`，用户无需安装 Python。`-SkipInstall` 仅跳过 npm 安装，仍校验 Python。`services/setup_python_runtime.py` 用标准库验证及解包固定 wheel，不执行安装钩子。

- 网易云登录流程参考 [YesPlayMusic 固定版本](https://github.com/qier222/YesPlayMusic/tree/df075cca247eab7bf8686155cb8cc9a1f4c7e271)，未复制 Vue 页面。
- 实际使用 [NetEase API 固定源码](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/tree/2aab9957dfd5231e5b192aecdb177f93ace4c92e)，MIT。所需源码、LICENSE 和修改说明在 `services/vendor/NeteaseCloudMusicApi-2aab9957dfd5231e5b192aecdb177f93ace4c92e/`；移除替代音源、代理和 checkToken 集成。
- QQ 使用 [QQMusicApi 固定源码](https://github.com/L-1124/QQMusicApi/tree/3fc57f02e4eddb68a069bba4e0e12a7893f2a814)，分发包 `qqmusic-api-python==0.8.1`，GPL-3.0-or-later。Python 源码随运行时以 `.py` 文件分发；桥接源码为 `services/qq_bridge.py`。用户已确认随程序内置。上游 README 另声明研究学习、非商业使用，计划商业分发前应向上游核实该声明与许可的关系。
- 官方 [Python 3.13.7 发布页及匹配源码](https://www.python.org/downloads/release/python-3137/)，PSF 与历史许可证保存在 `licenses/python/Python-LICENSE.txt`。
- Python 全部 22 项精确依赖、官方 wheel URL 和 SHA-256 在 `services/python-runtime.json`，可校验锁文件在 `services/python-requirements.lock`。每项对应源码可从 `https://pypi.org/project/<name>/<version>/#files` 获取同版本 source distribution。orjson 的 MPL 覆盖部分保留通知，源码入口为 [orjson 3.12.0](https://pypi.org/project/orjson/3.12.0/#files)。cryptography 使用 Apache-2.0/BSD 许可选择，paho-mqtt 使用 BSD-3-Clause 选择，qh3 使用附带 BSD-3-Clause 文本。各包许可证、NOTICE、作者材料保存在 `licenses/python/`。
- Node 精确生产依赖在 `services/package-lock.json`，许可证清单与文本在 `licenses/cloud-node/`。fzstd 0.1.1 为 MIT，axios 1.20.0、form-data 4.0.6 为 MIT，node-forge 1.4.0 采用 BSD 许可选择。

应用发布仍需匹配的项目源码、依赖获取和构建脚本，不替代现有 CEF/FFmpeg 源码交付要求。当前修改尚未公开提交，不能宣称旧 Release 源码入口匹配本构建。

## 验证范围

2026-10-06：修正 QQMusicApi 0.8.1 的 Cookie 快照读取。QQ 扫码确认响应可能包含空值 Cookie，原来的按名称索引触发 Niquests KeyError；改为读取实际键值对。修改记录在 `licenses/python/qqmusic-api-python/MUSXI-MODIFICATIONS.md`，构建脚本含固定源代码匹配检查及补丁，运行时附带完整修改后源码。

自动测试覆盖多账号会话、旧凭证兼容、平台路由、跨平台写入拒绝、部分同步失败保留数据、网易云取消收藏、试听和降级音质过滤，以及 QQ 扫码状态与凭证设置。真实网络验证检查二维码返回，不记录二维码、Cookie、私有响应。完整账号歌单、音质权限及收藏写入需用户扫码后验证。
