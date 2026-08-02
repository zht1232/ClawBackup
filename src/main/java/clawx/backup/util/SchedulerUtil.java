package clawx.backup.util;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;

/**
 * 调度工具 - 兼容 Paper / Purpur / Folia
 * <p>
 * Paper 1.20.4+ 与 Folia 均提供区域调度 API（io.papermc.paper.threadedregions.scheduler）。
 * 统一使用该 API，避免直接调用 {@code Bukkit.getScheduler()}（Folia 上不存在，会抛异常）。
 * 在 Paper 上同步任务运行于主线程，在 Folia 上运行于全局区域线程，语义一致。
 */
public final class SchedulerUtil {

    /** 1 tick 对应的毫秒数（异步调度器使用毫秒单位） */
    private static final long TICK_MS = 50L;

    private SchedulerUtil() {
    }

    /** 当前是否运行在同步线程（Paper 主线程 / Folia 全局区域线程） */
    public static boolean isPrimaryThread() {
        try {
            return Bukkit.isPrimaryThread();
        } catch (Throwable t) {
            return false; // Folia 无主线程概念，视为非同步上下文
        }
    }

    /** 在同步线程执行任务（可安全调用 Bukkit API） */
    public static void runSync(Plugin plugin, Runnable task) {
        Bukkit.getGlobalRegionScheduler().run(plugin, t -> task.run());
    }

    /** 延迟指定 tick 后在同步线程执行 */
    public static ScheduledTask runLater(Plugin plugin, long delayTicks, Runnable task) {
        return Bukkit.getGlobalRegionScheduler()
                .runDelayed(plugin, t -> task.run(), delayTicks);
    }

    /** 按固定周期在同步线程重复执行（返回句柄用于取消） */
    public static ScheduledTask runTimer(Plugin plugin, long delayTicks, long periodTicks, Runnable task) {
        return Bukkit.getGlobalRegionScheduler()
                .runAtFixedRate(plugin, t -> task.run(), delayTicks, periodTicks);
    }

    /** 异步执行任务 */
    public static void runAsync(Plugin plugin, Runnable task) {
        Bukkit.getAsyncScheduler().runNow(plugin, t -> task.run());
    }

    /** 按固定周期异步重复执行（异步调度单位为毫秒，1 tick = 50ms） */
    public static ScheduledTask runAsyncTimer(Plugin plugin, long delayTicks, long periodTicks, Runnable task) {
        return Bukkit.getAsyncScheduler()
                .runAtFixedRate(plugin, t -> task.run(),
                        delayTicks * TICK_MS, periodTicks * TICK_MS, TimeUnit.MILLISECONDS);
    }
}
