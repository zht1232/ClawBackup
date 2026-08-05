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

    // ==== 调度设置 ====
    private boolean scheduleEnabled = true;
    private long scheduleIntervalMinutes = 120;
    private boolean backupOnStart = true;

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
    private boolean autoRestoreQuickshop = true;

    // ==== 云备份上传 ====
    private boolean cloudBackupEnabled = false;
    private boolean githubEnabled = false;
    private String githubToken = "";
    private String githubRepo = "zht1232/ClawBackup";
    private boolean baiduEnabled = false;
    private String baiduAccessToken = "";
    private long baiduAppId = 0;
    private String baiduDir = "/apps/ClawBackup";

    // ==== 告警通知 ====
    private boolean notifyOnBackupSuccess = true;
    private boolean notifyOnBackupFailure = true;
    private boolean notifyOnBackupStart = false;
    private boolean emailEnabled = false;
    private String emailHost = "smtp.qq.com";
    private int emailPort = 465;
    private boolean emailSsl = true;
    private String emailUsername = "";
    private String emailPassword = "";
    private String emailFrom = "";
    private List<String> emailTo = Collections.emptyList();
    private boolean feishuEnabled = false;
    private String feishuWebhook = "";
    private boolean dingtalkEnabled = false;
    private String dingtalkWebhook = "";
    private String dingtalkSecret = "";

    public BackupConfig(ClawBackup plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "config.yml");
        handleMigration();
        load();
    }

    /**
     * 配置文件处理：仅当 config.yml 不存在时生成默认文件。
     * 若用户已有 config.yml，绝不覆盖、不迁移（新增字段由 load() 的默认值兜底），
     * 避免旧版启发式迁移反复把用户配置备份为 config.old.yml 且回迁不完整的问题。
     */
    private void handleMigration() {
        if (!configFile.exists()) {
            plugin.getDataFolder().mkdirs();
            plugin.saveResource("config.yml", false);
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
        // 直接加载文件，解析失败时给出明确提示（而不是静默回退默认值，让用户误以为设置失效）
        try {
            config = new YamlConfiguration();
            config.load(configFile);
        } catch (Exception e) {
            plugin.getLogger().severe("[配置] ❌ config.yml 解析失败！已临时使用默认配置，你的自定义设置不会生效。");
            plugin.getLogger().severe("[配置] 常见原因：双引号字符串里的反斜杠需转义（如 \"D:\\\\path\"），建议改用正斜杠或单引号（如 'D:/path'）。");
            plugin.getLogger().severe("[配置] 错误详情: " + e.getMessage());
            config = new YamlConfiguration();
        }

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

        // 调度
        scheduleEnabled = config.getBoolean("schedule.enabled", scheduleEnabled);
        scheduleIntervalMinutes = Math.max(1, config.getLong("schedule.interval-minutes", scheduleIntervalMinutes));
        backupOnStart = config.getBoolean("schedule.backup-on-start", backupOnStart);

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
        autoRestoreQuickshop = config.getBoolean("restore.auto-restore-quickshop", autoRestoreQuickshop);

        // 云备份上传
        cloudBackupEnabled = config.getBoolean("cloud-backup.enabled", cloudBackupEnabled);
        githubEnabled = config.getBoolean("cloud-backup.github.enabled", githubEnabled);
        githubToken = config.getString("cloud-backup.github.token", githubToken).trim();
        githubRepo = config.getString("cloud-backup.github.repo", githubRepo).trim();
        baiduEnabled = config.getBoolean("cloud-backup.baidu.enabled", baiduEnabled);
        baiduAccessToken = config.getString("cloud-backup.baidu.access-token", baiduAccessToken).trim();
        baiduAppId = Math.max(0, config.getLong("cloud-backup.baidu.app-id", baiduAppId));
        baiduDir = config.getString("cloud-backup.baidu.dir", baiduDir).trim();

        // 告警通知
        notifyOnBackupSuccess = config.getBoolean("notify.on-backup-success", notifyOnBackupSuccess);
        notifyOnBackupFailure = config.getBoolean("notify.on-backup-failure", notifyOnBackupFailure);
        notifyOnBackupStart = config.getBoolean("notify.on-backup-start", notifyOnBackupStart);
        emailEnabled = config.getBoolean("notify.email.enabled", emailEnabled);
        emailHost = config.getString("notify.email.host", emailHost).trim();
        emailPort = config.getInt("notify.email.port", emailPort);
        emailSsl = config.getBoolean("notify.email.ssl", emailSsl);
        emailUsername = config.getString("notify.email.username", emailUsername).trim();
        emailPassword = config.getString("notify.email.password", emailPassword).trim();
        emailFrom = config.getString("notify.email.from", emailFrom).trim();
        emailTo = config.getStringList("notify.email.to");
        feishuEnabled = config.getBoolean("notify.feishu.enabled", feishuEnabled);
        feishuWebhook = config.getString("notify.feishu.webhook", feishuWebhook).trim();
        dingtalkEnabled = config.getBoolean("notify.dingtalk.enabled", dingtalkEnabled);
        dingtalkWebhook = config.getString("notify.dingtalk.webhook", dingtalkWebhook).trim();
        dingtalkSecret = config.getString("notify.dingtalk.secret", dingtalkSecret).trim();

        // 调试：记录加载的路径
        plugin.getLogger().info("[配置] 备份路径: '" + backupPath + "' → 解析: " + getResolvedBackupPath());
    }

    public void reload() {
        // 直接走 load()：内部自带 try/catch 优雅降级，且只解析一次文件
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

    public boolean isScheduleEnabled() { return scheduleEnabled; }
    public long getScheduleIntervalMinutes() { return scheduleIntervalMinutes; }
    public boolean isBackupOnStart() { return backupOnStart; }

    public boolean isSmartBackup() { return smartBackup; }
    public int getSmartBackupThreshold() { return smartBackupThreshold; }

    public int getMaxBackups() { return maxBackups; }
    public long getMinFreeSpaceMB() { return minFreeSpaceMB; }

    public int getFileLockRetries() { return fileLockRetries; }
    public int getFileLockRetryDelay() { return fileLockRetryDelay; }
    public boolean isAutoSaveDisable() { return autoSaveDisable; }
    public boolean isSaveAllBeforeBackup() { return saveAllBeforeBackup; }
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
    public boolean isAutoRestoreQuickshop() { return autoRestoreQuickshop; }

    // ===== 云备份上传 =====
    public boolean isCloudBackupEnabled() { return cloudBackupEnabled; }
    public boolean isGithubEnabled() { return githubEnabled; }
    public String getGithubToken() { return githubToken; }
    public String getGithubRepo() { return githubRepo; }
    public boolean isBaiduEnabled() { return baiduEnabled; }
    public String getBaiduAccessToken() { return baiduAccessToken; }
    public long getBaiduAppId() { return baiduAppId; }
    public String getBaiduDir() { return baiduDir; }

    // ===== 告警通知 =====
    public boolean isNotifyOnBackupSuccess() { return notifyOnBackupSuccess; }
    public boolean isNotifyOnBackupFailure() { return notifyOnBackupFailure; }
    public boolean isNotifyOnBackupStart() { return notifyOnBackupStart; }
    public boolean isEmailEnabled() { return emailEnabled; }
    public String getEmailHost() { return emailHost; }
    public int getEmailPort() { return emailPort; }
    public boolean isEmailSsl() { return emailSsl; }
    public String getEmailUsername() { return emailUsername; }
    public String getEmailPassword() { return emailPassword; }
    public String getEmailFrom() { return emailFrom; }
    public List<String> getEmailTo() { return emailTo; }
    public boolean isFeishuEnabled() { return feishuEnabled; }
    public String getFeishuWebhook() { return feishuWebhook; }
    public boolean isDingtalkEnabled() { return dingtalkEnabled; }
    public String getDingtalkWebhook() { return dingtalkWebhook; }
    public String getDingtalkSecret() { return dingtalkSecret; }
}
