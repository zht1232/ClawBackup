package clawx.backup.integration;

import clawx.backup.config.BackupConfig;
import clawx.backup.util.Message;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 通用 SQLite 数据库热备份（SQLite 官方 VACUUM INTO 在线一致性快照）。
 * <p>
 * SQLite 允许多进程并发读，官方提供 Online Backup API。通过 JDBC 执行
 * VACUUM INTO 可在插件运行时导出一致性快照（包含 WAL 中已提交数据），
 * 无需等服务器关闭。备份文件命名为 sqlitebackup-&lt;原文件名&gt;，放在
 * .db 同目录随备份打包；回档后扫描这些文件覆盖回原路径恢复。
 * <p>
 * 定位：覆盖 LuckPerms/QuickShop/CustomNameplates/MineStock 之外的所有
 * SQLite 插件（AuthMe/Brewery/PlayerPoints/TrMenu 等），无需逐插件适配。
 */
public final class SqliteBackupExporter {

    private static final String DRIVER = "org.sqlite.JDBC";
    private static final String PREFIX = "sqlitebackup-";
    private static final String[] EXTS = {".db", ".sqlite", ".sqlite3", ".db3"};

    // 本次备份中已成功 VACUUM INTO 导出的 SQLite 文件（绝对路径），
    // 供 collectFiles 判断：这些原始文件无需再直接复制（避免冗余与不一致快照）
    private static final Set<Path> successful = new HashSet<>();
    // 本次备份中 VACUUM INTO 失败的库（绝对路径），供备份汇总判断「未覆盖」
    private static final Set<Path> failedDbs = new HashSet<>();

    private SqliteBackupExporter() {
    }

    /** 该 SQLite 文件是否已被本次备份的 VACUUM INTO 成功导出（若是则跳过直接复制） */
    public static boolean isBackedUp(Path file) {
        return successful.contains(file.toAbsolutePath().normalize());
    }

    /** 备份前导出：扫描所有 SQLite 库，用 VACUUM INTO 生成一致性备份文件。返回导出成功数。 */
    public static int export(BackupConfig config) {
        if (!config.isSqliteBackupEnabled()) return 0;
        successful.clear(); // 每次备份独立，避免残留上次的记录
        failedDbs.clear();
        List<Path> dbs = findSqliteDbs();
        if (dbs.isEmpty()) return 0;
        int ok = 0;
        List<String> failed = new ArrayList<>();
        for (Path db : dbs) {
            if (isExcluded(config, db)) continue;
            try {
                Class.forName(DRIVER);
                Path backup = db.getParent().resolve(PREFIX + db.getFileName().toString());
                Files.deleteIfExists(backup);
                String dbPath = db.toAbsolutePath().normalize().toString().replace('\\', '/');
                String backupPath = backup.toAbsolutePath().normalize().toString().replace('\\', '/');
                // busy_timeout: 插件正在写库时短暂等待而不是立即失败
                String url = "jdbc:sqlite:" + dbPath + "?busy_timeout=8000";
                try (Connection conn = DriverManager.getConnection(url);
                     Statement st = conn.createStatement()) {
                    st.execute("PRAGMA busy_timeout=8000");
                    st.execute("VACUUM INTO '" + sqlQuote(backupPath) + "'");
                }
                successful.add(db.toAbsolutePath().normalize());
                ok++;
            } catch (Exception e) {
                failedDbs.add(db.toAbsolutePath().normalize());
                failed.add(db.getFileName().toString());
            }
        }
        if (ok > 0) {
            Message.log("§e[备份] §a✔ SQLite 热备份 §7(" + ok + " 个库)");
        }
        if (!failed.isEmpty()) {
            Message.log("§e[备份] §6⚠ SQLite 热备份失败 §7" + failed.size() + " 个: §7"
                    + String.join(", ", failed)
                    + " §8（这些库将按普通文件尝试打包，运行中可能被锁跳过、缺席本次备份）");
        }
        return ok;
    }

    /** 回档后恢复：ATTACH 备份库并通过 SQL 逐表复制数据到插件正在使用的库。返回恢复成功数。 */
    public static int restore() {
        List<Path> backups = new ArrayList<>();
        Path plugins = Paths.get("plugins");
        if (Files.isDirectory(plugins)) {
            try {
                Files.walkFileTree(plugins, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        String name = file.getFileName().toString();
                        if (name.startsWith(PREFIX) && isSqliteName(name.substring(PREFIX.length()))) {
                            backups.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (Exception ignored) {
            }
        }
        if (backups.isEmpty()) return 0;
        int ok = 0;
        int skipped = 0;
        for (Path backup : backups) {
            String name = backup.getFileName().toString();
            String origName = name.substring(PREFIX.length());
            Path target = backup.getParent().resolve(origName);
            try {
                Class.forName(DRIVER);
                // 等待目标插件完成数据库初始化（否则连接不存在的库会隐式创建空库、且无表可复制 → 假成功）
                if (!waitForTargetReady(target)) {
                    Message.log("§c[回档] §4SQLite 恢复跳过（目标库未初始化）§7" + name);
                    skipped++;
                    continue;
                }
                String targetPath = target.toAbsolutePath().normalize().toString().replace('\\', '/');
                String backupPath = backup.toAbsolutePath().normalize().toString().replace('\\', '/');
                // 不替换文件（插件已打开库，覆盖会被占用）：改为 ATTACH 备份库后逐表复制数据
                String url = "jdbc:sqlite:" + targetPath + "?busy_timeout=8000";
                int copiedTables = 0;
                try (Connection conn = DriverManager.getConnection(url);
                     Statement st = conn.createStatement()) {
                    st.execute("PRAGMA busy_timeout=8000");
                    st.execute("ATTACH DATABASE '" + sqlQuote(backupPath) + "' AS bak");
                    List<String> tables = new ArrayList<>();
                    try (ResultSet rs = st.executeQuery(
                            "SELECT name FROM bak.sqlite_master WHERE type='table'")) {
                        while (rs.next()) {
                            String t = rs.getString(1);
                            if (t != null && !t.startsWith("sqlite_")) tables.add(t);
                        }
                    }
                    conn.setAutoCommit(false);
                    for (String t : tables) {
                        // 目标库没有该表（插件版本差异）则跳过
                        if (!targetHasTable(st, t)) continue;
                        String id = "\"" + t.replace("\"", "\"\"") + "\"";
                        st.executeUpdate("DELETE FROM " + id);
                        st.executeUpdate("INSERT INTO " + id + " SELECT * FROM bak." + id);
                        copiedTables++;
                    }
                    conn.setAutoCommit(true); // 提交事务
                    try { st.execute("DETACH DATABASE bak"); } catch (Exception ignored) {}
                }
                if (copiedTables > 0) {
                    ok++;
                    Message.log("§e[回档] §a✔ SQLite 恢复 §7" + name + " §8(" + copiedTables + " 张表)");
                } else {
                    Message.log("§c[回档] §4SQLite 恢复失败（无表可复制，目标库结构可能不匹配）§7" + name);
                    skipped++;
                }
            } catch (Exception e) {
                Message.log("§c[回档] §4SQLite 恢复失败 §7" + name);
                skipped++;
            }
        }
        Message.log("§e[回档] §a✔ SQLite 恢复 §7(" + ok + "/" + backups.size() + " 个库"
                + (skipped > 0 ? "，跳过 " + skipped + " 个" : "") + ")");
        return ok;
    }

    /**
     * 回档时直接复制恢复（在回档解压阶段调用——此时所有插件已被禁用、库文件无占用）。
     * <p>
     * 把 VACUUM INTO 生成的一致性快照 sqlitebackup-*.db 整体覆盖回原 .db 路径。
     * 相比 ATTACH 逐表复制，直接复制整个库更完整可靠：保留索引/触发器/自增序列，
     * 不受备份库与目标库表结构差异影响，也不会出现 sqlite_sequence 未恢复导致的主键冲突。
     * <p>
     * 复制成功即删除快照文件；失败（文件仍被占用等）则保留，由下次启动的
     * restore()（ATTACH 逐表）兜底处理。
     */
    public static int restoreByCopy() {
        List<Path> backups = new ArrayList<>();
        Path plugins = Paths.get("plugins");
        if (Files.isDirectory(plugins)) {
            try {
                Files.walkFileTree(plugins, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        String name = file.getFileName().toString();
                        if (name.startsWith(PREFIX) && isSqliteName(name.substring(PREFIX.length()))) {
                            backups.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (Exception ignored) {
            }
        }
        if (backups.isEmpty()) return 0;
        int ok = 0;
        int failed = 0;
        for (Path backup : backups) {
            String name = backup.getFileName().toString();
            String origName = name.substring(PREFIX.length());
            Path target = backup.getParent().resolve(origName);
            boolean copied = false;
            // 重试几次，等待插件彻底释放文件句柄（onDisable 阶段一般已释放）
            for (int attempt = 0; attempt < 5 && !copied; attempt++) {
                try {
                    Files.copy(backup, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    copied = true;
                } catch (IOException e) {
                    try { Thread.sleep(1000); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); return ok; }
                }
            }
            if (copied) {
                try { Files.deleteIfExists(backup); } catch (Exception ignored) {}
                ok++;
                Message.log("§e[回档] §a✔ SQLite 直接复制恢复 §7" + name + " §8→ " + origName);
            } else {
                Message.log("§c[回档] §4SQLite 直接复制失败（文件仍被占用）§7" + name
                        + " §8（将由下次启动的逐表恢复兜底）");
                failed++;
            }
        }
        Message.log("§e[回档] §a✔ SQLite 直接复制恢复 §7(" + ok + "/" + backups.size() + " 个库"
                + (failed > 0 ? "，失败 " + failed + " 个（将走启动兜底）" : "") + ")");
        return ok;
    }

    /**
     * 等待目标库就绪：文件已存在且至少有一张业务表（插件可能懒初始化）。
     * 最多等待 60 秒（30 次 × 2 秒）；期间不连接不存在的文件，避免隐式创建空库。
     */
    private static boolean waitForTargetReady(Path target) {
        String path = target.toAbsolutePath().normalize().toString().replace('\\', '/');
        String url = "jdbc:sqlite:" + path + "?busy_timeout=8000";
        for (int attempt = 0; attempt < 30; attempt++) {
            if (Files.exists(target)) {
                try (Connection c = DriverManager.getConnection(url);
                     Statement s = c.createStatement();
                     ResultSet rs = s.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table'")) {
                    if (rs.next() && rs.getInt(1) > 0) return true;
                } catch (Exception ignored) {
                    // 插件可能正在独占初始化，稍后重试
                }
            }
            try { Thread.sleep(2000); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); return false; }
        }
        return false;
    }

    /** 转义 SQL 字符串字面量中的单引号，避免文件名破坏 VACUUM INTO / ATTACH 语句 */
    private static String sqlQuote(String s) {
        return s.replace("'", "''");
    }

    /** 目标库（main）是否存在指定表 */
    private static boolean targetHasTable(Statement st, String table) throws Exception {
        try (ResultSet rs = st.executeQuery(
                "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='"
                        + table.replace("'", "''") + "'")) {
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    /** 扫描 plugins/ 下所有 SQLite 数据库文件（跳过已生成的 sqlitebackup-* 备份） */
    private static List<Path> findSqliteDbs() {
        List<Path> result = new ArrayList<>();
        Path plugins = Paths.get("plugins");
        if (!Files.isDirectory(plugins)) return result;
        try {
            Files.walkFileTree(plugins, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    if (name.startsWith(PREFIX)) return FileVisitResult.CONTINUE;
                    if (isSqliteName(name)) result.add(file);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception ignored) {
        }
        return result;
    }

    private static boolean isSqliteName(String name) {
        String lower = name.toLowerCase();
        // 排除 H2 的 .mv.db/.trace.db/.lock.db（它们也以 .db 结尾，但不是 SQLite）
        if (lower.endsWith(".mv.db") || lower.endsWith(".trace.db") || lower.endsWith(".lock.db")) {
            return false;
        }
        for (String ext : EXTS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    /** 是否排除该库 */
    private static boolean isExcluded(BackupConfig config, Path db) {
        List<String> ex = config.getSqliteBackupExcluded();
        if (ex == null || ex.isEmpty()) return false;
        String rel = db.toString().replace('\\', '/');
        String name = db.getFileName().toString();
        for (String e : ex) {
            String s = e.trim().replace('\\', '/');
            if (s.isEmpty()) continue;
            if (rel.contains(s) || name.contains(s)) return true;
        }
        return false;
    }
}
