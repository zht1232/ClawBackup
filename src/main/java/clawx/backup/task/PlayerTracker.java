package clawx.backup.task;

import clawx.backup.ClawBackup;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 玩家在线追踪器
 */
public class PlayerTracker implements Listener {

    private final ClawBackup plugin;
    private final AtomicInteger onlineCount = new AtomicInteger(0);

    public PlayerTracker(ClawBackup plugin) {
        this.plugin = plugin;
        int initial = Bukkit.getOnlinePlayers().size();
        onlineCount.set(initial);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        onlineCount.incrementAndGet();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        onlineCount.decrementAndGet();
    }

    public int getOnlineCount() {
        return onlineCount.get();
    }

    public boolean hasPlayers() {
        return onlineCount.get() > 0;
    }

    /** 取消事件监听（热重载/禁用时调用） */
    public void unregister() {
        org.bukkit.event.HandlerList.unregisterAll(this);
        onlineCount.set(0);
    }
}
