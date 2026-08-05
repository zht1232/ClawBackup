package clawx.backup;

import clawx.backup.command.BackupCommand;
import clawx.backup.config.BackupConfig;
import clawx.backup.integration.CustomNameplatesExporter;
import clawx.backup.task.BackupManager;
import clawx.backup.task.BackupScheduler;
import clawx.backup.task.PlayerTracker;
import clawx.backup.util.Message;
import clawx.backup.util.SchedulerUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * ClawBackup - 开源 Minecraft 服务器备份插件
 * <p>
 * 兼容 Paper / Purpur / Folia（调度通过区域调度 API 统一适配）<br>
 * 支持 Minecraft 1.16 ~ 26.2（需 Java 8+）
 * <p>
 * 主要功能：
 * <ul>
 *   <li>备份插件目录和世界目录（含自动发现多世界）</li>
 *   <li>跨磁盘备份存放</li>
 *   <li>ZIP 压缩（0-9 级可调）</li>
 *   <li>文件锁重试 / 排除机制</li>
 *   <li>智能备份：无玩家时跳过</li>
 *   <li>定时自动备份</li>
 *   <li>一键回档（/cb restore --force）</li>
 *   <li>IO 限速防卡服 + TPS 保护</li>
 *   <li>备份前倒计时广播</li>
 *   <li>旧备份自动清理</li>
 *   <li>备份进度报告</li>
 *   <li>支持热重载（无需重启服务器）</li>
 * </ul>
 */
public class ClawBackup extends JavaPlugin {

    private static ClawBackup instance;
    private BackupConfig config;
    private BackupManager backupManager;
    private BackupScheduler backupScheduler;
    private PlayerTracker playerTracker;
    private BackupCommand commandExecutor;

    // 插件是否正在关闭（用于避免备份线程与 onDisable 主线程互锁）
    private volatile boolean disabling = false;

    @Override
    public void onLoad() {
        instance = this;
        Message.log("§e[ClawBackup] §f插件开始加载...");

        // 检测服务器类型
        String serverType = detectServerType();
        Message.log("§e[ClawBackup] §f  服务器类型: §b" + serverType);
        if ("Folia".equals(serverType)) {
            Message.log("§e[ClawBackup] §a  ✔ 已启用 Folia 兼容模式（区域调度 API）");
        }
        if ("Spigot".equals(serverType) || "Bukkit/Spigot (unknown)".equals(serverType)) {
            Message.log("§e[ClawBackup] §c  ⚠ 检测到纯 Spigot/Bukkit：当前版本不支持纯 Spigot（依赖 Paper 区域调度 API），");
            Message.log("§e[ClawBackup] §c    请使用 Paper / Purpur / Folia，否则插件可能无法正常调度！");
        }
        String mcVer = Bukkit.getBukkitVersion();
        Message.log("§e[ClawBackup] §f  MC 版本: §b" + mcVer);
    }

    @Override
    public void onEnable() {
        long startTime = System.currentTimeMillis();

        Message.log("§e[ClawBackup] §f==================================");
        Message.log("§e[ClawBackup] §f       🐾 ClawBackup v" + getDescription().getVersion());
        Message.log("§e[ClawBackup] §f       开源服务器备份插件");
        Message.log("§e[ClawBackup] §f       兼容 Paper/Purpur/Folia 1.16-26.2");
        Message.log("§e[ClawBackup] §f==================================");

        // 1. 加载配置
        Message.log("§e[ClawBackup] §f[1/7] §7正在加载配置文件...");
        this.config = new BackupConfig(this);
        Message.log("§e[ClawBackup] §f[1/7] §a✔ 配置文件加载完成");

        // 2. 初始化玩家追踪器
        Message.log("§e[ClawBackup] §f[2/7] §7正在初始化玩家追踪器...");
        this.playerTracker = new PlayerTracker(this);
        Message.log("§e[ClawBackup] §f[2/7] §a✔ 玩家追踪器就绪 §7(在线: " + playerTracker.getOnlineCount() + ")");

        // 3. 初始化备份管理器
        Message.log("§e[ClawBackup] §f[3/7] §7正在初始化备份管理器...");
        this.backupManager = new BackupManager(this);
        Message.log("§e[ClawBackup] §f[3/7] §a✔ 备份管理器就绪");

        // 4. 初始化定时器
        Message.log("§e[ClawBackup] §f[4/7] §7正在初始化定时备份...");
        this.backupScheduler = new BackupScheduler(this);
        if (config.isScheduleEnabled()) {
            Message.log("§e[ClawBackup] §f[4/7] §a✔ 定时备份已启用 §7(间隔: " + config.getScheduleIntervalMinutes() + " 分钟)");
        } else {
            Message.log("§e[ClawBackup] §f[4/7] §e◌ 定时备份未启用");
        }

        // 5. 注册命令
        Message.log("§e[ClawBackup] §f[5/7] §7正在注册命令...");
        this.commandExecutor = new BackupCommand(this);
        PluginCommand cmdObj = getCommand("clawbackup");
        if (cmdObj != null) {
            cmdObj.setExecutor(commandExecutor);
            cmdObj.setTabCompleter(commandExecutor);
        }
        Message.log("§e[ClawBackup] §f[5/7] §a✔ 命令注册完成 §7(/cb)");

        // 6. 检查备份目录
        Message.log("§e[ClawBackup] §f[6/7] §7检查备份目录...");
        File backupDir = config.getResolvedBackupPath().toFile();
        if (!backupDir.exists()) {
            backupDir.mkdirs();
            Message.log("§e[ClawBackup] §f[6/7] §a✔ 已创建备份目录");
        } else {
            Message.log("§e[ClawBackup] §f[6/7] §a✔ 备份目录就绪");
        }

        // 6.5 检查回档后命令标记文件
        checkAndExecutePostRestoreCommands();

        // 7. 启动时备份（如果配置了）
        Message.log("§e[ClawBackup] §f[7/7] §7检查启动备份设置...");
        if (config.isBackupOnStart()) {
            // 延迟 60 秒，等待服务器完全启动、区块加载和异步 IO 完成
            Message.log("§e[ClawBackup] §f[7/7] §e⚡ 将在 60 秒后执行启动备份（等待服务器稳定）...");
            SchedulerUtil.runLater(this, 1200L, () -> {
                if (backupManager != null) {
                    backupManager.runBackup("启动", null);
                }
            });
        } else {
            Message.log("§e[ClawBackup] §f[7/7] §e◌ 启动备份已禁用");
        }

        long elapsed = System.currentTimeMillis() - startTime;
        Message.log("§e[ClawBackup] §f==================================");
        Message.log("§e[ClawBackup] §a  ✅ 插件启动完成! 耗时: " + elapsed + "ms");
        Message.log("§e[ClawBackup] §f  备份目录: §7" + config.getBackupPath());
        Message.log("§e[ClawBackup] §f  压缩等级: §7" + config.getCompressionLevel());
        Message.log("§e[ClawBackup] §f  自动发现: §7" + (config.isWorldAutoDiscover() ? "§a启用" : "§e禁用"));
        Message.log("§e[ClawBackup] §f  最大备份: §7" + config.getMaxBackups() + " 个");
        Message.log("§e[ClawBackup] §f  TPS保护: §7" + (config.isTpsProtectionEnabled() ? "§a启用 (阈值 " + config.getTpsThreshold() + ")" : "§e禁用"));
        Message.log("§e[ClawBackup] §f  IO限速: §7" + (config.getIoThrottleKBps() > 0 ? config.getIoThrottleKBps() + " KB/s" : "§a不限速"));
        Message.log("§e[ClawBackup] §f  一键回档: §7/cb restore <序号> --force");
        Message.log("§e[ClawBackup] §f==================================");
    }

    @Override
    public void onDisable() {
        disabling = true;
        Message.log("§e[ClawBackup] §f插件正在关闭...");

        // 优雅终止正在运行的备份
        shutdownBackupManager();

        // 兜底恢复自动保存：若备份因插件关闭被中断，确保自动保存不再处于关闭状态
        if (backupManager != null && backupManager.isAutoSaveWasDisabled()) {
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-on");
                backupManager.resetAutoSaveFlag();
                Message.log("§e[ClawBackup] §a✔ 已恢复自动保存（关闭兜底）");
            } catch (Exception ignored) {}
        }

        // 清理定时任务
        if (backupScheduler != null) {
            backupScheduler.shutdown();
            backupScheduler = null;
        }

        // 清理玩家追踪器事件监听
        if (playerTracker != null) {
            playerTracker.unregister();
            playerTracker = null;
        }

        // 检查是否有待执行的回档任务
        checkPendingRestore();

        Message.log("§e[ClawBackup] §f插件已关闭。再见! 🐾");
    }

    /** 检查并执行待回档任务（在服务器关闭、世界卸载后执行） */
    private void checkPendingRestore() {
        java.nio.file.Path markerFile = java.nio.file.Paths.get("plugins/ClawBackup/pending-restore.txt");
        if (!java.nio.file.Files.exists(markerFile)) return;

        try {
            String zipFilePath = new String(java.nio.file.Files.readAllBytes(markerFile),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            java.nio.file.Files.delete(markerFile);

            if (zipFilePath.isEmpty()) return;

            // 等待世界卸载和文件句柄释放
            Message.log("§e[ClawBackup] §7检测到待回档任务，等待文件释放...");
            Thread.sleep(3000);

            // 执行实际回档
            if (backupManager != null) {
                backupManager.doRestore(zipFilePath);
            } else {
                Message.log("§c[ClawBackup] §4备份管理器不可用，无法执行回档");
                return;
            }

            // 写入回档后命令标记文件（下次启动时执行）
            java.util.List<String> postCommands = new java.util.ArrayList<>(config.getPostRestoreCommands());
            if (config.isAutoHookPlugins()) {
                // LuckPerms：检测导出文件（lp export 默认生成 backup.json.gz，兼容 json/yml）
                String[] lpExportNames = {
                        "plugins/LuckPerms/backup.json.gz",
                        "plugins/LuckPerms/backup.json",
                        "plugins/LuckPerms/backup.yml"
                };
                for (String lpName : lpExportNames) {
                    if (java.nio.file.Files.exists(java.nio.file.Paths.get(lpName))) {
                        postCommands.add("lp import backup");
                        Message.log("§e[ClawBackup] §a✔ 检测到 LuckPerms 导出文件，将在启动后导入");
                        break;
                    }
                }
                // QuickShop：检测导出 zip（quickshop export 生成 export-<时间戳>.zip）。
                // 实测 recovery 会查找固定的 recovery.zip；故取最新导出复制为 recovery.zip 再执行恢复。
                // recovery 会覆盖现有商店，是否自动执行由 restore.auto-restore-quickshop 控制。
                java.nio.file.Path qsDir = java.nio.file.Paths.get("plugins/QuickShop-Hikari");
                if (java.nio.file.Files.isDirectory(qsDir)) {
                    java.nio.file.Path newestZip = null;
                    long newestTime = -1;
                    try (java.nio.file.DirectoryStream<java.nio.file.Path> ds =
                                 java.nio.file.Files.newDirectoryStream(qsDir, "export-*.zip")) {
                        for (java.nio.file.Path p : ds) {
                            try {
                                long t = java.nio.file.Files.getLastModifiedTime(p).toMillis();
                                if (t > newestTime) { newestTime = t; newestZip = p; }
                            } catch (Exception ignored) {}
                        }
                    } catch (Exception ignored) {}
                    if (newestZip != null) {
                        Message.log("§e[ClawBackup] §a✔ 检测到 QuickShop 导出备份: §f" + newestZip.getFileName());
                        if (config.isAutoRestoreQuickshop()) {
                            try {
                                java.nio.file.Path recZip = qsDir.resolve("recovery.zip");
                                java.nio.file.Files.copy(newestZip, recZip,
                                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                postCommands.add("quickshop recovery recovery.zip");
                                Message.log("§e[ClawBackup] §7已复制为 recovery.zip，自动执行 QuickShop 恢复...");
                            } catch (Exception e) {
                                Message.log("§c[ClawBackup] §4复制 recovery.zip 失败: " + e.getMessage());
                            }
                        } else {
                            Message.log("§e[ClawBackup] §6   如需恢复商店，请手动执行 §e/quickshop recovery §6<export zip>");
                        }
                    }
                }
            }
            if (!postCommands.isEmpty()) {
                java.nio.file.Path cmdFile = java.nio.file.Paths.get("plugins/ClawBackup/post-restore-commands.txt");
                java.nio.file.Files.write(cmdFile, postCommands, java.nio.charset.StandardCharsets.UTF_8);
                Message.log("§e[ClawBackup] §7已写入回档后命令，启动后自动执行");
            }

        } catch (Exception e) {
            Message.log("§c[ClawBackup] §4回档执行失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** 目录是否存在且非空 */
    private static boolean hasAnyFile(java.nio.file.Path dir) {
        if (!java.nio.file.Files.isDirectory(dir)) return false;
        try (java.nio.file.DirectoryStream<java.nio.file.Path> ds = java.nio.file.Files.newDirectoryStream(dir)) {
            return ds.iterator().hasNext();
        } catch (Exception e) {
            return false;
        }
    }

    /** 热重载：只重载配置和定时任务，不重建备份管理器 */
    public void hotReload() {
        Message.log("§e[ClawBackup] §e⚡ 正在热重载...");

        // 重载配置
        config.reload();
        Message.log("§e[ClawBackup] §a✔ 配置已重载");

        // 重建定时器
        if (backupScheduler != null) {
            backupScheduler.shutdown();
        }
        backupScheduler = new BackupScheduler(this);

        if (config.isScheduleEnabled()) {
            Message.log("§e[ClawBackup] §a✔ 定时备份已重建 §7(间隔: " + config.getScheduleIntervalMinutes() + " 分钟)");
        } else {
            Message.log("§e[ClawBackup] §7  定时备份已禁用");
        }

        Message.log("§e[ClawBackup] §a✅ 热重载完成");
    }

    /** 检查并执行回档后命令（服务器重启后） */
    private void checkAndExecutePostRestoreCommands() {
        java.nio.file.Path markerFile = java.nio.file.Paths.get("plugins/ClawBackup/post-restore-commands.txt");
        if (!java.nio.file.Files.exists(markerFile)) return;

        // 回档后 CustomNameplates 数据导入（延迟异步执行，等待插件就绪后写回 H2）
        if (config.isAutoHookPlugins() && CustomNameplatesExporter.isAvailable()) {
            SchedulerUtil.runAsync(this, () -> {
                try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                CustomNameplatesExporter.restore();
            });
        }

        try {
            java.util.List<String> commands = java.nio.file.Files.readAllLines(markerFile);
            java.nio.file.Files.delete(markerFile);

            if (commands.isEmpty()) return;

            Message.log("§e[ClawBackup] §a✔ 检测到回档后命令标记文件");
            Message.log("§e[ClawBackup] §7将在 5 秒后执行回档后命令...");

            // 延迟执行，等待其他插件加载完成
            SchedulerUtil.runLater(this, 100L, () -> {
                Message.log("§e[ClawBackup] §7开始执行回档后命令...");
                for (String cmd : commands) {
                    Message.log("§e[ClawBackup] §7  > §f" + cmd);
                    getServer().dispatchCommand(getServer().getConsoleSender(), cmd);
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                }
                Message.log("§e[ClawBackup] §a✔ 回档后命令执行完成");
            });

        } catch (Exception e) {
            Message.log("§e[ClawBackup] §c✗ 读取回档后命令失败: " + e.getMessage());
        }
    }

    /** 安全关闭备份管理器（停止进行中的任务 + 清理异步任务） */
    private void shutdownBackupManager() {
        if (backupManager != null) {
            if (backupManager.isRunning()) {
                Message.log("§e[ClawBackup] §e⚠ 检测到正在运行的备份任务，尝试安全中断...");
                backupManager.cancel();
                // 最多等待 5 秒让异步任务退出
                int waited = 0;
                while (backupManager.isRunning() && waited < 50) {
                    try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    waited++;
                }
                if (backupManager.isRunning()) {
                    Message.log("§e[ClawBackup] §e⚠ 备份任务未能在 5 秒内终止，已强制中断");
                }
            }
        }
    }

    /** 检测当前服务器核心类型（优先用品牌名与 Paper API 反射，避免 getVersion() 不含品牌导致误判） */
    public static String detectServerType() {
        // 1. Bukkit.getName()：Paper / Purpur / Folia 会返回品牌名
        try {
            String name = Bukkit.getName();
            if (name != null && !name.isEmpty()) {
                String lower = name.toLowerCase();
                if (lower.contains("folia")) return "Folia";
                if (lower.contains("purpur")) return "Purpur";
                if (lower.contains("paper")) return "Paper";
            }
        } catch (Exception ignored) {}
        // 2. 反射检测 Paper 区域调度 API（Paper 1.20.4+ / Folia 独有，纯 Spigot 没有）
        try {
            Class.forName("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler");
            return "Paper";
        } catch (Throwable ignored) {}
        // 3. 回退到版本字符串
        try {
            String version = Bukkit.getVersion().toLowerCase();
            if (version.contains("purpur")) return "Purpur";
            if (version.contains("pufferfish")) return "Pufferfish";
            if (version.contains("paper")) return "Paper";
            if (version.contains("folia")) return "Folia";
            if (version.contains("spigot") || version.contains("bukkit")) return "Spigot";
        } catch (Exception ignored) {}
        return "Bukkit/Spigot (unknown)";
    }

    /** 检测是否 Paper 系列（包含 getTPS 方法） */
    public static boolean isPaperBased() {
        return detectServerType().matches("Paper|Purpur|Pufferfish|Folia");
    }

    public static ClawBackup getInstance() {
        return instance;
    }

    /** 插件是否正在关闭（备份线程据此避免与 onDisable 互锁） */
    public boolean isDisabling() {
        return disabling;
    }

    public BackupConfig getBackupConfig() {
        return config;
    }

    public BackupManager getBackupManager() {
        return backupManager;
    }

    public BackupScheduler getBackupScheduler() {
        return backupScheduler;
    }

    public PlayerTracker getPlayerTracker() {
        return playerTracker;
    }
}
