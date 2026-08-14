package clawx.backup.integration;

import clawx.backup.config.BackupConfig;
import clawx.backup.util.Message;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 通用 H2 数据库兜底备份。
 * <p>
 * 对被锁的 .mv.db（运行时无法复制文件）逐个尝试以 AUTO_SERVER=TRUE 连接，
 * 能连上的用 H2 内置 SCRIPT TO 导出整个库为 SQL（放在 .mv.db 同目录 h2backup-&lt;库名&gt;.sql），
 * 随备份一起打包；回档后扫描这些 h2backup-*.sql 用 RUNSCRIPT FROM 恢复。
 * 连不上的（默认排它锁）跳过并记录，不影响备份主流程。
 * <p>
 * 定位：各插件专用钩子（LuckPerms/QuickShop/CustomNameplates/MineStock）之外的通用兜底，
 * 能覆盖所有开启 AUTO_SERVER=TRUE 的 H2 插件，无需逐插件适配。
 */
public final class H2BackupExporter {

    private static final String H2_DRIVER = "org.h2.Driver";
    private static final String PREFIX = "h2backup-";
    private static final String SUFFIX = ".sql";

    // 本次备份中 SCRIPT TO 导出成功的 .mv.db（绝对路径），供备份汇总判断「已覆盖」
    private static final Set<Path> exported = new HashSet<>();
    // 尝试连接但失败的 .mv.db
    private static final Set<Path> failedDbs = new HashSet<>();

    private H2BackupExporter() {
    }

    /** 该 H2 库是否已被本次备份的 SCRIPT TO 成功导出 */
    public static boolean isExported(Path file) {
        return exported.contains(file.toAbsolutePath().normalize());
    }

    /** 备份前导出：扫描被锁的 H2 库，能连的用 SCRIPT TO 导出为 SQL。返回导出成功数。 */
    public static int export(BackupConfig config) {
        if (!config.isH2BackupEnabled()) return 0;
        exported.clear();
        failedDbs.clear();
        List<Path> locked = findLockedH2Dbs();
        if (locked.isEmpty()) return 0;
        int ok = 0;
        List<String> failed = new ArrayList<>();
        for (Path db : locked) {
            if (isExcluded(config, db)) continue;
            try {
                Class.forName(H2_DRIVER);
                Path dir = db.getParent();
                String fileName = db.getFileName().toString();
                String dbName = fileName.substring(0, fileName.length() - ".mv.db".length());
                Path sql = dir.resolve(PREFIX + dbName + SUFFIX);
                Files.deleteIfExists(sql);
                String sqlPath = sql.toAbsolutePath().normalize().toString().replace('\\', '/');
                String url = "jdbc:h2:file:" + dbUrlBase(db) + ";AUTO_SERVER=TRUE";
                try (Connection conn = DriverManager.getConnection(url);
                     Statement st = conn.createStatement()) {
                    st.execute("SCRIPT TO '" + sqlQuote(sqlPath) + "'");
                }
                exported.add(db.toAbsolutePath().normalize());
                ok++;
            } catch (Exception e) {
                failedDbs.add(db.toAbsolutePath().normalize());
                failed.add(db.getFileName().toString());
            }
        }
        if (ok > 0) {
            Message.log("§e[备份] §a✔ H2 兜底导出 §7(" + ok + " 个库)");
        }
        if (!failed.isEmpty()) {
            Message.log("§e[备份] §6⚠ H2 跳过 §7" + failed.size() + " 个: §7"
                    + String.join(", ", failed));
        }
        return ok;
    }

    /** 回档后导入：扫描所有 h2backup-*.sql 并 RUNSCRIPT 恢复。返回恢复成功数。 */
    public static int restore() {
        List<Path> sqls = new ArrayList<>();
        Path plugins = Paths.get("plugins");
        if (Files.isDirectory(plugins)) {
            try {
                Files.walkFileTree(plugins, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        String name = file.getFileName().toString();
                        if (name.startsWith(PREFIX) && name.endsWith(SUFFIX)) {
                            sqls.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (Exception ignored) {
            }
        }
        if (sqls.isEmpty()) return 0;
        int ok = 0;
        for (Path sql : sqls) {
            String name = sql.getFileName().toString();
            String dbName = name.substring(PREFIX.length(), name.length() - SUFFIX.length());
            try {
                Class.forName(H2_DRIVER);
                String dirUrl = sql.getParent().toAbsolutePath().normalize().toString().replace('\\', '/');
                String url = "jdbc:h2:file:" + dirUrl + "/" + dbName + ";AUTO_SERVER=TRUE";
                String sqlPath = sql.toAbsolutePath().normalize().toString().replace('\\', '/');
                try (Connection conn = DriverManager.getConnection(url);
                     Statement st = conn.createStatement()) {
                    // 回档后目标库可能是插件刚创建的空库，先清空再重建，避免表已存在冲突
                    st.execute("DROP ALL OBJECTS");
                    st.execute("RUNSCRIPT FROM '" + sqlQuote(sqlPath) + "'");
                }
                ok++;
            } catch (Exception e) {
                Message.log("§c[回档] §4H2 恢复失败 §7" + sql.getFileName());
            }
        }
        Message.log("§e[回档] §a✔ H2 兜底恢复 §7(" + ok + "/" + sqls.size() + " 个库)");
        return ok;
    }

    /** 数据库文件的基础路径（不含 .mv.db），转正斜杠，用于 JDBC URL */
    private static String dbUrlBase(Path db) {
        String abs = db.toAbsolutePath().normalize().toString();
        return abs.substring(0, abs.length() - ".mv.db".length()).replace('\\', '/');
    }

    /** 转义 SQL 字符串字面量中的单引号，避免路径破坏 SCRIPT TO / RUNSCRIPT FROM 语句 */
    private static String sqlQuote(String s) {
        return s.replace("'", "''");
    }

    /** 是否排除该库 */
    private static boolean isExcluded(BackupConfig config, Path db) {
        List<String> ex = config.getH2BackupExcluded();
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

    /** 扫描 plugins/ 下被锁定的 .mv.db（未被锁的会正常打包，无需导出） */
    private static List<Path> findLockedH2Dbs() {
        List<Path> result = new ArrayList<>();
        Path plugins = Paths.get("plugins");
        if (!Files.isDirectory(plugins)) return result;
        try {
            Files.walkFileTree(plugins, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.getFileName().toString().endsWith(".mv.db") && isFileLocked(file)) {
                        result.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception ignored) {
        }
        return result;
    }

    /** 检测文件是否被其他进程锁定 */
    private static boolean isFileLocked(Path file) {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            FileLock lock = ch.tryLock(0, Long.MAX_VALUE, true);
            if (lock == null) return true;
            lock.release();
            return false;
        } catch (Exception e) {
            return true;
        }
    }
}
