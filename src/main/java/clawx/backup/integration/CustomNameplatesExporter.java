package clawx.backup.integration;

import clawx.backup.util.Message;
import net.momirealms.customnameplates.api.CustomNameplates;
import net.momirealms.customnameplates.api.storage.DataStorageProvider;
import net.momirealms.customnameplates.api.storage.StorageManager;
import net.momirealms.customnameplates.api.storage.data.PlayerData;
import org.bukkit.Bukkit;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * CustomNameplates 数据导出/导入（通过官方 API）。
 * <p>
 * CustomNameplates 使用 H2 连接池独占锁定数据库文件（h2.mv.db），运行期间无法直接复制，
 * 且插件没有内置导出命令。这里通过其公开 API 在备份前把每个玩家的数据
 * （nameplate / bubble / flags）读出来写入 backup.json，随备份一起打包；
 * 回档后读取该文件并通过 API 写回 H2。
 */
public final class CustomNameplatesExporter {

    private static final String PLUGIN_NAME = "CustomNameplates";
    private static final Path EXPORT_FILE = Paths.get("plugins", "CustomNameplates", "backup.json");
    private static final long PER_PLAYER_TIMEOUT_MS = 5000;

    private CustomNameplatesExporter() {
    }

    /** CustomNameplates 插件是否已加载 */
    public static boolean isAvailable() {
        return Bukkit.getPluginManager().getPlugin(PLUGIN_NAME) != null;
    }

    /** 备份前导出：把 H2 中全部玩家数据写入 backup.json（随备份打包）。返回是否执行了导出。 */
    public static boolean export() {
        if (!isAvailable()) return false;
        try {
            // 先删除旧导出，避免打包到上一次的旧数据
            Files.deleteIfExists(EXPORT_FILE);
            StorageManager sm = CustomNameplates.getInstance().getStorageManager();
            DataStorageProvider ds = sm.dataSource();
            Set<UUID> users = ds.getUniqueUsers();
            if (users == null || users.isEmpty()) return true;

            StringBuilder sb = new StringBuilder();
            int exported = 0;
            for (UUID uuid : users) {
                try {
                    Optional<PlayerData> opt = ds.getPlayerData(uuid, Runnable::run)
                            .get(PER_PLAYER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    if (opt != null && opt.isPresent()) {
                        String json = sm.toJson(opt.get());
                        if (json != null && !json.isEmpty()) {
                            sb.append(uuid).append('\t').append(json).append('\n');
                            exported++;
                        }
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    Message.log("§c[备份] §4CustomNameplates 导出玩家失败 §7" + uuid
                            + " §8(" + e.getMessage() + ")");
                }
            }
            Files.write(EXPORT_FILE, sb.toString().getBytes(StandardCharsets.UTF_8));
            Message.log("§e[备份] §a✔ CustomNameplates 数据已导出 §7(" + exported + "/" + users.size() + " 名玩家)");
            return true;
        } catch (Exception e) {
            Message.log("§c[备份] §4CustomNameplates 导出失败: " + e.getMessage());
            return false;
        }
    }

    /** 回档后导入：读取 backup.json 并把数据写回 H2。返回是否导入了数据。 */
    public static boolean restore() {
        if (!isAvailable()) return false;
        if (!Files.exists(EXPORT_FILE)) return false;
        try {
            StorageManager sm = CustomNameplates.getInstance().getStorageManager();
            DataStorageProvider ds = sm.dataSource();
            int count = 0;
            try (BufferedReader r = Files.newBufferedReader(EXPORT_FILE, StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    int tab = line.indexOf('\t');
                    if (tab <= 0) continue;
                    UUID uuid;
                    try {
                        uuid = UUID.fromString(line.substring(0, tab));
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    String json = line.substring(tab + 1);
                    try {
                        PlayerData pd = sm.fromJson(uuid, json);
                        ds.updatePlayerData(pd, Runnable::run)
                                .get(PER_PLAYER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                        count++;
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        Message.log("§c[回档] §4CustomNameplates 导入玩家失败 §7" + uuid
                                + " §8(" + e.getMessage() + ")");
                    }
                }
            }
            Message.log("§e[回档] §a✔ CustomNameplates 数据已导入 §7(" + count + " 名玩家)");
            return true;
        } catch (Exception e) {
            Message.log("§c[回档] §4CustomNameplates 导入失败: " + e.getMessage());
            return false;
        }
    }
}
