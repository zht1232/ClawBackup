package clawx.backup.config;

import clawx.backup.ClawBackup;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;

/**
 * 备份配置文件管理
 * <p>
 * 支持旧配置自动迁移：升级插件时，旧 config.yml 会被备份为 config.old.yml，
 * 新默认配置写入 config.yml，然后从旧文件回迁所有可识别的值。
 */
public class BackupConfig {

    private final ClawBackup plugin;
    private File configFile;
    private FileConfiguration config;

    // ==== 备份目标 ====
    private boolean backupPlugins = true;
    private boolean backupWorlds = true;
    private boolean worldAutoDiscover = true;
    private List<String> worldList = Arrays.asList("world", "world_nether", "world_the_end");
    private List<String> excludedPlugins = Arrays.asList("ClawBackup");
    private List<String> excludedWorlds = Collections.emptyList();
    private List<String> preBackupCommands = Collections.emptyList();

    // ==== 存储设置 ====
    private String backupPath = "backups";
    private int compressionLevel = 6;
    private boolean useTempDir = false;
    private String tempPath = "plugins/ClawBackup/temp";

    // ==== 调度设置 ====
    private boolean scheduleEnabled = true;
    private long scheduleIntervalMinutes = 120;
    private boolean backupOnStart = true;
    private boolean backupOnStop = false;

    // ==== 智能备份 ====
    private boolean smartBackup = true;
    private int smartBackupThreshold = 0;

    // ==== 保留策略 ====
    private int maxBackups = 30;
    private long minFreeSpaceMB = 500;

    // ==== 高级设置 ====
    private int fileLockRetries = 3;
    private int fileLockRetryDelay = 1000;
    private boolean autoSaveDisable = true;
    private boolean saveAllBeforeBackup = true;
    private int backupTimeoutMinutes = 30;
    private List<String> excludeFileTypes = Arrays.asList(".log", ".tmp", ".DS_Store", "thumbs.db");

    // ==== IO 限速 (防止备份卡服) ====
    private int ioThrottleKBps = 0;
    private int ioThrottleChunkKB = 64;

    // ==== TPS 保护 ====
    private boolean tpsProtectionEnabled = true;
    private double tpsThreshold = 15.0;

    // ==== 广播倒计时 ====
    private boolean broadcastCountdown = true;
    private int countdownSeconds = 5;

    // ==== 通知设置 ====
    private boolean notifyPlayers = true;
    private boolean showProgress = true;
    private int progressInterval = 20;

    // ==== 回档设置 ====
    private boolean autoStopAfterRestore = true;
    private int autoStopDelaySeconds = 5;
    private List<String> restoreExcludedPlugins = Collections.emptyList();
    private List<String> postRestoreCommands = Collections.emptyList();
    private boolean autoHookPlugins = true;

    public BackupConfig(ClawBackup plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "config.yml");
        handleMigration();
        load();
    }

    /** 旧配置自动迁移：备份旧文件 → 写入新默认 → 回迁旧值 */
    private void handleMigration() {
        if (!configFile.exists()) {
            plugin.getDataFolder().mkdirs();
            plugin.saveResource("config.yml", false);
            return;
        }

        // 检查是否旧版本配置（通过检测关键新字段和废弃字段判断）
        YamlConfiguration oldConfig = YamlConfiguration.loadConfiguration(configFile);
        boolean isOldVersion = oldConfig.contains("retention.keep-days")      // 1.0.x 废弃字段
                            || !oldConfig.contains("backup.world-auto-discover")  // 缺少新字段
                            || !oldConfig.contains("advanced.io-throttle-kbps")
                            || !oldConfig.contains("advanced.tps-protection-enabled");

        if (!isOldVersion) return;  // 已经是新版本配置，无需迁移

        plugin.getLogger().info("[配置迁移] 检测到旧版本配置文件，正在自动迁移...");

        try {
            // 1. 备份旧文件
            File backup = new File(configFile.getParentFile(), "config.old.yml");
            // 删除上一次的旧备份
            Files.deleteIfExists(backup.toPath());
            Files.copy(configFile.toPath(), backup.toPath());
            plugin.getLogger().info("[配置迁移] 旧配置已备份为 config.old.yml");

            // 2. 写入新的默认配置
            plugin.saveResource("config.yml", true);
            plugin.getLogger().info("[配置迁移] 新默认配置已写入");

            // 3. 重新加载为 YamlConfiguration 以合并旧值
            YamlConfiguration newConfig = YamlConfiguration.loadConfiguration(configFile);

            // 3a. 回迁旧配置中仍存在的值
            transferIfExists(oldConfig, newConfig, "backup.plugins");
            transferIfExists(oldConfig, newConfig, "backup.worlds");
            transferIfExists(oldConfig, newConfig, "backup.world-list");
            transferIfExists(oldConfig, newConfig, "backup.excluded-plugins");
            transferIfExists(oldConfig, newConfig, "backup.excluded-worlds");

            transferIfExists(oldConfig, newConfig, "storage.backup-path");
            transferIfExists(oldConfig, newConfig, "storage.compression-level");
            transferIfExists(oldConfig, newConfig, "storage.use-temp-dir");
            transferIfExists(oldConfig, newConfig, "storage.temp-path");

            transferIfExists(oldConfig, newConfig, "schedule.enabled");
            transferIfExists(oldConfig, newConfig, "schedule.interval-minutes");
            transferIfExists(oldConfig, newConfig, "schedule.backup-on-start");
            transferIfExists(oldConfig, newConfig, "schedule.backup-on-stop");

            transferIfExists(oldConfig, newConfig, "smart.enabled");
            transferIfExists(oldConfig, newConfig, "smart.min-players");

            transferIfExists(oldConfig, newConfig, "retention.max-backups");
            transferIfExists(oldConfig, newConfig, "retention.min-free-space-mb");

            transferIfExists(oldConfig, newConfig, "advanced.file-lock-retries");
            transferIfExists(oldConfig, newConfig, "advanced.file-lock-retry-delay-ms");
            transferIfExists(oldConfig, newConfig, "advanced.disable-autosave-during-backup");
            transferIfExists(oldConfig, newConfig, "advanced.save-all-before-backup");
            transferIfExists(oldConfig, newConfig, "advanced.backup-timeout-minutes");
            transferIfExists(oldConfig, newConfig, "advanced.exclude-file-types");

            transferIfExists(oldConfig, newConfig, "notification.notify-players");
            transferIfExists(oldConfig, newConfig, "notification.show-progress");
            transferIfExists(oldConfig, newConfig, "notification.progress-interval-ticks");

            // 清理废弃字段（旧版 keep-days 等）
            String[] deprecatedKeys = {"retention.keep-days"};
            for (String key : deprecatedKeys) {
                if (newConfig.contains(key)) {
                    newConfig.set(key, null);
                    plugin.getLogger().info("[配置迁移] 已移除废弃字段: " + key);
                }
            }

            // 保存合并后的配置
            newConfig.save(configFile);
            plugin.getLogger().info("[配置迁移] ✅ 配置迁移完成");
        } catch (Exception e) {
            plugin.getLogger().severe("[配置迁移] 迁移失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** 如果 old 中有该路径，则复制到 new */
    private static void transferIfExists(YamlConfiguration oldCfg, YamlConfiguration newCfg, String path) {
        if (oldCfg.contains(path)) {
            Object val = oldCfg.get(path);
            newCfg.set(path, val);
        }
    }

    private void load() {
        config = YamlConfiguration.loadConfiguration(configFile);

        // 备份目标
        backupPlugins = config.getBoolean("backup.plugins", backupPlugins);
        backupWorlds = config.getBoolean("backup.worlds", backupWorlds);
        worldAutoDiscover = config.getBoolean("backup.world-auto-discover", worldAutoDiscover);
        worldList = config.getStringList("backup.world-list");
        excludedPlugins = config.getStringList("backup.excluded-plugins");
        excludedWorlds = config.getStringList("backup.excluded-worlds");
        preBackupCommands = config.getStringList("backup.pre-backup-commands");

        // 存储 - 路径去首尾空格（防止 " D:\path " 导致解析问题）
        backupPath = config.getString("storage.backup-path", backupPath).trim();
        compressionLevel = clamp(config.getInt("storage.compression-level", compressionLevel), 0, 9);
        useTempDir = config.getBoolean("storage.use-temp-dir", useTempDir);
        tempPath = config.getString("storage.temp-path", tempPath).trim();

        // 调度
        scheduleEnabled = config.getBoolean("schedule.enabled", scheduleEnabled);
        scheduleIntervalMinutes = Math.max(1, config.getLong("schedule.interval-minutes", scheduleIntervalMinutes));
        backupOnStart = config.getBoolean("schedule.backup-on-start", backupOnStart);
        backupOnStop = config.getBoolean("schedule.backup-on-stop", backupOnStop);

        // 智能
        smartBackup = config.getBoolean("smart.enabled", smartBackup);
        smartBackupThreshold = Math.max(0, config.getInt("smart.min-players", smartBackupThreshold));

        // 保留
        maxBackups = Math.max(1, config.getInt("retention.max-backups", maxBackups));
        minFreeSpaceMB = Math.max(1, config.getLong("retention.min-free-space-mb", minFreeSpaceMB));

        // 高级
        fileLockRetries = Math.max(0, config.getInt("advanced.file-lock-retries", fileLockRetries));
        fileLockRetryDelay = Math.max(10, config.getInt("advanced.file-lock-retry-delay-ms", fileLockRetryDelay));
        autoSaveDisable = config.getBoolean("advanced.disable-autosave-during-backup", autoSaveDisable);
        saveAllBeforeBackup = config.getBoolean("advanced.save-all-before-backup", saveAllBeforeBackup);
        backupTimeoutMinutes = Math.max(1, config.getInt("advanced.backup-timeout-minutes", backupTimeoutMinutes));
        excludeFileTypes = config.getStringList("advanced.exclude-file-types");

        // IO 限速
        ioThrottleKBps = Math.max(0, config.getInt("advanced.io-throttle-kbps", ioThrottleKBps));
        ioThrottleChunkKB = Math.max(1, Math.min(256, config.getInt("advanced.io-throttle-chunk-kb", ioThrottleChunkKB)));

        // TPS 保护
        tpsProtectionEnabled = config.getBoolean("advanced.tps-protection-enabled", tpsProtectionEnabled);
        tpsThreshold = clamp(config.getDouble("advanced.tps-threshold", tpsThreshold), 5.0, 19.0);

        // 广播倒计时
        broadcastCountdown = config.getBoolean("advanced.broadcast-countdown", broadcastCountdown);
        countdownSeconds = Math.max(0, Math.min(30, config.getInt("advanced.countdown-seconds", countdownSeconds)));

        // 通知
        notifyPlayers = config.getBoolean("notification.notify-players", notifyPlayers);
        showProgress = config.getBoolean("notification.show-progress", showProgress);
        progressInterval = Math.max(1, config.getInt("notification.progress-interval-ticks", progressInterval));

        // 回档设置
        autoStopAfterRestore = config.getBoolean("restore.auto-stop-after-restore", autoStopAfterRestore);
        autoStopDelaySeconds = Math.max(0, config.getInt("restore.auto-stop-delay-seconds", autoStopDelaySeconds));
        restoreExcludedPlugins = config.getStringList("restore.excluded-plugins");
        postRestoreCommands = config.getStringList("restore.post-restore-commands");
        autoHookPlugins = config.getBoolean("restore.auto-hook-plugins", autoHookPlugins);

        // 调试：记录加载的路径
        plugin.getLogger().info("[配置] 备份路径: '" + backupPath + "' → 解析: " + getResolvedBackupPath());
    }

    public void reload() {
        config = YamlConfiguration.loadConfiguration(configFile);
        load();
    }

    public Path getResolvedBackupPath() {
        Path p = Paths.get(backupPath);
        return p.isAbsolute() ? p.normalize() : Paths.get(".").resolve(p).normalize();
    }

    private static int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }
    private static double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }

    // ===================== Getters =====================
    public boolean isBackupPlugins() { return backupPlugins; }
    public boolean isBackupWorlds() { return backupWorlds; }
    public boolean isWorldAutoDiscover() { return worldAutoDiscover; }
    public List<String> getWorldList() { return worldList; }
    public List<String> getExcludedPlugins() { return excludedPlugins; }
    public List<String> getExcludedWorlds() { return excludedWorlds; }
    public List<String> getPreBackupCommands() { return preBackupCommands; }

    public String getBackupPath() { return backupPath; }
    public int getCompressionLevel() { return compressionLevel; }
    public boolean isUseTempDir() { return useTempDir; }
    public String getTempPath() { return tempPath; }

    public boolean isScheduleEnabled() { return scheduleEnabled; }
    public long getScheduleIntervalMinutes() { return scheduleIntervalMinutes; }
    public boolean isBackupOnStart() { return backupOnStart; }
    public boolean isBackupOnStop() { return backupOnStop; }

    public boolean isSmartBackup() { return smartBackup; }
    public int getSmartBackupThreshold() { return smartBackupThreshold; }

    public int getMaxBackups() { return maxBackups; }
    public long getMinFreeSpaceMB() { return minFreeSpaceMB; }

    public int getFileLockRetries() { return fileLockRetries; }
    public int getFileLockRetryDelay() { return fileLockRetryDelay; }
    public boolean isAutoSaveDisable() { return autoSaveDisable; }
    public boolean isSaveAllBeforeBackup() { return saveAllBeforeBackup; }
    public int getBackupTimeoutMinutes() { return backupTimeoutMinutes; }
    public List<String> getExcludeFileTypes() { return excludeFileTypes; }

    public int getIoThrottleKBps() { return ioThrottleKBps; }
    public int getIoThrottleChunkKB() { return ioThrottleChunkKB; }

    public boolean isTpsProtectionEnabled() { return tpsProtectionEnabled; }
    public double getTpsThreshold() { return tpsThreshold; }

    public boolean isBroadcastCountdown() { return broadcastCountdown; }
    public int getCountdownSeconds() { return countdownSeconds; }

    public boolean isNotifyPlayers() { return notifyPlayers; }
    public boolean isShowProgress() { return showProgress; }
    public int getProgressInterval() { return progressInterval; }

    public boolean isAutoStopAfterRestore() { return autoStopAfterRestore; }
    public int getAutoStopDelaySeconds() { return autoStopDelaySeconds; }
    public List<String> getRestoreExcludedPlugins() { return restoreExcludedPlugins; }
    public List<String> getPostRestoreCommands() { return postRestoreCommands; }
    public boolean isAutoHookPlugins() { return autoHookPlugins; }
}
