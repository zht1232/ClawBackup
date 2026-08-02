package clawx.backup.util;

import org.bukkit.Bukkit;

public class Message {

    public static final String LOG_PREFIX = "\u00a7e[ClawBackup] \u00a7f";

    public static void log(String message) {
        Bukkit.getConsoleSender().sendMessage(message);
    }

    public static String prefix(String message) {
        return "\u00a76[ClawBackup] \u00a7f" + message;
    }
}