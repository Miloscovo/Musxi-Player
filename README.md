# Musxi Player

一款使用AI开发的简洁，开源的支持第三方的音乐播放器

## 目前已实现功能

- 支持手机扫码登录酷狗概念版账号
- 同步用户收藏的歌曲和歌单
- 显示歌曲和歌单的封面
- 显示用户头像
- 支持搜索酷狗概念版中的歌曲、收藏歌曲、将歌曲添加至歌单
- 登录自动领取 VIP
- 可循环切换的主题，包括浅色、深色、半透明主题

## Web UI 迁移进度

音频后端已完成 A1 隔离：本地文件与云端缓存统一通过 `IAudioBackend`
播放，当前实现仍为 MCI。新增 Stop/Unload 语义及独立后端测试，尚未接入
FFmpeg/WASAPI。见[音频后端迁移说明](docs/audio-backend-migration.md)。

第六阶段已接入独立测试版窗口和安装包流程，现有原生 UI 仍然保留；
`frontend/phase2` 是独立的 HTML/JavaScript 状态预览页面。

新前端采用 **Vue 3 + TypeScript + Vite**，使用 Composition API 和
`<script setup lang="ts">`。调用方向为：

```text
Vue 组件 → Composable / 可选 Store → Native API Client
→ CEF IPC / Native Bridge → C++ Application Service → C++ Core
```

Core/Application 保持 C++17，CEF Host/Bridge/wrapper 独立使用 C++20。
播放器真实状态与音频、队列、媒体库逻辑由 C++ 管理；Vue 只维护界面状态和
原生状态的显示映射。页面重新加载后通过 Native API 重新读取状态。

第三至第六阶段依次为：Vue 最小界面和只读客户端、播放命令与事件、业务页面
渐进迁移、整合打包与受控切换。完整边界、构建命令和验收要求见
[UI 迁移计划](docs/ui-migration.md)。

构建并验证 Vue 预览：

```powershell
./build-cef.ps1 -CefRoot 'D:/develop/cef_binary_152.0.6+g708dc14+chromium-152.0.7977.83_windows64' -Vue -Test
./build/cef-msvc/src/cef/Release/MusxiPlayerWeb.exe --cef-vue
```

Vue 页面支持搜索分页、歌单浏览、扫码登录/退出、同步歌单、封面、右键收藏/取消
收藏、添加到歌单、选曲播放和切歌。暂停、继续播放、进度和音量由原生事件同步。
下载请求在 C++ 中跟踪为等待、完成、失败或取消；提交下载不代表播放成功。
开发构建不加参数仍打开第二阶段测试页面；`--test-app` 启动单窗口测试版。
测试安装包使用独立的 MusxiPlayerTest.exe，双击即进入 Vue；正式版默认入口未切换。

测试版支持浅色、深色和偏黑的桌面半透明主题（整窗透明，文字及封面也参与混合）。
账号及云端操作沿用原生适配器，真实扫码和账号写入需要在登录后验收。

## 第六阶段测试安装包

```powershell
./package-test.ps1 -CefRoot 'D:/develop/cef_binary_152.0.6+g708dc14+chromium-152.0.7977.83_windows64'
```

产物：`dist/MusxiPlayer-Test-0.1-Setup-x64.exe`，附 SHA256 文件。
安装名“Musxi Player 测试版”，与旧版并存，不覆盖旧版。需要先按原有
`setup-cloud.ps1` 准备 Node 和云服务依赖；安装后的用户不需要 Node 开发环境。

测试版数据位于 `%LOCALAPPDATA%/MusxiPlayer-Test`；旧版仍使用 `MintPlayer`。
首次需要重新登录，云端收藏和歌单操作仍会修改所登录账号的数据。
卸载测试版保留账号和缓存目录，便于重新安装；不会删除旧版数据。

关闭窗口停止播放并退出。界面崩溃自动恢复一次，随后提供原生重试/关闭入口。
顶部标题旁空白和侧栏空白可拖动窗口，边缘可缩放；没有额外拖动条。
非输入框、按钮或对话框聚焦时，空格暂停/继续，左右方向键前后跳转 5 秒。

测试包等待真实账号、云端播放及多显示器 DPI 验收；通过后才决定默认 UI 切换。
