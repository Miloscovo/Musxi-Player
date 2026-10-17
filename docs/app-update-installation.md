# 二阶段：下载与覆盖升级

2026-10-17。Codex 负责原生/发布/安装，CC 负责前端；未开新 issue。第一阶段 PR #19 尚待合入，二阶段依赖它。

## 两个 PR

1. A：`feat/update-upgrade-foundation`，发布清单、严格解析、隔离安装规则探针及完整产品升级验收步骤。
2. B：基于 A，原生下载管理、进度、取消、校验和用户主动安装；新增 bridge 契约，CC 负责 TS client、hook、界面、demo 和真实产品 UI 点击验收。

## 已实现：官方安装包清单

`package.ps1` 使用最终安装器生成 `MusxiPlayer-Update-<version>.json`，并将其加入 `dist/release-assets.json` 的默认上传列表。维护者应同时上传安装器和清单，不能上传整个 dist。旧 Release 无清单时，第一阶段浏览器下载入口保持可用。

```json
{"schemaVersion":1,"version":"0.5.0","architecture":"x64","assetName":"MusxiPlayer-Setup-0.5.0.exe","bytes":123456,"sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"}
```

清单最多 4096 字节，资产必须唯一、uploaded、官方仓库精确 HTTPS URL；字段严格绑定已选 release 和 installer。SHA-256 为 64 位小写十六进制。清单基于官方 GitHub HTTPS 发布来源的信任，验证文件一致性；它不是数字签名，也不提供独立于 GitHub 账号的发布者身份验证。

本地缓存保留清单资产身份；下载开始时仍需重新下载清单，不从本地偏好文件信任可执行文件哈希。清单失败不会修改第一阶段版本检查结果。

## 安装验证分层

安装器已使用固定 AppId `A696A45D-3218-45C2-A565-EAC5642B976E`、per-user 安装目录、`CloseApplications=yes`、交互安装后的启动选项。profile 在独立 `%LOCALAPPDATA%/MusxiPlayer`，不是安装目录。旧独立测试安装器的身份及目录不同，本轮不迁移它。

### 隔离规则探针

```powershell
./scripts/test-upgrade-installation.ps1 -Compiler <ISCC.exe> -OutputDirectory <全新专用目录> -Execute
```

提取生产安装器的 Setup 指令，替换为随机独立 AppId 和临时目录，生成两个含惰性文件的安装器，验证 0.4.0 → 0.5.0 替换、Unicode 自定义路径、路径记忆、卸载身份、独立 profile 哨兵。结束卸载测试身份，保留日志及样本；不会运行正式播放器，也不清理真实账号数据。无 `-Execute` 只编译探针。

这不等于真实产品覆盖升级成功。探针不覆盖播放、CEF/Node/Python 文件占用、数据库迁移或真实登录。

2026-10-17 本机已执行探针并通过：真实 Inno 安装 0.4.0 → 0.5.0、Unicode 自定义目录记忆、同一卸载身份、哨兵不变和卸载清理。证据 `build/app-updates/upgrade-probe-final-20261017/result.json` 及两个安装日志。首轮探针误写注册表键名（漏 GUID 大括号），已修正并清理首轮独立安装。Core 测试 **10/10** 通过，含清单绑定/篡改/旧版回退和打包哈希生成。

### 完整产品验收（待真实发布包）

在 Windows Sandbox/专用测试账户中安装真实旧发布，再覆盖新版候选包，避免影响日常安装。

| 场景 | 必须核对 |
| --- | --- |
| 默认/自定义安装路径 | 使用原目录、一个卸载项、启动后显示新版本 |
| 播放中、托盘、桌面歌词 | 保存播放历史，所有产品子进程退出，安装无锁文件错误 |
| 数据保留 | 设置、曲库、歌单、歌词绑定与播放历史保持；登录状态按实际平台验证，不在日志复制凭据 |
| 取消安装/安装失败 | 原版可启动，用户数据仍在，日志能够诊断 |
| 安装完成 | 通过现有安装向导主动启动新版，无强制静默重启 |

二阶段不实现静默安装、增量补丁、自动回退、独立渠道或断点续传。

## B 接口进度

原生下载/安装命令尚在实现，CC 暂不假定可用；完成后在本节写入实际字段、错误码与样本。
