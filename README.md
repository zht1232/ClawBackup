# 🐾 ClawBackup

专业 Minecraft 服务器备份插件 · 开源（GPL-3.0）

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)

> 支持 **Paper / Spigot / Purpur / Folia** · MC **1.16 ~ 26.2** · Java 8+

---

## 功能特性

- 📦 **一键备份**：打包 `plugins/` 与世界目录为 ZIP（自动发现多世界）
- 💾 **跨盘备份**：`backup-path` 支持绝对路径，可存到任意磁盘
- 🗜️ **压缩可调**：ZIP/Deflate 等级 0–9
- 🧠 **智能备份**：在线玩家低于阈值时自动跳过
- ⏰ **定时备份**：自动定时 + 启动延迟备份
- 🔄 **一键回档**：`/cb restore <序号> --force`，服务器关闭时完整恢复（含目标目录清理，避免残留文件）
- 🚦 **IO 限速 + TPS 保护**：防止备份拖垮服务器性能
- 🧹 **旧备份自动清理**：按数量保留
- ⚡ **热重载**：无需重启服务器
- 🔌 **插件钩子**：自动检测 LuckPerms / QuickShop，备份前导出、回档后导入

## 支持平台

| 平台 | 支持 |
|---|---|
| Paper (1.20.4+) | ✅ 完整支持 |
| Spigot / Purpur | ✅ 完整支持 |
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

完整注释见配置文件本身。

## 构建

项目使用轻量构建脚本（无需 Maven/Gradle）：

```powershell
# 方式一：双击运行
build.bat

# 方式二：命令行
.\build.ps1
```

脚本会读取 `plugin.yml` 的版本号，编译（Java 8 字节码）并打包为
`ClawBackup-<版本>.jar` 输出到桌面。编译依赖来自服务器 `libraries/`
目录（Paper API 及其传递依赖），路径可在 `build.ps1` 顶部配置。

## 许可证

本项目采用 **GNU General Public License v3.0 (GPL-3.0)** 开源许可。

任何使用、修改、分发本项目的作品，都必须以同样许可开源。

详见 [LICENSE](LICENSE)。

---

© 2026 **CrystalKingdom 团队**
