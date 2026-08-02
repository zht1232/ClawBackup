package clawx.backup.task;

import clawx.backup.ClawBackup;
import clawx.backup.config.BackupConfig;
import clawx.backup.integration.CloudUploader;
import clawx.backup.integration.NotificationManager;
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
    private String currentPhase = "";

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

        // 检查是否被智能跳过
        if (config.isSmartBackup() && config.getSmartBackupThreshold() >= 0) {
            int online = plugin.getPlayerTracker().getOnlineCount();
            if (online < config.getSmartBackupThreshold()) {
                String reason = "智能跳过: 在线玩家 " + online + " < " + config.getSmartBackupThreshold();
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
        String backupName = "backup_" + trigger + "_" + timestamp;
        Path zipFile = backupDir.resolve(backupName + ".zip");

        final ThreadSafeSender safeSender = (sender != null) ? new ThreadSafeSender(sender, plugin) : null;

        // 倒计时广播
        if (config.isBroadcastCountdown() && config.getCountdownSeconds() > 0 && config.isNotifyPlayers()) {
            int secs = config.getCountdownSeconds();
            SchedulerUtil.runSync(plugin, () -> {
                Bukkit.broadcastMessage(Message.prefix("§e⚠ 服务器将在 §6" + secs + " §e秒后开始备份..."));
            });
            // 倒数由 supplyAsync 循环处理
        }

        Message.log("§e[备份] §f开始备份: §b" + backupName);
        Message.log("§e[备份] §f触发方式: §7" + trigger);
        Message.log("§e[备份] §f目标文件: §7" + zipFile);

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
                // LuckPerms
                if (Bukkit.getPluginManager().getPlugin("LuckPerms") != null) {
                    boolean hasLpExport = preCommands.stream().anyMatch(cmd -> 
                        cmd.toLowerCase().startsWith("lp export") || cmd.toLowerCase().startsWith("luckperms export"));
                    if (!hasLpExport) {
                        preCommands.add("lp export backup");
                        Message.log("§e[备份] §a✔ 检测到 LuckPerms，自动添加导出命令");
                    }
                }
                // QuickShop
                if (Bukkit.getPluginManager().getPlugin("QuickShop") != null) {
                    boolean hasQsExport = preCommands.stream().anyMatch(cmd -> 
                        cmd.toLowerCase().startsWith("qs export") || cmd.toLowerCase().startsWith("quickshop export"));
                    if (!hasQsExport) {
                        preCommands.add("quickshop export");
                        Message.log("§e[备份] §a✔ 检测到 QuickShop，自动添加导出命令");
                    }
                }
            }

            if (!preCommands.isEmpty()) {
                setPhase("执行备份前命令...");
                Message.log("§e[备份] §7执行备份前命令...");
                for (String cmd : preCommands) {
                    Message.log("§e[备份] §7  > §f" + cmd);
                    runSyncAndWait(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
                    Thread.sleep(500);
                }
                Message.log("§e[备份] §a✔ 备份前命令执行完成");
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
                            Message.log("§e[备份] §6⚠ 跳过被锁文件: §7" + entryName);
                        }
                        processedFiles.incrementAndGet();

                    } catch (IOException e) {
                        if (entryOpen) { try { zos.closeEntry(); } catch (IOException ignored) {} }
                        skippedFiles.add(entryName);
                        fileLockSkipped++;
                        Message.log("§e[备份] §6⚠ 跳过被锁文件: §7" + entryName + " §8(" + e.getMessage() + ")");
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
            Message.log("§e[备份] §f  大小: §7" + sizeStr + " §8| §7耗时: §7" + timeStr);
            Message.log("§e[备份] §f  文件数: §7" + processedFiles.get() + "/" + totalFiles.get());
            if (fileLockSkipped > 0) {
                Message.log("§e[备份] §6  ⚠ 跳过被锁文件: §7" + fileLockSkipped + " 个");
                for (String f : skippedFiles) {
                    Message.log("§e[备份] §6    - §7" + f);
                }
            }
            if (tpsPauses > 0)
                Message.log("§e[备份] §6  ⏸ TPS 暂停次数: §7" + tpsPauses);
            Message.log("§e[备份] §a==================================");

            // 玩家广播
            if (config.isNotifyPlayers()) {
                String finalSize = sizeStr, finalTime = timeStr;
                int finalSkipped = fileLockSkipped;
                SchedulerUtil.runSync(plugin, () -> {
                    Bukkit.broadcastMessage(Message.prefix("§a✅ 备份完成！ §7(§f" + finalSize
                            + " §8| §f" + finalTime + "§7)"));
                    if (finalSkipped > 0)
                        Bukkit.broadcastMessage(Message.prefix("§6⚠ 跳过 " + finalSkipped + " 个被锁文件"));
                });
            }

            // sender 通知
            if (sender != null) {
                sender.sendMessage(Message.prefix("§a✅ 备份完成! §8[§7" + sizeStr + " §8| §7" + timeStr + "§8]"));
                sender.sendMessage(Message.prefix("§7文件: §f" + zipFile.getFileName()));
                if (fileLockSkipped > 0)
                    sender.sendMessage(Message.prefix("§6⚠ 跳过 " + fileLockSkipped + " 个被锁文件"));
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
        autoSaveWasDisabled = false;
        Message.log("§e[备份] §a✔ 已恢复自动保存");
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
                Message.log("§e[备份] §7  自动发现世界目录...");
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
                String label = serverRoot.relativize(worldDir).toString();
                Message.log("§e[备份] §7    收集: §f" + label);
                collectDirectory(worldDir, files, lowerExclude);
            }
            Message.log("§e[备份] §7  世界文件数: §f" + (files.size() - worldFilesBefore));
        }

        if (config.isBackupPlugins()) {
            Path pluginsPath = serverRoot.resolve("plugins");
            if (Files.exists(pluginsPath) && Files.isDirectory(pluginsPath)) {
                Message.log("§e[备份] §7  收集插件...");
                File[] pluginEntries = pluginsPath.toFile().listFiles();
                if (pluginEntries != null) {
                    for (File f : pluginEntries) {
                        if (config.getExcludedPlugins().contains(f.getName())) {
                            Message.log("§e[备份] §8  排除插件: " + f.getName());
                            continue;
                        }
                        if (f.isDirectory()) {
                            collectDirectory(f.toPath(), files, lowerExclude);
                        } else {
                            if (!shouldExcludeFile(f.getName(), lowerExclude))
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
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    if (name.equals("session.lock")) return FileVisitResult.CONTINUE;
                    if (shouldExcludeFile(name, excludeTypes)) return FileVisitResult.CONTINUE;
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
        Path zipFile = backupDir.resolve(filename);
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
                Thread.sleep(300);
                Message.log("§e[回档] §7正在保存世界数据...");
                runSyncAndWait(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-all flush"));
                Thread.sleep(2000);

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
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (OutputStream os = Files.newOutputStream(target)) {
                        int read;
                        while ((read = zis.read(buffer)) != -1) {
                            os.write(buffer, 0, read);
                        }
                    }
                    count++;
                }
                zis.closeEntry();
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
        this.currentPhase = phase;
        Message.log("§e[备份] §7▶ " + phase);
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
            if (SchedulerUtil.isPrimaryThread()) delegate.sendMessage(msg);
            else SchedulerUtil.runSync(plugin, () -> delegate.sendMessage(msg));
        }
    }
}
