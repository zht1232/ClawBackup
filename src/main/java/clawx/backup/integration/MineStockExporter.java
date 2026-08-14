package clawx.backup.integration;

import clawx.backup.ClawBackup;
import clawx.backup.util.Message;
import org.bukkit.Bukkit;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * MineStock 数据导出/导入（通过 H2 AUTO_SERVER=TRUE 运行时直连）。
 * <p>
 * MineStock 使用 H2 连接池独占锁定 minestock.mv.db，运行时无法复制文件，且无内置导出命令。
 * 但其连接串带 AUTO_SERVER=TRUE（允许其他连接通过 TCP 连入同一个数据库），
 * 因此 ClawBackup 自带 H2 驱动，备份前直接 JDBC 读取全量 holdings 表导出为 backup.json，
 * 回档后清空表并写回。
 * <p>
 * 依赖：ClawBackup 内置 h2-2.2.224 驱动（与 MineStock 同版本，兼容 .mv.db 文件格式）。
 */
public final class MineStockExporter {

    private static final String PLUGIN_NAME = "MineStock";
    private static final String DB_FILE = "plugins/MineStock/data/minestock";
    // 注意：JDBC URL 必须与 MineStock 插件内部使用的路径写法一致（相对 ./plugins/...），
    // 否则 H2 AUTO_SERVER 会视为不同的库而连不上；ClawBackup 与 MineStock 同属一个 JVM，
    // 相对路径解析结果必然一致（CWD 相同），因此这里保持相对路径、不要改成绝对路径。
    private static final String JDBC_URL =
            "jdbc:h2:file:./plugins/MineStock/data/minestock;MODE=MySQL;AUTO_SERVER=TRUE";
    private static final String H2_DRIVER = "org.h2.Driver";

    private MineStockExporter() {
    }

    /** 导出文件路径（基于 Bukkit 服务器根目录，适配任意部署环境） */
    private static Path exportFile() {
        return ClawBackup.getServerRoot().resolve("plugins/MineStock/backup.json");
    }

    /** MineStock 插件是否已加载且使用 H2（存在 .mv.db 文件） */
    public static boolean isAvailable() {
        return Bukkit.getPluginManager().getPlugin(PLUGIN_NAME) != null
                && Files.exists(ClawBackup.getServerRoot().resolve(DB_FILE + ".mv.db"));
    }

    /** 备份前导出：JDBC 读取 holdings 表写入 backup.json（随备份打包）。返回是否执行了导出。 */
    public static boolean export() {
        if (!isAvailable()) return false;
        try {
            Path exportFile = exportFile();
            Files.deleteIfExists(exportFile);
            Class.forName(H2_DRIVER);
            StringBuilder sb = new StringBuilder();
            int count = 0;
            try (Connection conn = DriverManager.getConnection(JDBC_URL);
                 Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT player_uuid, stock_code, amount, avg_cost, last_price, last_fetched FROM holdings")) {
                while (rs.next()) {
                    sb.append(rs.getString("player_uuid")).append('\t')
                      .append(rs.getString("stock_code")).append('\t')
                      .append(rs.getInt("amount")).append('\t')
                      .append(rs.getDouble("avg_cost")).append('\t')
                      .append(rs.getDouble("last_price")).append('\t')
                      .append(rs.getLong("last_fetched")).append('\n');
                    count++;
                }
            }
            Files.write(exportFile, sb.toString().getBytes(StandardCharsets.UTF_8));
            Message.log("§e[备份] §a✔ MineStock 持仓数据已导出 §7(" + count + " 条)");
            return true;
        } catch (Exception e) {
            Message.log("§c[备份] §4MineStock 导出失败: " + e.getMessage());
            return false;
        }
    }

    /** 回档后导入：清空 holdings 表并写回 backup.json 的数据。返回是否导入了数据。 */
    public static boolean restore() {
        Path exportFile = exportFile();
        if (!Files.exists(exportFile)) return false;
        try {
            Class.forName(H2_DRIVER);
            int count = 0;
            try (Connection conn = DriverManager.getConnection(JDBC_URL);
                 Statement del = conn.createStatement()) {
                del.executeUpdate("DELETE FROM holdings");
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO holdings (player_uuid, stock_code, amount, avg_cost, last_price, last_fetched) "
                                + "VALUES (?,?,?,?,?,?)");
                     BufferedReader r = Files.newBufferedReader(exportFile, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty()) continue;
                        String[] p = line.split("\\t", -1);
                        if (p.length < 6) continue;
                        ps.setString(1, p[0]);
                        ps.setString(2, p[1]);
                        ps.setInt(3, Integer.parseInt(p[2]));
                        ps.setDouble(4, Double.parseDouble(p[3]));
                        ps.setDouble(5, Double.parseDouble(p[4]));
                        ps.setLong(6, Long.parseLong(p[5]));
                        ps.addBatch();
                        count++;
                    }
                    ps.executeBatch();
                }
            }
            Message.log("§e[回档] §a✔ MineStock 持仓数据已导入 §7(" + count + " 条)");
            return true;
        } catch (Exception e) {
            Message.log("§c[回档] §4MineStock 导入失败: " + e.getMessage());
            return false;
        }
    }
}
