import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 极简 JDBC 执行器（**开发/排障工具**，不参与生产构建、不被后端打包）。
 *
 * <p>用途：本系统部署在崖山 YashanDB 上，开发机往往没有 yasql 客户端；
 * 需要手工查/改数据（排障、清演示数据、核对字段）时用它兜底。</p>
 *
 * <p>连接信息全部来自环境变量，<b>不落盘、不打印口令</b>：
 * {@code DB_URL} / {@code DB_USER} / {@code DB_PASSWORD}（由 {@code scripts/db-sql.sh} 注入）。</p>
 *
 * <p>JDK 11+ 可直接以「源码文件模式」运行，无需 javac：</p>
 * <pre>
 * java -cp backend/lib/yashandb-jdbc-1.9.3.jar scripts/jdbc/RunSql.java demo-reset.sql
 * </pre>
 *
 * <p>切句规则与后端 {@code YashanMigrationRunner} 保持一致：按「行尾分号」切句、剥离整行注释；
 * SELECT 会打印结果表格，其余语句只报成功/失败。</p>
 */
public class RunSql {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("用法: java -cp <yashandb-jdbc.jar> RunSql.java <sql 文件>");
            System.exit(2);
        }
        String url = require("DB_URL");
        String user = require("DB_USER");
        String pass = require("DB_PASSWORD");
        String sql = new String(Files.readAllBytes(Path.of(args[0])), StandardCharsets.UTF_8);

        int ok = 0;
        int skipped = 0;
        try (Connection conn = DriverManager.getConnection(url, user, pass);
             Statement st = conn.createStatement()) {
            for (String raw : sql.split(";\r?\n")) {
                String stmt = strip(raw);
                if (stmt.isEmpty()) {
                    continue;
                }
                boolean isQuery = stmt.regionMatches(true, 0, "SELECT", 0, 6)
                        || stmt.regionMatches(true, 0, "WITH", 0, 4);
                try {
                    if (isQuery) {
                        printResult(st, stmt);
                        ok++;
                    } else {
                        st.execute(stmt);
                        System.out.println("OK   " + brief(stmt));
                        ok++;
                    }
                } catch (SQLException e) {
                    skipped++;
                    System.out.println("FAIL " + e.getMessage().replaceAll("\\s+", " ") + "\n     <<< " + brief(stmt));
                }
            }
        }
        System.out.println();
        System.out.println("执行完成：成功 " + ok + " 条，失败 " + skipped + " 条");
        if (skipped > 0) {
            System.exit(1);
        }
    }

    /** 剥离空行与整行注释（-- 开头），保留正文 */
    private static String strip(String raw) {
        StringBuilder sb = new StringBuilder();
        for (String line : raw.split("\r?\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("--")) {
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }

    private static String brief(String s) {
        String flat = s.replaceAll("\\s+", " ");
        return flat.length() > 100 ? flat.substring(0, 100) + "…" : flat;
    }

    private static void printResult(Statement st, String sql) throws SQLException {
        System.out.println("SQL> " + brief(sql));
        try (ResultSet rs = st.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            StringBuilder head = new StringBuilder();
            for (int i = 1; i <= n; i++) {
                head.append(md.getColumnLabel(i)).append(i < n ? " | " : "");
            }
            System.out.println(head);
            int rows = 0;
            while (rs.next()) {
                StringBuilder line = new StringBuilder();
                for (int i = 1; i <= n; i++) {
                    line.append(rs.getString(i)).append(i < n ? " | " : "");
                }
                System.out.println(line);
                rows++;
            }
            System.out.println("(" + rows + " rows)");
        }
    }

    private static String require(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            System.err.println("缺少环境变量 " + key + "（请通过 scripts/db-sql.sh 运行）");
            System.exit(2);
        }
        return v;
    }
}
