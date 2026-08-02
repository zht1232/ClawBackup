package clawx.backup.task;

import clawx.backup.ClawBackup;
import clawx.backup.config.BackupConfig;
import clawx.backup.util.Message;
import clawx.backup.util.SchedulerUtil;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

/**
 * 定时备份调度器
 */
public class BackupScheduler {

    private final ClawBackup plugin;
    private final BackupConfig config;
    private final BackupManager backupManager;
    private final PlayerTracker playerTracker;
    private ScheduledTask scheduledTask = null;
    private long lastBackupTime = 0;

    public BackupScheduler(ClawBackup plugin) {
        this.plugin = plugin;
        this.config = plugin.getBackupConfig();
        this.backupManager = plugin.getBackupManager();
        this.playerTracker = plugin.getPlayerTracker();
        start();
    }

    private void start() {
        if (!config.isScheduleEnabled()) return;

        long interval = config.getScheduleIntervalMinutes() * 60 * 20; // 转换为 tick
        Message.log("§e[调度] §7定时备份已启动，间隔: " + config.getScheduleIntervalMinutes() + " 分钟");

        // 直接用同步定时器，检查开销很低，不会阻塞主线程
        scheduledTask = SchedulerUtil.runTimer(plugin, interval, interval, this::checkAndBackup);

        lastBackupTime = System.currentTimeMillis();
    }

    private void checkAndBackup() {
        // 检查是否已有任务在运行
        if (backupManager.isRunning()) {
            Message.log("§e[调度] §8上次备份仍在进行中，跳过本次");
            return;
        }

        // 智能备份检查
        if (config.isSmartBackup()) {
            int onlineCount = playerTracker.getOnlineCount();
            int threshold = config.getSmartBackupThreshold();

            if (onlineCount <= threshold) {
                Message.log("§e[调度] §7智能模式: 在线玩家数 (" + onlineCount + ") ≤ " 
                        + threshold + "，跳过本次备份");
                return;
            }
        }

        Message.log("§e[调度] §7定时备份触发");
        backupManager.runBackup("定时", null);
        lastBackupTime = System.currentTimeMillis();
    }

    public long getLastBackupTime() {
        return lastBackupTime;
    }

    public void shutdown() {
        if (scheduledTask != null) {
            scheduledTask.cancel();
            scheduledTask = null;
        }
    }

    /**
     * 重启调度器（配置变更后调用）
     */
    public void restart() {
        shutdown();
        start();
    }
}
