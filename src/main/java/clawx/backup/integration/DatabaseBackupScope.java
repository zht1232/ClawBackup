package clawx.backup.integration;

import clawx.backup.ClawBackup;
import clawx.backup.config.BackupConfig;

import java.nio.file.Path;

/** Shared inclusion rules for database snapshots inside plugins/. */
final class DatabaseBackupScope {

    private DatabaseBackupScope() {
    }

    static boolean includes(BackupConfig config, Path file) {
        if (config == null || file == null || !config.isBackupPlugins()) return false;

        Path pluginRoot = ClawBackup.getServerRoot().resolve("plugins").toAbsolutePath().normalize();
        Path absoluteFile = file.toAbsolutePath().normalize();
        if (!absoluteFile.startsWith(pluginRoot)) return false;

        Path relative = pluginRoot.relativize(absoluteFile);
        if (relative.getNameCount() == 0) return false;
        return !config.getExcludedPlugins().contains(relative.getName(0).toString());
    }

    static boolean includesForRestore(BackupConfig config, Path file) {
        if (!includes(config, file)) return false;
        Path pluginRoot = ClawBackup.getServerRoot().resolve("plugins").toAbsolutePath().normalize();
        Path relative = pluginRoot.relativize(file.toAbsolutePath().normalize());
        return relative.getNameCount() > 0
                && !config.getRestoreExcludedPlugins().contains(relative.getName(0).toString());
    }
}
