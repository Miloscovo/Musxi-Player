# Musxi Player

一款以 C++ 为核心、React 为界面的支持第三方的音乐播放器。

## 已实现

- 手机扫码登录、同步收藏歌曲和歌单，显示用户头像、歌曲及歌单封面
- 搜索歌曲、收藏或取消收藏、添加歌曲到歌单
- 登录后检查并领取当日概念版 VIP 权益
- 浅色、深色、半透明主题
- FFmpeg 解码与 WASAPI 音频输出，支持暂停、继续、切歌、Seek 和音量调整
- 本地音乐文件夹导入、文件夹歌单和全部本地歌曲整合
- 播放列表、下一首播放、顺序播放、随机播放、单曲循环及在线音质选择
- 系统托盘及后台播放；重启后恢复歌曲、播放进度和队列，保持暂停
- 可完全收起的侧栏、shadcn/ui 控件和统一的主题悬停效果

## 架构与构建

```text
React + TypeScript + Vite
  → Native API Client → CEF IPC/Bridge (C++20)
  → Application/Player (C++17) → FFmpeg/WASAPI (C++17)
```

React 是默认界面，C++ 持有真实播放状态和云端媒体库状态。Application 保留一个不可见的 Windows 消息窗口，用来驱动后台轮询、事件通知和资源清理；点击关闭按钮可选择退出或最小化到托盘，最小化后继续播放。Core 可在不配置 CEF/FFmpeg 的情况下独立编译。

以下命令在仓库根目录的 PowerShell 中执行。仓库可以放在任意目录，示例不依赖开发者的本地盘符。

先安装 Windows x64 构建环境：Visual Studio 2026（包含 MSVC C++ 工具和 Windows SDK，并具备适用许可）、支持该 Visual Studio 版本的 CMake，以及 Node.js 24 和 npm。确保 `cmake.exe`、`node.exe` 和 `npm.cmd` 可从命令行调用；当前脚本默认使用 `Visual Studio 18 2026` 生成器。

准备 SDK 和服务依赖：

1. 手动下载并解压 CEF Windows x64 Standard Binary Distribution。当前验证版本为 CEF `152.0.6+g708dc14` / Chromium `152.0.7977.83`，版本及来源见 [CEF 构建审计](licenses/CEF-Windows-Build-Audit.md)。下方假设解压目录为 `build/deps/cef-sdk`，该目录内应直接包含 `cmake/FindCEF.cmake`；也可把 `$cefRoot` 改为自己的 SDK 目录。构建脚本不会自动下载 CEF。
2. 当前播放 SDK 已改为固定源码自行构建的 LGPL-2.1-or-later 共享库，目录约定为 `build/deps/ffmpeg-musxi-a5923073-msvc`。准备与当前清单匹配的 `MusxiPlayer-FFmpeg-SDK-0.2.zip`，首次运行 `./setup-ffmpeg.ps1 -SdkArchive <SDK ZIP 路径>`，之后可直接运行 `./setup-ffmpeg.ps1` 校验。该 ZIP 已在本地生成，当前尚未公开发布。也可使用 `build-ffmpeg.ps1` 从源码重建，完整说明见 [发行核查记录](licenses/Release-Readiness.md)；不同工具链或路径的重建结果需要重新记录发行哈希，不能假定逐字节相同。
3. 运行 `setup-cloud.ps1` 准备本机服务依赖，再将服务文件放入 `build/services`。当前 `-Package` 分支仍有旧原生程序路径检查，因此此处使用显式复制，不调用该分支。

```powershell
$cefRoot = (Resolve-Path './build/deps/cef-sdk').Path
./setup-ffmpeg.ps1
$ffmpegRoot = (Resolve-Path './build/deps/ffmpeg-musxi-a5923073-msvc').Path
./setup-cloud.ps1

New-Item -ItemType Directory -Path './build/services' -Force | Out-Null
Copy-Item './services/bridge.cjs', './services/package.json', './services/package-lock.json' -Destination './build/services' -Force
Copy-Item './services/node_modules', './services/vendor' -Destination './build/services' -Recurse -Force

./build.ps1 -CefRoot $cefRoot -FfmpegRoot $ffmpegRoot
./build/cef-msvc/src/cef/Release/MusxiPlayerWeb.exe
```

`build.ps1` 会安装前端依赖、构建 React 页面和原生目标。运行测试时额外指定 `-Test -FixtureTool <带 libmp3lame/libvorbis/libopus 编码器的 ffmpeg.exe>`；该 CLI 只生成测试样本，不随应用分发，当前精简 SDK 不包含编码 CLI。CEF 构建会把已准备的 `build/services`、`build/runtime` 和界面资源部署到程序目录。请使用上方 CEF 程序入口；`setup-cloud.ps1` 末尾的 `build/MusxiPlayer.exe --kugou` 提示是旧入口说明。

`--cef-preview` 仅用于打开早期 IPC 技术验证页；`--cef-smoke` 用于自动检查。构建要求 Windows x64/MSVC。CMake 中的 `music_core` 与 `music_application` 仍固定为 C++17，CEF 层独立使用 C++20。

云端适配器的离线测试可单独运行：

```powershell
cd services
npm.cmd ci
npm.cmd test
```

这些测试使用模拟数据，不会登录账号或调用酷狗接口。

## License

Copyright (c) 2026 Miloscovo.

Musxi Player 自身代码采用 **GNU General Public License v3.0 or later**，SPDX 标识为 **GPL-3.0-or-later**。你可以依照自由软件基金会发布的 GNU GPL 第 3 版，或自行选择任何后续版本，重新分发和修改本项目。完整许可证正文见 [LICENSE](LICENSE)。

第三方组件继续遵循各自的许可证；许可证、版权声明和分发注意事项见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

### 浏览器离线演示

在 `frontend/` 执行 `npm ci` 和 `npm run dev`，打开 `http://localhost:5173/?demo`。仅明确传入 `?demo` 且没有 CEF bridge 时启用；不会覆盖桌面应用、托盘或 smoke test。演示支持歌单、搜索、队列、收藏、播放模式、音质和虚拟本地导入。歌曲进度是模拟的，不播放真实音频、不读取真实文件、不登录真实账号、不请求在线服务；刷新页面后重置。
