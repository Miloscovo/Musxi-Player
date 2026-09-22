# Musxi Player

一款使用AI开发的简洁，开源的支持第三方的音乐播放器

## 目前已实现功能

- 导入本机音乐文件（MP3、WAV、WMA），重复文件自动跳过
- 本地音乐库列表保存到本机，启动时自动恢复
- 记住上次播放的歌曲和播放进度，下次打开可继续播放
- 支持手机扫码登录酷狗概念版账号
- 同步用户收藏的歌曲和歌单
- 显示歌曲和歌单的封面
- 显示用户头像
- 支持搜索酷狗概念版中的歌曲、收藏歌曲、将歌曲添加至歌单
- 登录自动领取 VIP
- 可循环切换的主题，包括浅色、深色、半透明主题

## 构建

本项目需要 Windows 10 或更高版本、CMake 3.20 或更高版本，以及安装了“使用 C++ 的桌面开发”工作负载的 Visual Studio 2022。

使用以下命令构建 64 位 Release 版本：

```powershell
cmake -S . -B build -A x64
cmake --build build --config Release
```

构建完成后，可执行文件位于 `build\Release\MusxiPlayer.exe`。

## 测试

云端接口适配层需要 Node.js LTS。安装依赖并运行全部离线测试：

```powershell
cd services
npm.cmd ci
npm.cmd test
```

这些测试使用模拟数据，不会登录账号或调用酷狗接口。
