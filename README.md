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

已接入第三阶段的 Vue 最小只读状态预览，现有原生 UI 仍然保留；
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

Vue 页面目前只显示播放状态、进度、音量和原生曲目标识。播放操作仍在原生窗口
进行。不加 `--cef-vue` 可打开第二阶段测试页面。尚未开始第四阶段播放命令迁移。
