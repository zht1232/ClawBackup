# 🐾 ClawBackup

专业 Minecraft 服务器备份插件 · 开源（GPL-3.0）

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)

> 支持 **Paper / Purpur / Folia** · MC **1.16 ~ 26.2** · Java 8+
> 当前版本 **1.4.1**

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
- 🔌 **插件钩子**：自动检测 LuckPerms / QuickShop，备份前导出、回档后导入
- 🗄️ **数据库文件识别**：自动识别被锁的 H2/SQLite 数据库文件，跳过并列出清单，避免备份到损坏的库

## 支持平台

| 平台 | 支持 |
|---|---|
| Paper (1.20.4+) | ✅ 完整支持 |
| Purpur | ✅ 完整支持 |
| Folia | ✅ 支持（区域调度 API 适配） |
| 更早版本 (1.16+) | ✅ 调度兼容 |

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
- `schedule`：定时备份间隔、启动/关闭备份
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
    token: "ghp_xxx"                      # GitHub PAT（需 repo 权限）
    repo: "zht1232/ClawBackup"
  baidu:
    enabled: false
    access-token: ""                      # 百度开发者应用 OAuth token
    app-id: 0
    dir: "/apps/ClawBackup"
```

### 告警通知示例

```yaml
notify:
  on-backup-success: true
  on-backup-failure: true
  on-backup-start: false
  email:
    enabled: true
    host: "smtp.qq.com"
    port: 465
    ssl: true
    username: "you@qq.com"
    password: "SMTP授权码"                 # 非登录密码！
    from: ""
    to: ["admin@example.com"]
  feishu:
    enabled: true
    webhook: "https://open.feishu.cn/open-apis/bot/v2/hook/xxx"
  dingtalk:
    enabled: true
    webhook: "https://oapi.dingtalk.com/robot/send?access_token=xxx"
    secret: ""                            # 机器人加签密钥（可选）
```

## ⚠️ 注意点

1. **数据库文件热备份限制**：H2/SQLite 数据库（`.mv.db` / `.db` / `.sqlite`）在服务器运行时会被占用，无法热复制（复制运行中的 H2 库也不安全）。ClawBackup 会跳过它们并**在备份日志列出被锁文件清单**（1.4.1+）。处理建议：
   - LuckPerms / QuickShop 已自动导出（数据有快照）
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
