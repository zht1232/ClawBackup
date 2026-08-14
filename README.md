# 🐾 ClawBackup

专业 Minecraft 服务器备份插件 · 开源（GPL-3.0）

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)

> 支持 **Paper / Purpur / Folia** · MC **1.20.1 ~ 26.2** · Java 17+
> 当前版本 **1.6.10**

---

## ✨ 主要特色

- 📦 **一键备份**：打包 `plugins/` 与世界目录为 ZIP（自动发现多世界）
- 💾 **跨盘备份**：`backup-path` 支持绝对路径，可存到任意磁盘
- 🗜️ **压缩可调**：ZIP/Deflate 等级 0–9
- 🧠 **智能备份**：在线玩家低于阈值时自动跳过
- ⏰ **定时备份**：自动定时 + 启动延迟备份
- 🔄 **一键回档**：`/cb restore <序号> --force`，服务器关闭时完整恢复（含目标目录清理，避免残留文件）
- 🚦 **IO 限速 + TPS 保护**：防止备份拖垮服务器性能
- 🧹 **旧备份自动清理**：按数量保留
- ⚡ **热重载**：无需重启服务器
- ☁️ **云备份上传**：备份完成后自动上传（GitHub Release / 百度网盘）
- 🔔 **告警通知**：备份开始/成功/失败自动通知（邮件 / 飞书 / 钉钉）
- 🔌 **插件钩子**：自动检测 LuckPerms / QuickShop / CustomNameplates（备份前导出、回档后导入）
- 🗄️ **数据库文件识别**：自动识别被锁的 H2/SQLite 数据库文件，跳过并列出清单，避免备份到损坏的库

## 支持平台

| 平台 | 支持 |
|---|---|
| Paper (1.20.1+) | ✅ 完整支持 |
| Purpur | ✅ 完整支持 |
| Folia | ✅ 支持（区域调度 API 适配） |
| 更早版本 (1.16–1.19) | ❌ 不支持（缺区域调度 API） |

## 安装

1. 将 `ClawBackup-<版本>.jar` 放入服务器 `plugins/` 目录
2. 重启服务器（或热加载）
3. 首次启动自动生成 `plugins/ClawBackup/config.yml`
4. 使用 `/cb backup` 验证备份是否正常

## 命令

| 命令 | 说明 | 权限 |
|---|---|---|
| `/cb backup [名称]` | 立即执行备份 | `clawbackup.backup` |
| `/cb cancel` | 取消正在运行的备份 | `clawbackup.admin` |
| `/cb list` | 列出所有备份 | 查看 |
| `/cb restore <序号/文件名> --force` | 一键回档 | `clawbackup.admin` |
| `/cb schedule` | 查看定时备份状态 | 查看 |
| `/cb status` | 备份状态与磁盘空间 | 查看 |
| `/cb reload` | 重载配置文件 | `clawbackup.admin` |
| `/cb clean` | 立即清理旧备份 | `clawbackup.admin` |
| `/cb info` | 版本信息 | 查看 |

## 配置

主要配置位于 `plugins/ClawBackup/config.yml`：

- `backup`：备份目标（插件/世界、自动发现、排除项、备份前命令）
- `storage`：备份路径、压缩等级
- `schedule`：定时备份间隔、启动备份
- `smart`：智能备份阈值
- `retention`：最大备份数、最小磁盘空间
- `advanced`：IO 限速、TPS 保护、倒计时等
- `restore`：回档设置（自动关服、回档排除插件、回档后命令）
- `cloud-backup`：云备份上传（GitHub / 百度网盘）
- `notify`：告警通知（邮件 / 飞书 / 钉钉）

### 云备份上传示例

```yaml
cloud-backup:
  enabled: true
  github:
    enabled: true
    token: 'ghp_xxx'                      # GitHub PAT（需 repo 权限）
    repo: 'zht1232/ClawBackup'
  baidu:
    enabled: false
    access-token: ''                      # 百度开发者应用 OAuth token
    app-id: 0
    dir: '/apps/ClawBackup'
```

### 告警通知示例

```yaml
notify:
  on-backup-success: true
  on-backup-failure: true
  on-backup-start: false
  email:
    enabled: true
    host: 'smtp.qq.com'
    port: 465
    ssl: true
    username: 'you@qq.com'
    password: 'SMTP授权码'                 # 非登录密码！
    from: ''
    to: ['admin@example.com']
  feishu:
    enabled: true
    webhook: 'https://open.feishu.cn/open-apis/bot/v2/hook/xxx'
  dingtalk:
    enabled: true
    webhook: 'https://oapi.dingtalk.com/robot/send?access_token=xxx'
    secret: ''                            # 机器人加签密钥（可选）
```

## ⚠️ 注意点

1. **数据库文件热备份限制**：H2/SQLite 数据库（`.mv.db` / `.db` / `.sqlite`）在服务器运行时会被占用，无法热复制（复制运行中的 H2 库也不安全）。ClawBackup 会跳过它们并**在备份日志列出被锁文件清单**（1.4.1+）。处理建议：
   - LuckPerms / QuickShop / CustomNameplates 已自动导出（数据有快照）
   - 其他插件可在 `backup.pre-backup-commands` 加导出命令，或迁移 MySQL
2. **凭据安全**：GitHub token、SMTP 授权码等**只存在你服务器上的 `config.yml`**，不会被提交到仓库（`libs/` 等已 gitignore）
3. **邮件依赖**：构建时会自动下载 JavaMail 并打进 jar（构建脚本需联网一次）
4. **123云盘**：无官方开放 API，暂不支持
5. **最低要求**：仅支持 Paper 1.20.4+ / Purpur / Folia（纯 Spigot 无区域调度 API，不支持）

## 构建

项目使用轻量构建脚本（无需 Maven/Gradle）：

```powershell
# 方式一：双击运行
build.bat

# 方式二：命令行
.\build.ps1
```

脚本会读取 `plugin.yml` 的版本号，自动下载依赖（JavaMail 等），编译
（Java 8 字节码）并打包为 `ClawBackup-<版本>.jar` 输出到桌面。编译依赖
来自服务器 `libraries/` 目录（Paper API 及其传递依赖），路径可在
`build.ps1` 顶部配置。

## 更新历史

- **1.6.10**：备份完成日志精简 — 已覆盖的数据库库不再提示为「跳过」；被锁文件汇总改为一行总结（如「4/5 已覆盖 | 1 未备份」），只列出真正未备份的文件；聊天栏不再重复广播「跳过被锁文件」
- **1.6.9**：SQLite 回档恢复改为「直接复制」（回档时插件已禁用、无文件占用，直接把 VACUUM INTO 快照覆盖回原 .db，绕开 ATTACH 逐表复制的表结构依赖/自增序列丢失问题；复制失败仍保留启动兜底）；备份日志新增「数据库备份汇总」分类（✅已覆盖 / ❌未备份，一眼区分数据是否在备份包内）
- **1.6.8**：修复 Folia 下日志调用 Bukkit.isPrimaryThread() 崩溃；修复被同 JVM 锁定的世界区域文件被误判跳过（备份不再缺世界数据）；修复回档准备阶段关闭自动保存后、手动关服/异常路径不恢复 save-on；备份名触发源过滤非法字符（防路径穿越）；SQLite 恢复等待目标库就绪并逐表计数（消除空库假成功）；SQLite/H2 导出与恢复 SQL 路径单引号转义；恢复任务被中断后不再继续执行；构建脚本 target 8→17（适配 Paper 1.20.1+/Java 17）
- **1.6.7**：日志大幅精简（备份过程不再刷阶段日志，跳过文件只汇总）；SQLite 扫描排除 H2 文件（不再误报）；MineStock 连接去掉多余 USER 参数（修复认证失败）
- **1.6.6**：回档禁用插件按依赖顺序执行（先禁依赖别人的，最后禁 LuckPerms/PAPI），消除 onDisable 报错噪音；插件禁用后异步线程不再尝试调度任务（消除"回档准备失败"误报）
- **1.6.5**：修复 SQLite 回档恢复 — 不再覆盖被插件占用的文件，改为 ATTACH 备份库 + SQL 逐表复制数据（插件运行时也能恢复）
- **1.6.4**：回档容错 — 单个文件被占用不再中断整个回档（跳过并提示）；备份排除 tmp 临时目录（sqlite-jdbc 等 native 库）；数据库导出日志精简为汇总
- **1.6.3**：SQLite 优化 — 已被 VACUUM INTO 热备份的原始 .db 不再重复直接复制，备份包更干净
- **1.6.2**：新增通用 SQLite 数据库热备份（官方 VACUUM INTO 一致性快照，自动覆盖 AuthMe/Brewery/PlayerPoints/TrMenu 等所有 SQLite 插件，回档后自动恢复）
- **1.6.1**：新增通用 H2 数据库兜底备份（自动尝试连接被锁的 H2 库，能连的用 SCRIPT TO 导出整个库随备份打包，回档后自动恢复；失败跳过不拖慢备份）
- **1.6.0**：新增 MineStock 持仓数据自动导出/回档导入（利用其 H2 AUTO_SERVER=TRUE，ClawBackup 内置 H2 驱动运行时 JDBC 直连，无需 MineStock 编译依赖）
- **1.5.9**：QuickShop 自动恢复补发二次确认命令（quickshop recovery confirm），实现全自动导入无需人工确认
- **1.5.8**：回档后自动执行插件恢复（QuickShop 等）并进入恢复窗口禁止备份；回档后重启跳过本次启动备份，避免与恢复冲突覆盖数据
- **1.5.7**：修复回档时其他插件仍在运行导致目录被清空时写入失败（回档准备阶段先禁用所有其他插件，再执行清空/覆盖）
- **1.5.6**：新增 CustomNameplates 数据自动导出/回档导入（通过官方 API：备份前导出玩家名牌数据、回档后写回 H2）
- **1.5.5**：全面检修 — 修复飞书/钉钉通知 JSON 结构错误、GitHub 上传改流式（大备份不再占满内存）、修复插件关闭时自动保存可能不恢复、被锁文件写入前预检避免截断残体进 zip、统一智能备份判定边界、移除未实现的配置项（backup-on-stop / use-temp-dir / backup-timeout-minutes）、修复 reload、异步日志线程安全、回档文件名路径穿越校验、倒计时广播去重、GitHub tag 加毫秒防冲突
- **1.5.4**：备份前清理同时删除遗留的 recovery.zip，避免重复体积
- **1.5.3**：QuickShop 自动恢复改为复制为 recovery.zip 后执行 quickshop recovery recovery.zip
- **1.5.2**：备份前自动删除 LuckPerms / QuickShop 旧导出文件，修复导出文件已存在导致打包旧数据
- **1.5.1**：QuickShop 自动恢复开关（restore.auto-restore-quickshop）
- **1.5.0**：LuckPerms / QuickShop 导出文件自动恢复支持
- **1.4.9**：恢复 QuickShop 导出钩子并等待导出完成
- **1.4.8**：修复 QuickShop 钩子编译错误
- **1.4.7**：备份检测兼容 backup.json.gz / json / yml
- **1.4.6**：LuckPerms 导入钩子
- **1.4.5**：配置模板改用单引号避免 Windows 路径转义问题
- **1.4.4**：配置文件解析失败时输出明确错误提示
- **1.4.3**：修复 Paper 误判、配置文件不再被覆盖为 .old
- **1.4.2**：修复 Spigot 声明
- **1.4.1**：备份日志列出所有被锁数据库文件清单
- **1.4.0**：新增云备份上传（GitHub/百度网盘）、告警通知（邮件/飞书/钉钉）
- **1.3.0**：Folia 适配（区域调度 API）、团队署名 CrystalKingdom
- **1.2.x**：回档完整化、关闭互锁修复、Java 8 兼容等

## 许可证

本项目采用 **GNU General Public License v3.0 (GPL-3.0)** 开源许可。

任何使用、修改、分发本项目的作品，都必须以同样许可开源。

详见 [LICENSE](LICENSE)。

---

© 2026 **CrystalKingdom 团队**
