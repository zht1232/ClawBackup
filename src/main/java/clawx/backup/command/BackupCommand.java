package clawx.backup.command;

import clawx.backup.ClawBackup;
import clawx.backup.config.BackupConfig;
import clawx.backup.task.BackupManager;
import clawx.backup.task.BackupResult;
import clawx.backup.util.Message;
import org.bukkit.command.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static clawx.backup.util.Message.prefix;

public class BackupCommand implements CommandExecutor, TabCompleter {

    private final ClawBackup plugin;

    public BackupCommand(ClawBackup plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            showHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "backup":
            case "now":
            case "start":
                return handleBackup(sender, args);
            case "cancel":
            case "stop":
                return handleCancel(sender);
            case "restore":
            case "rollback":
                return handleRestore(sender, args);
            case "schedule":
                return handleSchedule(sender);
            case "status":
                return handleStatus(sender);
            case "reload":
                return handleReload(sender);
            case "clean":
            case "purge":
                return handleClean(sender);
            case "list":
            case "ls":
                return handleList(sender);
            case "info":
            case "about":
                return handleInfo(sender);
            default:
                showHelp(sender);
                return true;
        }
    }

    // ===== /cb help =====
    private void showHelp(CommandSender sender) {
        sender.sendMessage(prefix("§6§l========= ClawBackup 帮助 ========="));
        sender.sendMessage(prefix("§e/cb backup [名称] §f— 立即执行备份"));
        sender.sendMessage(prefix("§e/cb cancel         §f— 取消正在运行的备份任务"));
        sender.sendMessage(prefix("§e/cb list           §f— 列出所有备份文件"));
        sender.sendMessage(prefix("§e/cb restore <序号/文件名> --force §f— 一键回档"));
        sender.sendMessage(prefix("§e/cb status         §f— 查看备份状态与磁盘空间"));
        sender.sendMessage(prefix("§e/cb list           §f— 列出所有备份文件"));
        sender.sendMessage(prefix("§e/cb clean          §f— 立即清理过期/超额备份"));
        sender.sendMessage(prefix("§e/cb reload         §f— 重载配置文件"));
        sender.sendMessage(prefix("§e/cb info           §f— 插件版本信息"));
    }

    // ===== /cb backup [名称] =====
    private boolean handleBackup(CommandSender sender, String[] args) {
        if (!sender.hasPermission("clawbackup.backup")) {
            sender.sendMessage(prefix("§c你没有权限执行此操作。"));
            return true;
        }

        BackupManager bm = plugin.getBackupManager();
        if (bm.isRunning()) {
            sender.sendMessage(prefix("§c备份任务正在运行中，请等待完成或用 §e/cb cancel §c取消。"));
            return true;
        }

        String trigger = args.length > 1 ? String.join("_", Arrays.copyOfRange(args, 1, args.length)) : "手动";
        sender.sendMessage(prefix("§7正在启动备份任务 (触发: §f" + trigger + "§7)..."));
        bm.runBackup(trigger, sender);
        return true;
    }

    // ===== /cb cancel =====
    private boolean handleCancel(CommandSender sender) {
        if (!sender.hasPermission("clawbackup.admin")) {
            sender.sendMessage(prefix("§c你没有权限执行此操作。"));
            return true;
        }
        BackupManager bm = plugin.getBackupManager();
        if (!bm.isRunning()) {
            sender.sendMessage(prefix("§7当前没有正在运行的备份任务。"));
            return true;
        }
        bm.cancel();
        sender.sendMessage(prefix("§e已发送取消请求，备份任务将尽快终止..."));
        return true;
    }

    // ===== /cb restore <文件名> [--force] =====
    private boolean handleRestore(CommandSender sender, String[] args) {
        if (!sender.hasPermission("clawbackup.admin")) {
            sender.sendMessage(prefix("§c你没有权限执行回档操作。"));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(prefix("§c用法: §e/cb restore <文件名或序号>"));
            sender.sendMessage(prefix("§7先使用 §e/cb list §7查看备份文件列表"));
            sender.sendMessage(prefix("§c⚠ 回档会踢出所有玩家并覆盖服务器数据！"));
            return true;
        }

        String input = args[1];
        boolean forceFlag = args.length > 2 && "--force".equals(args[2]);

        Path backupDir = plugin.getBackupConfig().getResolvedBackupPath();
        File[] backups = backupDir.toFile().listFiles(
            (dir, name) -> name.startsWith("backup_") && name.endsWith(".zip"));

        if (backups == null || backups.length == 0) {
            sender.sendMessage(prefix("§c没有可用的备份文件。"));
            return true;
        }

        Arrays.sort(backups, Comparator.comparingLong(File::lastModified));

        String targetFile;
        try {
            int idx = Integer.parseInt(input);
            if (idx < 1 || idx > backups.length) {
                sender.sendMessage(prefix("§c序号无效。有效范围: 1~" + backups.length));
                return true;
            }
            targetFile = backups[backups.length - idx].getName();
        } catch (NumberFormatException e) {
            targetFile = input;
            if (!Files.exists(backupDir.resolve(targetFile))) {
                sender.sendMessage(prefix("§c找不到备份文件: §7" + input));
                sender.sendMessage(prefix("§7使用 §e/cb list §7查看可用文件"));
                return true;
            }
        }

        if (!forceFlag) {
            sender.sendMessage(prefix("§c⚠ ====== 回档确认 ======"));
            sender.sendMessage(prefix("§c文件: §7" + targetFile));
            sender.sendMessage(prefix("§c即将踢出所有玩家并覆盖服务器文件！"));
            sender.sendMessage(prefix("§c确认请执行: §e/cb restore " + input + " --force"));
            return true;
        }

        sender.sendMessage(prefix("§e⏳ 开始回档..."));
        plugin.getBackupManager().restoreBackup(targetFile, sender);
        return true;
    }

    // ===== /cb schedule =====
    private boolean handleSchedule(CommandSender sender) {
        BackupConfig config = plugin.getBackupConfig();
        sender.sendMessage(prefix("§6§l========= 定时备份状态 ========="));
        sender.sendMessage(prefix("§7定时任务: §f" + (config.isScheduleEnabled() ? "§a✓ 已启用" : "§c✗ 已禁用")));
        sender.sendMessage(prefix("§7备份间隔: §f" + config.getScheduleIntervalMinutes() + " 分钟"));
        sender.sendMessage(prefix("§7启动备份: §f" + (config.isBackupOnStart() ? "§a是" : "§c否")));
        sender.sendMessage(prefix(""));
        sender.sendMessage(prefix("§6§l========= 智能策略 ========="));
        sender.sendMessage(prefix("§7智能跳过: §f" + (config.isSmartBackup() ? "§a✓ 已启用" : "§c✗ 已禁用")));
        if (config.isSmartBackup()) {
            sender.sendMessage(prefix("§7最小在线: §f" + config.getSmartBackupThreshold() + " 人"));
        }
        sender.sendMessage(prefix("§7TPS 保护: §f" + (config.isTpsProtectionEnabled() ? ("§a✓ 启用 (阈值 " + config.getTpsThreshold() + ")") : "§c✗ 已禁用")));
        return true;
    }

    // ===== /cb status =====
    private boolean handleStatus(CommandSender sender) {
        BackupManager bm = plugin.getBackupManager();
        BackupConfig config = plugin.getBackupConfig();

        sender.sendMessage(prefix("§6§l========= 备份状态 ========="));
        if (bm.isRunning()) {
            int pct = bm.getProgressPercent();
            sender.sendMessage(prefix("§7当前任务: §e运行中 §8[" + pct + "%]"));
            sender.sendMessage(prefix("§7当前阶段: §f" + bm.getCurrentPhase()));
            sender.sendMessage(prefix("§7已处理文件: §f" + bm.getProcessedFiles() + " / " + bm.getTotalFiles()));
        } else {
            sender.sendMessage(prefix("§7当前任务: §a空闲"));
        }
        sender.sendMessage(prefix("§7备份目录: §f" + config.getResolvedBackupPath()));
        sender.sendMessage(prefix("§7压缩等级: §f" + config.getCompressionLevel()));
        sender.sendMessage(prefix("§7最大备份: §f" + config.getMaxBackups() + " 个"));
        sender.sendMessage(prefix("§7限速写入: §f" + (config.getIoThrottleKBps() > 0 ? config.getIoThrottleKBps() + " KB/s" : "§a无限制")));
        sender.sendMessage(prefix("§7在线玩家: §f" + plugin.getPlayerTracker().getOnlineCount()));

        // 磁盘空间
        try {
            Path backupDir = config.getResolvedBackupPath();
            if (!Files.exists(backupDir)) Files.createDirectories(backupDir);
            long free = Files.getFileStore(backupDir).getUsableSpace() / (1024 * 1024);
            long total = Files.getFileStore(backupDir).getTotalSpace() / (1024 * 1024);
            long used = total - free;
            String color = free < config.getMinFreeSpaceMB() ? "§c" : "§a";
            sender.sendMessage(prefix("§7磁盘空间: " + color + free + " MB 可用 §8/ §f" + total + " MB 总计"));
        } catch (Exception ignored) {
            sender.sendMessage(prefix("§7磁盘空间: §c无法获取"));
        }

        return true;
    }

    // ===== /cb reload =====
    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("clawbackup.admin")) {
            sender.sendMessage(prefix("§c你没有权限执行此操作。"));
            return true;
        }
        // 走统一的 hotReload：重载配置 + 重建定时器
        plugin.hotReload();
        return true;
    }

    // ===== /cb clean =====
    private boolean handleClean(CommandSender sender) {
        if (!sender.hasPermission("clawbackup.admin")) {
            sender.sendMessage(prefix("§c你没有权限执行此操作。"));
            return true;
        }
        if (plugin.getBackupManager().isRunning()) {
            sender.sendMessage(prefix("§c备份任务正在运行中，无法同时清理。"));
            return true;
        }
        sender.sendMessage(prefix("§7正在清理旧备份..."));
        int deleted = plugin.getBackupManager().cleanOldBackups();
        sender.sendMessage(prefix("§a✔ 清理完成! 删除了 §f" + deleted + " §a个旧备份。"));
        return true;
    }

    // ===== /cb list =====
    private boolean handleList(CommandSender sender) {
        Path backupDir = plugin.getBackupConfig().getResolvedBackupPath();
        if (!Files.exists(backupDir)) {
            sender.sendMessage(prefix("§7备份目录尚不存在，还没有任何备份。"));
            return true;
        }

        File[] backups = backupDir.toFile().listFiles(
            (dir, name) -> name.startsWith("backup_") && name.endsWith(".zip")
        );

        if (backups == null || backups.length == 0) {
            sender.sendMessage(prefix("§7暂无备份文件。"));
            return true;
        }

        Arrays.sort(backups, Comparator.comparingLong(File::lastModified).reversed());

        long totalSize = 0;
        for (File f : backups) totalSize += f.length();

        sender.sendMessage(prefix("§6§l========= 备份列表 (" + backups.length + " 个) ========="));
        sender.sendMessage(prefix("§7总计占用: §f" + BackupManager.formatSize(totalSize)));
        sender.sendMessage("");

        int show = Math.min(15, backups.length);
        for (int i = 0; i < show; i++) {
            File f = backups[i];
            String size = BackupManager.formatSize(f.length());
            String time = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(f.lastModified()));
            sender.sendMessage(" §7" + (i + 1) + ". §f" + f.getName());
            sender.sendMessage("    §7" + size + " §8| §7" + time);
        }

        if (backups.length > show) {
            sender.sendMessage(" §8... 还有 " + (backups.length - show) + " 个备份，使用 §7/cb list §8查看完整列表");
        }

        return true;
    }

    // ===== /cb info =====
    private boolean handleInfo(CommandSender sender) {
        sender.sendMessage(prefix("§6§l========= ClawBackup 信息 ========="));
        sender.sendMessage(prefix("§7  版本: §f" + plugin.getDescription().getVersion()));
        sender.sendMessage(prefix("§7  作者: §fCrystalKingdom"));
        sender.sendMessage(prefix("§7  服务器: §f" + plugin.getServer().getName() + " " + plugin.getServer().getVersion()));
        sender.sendMessage(prefix("§7  在线玩家: §f" + plugin.getPlayerTracker().getOnlineCount()));
        sender.sendMessage(prefix("§7  备份引擎: §fZIP (Deflate)"));
        sender.sendMessage(prefix(""));
        sender.sendMessage(prefix("§7  §o🐾 CrystalKingdom 团队出品"));
        return true;
    }

    // ===== Tab 补全 =====
    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1) {
            ArrayList<String> options = new ArrayList<>(Arrays.asList(
                    "backup", "cancel", "restore", "schedule", "status", "reload", "clean", "list", "info"));
            String input = args[0].toLowerCase();
            options.removeIf(s -> !s.startsWith(input));
            return options;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("backup")) {
            return Collections.singletonList("<触发名称>");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("restore")) {
            Path backupDir = plugin.getBackupConfig().getResolvedBackupPath();
            File[] backups = backupDir.toFile().listFiles(
                (dir, name) -> name.startsWith("backup_") && name.endsWith(".zip"));
            if (backups != null) {
                return Arrays.stream(backups).map(File::getName)
                        .collect(Collectors.toList());
            }
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("restore")) {
            return Collections.singletonList("--force");
        }
        return Collections.emptyList();
    }
}
