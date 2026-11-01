# QQ 网页登录验证结论（2026-10-28）

QQ 网页备选登录在本机、当前账号下尚未打通：用户完成密码和短信步骤后，官方页面仍要求使用 QQ App。正式账号页继续使用官方 QQ App，网页登录实验仅保留在 debug 源集，不展示为可用功能。不能将本轮结果推广为所有设备、账号或未来的 QQ 网页登录均不可行。

## 验证经过与证据

本次经用户授权，评估网页登录作为备选途径。设备为 PJF110 / Android 16。

10 月 27 日尝试了三条官方页面路径：

| 路径 | 本轮结果 |
| --- | --- |
| `graph.qq.com/oauth2.0/authorize`，`display=mobile` | 要求使用 QQ App |
| 同一 authorize，`display=pc` | 手机端仍导向 App |
| `xui.ptlogin2.qq.com/cgi-bin/xlogin`，`style=40` | 可显示二维码和密码入口；修正 viewport 后可操作密码登录。用户亲手完成密码和短信步骤，随后仍被要求使用 QQ App |

10 月 28 日补读手机侧阶段记录，保存为 `.local/artifacts/qq-web-login-20261027/password-sms-app-required.json`。记录包含两次 `PAGE_READY`（host 为 `xui.ptlogin2.qq.com`），随后两次 `NAVIGATION_BLOCKED`（host 为 `ptlogin`），没有 `AUTHORIZED`、`VERIFYING` 或 `VERIFIED`。未观察到授权回调，也没有保存隔离音乐会话的记录；音乐身份确认、本人曲库读取和重启恢复均未得到验证。

公开登录页的静态 HTML 同时包含设备锁、异常环境、推荐快捷登录和打开 QQ 确认等不同条件分支。阶段记录只保留 host，不包含当时显示的具体分支，因此不能断言最后要求 App 是安全校验还是普通跳转引导。

原 `no_backup/qqmusic-session.enc` 在试验前、收尾前及最终覆盖安装后摘要一致。实验没有替换正式 QQ 会话，没有执行退出、清数据、收藏写入或队列修改。10 月 28 日安装前的有效快照为暂停、队列 0；未用历史队列覆盖设备当前状态。

## 代码收尾

- 移除正式页面、账号层的未验证 QQ 网页备选接线；官方 QQ App 登录仍是正式入口。
- 网页窗口、导航策略、一次性随机 state 与授权换码封装放在 `platform-qq/src/debug`，对应测试放在 `src/testDebug`。
- 固定验收壳保留在 `app/src/debug`，Activity 不导出；触发入口由 `DUMP` 权限限制。实验使用独立会话存储。
- release 的 DEX 和 Manifest 已检查，不含网页实验和 debug 验收类、组件。main 侧仅提取供模块内部复用的原有换码函数。
- 未发布的账号层原型归档到 `.local/archive/qq-web-login-20261028/`，恢复限制见该目录说明。

实验不读取输入框、密码或短信内容，不导入现有 Cookie，不记录完整授权 URL；登录操作由用户完成。

## 最终验证

2026-10-28 的收尾源码通过 `build.ps1 -Test -Lint`：671 项 JVM 测试，0 失败、0 错误、0 跳过；Lint 0 错误、101 警告。另执行 `:app:assembleRelease` 并检查实验代码隔离。上述数量属于本次最终构建，早期试验包日志仅作历史材料。

最终 debug APK 已保留数据覆盖安装，手机安装包摘要与构建产物一致：

```text
E7B482AEB4929856766B5BEB49DB341ECD49872A74A95E8CA74FCDE38E9EE976
```

用于隔离检查的 release APK 未安装、未发布，SHA-256：

```text
33332D82F281AA324F3E4878593FC929F1DB3E7B9592927CED35DA8A1C71281A
```

安装前最后一次有效快照显示三平台已连接、暂停、队列 0。安装后广播受系统后台冻结影响，读取到的播放器快照仍为 10:25:19 的旧时间戳；账号快照没有内置时间戳，也不能当作安装后新验收。最终确认的实机结果限于覆盖安装、包摘要一致及原 QQ 会话文件未改写，不据旧快照宣称安装后主流程通过。没有重新打开登录页或自动启动播放。

汇总台账为 `.local/artifacts/qq-web-login-20261027/finish-20261028-checks.json`，证据用途和时效见同目录 `README.md`。当前未提交、推送或发布。后续若重新评估网页方案，应先取得真实授权回调，再依次验证音乐身份、本人曲库和重启恢复；外接设备与实际听音继续保留为待验。
