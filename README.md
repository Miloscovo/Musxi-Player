# Musxi Player

一款以 C++17 为核心、React 为界面的 Windows 音乐播放器。目前接入酷狗概念版账号与云端歌单；此接入使用第三方接口，0.2 仅供本机或私下测试。

## 已实现

- 手机扫码登录、同步收藏歌曲和歌单，显示用户头像、歌曲及歌单封面
- 搜索歌曲、收藏或取消收藏、添加歌曲到歌单
- 登录后检查并领取当日概念版 VIP 权益
- 浅色、深色、半透明主题
- FFmpeg 解码与 WASAPI 音频输出，支持暂停、继续、切歌、Seek 和音量调整

## 架构与构建

```text
React + TypeScript + Vite
  → Native API Client → CEF IPC/Bridge (C++20)
  → Application/Player (C++17) → FFmpeg/WASAPI (C++17)
```

React 是默认界面，C++ 持有真实播放状态和云端媒体库状态。原生 GDI 界面与 MCI 后端已从源码和构建中移除。Application 保留一个不可见的 Windows 消息窗口，用来驱动后台轮询、事件通知和资源清理；关闭 React 窗口会退出并停止播放。Core 可在不配置 CEF/FFmpeg 的情况下独立编译。

前端已从 Vue 迁移为 React，复用现有 CSS 和 Native API。为保持 CEF 加载与打包兼容，现有 `MUSXI_BUILD_VUE_UI`、`vue_ui` 和 `ui-vue` 名称暂时保留；这些名称不代表仍使用 Vue。

准备好 CEF SDK、已校验的 LGPL 共享版 FFmpeg SDK，以及 `setup-cloud.ps1` 所需本机服务依赖后，执行：

```powershell
./build.ps1 -CefRoot 'D:/develop/cef_binary_152.0.6+g708dc14+chromium-152.0.7977.83_windows64' -FfmpegRoot 'D:/Music/build/deps/ffmpeg-n9.0.2-3-ga5923073bf-win64-lgpl-shared-9.0' -Test
./build/cef-msvc/src/cef/Release/MusxiPlayerWeb.exe
```

`--cef-preview` 仅用于打开早期 IPC 技术验证页；`--cef-smoke` 用于自动检查。构建要求 Windows x64/MSVC，不再构建 MinGW/MCI 版本。CMake 中的 `music_core` 与 `music_application` 仍固定为 C++17，CEF 层独立使用 C++20。

## 0.2 测试安装包（暂缓更新）

A6 当前只交付源码和构建验证。待需要安装包时再运行以下命令；`dist` 中已有安装包生成于本轮最后一次源码调整之前，不代表当前源码。

```powershell
./package.ps1 -CefRoot 'D:/develop/cef_binary_152.0.6+g708dc14+chromium-152.0.7977.83_windows64' -FfmpegRoot 'D:/Music/build/deps/ffmpeg-n9.0.2-3-ga5923073bf-win64-lgpl-shared-9.0'
```

届时产物为 `dist/MusxiPlayer-0.2-Test-Upgrade-Setup-x64.exe` 和对应 SHA256 文件。安装包使用原 0.1 版安装标识进行升级，但产品名显示为“Musxi Player 测试版”；它不是正式发布版。先前独立安装的 `MusxiPlayerTest.exe` 及其 0.2 测试版仍可并存。

新入口使用 `%LOCALAPPDATA%/MusxiPlayer`。首次启动只从 `%LOCALAPPDATA%/MusxiPlayer-Test` 复制登录会话与歌单快照一次，不复制临时歌曲缓存，不改动旧 `%LOCALAPPDATA%/MintPlayer` 数据。后续退出登录不会再次导入旧会话。两个安装实例若登录同一个账号，收藏与歌单更改仍会作用于同一云端账号。

窗口支持拖动、缩放及浅色/深色/半透明主题。界面崩溃时自动恢复一次，再失败会显示重试入口。已在本机验收 FFmpeg 与旧 MCI 的播放行为；跨显示器 DPI、物理设备拔插、全部账号可用编码及长时间播放仍待专项验证。

历史迁移记录见 [UI 迁移计划](docs/ui-migration.md) 和 [音频后端迁移记录](docs/audio-backend-migration.md)。第三方许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## License

本项目采用 [MIT License](LICENSE)。
