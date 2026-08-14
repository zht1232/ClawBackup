package clawx.backup.task;

import clawx.backup.ClawBackup;
import clawx.backup.config.BackupConfig;
import clawx.backup.integration.CloudUploader;
import clawx.backup.integration.CustomNameplatesExporter;
import clawx.backup.integration.H2BackupExporter;
import clawx.backup.integration.MineStockExporter;
import clawx.backup.integration.NotificationManager;
import clawx.backup.integration.SqliteBackupExporter;
import clawx.backup.util.Message;
import clawx.backup.util.SchedulerUtil;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 备份管理器 - 核心备份逻辑
 * <p>
 * 负责执行备份、处理文件锁、压缩打包、IO限速、TPS保护、清理旧备份。
 * 所有 Bukkit API 调用均通过主线程安全执行。
 */
public class BackupManager {

    private final ClawBackup plugin;
    private final BackupConfig config;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private CompletableFuture<BackupResult> currentTask = null;

    // 进度追踪（对外只读）
    private final AtomicLong totalFiles = new AtomicLong(0);
    private final AtomicLong processedFiles = new AtomicLong(0);
    private final AtomicLong totalBytes = new AtomicLong(0);
    private final AtomicLong processedBytes = new AtomicLong(0);
    private volatile String currentPhase = "";

    // 本次备份是否关闭了自动保存（供插件关闭时兜底恢复 save-on）
    private volatile boolean autoSaveWasDisabled = false;

    private ScheduledTask progressTask = null;

    public BackupManager(ClawBackup plugin) {
        this.plugin = plugin;
        this.config = plugin.getBackupConfig();
    }

    // ===== 公开状态接口（供状态命令读取） =====
    public boolean isRunning() { return running.get(); }
    public int getProgressPercent() {
        long t = totalFiles.get();
        return t > 0 ? (int)(processedFiles.get() * 100 / t) : 0;
    }
    public String getCurrentPhase() { return currentPhase; }
    public long getProcessedFiles() { return processedFiles.get(); }
    public long getTotalFiles() { return totalFiles.get(); }
    public void cancel() { cancelled.set(true); }

    /** 本次备份是否关闭了自动保存（供插件关闭时兜底恢复） */
    public boolean isAutoSaveWasDisabled() { return autoSaveWasDisabled; }

    /** 清除自动保存被关闭的标记（兜底恢复后调用） */
    public void resetAutoSaveFlag() { autoSaveWasDisabled = false; }

    /**
     * 执行备份（异步），结果通过 CompletableFuture 返回。
     */
    public CompletableFuture<BackupResult> runBackup(String trigger, CommandSender sender) {
        if (running.get()) {
            if (sender != null) {
                SchedulerUtil.runSync(plugin, () ->
                    sender.sendMessage(Message.prefix("§c备份任务已在运行中，使用 §e/cb cancel §c取消。"))
                );
            }
            return CompletableFuture.completedFuture(new BackupResult(false, "已在运行中", null));
        }

        // 回档后恢复窗口：禁止触发备份，避免覆盖刚恢复的数据
        if (plugin.isRestoring()) {
            Message.log("§e[备份] §6⏭ 回档后恢复进行中，已跳过本次备份（避免覆盖刚恢复的数据）");
            if (sender != null) {
                SchedulerUtil.runSync(plugin, () ->
                    sender.sendMessage(Message.prefix("§6⏭ 回档后恢复进行中，已跳过本次备份"))
                );
            }
            return CompletableFuture.completedFuture(new BackupResult(false, "回档后恢复中", null));
        }

        // 检查是否被智能跳过
        if (config.isSmartBackup() && config.getSmartBackupThreshold() >= 0) {
            int online = plugin.getPlayerTracker().getOnlineCount();
            if (online <= config.getSmartBackupThreshold()) {
                String reason = "智能跳过: 在线玩家 " + online + " <= " + config.getSmartBackupThreshold();
                Message.log("§e[备份] §6⏭ " + reason);
                return CompletableFuture.completedFuture(new BackupResult(false, reason, null));
            }
        }

        // 磁盘空间预检
        Path backupDir = config.getResolvedBackupPath();
        try {
            if (!Files.exists(backupDir)) Files.createDirectories(backupDir);
            long freeMB = Files.getFileStore(backupDir).getUsableSpace() / (1024 * 1024);
            if (freeMB < config.getMinFreeSpaceMB()) {
                String msg = "磁盘空间不足: " + freeMB + "MB < " + config.getMinFreeSpaceMB() + "MB";
                Message.log("§c" + msg);
                if (sender != null) SchedulerUtil.runSync(plugin,
                    () -> sender.sendMessage(Message.prefix("§c" + msg)));
                return CompletableFuture.completedFuture(new BackupResult(false, msg, null));
            }
        } catch (IOException e) {
            return CompletableFuture.completedFuture(
                new BackupResult(false, "无法访问备份目录: " + e.getMessage(), null));
        }

        running.set(true);
        cancelled.set(false);

        // 备份开始通知（后台异步，不阻塞）
        if (config.isNotifyOnBackupStart()) {
            final String startTrigger = trigger;
            CompletableFuture.runAsync(() ->
                NotificationManager.notifyStart(config, "触发: " + startTrigger));
        }

        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
        String safeTrigger = sanitizeTrigger(trigger);
        String backupName = "backup_" + safeTrigger + "_" + timestamp;
        Path zipFile = backupDir.resolve(backupName + ".zip");

        final ThreadSafeSender safeSender = (sender != null) ? new ThreadSafeSender(sender, plugin) : null;

        // 倒计时由 supplyAsync 内的递减循环统一广播（N..1），此处不重复发"将在 N 秒后"提示

        Message.log("§e[备份] §f开始: §b" + backupName + " §7(触发: §f" + trigger + "§7) §f→ §7" + zipFile);

        // 面向控制台的进度
        if (safeSender == null && config.isShowProgress()) startProgressDisplay();

        // 倒计时后异步执行
        int delaySeconds = config.isBroadcastCountdown() ? config.getCountdownSeconds() : 0;

        currentTask = CompletableFuture.supplyAsync(() -> {
            try {
                if (delaySeconds > 0) {
                    for (int i = delaySeconds; i > 0; i--) {
                        if (cancelled.get()) throw new CancellationException("备份已取消");
                        final int remaining = i;
                        if (config.isNotifyPlayers()) {
                            SchedulerUtil.runSync(plugin, () ->
                                Bukkit.broadcastMessage(Message.prefix("§e⏳ 距离备份开始: §6" + remaining + " §e秒..."))
                            );
                        }
                        Thread.sleep(1000);
                    }
                }
                return doBackup(zipFile, backupName, safeSender);
            } catch (CancellationException e) {
                return new BackupResult(false, "已取消", null);
            } catch (Exception e) {
                Message.log("§c[备份] §4异常: " + e.getMessage());
                e.printStackTrace();
                return new BackupResult(false, "异常: " + e.getMessage(), null);
            } finally {
                running.set(false);
                stopProgressDisplay();
            }
        });

        return currentTask;
    }

    // ===== 同步任务桥（跨线程调度到同步线程并等待）=====
    private void runSyncAndWait(Runnable task) {
        if (SchedulerUtil.isPrimaryThread()) { task.run(); return; }
        if (plugin.isDisabling()) {
            // 插件正在关闭：主线程即将退出，排队等待会导致与 onDisable 互锁，
            // 直接跳过（由 onDisable 兜底恢复自动保存等操作）
            Message.log("§e[备份] §7插件关闭中，跳过主线程任务");
            return;
        }
        try {
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<Exception> error = new AtomicReference<>();
            SchedulerUtil.runSync(plugin, () -> {
                try { task.run(); } catch (Exception e) { error.set(e); }
                finally { latch.countDown(); }
            });
            if (!latch.await(30, TimeUnit.SECONDS))
                Message.log("§c[备份] §4等待主线程任务超时(30秒)");
            if (error.get() != null)
                Message.log("§c[备份] §4主线程任务异常: " + error.get().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ===== 获取 TPS（通过 Paper API 反射获取，Spigot 环境回退为 20） =====
    private double getRecentTps() {
        try {
            // Paper API: Bukkit.getServer().getTPS()
            Method method = Bukkit.getServer().getClass().getMethod("getTPS");
            double[] tps = (double[]) method.invoke(Bukkit.getServer());
            return (tps != null && tps.length > 0) ? tps[0] : 20.0;
        } catch (Exception e) {
            return 20.0; // 反射失败（Spigot 无此方法）时假设 TPS 正常
        }
    }

    // ===== 核心备份逻辑 =====
    private BackupResult doBackup(Path zipFile, String backupName, ThreadSafeSender sender) {
        long startTime = System.currentTimeMillis();
        List<String> skippedFiles = new ArrayList<>();
        int fileLockSkipped = 0;
        int tpsPauses = 0;
        boolean autoSaveDisabled = false;

        try {
            // 1. TPS 预检
            if (config.isTpsProtectionEnabled()) {
                double tps = getRecentTps();
                if (tps < config.getTpsThreshold()) {
                    String warn = "TPS 过低 (" + String.format("%.1f", tps)
                            + " < " + config.getTpsThreshold() + ")，备份延期 30 秒";
                    Message.log("§e[备份] §6⚠ " + warn);
                    if (sender != null) sender.sendMessage(Message.prefix("§6⚠ " + warn));
                    Thread.sleep(30000);
                    tps = getRecentTps();
                    if (tps < config.getTpsThreshold()) {
                        String fail = "TPS 持续过低，放弃本次备份";
                        Message.log("§c[备份] §4✗ " + fail);
                        return new BackupResult(false, fail, null);
                    }
                }
            }

            // 2. 先关闭自动保存（防止收集文件时服务器仍在写入）
            if (config.isAutoSaveDisable()) {
                setPhase("暂停自动保存...");
                runSyncAndWait(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-off"));
                autoSaveDisabled = true;
                autoSaveWasDisabled = true;
                Thread.sleep(500);
            }

            // 3. 保存数据（在关闭自动保存之后执行，确保数据落盘后不再有写入）
            if (config.isSaveAllBeforeBackup()) {
                setPhase("保存世界数据...");
                Message.log("§e[备份] §7执行 save-all flush...");
                runSyncAndWait(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-all flush"));
                // 等待 Paper 异步 IO 完成（Paper 的 chunk IO 是异步的，save-all 返回后仍有写入）
                Thread.sleep(10000);
            }

            // 3.5 执行备份前命令（让插件保存/导出数据）
            List<String> preCommands = new java.util.ArrayList<>(config.getPreBackupCommands());

            // 自动检测插件并添加导出命令
            if (config.isAutoHookPlugins()) {
                // LuckPerms：备份前自动导出权限数据（生成 backup.json.gz 随备份打包）
                if (Bukkit.getPluginManager().getPlugin("LuckPerms") != null) {
                    boolean hasLpExport = preCommands.stream().anyMatch(cmd ->
                        cmd.toLowerCase().startsWith("lp export") || cmd.toLowerCase().startsWith("luckperms export"));
                    if (!hasLpExport) {
                        preCommands.add("lp export backup");
                        Message.log("§e[备份] §a✔ 检测到 LuckPerms，自动添加导出命令");
                    }
                }
                // QuickShop：备份前自动导出商店数据（quickshop export 生成 export-<时间戳>.zip）
                if (Bukkit.getPluginManager().getPlugin("QuickShop") != null
                        || Bukkit.getPluginManager().getPlugin("QuickShop-Hikari") != null) {
                    boolean hasQsExport = preCommands.stream().anyMatch(cmd ->
                        cmd.toLowerCase().startsWith("qs export") || cmd.toLowerCase().startsWith("quickshop export"));
                    if (!hasQsExport) {
                        preCommands.add("quickshop export");
                        Message.log("§e[备份] §a✔ 检测到 QuickShop，自动添加导出命令");
                    }
                }
            }

            if (!preCommands.isEmpty()) {
                // 清理会自动导出的旧文件：
                // - LuckPerms export 在目标文件已存在时会报错拒绝覆盖（backup.json.gz already exists），需先删除旧导出
                // - QuickShop export 生成新时间戳 zip，旧 zip 会不断积累，需先清理只保留当次最新
                for (String cmd : preCommands) {
                    String lowerCmd = cmd.toLowerCase();
                    if (lowerCmd.startsWith("lp export") || lowerCmd.startsWith("luckperms export")) {
                        deleteIfExists(java.nio.file.Paths.get("plugins/LuckPerms", "backup.json.gz"));
                        deleteIfExists(java.nio.file.Paths.get("plugins/LuckPerms", "backup.json"));
                        deleteIfExists(java.nio.file.Paths.get("plugins/LuckPerms", "backup.yml"));
                    }
                    if (lowerCmd.startsWith("qs export") || lowerCmd.startsWith("quickshop export")) {
                        deleteMatching(java.nio.file.Paths.get("plugins/QuickShop-Hikari"), "export-*.zip");
                        // 顺带删除回档遗留的 recovery.zip（临时副本，避免被打包进下次备份造成重复体积）
                        deleteIfExists(java.nio.file.Paths.get("plugins/QuickShop-Hikari", "recovery.zip"));
                    }
                }
                setPhase("执行备份前命令...");
                Message.log("§e[备份] §7执行备份前命令...");
                for (String cmd : preCommands) {
                    Message.log("§e[备份] §7  > §f" + cmd);
                    runSyncAndWait(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
                    // 导出类命令为异步执行，需等待其写入完成，否则会打包到上一次的旧导出文件（导致备份日期不同步）
                    if (cmd.toLowerCase().contains("export")) {
                        Thread.sleep(5000);
                    } else {
                        Thread.sleep(500);
                    }
                }
                Message.log("§e[备份] §a✔ 备份前命令执行完成");
            }

            // 3.7 CustomNameplates：H2 运行中被独占锁定，只能通过官方 API 导出玩家数据
            if (config.isAutoHookPlugins() && CustomNameplatesExporter.isAvailable()) {
                setPhase("导出 CustomNameplates 数据...");
                CustomNameplatesExporter.export();
            }

            // 3.8 MineStock：H2 带 AUTO_SERVER=TRUE，运行时 JDBC 直连导出持仓数据
            if (config.isAutoHookPlugins() && MineStockExporter.isAvailable()) {
                setPhase("导出 MineStock 数据...");
                MineStockExporter.export();
            }

            // 3.9 通用 H2 兜底：尝试导出所有被锁的 H2 库（能连上的自动导出，失败跳过）
            if (config.isH2BackupEnabled()) {
                setPhase("H2 兜底导出...");
                H2BackupExporter.export(config);
            }

            // 3.10 通用 SQLite 热备份（官方 VACUUM INTO 一致性快照，覆盖所有 SQLite 插件）
            if (config.isSqliteBackupEnabled()) {
                setPhase("SQLite 热备份...");
                SqliteBackupExporter.export(config);
            }

            // 4. 收集文件
            setPhase("统计待备份文件...");
            List<Path> allFiles = collectFiles();
            totalFiles.set(allFiles.size());
            processedFiles.set(0);
            processedBytes.set(0);

            long calcTotal = 0;
            for (Path f : allFiles) {
                try { calcTotal += Files.size(f); } catch (IOException ignored) {}
            }
            totalBytes.set(calcTotal);

            Message.log("§e[备份] §7文件: §f" + totalFiles.get() + " §7合计: §f" + formatSize(totalBytes.get()));

            // 5. 压缩
            setPhase("压缩打包中...");
            Path serverRoot = Paths.get(".").toAbsolutePath().normalize();

            try (FileOutputStream fos = new FileOutputStream(zipFile.toFile());
                 BufferedOutputStream bos = new BufferedOutputStream(fos, 65536);
                 ZipOutputStream zos = new ZipOutputStream(bos)) {

                int level = config.getCompressionLevel();
                zos.setLevel(level == 0 ? Deflater.NO_COMPRESSION : level);
                zos.setMethod(ZipOutputStream.DEFLATED);

                Message.log("§e[备份] §7压缩: §fLv" + level + " §8(" + describeCompressionLevel(level) + ")");

                int throttleKBps = config.getIoThrottleKBps();
                int chunkBytes = config.getIoThrottleChunkKB() * 1024;

                for (Path file : allFiles) {
                    if (cancelled.get()) throw new CancellationException("备份被取消");

                    // TPS 检测（每 50 个文件检查一次）
                    if (config.isTpsProtectionEnabled() && processedFiles.get() > 0
                            && processedFiles.get() % 50 == 0) {
                        double tps = getRecentTps();
                        if (tps < config.getTpsThreshold()) {
                            tpsPauses++;
                            Message.log("§e[备份] §6⏸ TPS " + String.format("%.1f", tps)
                                    + " < " + config.getTpsThreshold() + "，暂停5秒...");
                            Thread.sleep(5000);
                        }
                    }

                    Path relativePath = serverRoot.relativize(file);
                    String entryName = relativePath.toString().replace('\\', '/');

                    // 写入前预检：被其他进程独占锁定的文件直接跳过，避免截断残体写入 zip
                    if (isFileLocked(file)) {
                        skippedFiles.add(entryName);
                        fileLockSkipped++;
                        processedFiles.incrementAndGet();
                        continue;
                    }

                    boolean entryOpen = false;
                    try {
                        ZipEntry entry = new ZipEntry(entryName);
                        entry.setTime(Files.getLastModifiedTime(file).toMillis());
                        zos.putNextEntry(entry);
                        entryOpen = true;

                        boolean success = copyFileWithRetry(file, zos, throttleKBps, chunkBytes);
                        zos.closeEntry();
                        entryOpen = false;

                        if (success) {
                            processedBytes.addAndGet(Files.size(file));
                        } else {
                            skippedFiles.add(entryName);
                            fileLockSkipped++;
                        }
                        processedFiles.incrementAndGet();

                    } catch (IOException e) {
                        if (entryOpen) { try { zos.closeEntry(); } catch (IOException ignored) {} }
                        skippedFiles.add(entryName);
                        fileLockSkipped++;
                        processedFiles.incrementAndGet();
                    }
                }
                zos.finish();
            }

            // 6. 恢复自动保存（统一在 finally 中执行一次，避免重复 save-on）
            // 见下方 finally 中的 restoreAutoSave(autoSaveDisabled)

            // ==== 输出结果 ====
            long elapsed = System.currentTimeMillis() - startTime;
            long fileSize = 0;
            try { fileSize = Files.size(zipFile); } catch (IOException ignored) {}

            String sizeStr = formatSize(fileSize);
            String timeStr = formatTime(elapsed);

            Message.log("§e[备份] §a==================================");
            Message.log("§e[备份] §a  ✅ 备份完成!");
            Message.log("§e[备份] §f  文件: §7" + zipFile.getFileName());
            Message.log("§e[备份] §f  大小: §7" + sizeStr + " §8| §7耗时: §8" + timeStr);
            Message.log("§e[备份] §f  文件数: §7" + processedFiles.get() + "/" + totalFiles.get());
            if (tpsPauses > 0)
                Message.log("§e[备份] §6  ⏸ TPS 暂停次数: §7" + tpsPauses);
            Message.log("§e[备份] §a==================================");

            // 被锁文件汇总：数据库库分类为「已覆盖/未备份」，非数据库被锁文件单独列出。
            // 已覆盖的库数据已随备份打包，不再提示为"跳过"。
            int[] lockStat = reportDatabaseBackupStatus(skippedFiles);
            int totalMissing = lockStat[2] + lockStat[3];

            // 玩家广播（只报完成 + 未备份警告，不重复"跳过被锁文件"）
            if (config.isNotifyPlayers()) {
                String finalSize = sizeStr, finalTime = timeStr;
                int finalMissing = totalMissing;
                SchedulerUtil.runSync(plugin, () -> {
                    Bukkit.broadcastMessage(Message.prefix("§a✅ 备份完成！ §7(§f" + finalSize
                            + " §8| §f" + finalTime + "§7)"));
                    if (finalMissing > 0)
                        Bukkit.broadcastMessage(Message.prefix("§6⚠ 有 " + finalMissing + " 个文件未备份，详见控制台"));
                });
            }

            // sender 通知
            if (sender != null) {
                sender.sendMessage(Message.prefix("§a✅ 备份完成! §8[§7" + sizeStr + " §8| §7" + timeStr + "§8]"));
                sender.sendMessage(Message.prefix("§7文件: §f" + zipFile.getFileName()));
                if (totalMissing > 0)
                    sender.sendMessage(Message.prefix("§6⚠ 有 " + totalMissing + " 个文件未备份（详见控制台）"));
                if (tpsPauses > 0)
                    sender.sendMessage(Message.prefix("§6⏸ TPS 保护暂停 " + tpsPauses + " 次"));
            }

            // 清理旧备份
            cleanOldBackups();

            BackupResult result = new BackupResult(true, "成功", zipFile);
            result.setElapsedMs(elapsed);
            result.setCompressedSize(fileSize);
            result.setTotalFiles(totalFiles.get());
            result.setSkippedFiles(fileLockSkipped);
            result.setSkippedFileNames(skippedFiles);

            // 云备份上传 + 成功通知（后台异步，不阻塞备份完成）
            final long okElapsed = elapsed;
            final long okSize = fileSize;
            CompletableFuture.runAsync(() -> {
                CloudUploader.upload(config, zipFile);
                NotificationManager.notifyResult(config, true, "【ClawBackup】备份完成",
                        "文件: " + zipFile.getFileName()
                                + "\n大小: " + formatSize(okSize)
                                + "\n耗时: " + formatTime(okElapsed)
                                + "\n文件数: " + processedFiles.get() + "/" + totalFiles.get());
            });

            return result;

        } catch (CancellationException e) {
            Message.log("§e[备份] §6备份任务已取消");
            try { Files.deleteIfExists(zipFile); } catch (IOException ignored) {}
            CompletableFuture.runAsync(() ->
                NotificationManager.notifyResult(config, false, "【ClawBackup】备份取消",
                        "原因: 任务被取消"));
            return new BackupResult(false, "已取消", null);
        } catch (Exception e) {
            Message.log("§c[备份] §4备份失败: " + e.getMessage());
            e.printStackTrace();
            try { Files.deleteIfExists(zipFile); } catch (IOException ignored) {}
            final String failReason = e.getMessage() != null ? e.getMessage() : "未知异常";
            CompletableFuture.runAsync(() ->
                NotificationManager.notifyResult(config, false, "【ClawBackup】备份失败",
                        "原因: " + failReason));
            return new BackupResult(false, e.getMessage(), null);
        } finally {
            // 兜底恢复自动保存（任何退出路径都会执行）
            restoreAutoSave(autoSaveDisabled);
        }
    }

    /** 恢复自动保存（若本次备份曾关闭过） */
    private void restoreAutoSave(boolean wasDisabled) {
        if (!wasDisabled) return;
        runSyncAndWait(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-on"));
        // 若插件正在关闭，runSyncAndWait 会跳过 save-on；此时保留标记交由 onDisable 兜底恢复
        if (!plugin.isDisabling()) {
            autoSaveWasDisabled = false;
            Message.log("§e[备份] §a✔ 已恢复自动保存");
        }
    }

    /** 删除文件（若存在） */
    private static void deleteIfExists(java.nio.file.Path p) {
        try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignored) {}
    }

    /** 删除目录下匹配 glob 的文件（如 export-*.zip） */
    private static void deleteMatching(java.nio.file.Path dir, String glob) {
        if (!java.nio.file.Files.isDirectory(dir)) return;
        try (java.nio.file.DirectoryStream<java.nio.file.Path> ds = java.nio.file.Files.newDirectoryStream(dir, glob)) {
            for (java.nio.file.Path p : ds) {
                try { java.nio.file.Files.deleteIfExists(p); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    /**
     * 过滤备份名触发源中的非法字符，避免路径分隔符（/、\）或 ".." 造成
     * 目录穿越、把备份写到备份目录之外。允许字母（含中文）、数字、下划线、点、连字符。
     */
    private static String sanitizeTrigger(String trigger) {
        if (trigger == null) return "backup";
        String s = trigger.replaceAll("[^\\p{L}\\p{N}_.-]+", "_");
        // 去除首尾点号，避免 Windows 上被当作隐藏文件或产生 ".." 残留
        s = s.replaceAll("^\\.+|\\.+$", "");
        if (s.isEmpty()) s = "backup";
        return s;
    }

    // ===== 文件收集（含自动发现多世界、排除规则）=====
    private List<Path> collectFiles() {
        List<Path> files = new ArrayList<>();
        Path serverRoot = Paths.get(".").toAbsolutePath().normalize();
        List<String> excludeTypes = config.getExcludeFileTypes();
        Set<String> lowerExclude = new HashSet<>();
        for (String t : excludeTypes) lowerExclude.add(t.toLowerCase());

        if (config.isBackupWorlds()) {
            List<Path> worldDirs = new ArrayList<>();

            if (config.isWorldAutoDiscover()) {
                // 自动扫描: 查找根目录下所有包含 level.dat 的文件夹 = 世界
                File rootDir = serverRoot.toFile();
                File[] entries = rootDir.listFiles(File::isDirectory);
                if (entries != null) {
                    for (File entry : entries) {
                        String name = entry.getName();
                        // 跳过非世界目录
                        if (name.equals("plugins") || name.equals("logs")
                                || name.equals("libraries") || name.equals("versions")
                                || name.equals("config") || name.equals("cache")
                                || name.equals("backups") || name.startsWith("."))
                            continue;
                        // level.dat 或 level.dat_old = 世界目录
                        File levelDat = new File(entry, "level.dat");
                        if (levelDat.exists()) {
                            if (!config.getExcludedWorlds().contains(name)) {
                                worldDirs.add(entry.toPath());
                                Message.log("§e[备份] §7    ✓ 发现世界: §f" + name);
                            } else {
                                Message.log("§e[备份] §8    排除世界: " + name);
                            }
                        }
                    }
                }
                if (worldDirs.isEmpty()) {
                    Message.log("§e[备份] §8  自动发现未找到世界目录，回退到手动列表");
                }
            }

            // 手动列表作为补充
            if (worldDirs.isEmpty()) {
                for (String worldName : config.getWorldList()) {
                    if (config.getExcludedWorlds().contains(worldName)) continue;
                    Path worldPath = serverRoot.resolve(worldName);
                    if (Files.exists(worldPath) && Files.isDirectory(worldPath)) {
                        worldDirs.add(worldPath);
                    } else {
                        Message.log("§e[备份] §8  世界不存在: " + worldName);
                    }
                }
            }

            // 合并手册列表与自动发现
            for (String worldName : config.getWorldList()) {
                if (config.getExcludedWorlds().contains(worldName)) continue;
                Path worldPath = serverRoot.resolve(worldName);
                if (Files.exists(worldPath) && Files.isDirectory(worldPath)
                        && !worldDirs.contains(worldPath)) {
                    worldDirs.add(worldPath);
                    Message.log("§e[备份] §7    + 手动世界: §f" + worldName);
                }
            }

            // 去重后遍历收集（统计本次收集的世界文件数，避免之前“抵消式死算术”的错误日志）
            int worldFilesBefore = files.size();
            for (Path worldDir : worldDirs) {
                collectDirectory(worldDir, files, lowerExclude);
            }
            Message.log("§e[备份] §7世界: §f" + worldDirs.size() + " 个, §f" + (files.size() - worldFilesBefore) + " 个文件");
        }

        if (config.isBackupPlugins()) {
            Path pluginsPath = serverRoot.resolve("plugins");
            if (Files.exists(pluginsPath) && Files.isDirectory(pluginsPath)) {
                File[] pluginEntries = pluginsPath.toFile().listFiles();
                if (pluginEntries != null) {
                    for (File f : pluginEntries) {
                        if (config.getExcludedPlugins().contains(f.getName())) {
                            continue;
                        }
                        if (f.isDirectory()) {
                            collectDirectory(f.toPath(), files, lowerExclude);
                        } else {
                            if (shouldExcludeFile(f.getName(), lowerExclude)) continue;
                            // SQLite 原始文件已由 VACUUM INTO 热备份，不再直接复制
                            if (config.isSqliteBackupEnabled() && SqliteBackupExporter.isBackedUp(f.toPath())) {
                                continue;
                            }
                            files.add(f.toPath());
                        }
                    }
                }
            }
        }

        return files;
    }

    private void collectDirectory(Path dir, List<Path> files, Set<String> excludeTypes) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path sub, BasicFileAttributes attrs) {
                    // 跳过 tmp 临时目录（如 sqlite-jdbc 解压 native 库的目录），
                    // 避免把运行中被 JVM 加载的 .dll 等打进备份
                    if (sub.getFileName() != null
                            && sub.getFileName().toString().equalsIgnoreCase("tmp")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    if (name.equals("session.lock")) return FileVisitResult.CONTINUE;
                    if (shouldExcludeFile(name, excludeTypes)) return FileVisitResult.CONTINUE;
                    // SQLite 原始文件已由 VACUUM INTO 热备份，不再直接复制（避免冗余/不一致快照）
                    if (config.isSqliteBackupEnabled() && SqliteBackupExporter.isBackedUp(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    files.add(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            Message.log("§c[备份] §4遍历失败: " + dir + " - " + e.getMessage());
        }
    }

    private boolean shouldExcludeFile(String fileName, Set<String> excludeTypes) {
        String lower = fileName.toLowerCase();
        for (String ext : excludeTypes) {
            if (lower.endsWith(ext) || lower.equals(ext)) return true;
        }
        return false;
    }

    /**
     * 检测文件是否应跳过（写入前预检，避免把不一致/截断的数据写进 zip）。
     * <ul>
     *   <li>被「其它进程」持有锁（tryLock 返回 null）→ 视为被占用，跳过；</li>
     *   <li>被「本 JVM 自身」持有锁（OverlappingFileLockException）：
     *       世界区域文件等已在 save-all flush 后落盘，可安全读取，不跳过；
     *       但 plugins/ 下的数据库文件（H2/SQLite）运行中不能裸拷贝，仍跳过；</li>
     *   <li>文件打不开 → 视为被占用，跳过。</li>
     * </ul>
     */
    private boolean isFileLocked(Path file) {
        try (java.nio.channels.FileChannel ch =
                     java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.READ)) {
            try {
                java.nio.channels.FileLock lock = ch.tryLock(0, Long.MAX_VALUE, true);
                if (lock == null) return true; // 其它进程持有
                lock.release();
                return false;
            } catch (java.nio.channels.OverlappingFileLockException e) {
                // 本 JVM 已持有锁：世界文件可安全读取；plugins 下的数据库文件跳过（交给导出器）
                return isUnderPlugins(file);
            }
        } catch (Exception e) {
            return true; // 打不开或读取被拒 → 视为锁定
        }
    }

    /** 路径是否位于 plugins/ 目录下（用于区分世界文件与插件数据库文件） */
    private static boolean isUnderPlugins(Path file) {
        try {
            Path plugins = Paths.get("plugins").toAbsolutePath().normalize();
            return file.toAbsolutePath().normalize().startsWith(plugins);
        } catch (Exception e) {
            return true; // 判定失败时保守跳过，避免误拷贝运行中的库
        }
    }

    // ===== 带重试和限速的文件复制 =====
    private boolean copyFileWithRetry(Path source, OutputStream dest,
                                       int throttleKBps, int chunkBytes) {
        int maxRetries = config.getFileLockRetries();
        int retryDelay = config.getFileLockRetryDelay();

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try (InputStream is = new BufferedInputStream(Files.newInputStream(source), 8192)) {
                byte[] buffer = new byte[Math.min(chunkBytes, 65536)];
                int read;
                long writtenThisChunk = 0;
                long chunkStart = System.nanoTime();

                while ((read = is.read(buffer)) != -1) {
                    dest.write(buffer, 0, read);

                    // IO 限速
                    if (throttleKBps > 0) {
                        writtenThisChunk += read;
                        if (writtenThisChunk >= chunkBytes) {
                            long elapsedNs = System.nanoTime() - chunkStart;
                            long targetNs = writtenThisChunk * 1_000_000_000L / (throttleKBps * 1024L);
                            if (elapsedNs < targetNs) {
                                try { Thread.sleep((targetNs - elapsedNs) / 1_000_000); }
                                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return false; }
                            }
                            writtenThisChunk = 0;
                            chunkStart = System.nanoTime();
                        }
                    }
                }
                return true;
            } catch (IOException e) {
                if (attempt < maxRetries) {
                    try { Thread.sleep(retryDelay); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); return false; }
                }
            }
        }
        return false;
    }

    // ===== 清理旧备份（返回删除数量）=====
    public int cleanOldBackups() {
        Path backupDir = config.getResolvedBackupPath();
        if (!Files.exists(backupDir)) return 0;

        File[] backupFiles = backupDir.toFile().listFiles(
            (dir, name) -> name.startsWith("backup_") && name.endsWith(".zip"));
        if (backupFiles == null || backupFiles.length == 0) return 0;

        Arrays.sort(backupFiles, Comparator.comparingLong(File::lastModified));

        int deleted = 0;
        int remaining = backupFiles.length;

        // 按数量清理（超出最大保留数时删最旧的）
        if (remaining > config.getMaxBackups()) {
            for (File f : backupFiles) {
                if (remaining <= config.getMaxBackups()) break;
                if (f.exists() && f.delete()) {
                    Message.log("§e[清理] §7  超额: §8" + f.getName());
                    deleted++;
                    remaining--;
                }
            }
        }

        if (deleted > 0)
            Message.log("§e[清理] §a✔ 删除 " + deleted + " 个旧备份，当前 " + remaining + " 个");
        else
            Message.log("§e[清理] §7无需清理 (当前 " + remaining + " 个)");

        return deleted;
    }

    /**
     * 被锁文件汇总：数据库库分类为「已覆盖/未备份」，非数据库被锁文件单独列出。
     * 已覆盖的库（数据已随备份打包）不再提示为"跳过"。
     * 返回统计 int[]{dbTotal, dbCovered, dbMissing, otherLocked}。
     */
    private int[] reportDatabaseBackupStatus(List<String> skippedFiles) {
        int[] stat = new int[]{0, 0, 0, 0};
        if (skippedFiles == null || skippedFiles.isEmpty()) return stat;

        List<String> dbEntries = new ArrayList<>();
        List<String> otherLocked = new ArrayList<>();
        for (String entry : skippedFiles) {
            String lower = entry.toLowerCase();
            if (lower.endsWith(".mv.db") || lower.endsWith(".db") || lower.endsWith(".sqlite")
                    || lower.endsWith(".sqlite3") || lower.endsWith(".db3")) {
                dbEntries.add(entry);
            } else {
                otherLocked.add(entry);
            }
        }

        int dbTotal = dbEntries.size();
        stat[0] = dbTotal;
        int covered = 0;
        List<String> missing = new ArrayList<>();
        Path serverRoot = Paths.get(".").toAbsolutePath().normalize();
        for (String entry : dbEntries) {
            Path abs = serverRoot.resolve(entry).normalize();
            if (H2BackupExporter.isExported(abs)
                    || SqliteBackupExporter.isBackedUp(abs)
                    || officialExportReason(entry) != null) {
                covered++;
            } else {
                missing.add(entry);
            }
        }
        stat[1] = covered;
        stat[2] = dbTotal - covered;
        stat[3] = otherLocked.size();

        if (dbTotal == 0 && otherLocked.isEmpty()) return stat;

        Message.log("§e[备份] §f┌─ §b文件跳过汇总 §7(被锁未能直接打包) ──────────────────");
        if (dbTotal > 0) {
            if (stat[2] == 0) {
                Message.log("§e[备份] §a│ ✅ 数据库: §f" + covered + "/" + dbTotal
                        + " §a个库已覆盖（数据已随备份打包）");
            } else {
                Message.log("§e[备份] §6│ ⚠ 数据库: §f" + covered + "/" + dbTotal
                        + " §a已覆盖 §8| §c" + stat[2] + " §c个未备份:");
                for (String entry : missing) {
                    Message.log("§e[备份] §7│   • §f" + entry);
                }
                Message.log("§e[备份] §6│   建议: 给该库开启 AUTO_SERVER=TRUE / 迁移 MySQL / 插件自带导出");
            }
        }
        if (!otherLocked.isEmpty()) {
            Message.log("§e[备份] §c│ ❌ 其他被锁文件未打包: §f" + otherLocked.size() + " 个");
            for (String entry : otherLocked) {
                Message.log("§e[备份] §7│   • §f" + entry);
            }
        }
        Message.log("§e[备份] §f└─────────────────────────────────────────────");
        return stat;
    }

    /** 判断某个被锁的库是否已有官方导出文件覆盖（LuckPerms/QuickShop/CustomNameplates/MineStock） */
    private static String officialExportReason(String entry) {
        String path = entry.replace('\\', '/');
        if (path.contains("LuckPerms") && (Files.exists(Paths.get("plugins/LuckPerms/backup.json.gz"))
                || Files.exists(Paths.get("plugins/LuckPerms/backup.json"))
                || Files.exists(Paths.get("plugins/LuckPerms/backup.yml")))) {
            return "LuckPerms 官方导出 (lp export)";
        }
        if (path.contains("QuickShop") && hasQuickShopExport()) {
            return "QuickShop 官方导出 (quickshop export)";
        }
        if (path.contains("CustomNameplates") && Files.exists(Paths.get("plugins/CustomNameplates/backup.json"))) {
            return "CustomNameplates 官方导出 (API)";
        }
        if (path.contains("MineStock") && Files.exists(Paths.get("plugins/MineStock/backup.json"))) {
            return "MineStock 官方导出 (JDBC)";
        }
        return null;
    }

    /** QuickShop-Hikari 目录下是否存在本次导出的 export-*.zip */
    private static boolean hasQuickShopExport() {
        Path qs = Paths.get("plugins/QuickShop-Hikari");
        if (!Files.isDirectory(qs)) return false;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(qs, "export-*.zip")) {
            return ds.iterator().hasNext();
        } catch (Exception e) {
            return false;
        }
    }

    // 回档时需要跳过的文件（被服务端持锁，直接覆盖会导致文件占用错误）
    private static final Set<String> RESTORE_SKIP_FILES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("session.lock", "uid.dat", "level.dat_old", "level.dat_mcr")));

    // ===== 一键回档（准备阶段：踢人+保存+写标记+停止服务器）=====
    // 实际解压在 onDisable 中执行，确保世界文件句柄已释放
    public CompletableFuture<BackupResult> restoreBackup(String filename, CommandSender sender) {
        if (running.get()) {
            if (sender != null) {
                SchedulerUtil.runSync(plugin, () ->
                    sender.sendMessage(Message.prefix("§c备份任务正在运行，请稍后再试。"))
                );
            }
            return CompletableFuture.completedFuture(new BackupResult(false, "备份任务正在运行", null));
        }

        Path backupDir = config.getResolvedBackupPath();
        Path zipFile = backupDir.resolve(filename).normalize();
        // 防路径穿越：解析后必须仍位于备份目录内
        if (!zipFile.startsWith(backupDir.normalize())) {
            if (sender != null)
                SchedulerUtil.runSync(plugin, () ->
                    sender.sendMessage(Message.prefix("§c非法备份文件名: §7" + filename))
                );
            return CompletableFuture.completedFuture(new BackupResult(false, "非法文件名", null));
        }
        if (!Files.exists(zipFile)) {
            if (sender != null)
                SchedulerUtil.runSync(plugin, () ->
                    sender.sendMessage(Message.prefix("§c备份文件不存在: §7" + filename))
                );
            return CompletableFuture.completedFuture(new BackupResult(false, "文件不存在", null));
        }

        running.set(true);
        cancelled.set(false);
        ThreadSafeSender safeSender = (sender != null) ? new ThreadSafeSender(sender, plugin) : null;

        return CompletableFuture.supplyAsync(() -> {
            // 跟踪本次准备阶段是否关闭了自动保存、以及是否已下发 /stop（用于 finally 兜底恢复 save-on）
            final boolean[] saveOff = {false};
            final boolean[] stopping = {false};
            try {
                // 1. 广播警告
                SchedulerUtil.runSync(plugin, () ->
                    Bukkit.broadcastMessage(Message.prefix("§c⚠ 服务器即将回档！正在踢出所有玩家..."))
                );
                Thread.sleep(500);

                // 2. 踢出所有玩家 + 开启白名单
                runSyncAndWait(() -> {
                    Bukkit.getServer().setWhitelist(true);
                    for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
                        p.kickPlayer("§c服务器正在回档，请稍后重连。");
                    }
                });

                // 等待踢人完成（最多 20 秒）——通过主线程桥查询在线状态，避免异步线程直连 Bukkit API
                AtomicBoolean noPlayers = new AtomicBoolean(false);
                for (int i = 0; i < 40; i++) {
                    noPlayers.set(false);
                    runSyncAndWait(() -> noPlayers.set(Bukkit.getOnlinePlayers().isEmpty()));
                    if (noPlayers.get()) break;
                    Thread.sleep(500);
                }

                // 3. 关闭自动保存 + 保存数据
                runSyncAndWait(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-off"));
                saveOff[0] = true;
                Thread.sleep(300);
                Message.log("§e[回档] §7正在保存世界数据...");
                runSyncAndWait(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-all flush"));
                Thread.sleep(2000);

                // 3.5 禁用所有其他插件（保留 ClawBackup 自身）。
                // 实际回档（清空/覆盖 plugins 目录）发生在服务器关闭过程的 onDisable 中，
                // 此时若其他插件仍在运行（如 Ranking 的定时保存），会在目录被清空时写入失败或数据错乱。
                // 故提前逐个禁用，让它们先正常 onDisable 保存，再进入清空/解压阶段。
                final int[] disabledCount = {0};
                runSyncAndWait(() -> {
                    // 按"被依赖次数"升序禁用：先禁依赖别人的插件，最后禁 LuckPerms/PlaceholderAPI 等被依赖的
                    // （避免依赖方 onDisable 访问已禁用插件的 API 报错，减少日志噪音）
                    java.util.List<org.bukkit.plugin.Plugin> list = new java.util.ArrayList<>(
                            Arrays.asList(Bukkit.getPluginManager().getPlugins()));
                    java.util.Map<String, Integer> depCount = new java.util.HashMap<>();
                    for (org.bukkit.plugin.Plugin p : list) {
                        for (String d : p.getDescription().getDepend()) depCount.merge(d, 1, Integer::sum);
                        for (String d : p.getDescription().getSoftDepend()) depCount.merge(d, 1, Integer::sum);
                    }
                    list.sort((a, b) -> Integer.compare(
                            depCount.getOrDefault(a.getName(), 0),
                            depCount.getOrDefault(b.getName(), 0)));
                    for (org.bukkit.plugin.Plugin p : list) {
                        if (p == plugin) continue;
                        if (p.isEnabled()) {
                            try {
                                Bukkit.getPluginManager().disablePlugin(p);
                                disabledCount[0]++;
                            } catch (Exception ex) {
                                Message.log("§c[回档] §4禁用插件失败 §7" + p.getName()
                                        + " §8(" + ex.getMessage() + ")");
                            }
                        }
                    }
                });
                Message.log("§e[回档] §a✔ 已禁用 " + disabledCount[0] + " 个插件（避免回档期间插件写入数据）");
                // 等待各插件 onDisable 完成落盘
                Thread.sleep(1000);

                // 4. 写入回档标记文件（onDisable 会读取并执行实际回档）
                Path markerFile = Paths.get("plugins/ClawBackup/pending-restore.txt");
                Files.createDirectories(markerFile.getParent());
                Files.write(markerFile, zipFile.toAbsolutePath().toString()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                Message.log("§e[回档] §a✔ 已写入回档标记文件: §7" + filename);
                Message.log("§e[回档] §7实际回档将在服务器关闭时执行（世界文件释放后）");

                // 回档准备阶段已完成，提前释放“运行中”标志。
                // 否则服务器关闭触发 onDisable 时，会被 shutdownBackupManager 误判为
                // “备份未终止”，打印误导性的“强制中断”日志。
                // （实际解压由 onDisable 中的 doRestore 负责，本线程不再需要持有运行标志）
                running.set(false);

                if (safeSender != null) {
                    safeSender.sendMessage(Message.prefix("§a✔ 回档已准备就绪"));
                    safeSender.sendMessage(Message.prefix("§7备份文件: §f" + filename));
                    safeSender.sendMessage(Message.prefix("§7服务器关闭后将自动执行回档"));
                }

                // 5. 自动关闭服务器（回档实际在 onDisable 中执行，需要重启服务器才能生效）
                if (config.isAutoStopAfterRestore()) {
                    int delay = config.getAutoStopDelaySeconds();
                    if (delay > 0) {
                        Message.log("§e[回档] §6⚠ 将在 " + delay + " 秒后自动关闭服务器...");
                        Thread.sleep(delay * 1000L);
                    }
                    Message.log("§e[回档] §c正在关闭服务器...");
                    if (safeSender != null) {
                        safeSender.sendMessage(Message.prefix("§c服务器正在关闭，回档即将执行..."));
                    }
                    runSyncAndWait(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "stop"));
                    stopping[0] = true;

                    // 等待服务器关闭（onDisable 会执行实际回档）
                    Thread.sleep(30000);
                } else {
                    Message.log("§e[回档] §7自动关闭已禁用：请管理员手动执行 §e/stop §7以应用回档");
                    if (safeSender != null) {
                        safeSender.sendMessage(Message.prefix("§7请手动执行 §e/stop §7完成回档"));
                    }
                }

                BackupResult result = new BackupResult(true, "回档已准备，等待服务器关闭", zipFile);
                return result;

            } catch (Exception e) {
                Message.log("§c[回档] §4回档准备失败: " + e.getMessage());
                e.printStackTrace();
                return new BackupResult(false, e.getMessage(), null);
            } finally {
                // 兜底恢复自动保存：若准备阶段关了 save-off、但服务器没有真正停机
                // （手动关服模式或中途异常），必须补回 save-on，否则会一直停用自动保存。
                if (saveOff[0] && !stopping[0]) {
                    runSyncAndWait(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-on"));
                }
                running.set(false);
            }
        });
    }

    // ===== 实际回档（在 onDisable 中调用，服务器关闭时执行）=====
    public void doRestore(String zipFilePath) {
        long startTime = System.currentTimeMillis();
        Path zipFile = Paths.get(zipFilePath);
        Path serverRoot = Paths.get(".").toAbsolutePath().normalize();

        Message.log("§e[回档] §f==================================");
        Message.log("§e[回档] §f开始执行回档: §b" + zipFile.getFileName());
        Message.log("§e[回档] §f==================================");

        long count = 0;
        long skipped = 0;
        long failedFiles = 0;
        List<String> skippedFiles = new ArrayList<>();

        // 回档排除列表：直接使用已解析的配置（修复之前手工 YAML 解析
        // 匹配到 backup.excluded-plugins 而非 restore.excluded-plugins 的问题）
        List<String> excludedPlugins = new ArrayList<>(config.getRestoreExcludedPlugins());
        if (!excludedPlugins.isEmpty()) {
            Message.log("§e[回档] §7排除插件目录（不覆盖）: §f" + String.join(", ", excludedPlugins));
        }

        // 完整回滚：解压前先清空备份中出现的目标目录，避免残留备份后新增的文件
        clearRestoreTargets(zipFile, serverRoot, excludedPlugins);

        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                new BufferedInputStream(Files.newInputStream(zipFile)))) {
            java.util.zip.ZipEntry entry;
            byte[] buffer = new byte[65536];

            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                Path target = serverRoot.resolve(name).normalize();
                if (!target.startsWith(serverRoot)) {
                    Message.log("§c[回档] §4跳过不安全路径: " + name);
                    continue;
                }

                // 跳过锁定文件
                String baseName = target.getFileName() != null ? target.getFileName().toString() : "";
                if (!entry.isDirectory() && RESTORE_SKIP_FILES.contains(baseName)) {
                    skipped++;
                    skippedFiles.add(name);
                    zis.closeEntry();
                    continue;
                }

                // 跳过排除的插件目录
                if (!excludedPlugins.isEmpty() && name.startsWith("plugins/")) {
                    String relativePath = name.substring("plugins/".length());
                    boolean excluded = false;
                    for (String pluginName : excludedPlugins) {
                        if (relativePath.startsWith(pluginName + "/") || relativePath.equals(pluginName)) {
                            excluded = true;
                            break;
                        }
                    }
                    if (excluded) {
                        skipped++;
                        zis.closeEntry();
                        continue;
                    }
                }

                if (entry.isDirectory()) {
                    try {
                        Files.createDirectories(target);
                    } catch (Exception ex) {
                        // 目录创建失败：记录后继续，不中断回档
                    }
                } else {
                    try {
                        Files.createDirectories(target.getParent());
                        try (OutputStream os = Files.newOutputStream(target)) {
                            int read;
                            while ((read = zis.read(buffer)) != -1) {
                                os.write(buffer, 0, read);
                            }
                        }
                        count++;
                    } catch (Exception ex) {
                        // 单个文件被占用（如 JVM 已加载的 DLL、被锁文件）→ 只跳过该文件，不中断整个回档
                        failedFiles++;
                        Message.log("§c[回档] §4跳过占用文件: §7" + name);
                    }
                }
                try { zis.closeEntry(); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Message.log("§c[回档] §4回档失败: " + e.getMessage());
            e.printStackTrace();
            return;
        }

        long elapsed = System.currentTimeMillis() - startTime;
        Message.log("§e[回档] §a==================================");
        Message.log("§e[回档] §a  ✅ 回档完成！");
        Message.log("§e[回档] §f  耗时: §7" + formatTime(elapsed));
        Message.log("§e[回档] §f  文件数: §7" + count);
        if (skipped > 0) {
            Message.log("§e[回档] §6  ⚠ 跳过锁定文件: §7" + skipped);
            for (String f : skippedFiles) {
                Message.log("§e[回档] §6    - §7" + f);
            }
        }
        if (failedFiles > 0) {
            Message.log("§e[回档] §6  ⚠ " + failedFiles + " 个文件因被占用未能恢复（见上方日志，可手动处理）");
        }
        Message.log("§e[回档] §a==================================");
        Message.log("§e[回档] §a✔ 请启动服务器以完成回档");
    }

    /**
     * 完整回滚：解压前先清空备份中出现的顶层目标目录内容。
     * <p>
     * - 世界目录：整体清空（保留 session.lock / uid.dat 等被服务端持锁的文件）
     * - plugins 目录：只清空备份中出现的、且未被 restore.excluded-plugins 排除的插件子目录
     */
    private void clearRestoreTargets(Path zipFile, Path serverRoot, List<String> excludedPlugins) {
        Set<String> topLevels = new HashSet<>();
        Set<String> pluginSubDirs = new HashSet<>();

        // 第一遍扫描 zip，收集需要恢复的顶层路径
        try (java.util.zip.ZipInputStream scan = new java.util.zip.ZipInputStream(
                new BufferedInputStream(Files.newInputStream(zipFile)))) {
            java.util.zip.ZipEntry e;
            while ((e = scan.getNextEntry()) != null) {
                if (!e.isDirectory()) {
                    String name = e.getName();
                    int slash = name.indexOf('/');
                    String top = slash < 0 ? name : name.substring(0, slash);
                    if (!top.isEmpty()) topLevels.add(top);
                    if ("plugins".equals(top) && slash >= 0) {
                        int slash2 = name.indexOf('/', slash + 1);
                        String sub = slash2 < 0 ? name.substring(slash + 1) : name.substring(slash + 1, slash2);
                        if (!sub.isEmpty()) pluginSubDirs.add(sub);
                    }
                }
                scan.closeEntry();
            }
        } catch (Exception e) {
            Message.log("§c[回档] §4扫描备份文件失败，跳过清理: " + e.getMessage());
            return;
        }

        for (String top : topLevels) {
            Path topDir = serverRoot.resolve(top).normalize();
            if (!topDir.startsWith(serverRoot)) {
                Message.log("§c[回档] §4跳过不安全目录: " + top);
                continue;
            }
            if (!Files.isDirectory(topDir)) continue;

            if ("plugins".equals(top)) {
                // plugins 目录：只清空备份中出现且未被排除的插件子目录
                for (String sub : pluginSubDirs) {
                    if (excludedPlugins.contains(sub)) {
                        Message.log("§e[回档] §8保留排除插件目录: plugins/" + sub);
                        continue;
                    }
                    Path pluginDir = topDir.resolve(sub);
                    if (Files.isDirectory(pluginDir)) {
                        clearDirectoryContents(pluginDir, false);
                        Message.log("§e[回档] §7已清空插件目录: plugins/" + sub);
                    }
                }
            } else {
                clearDirectoryContents(topDir, true);
                Message.log("§e[回档] §7已清空目标目录: " + top);
            }
        }
    }

    /** 递归清空目录内容（保留锁定文件时，命中 RESTORE_SKIP_FILES 的文件会被跳过） */
    private static void clearDirectoryContents(Path dir, boolean keepLocked) {
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                try {
                    if (Files.isDirectory(child)) {
                        clearDirectoryContents(child, keepLocked);
                        Files.deleteIfExists(child);
                    } else {
                        if (keepLocked && RESTORE_SKIP_FILES.contains(child.getFileName().toString())) {
                            continue;
                        }
                        Files.deleteIfExists(child);
                    }
                } catch (IOException ignored) {
                    // 文件被占用无法删除时忽略（保留，由解压覆盖或留待下次清理）
                }
            }
        } catch (IOException ignored) {
        }
    }

    // ===== 进度 =====
    private void startProgressDisplay() {
        stopProgressDisplay();
        progressTask = SchedulerUtil.runAsyncTimer(plugin, 100L, config.getProgressInterval(), () -> {
            if (!running.get()) {
                if (progressTask != null) progressTask.cancel();
                return;
            }
            long p = processedFiles.get(), t = totalFiles.get();
            if (t > 0)
                Message.log("§e[进度] §f" + currentPhase + " §8["
                        + (p * 100 / t) + "%] §f" + p + "/" + t);
        });
    }

    private void stopProgressDisplay() {
        if (progressTask != null) { progressTask.cancel(); progressTask = null; }
    }

    private void setPhase(String phase) {
        // 仅更新进度状态（/cb status 读取），不再打印日志，避免阶段日志刷屏
        this.currentPhase = phase;
    }

    // ===== 格式化 =====
    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1048576) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1073741824) return String.format("%.1f MB", bytes / 1048576.0);
        return String.format("%.2f GB", bytes / 1073741824.0);
    }

    public static String formatTime(long ms) {
        if (ms < 1000) return ms + "ms";
        if (ms < 60000) return String.format("%.1fs", ms / 1000.0);
        long m = ms / 60000, s = (ms % 60000) / 1000;
        return m + "分" + s + "秒";
    }

    public static String describeCompressionLevel(int lv) {
        if (lv == 0) return "仅存储";
        if (lv <= 3) return "快速";
        if (lv <= 6) return "均衡";
        if (lv <= 8) return "高压";
        return "极限";
    }

    // ===== 线程安全发送器 =====
    private static class ThreadSafeSender {
        private final CommandSender delegate;
        private final ClawBackup plugin;
        ThreadSafeSender(CommandSender d, ClawBackup p) { this.delegate = d; this.plugin = p; }
        void sendMessage(String msg) {
            if (SchedulerUtil.isPrimaryThread()) {
                delegate.sendMessage(msg);
            } else if (plugin.isEnabled() && !plugin.isDisabling()) {
                SchedulerUtil.runSync(plugin, () -> delegate.sendMessage(msg));
            }
            // 插件已禁用/服务器关闭中：主线程调度不可用，直接丢弃该消息（避免 IllegalPluginAccessException）
        }
    }
}
