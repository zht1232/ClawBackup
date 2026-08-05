package clawx.backup.util;

import org.bukkit.Bukkit;

public class Message {

    public static final String LOG_PREFIX = "\u00a7e[ClawBackup] \u00a7f";

    public static void log(String message) {
        // 主线程用控制台 sender（保留颜色）；异步线程改用 logger（线程安全）并去除颜色码避免乱码
        if (Bukkit.isPrimaryThread()) {
            Bukkit.getConsoleSender().sendMessage(message);
        } else {
            Bukkit.getLogger().info(stripColor(message));
        }
    }

    /** 去除 Minecraft 颜色码（§ 后接一个字符） */
    private static String stripColor(String s) {
        return s.replaceAll("\u00a7.", "");
    }

    public static String prefix(String message) {
        return "\u00a76[ClawBackup] \u00a7f" + message;
    }
}