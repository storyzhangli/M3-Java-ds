import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * D40 R03 通用 JDBC 执行器（单文件源码启动，JDK21）。
 *
 * 用法：
 *   java -cp <driver.jar> DbExec.java mysql -e "SQL[;SQL...]"
 *   java -cp <driver.jar> DbExec.java mysql -f <file.sql>
 *   java -cp <driver.jar> DbExec.java pg    -e "SQL"
 *
 * 连接参数与工程 application.yml 同源；密码仅经环境变量注入
 * （MYSQL_PASSWORD / PG_VECTOR_PASSWORD），缺失即退出码 2，绝不打印密码。
 * 输出：每条 SQL 打印 "-- SQL:" 头 + TSV 结果集或 affected 行数；任一 SQL 失败退出码 1。
 */
public class DbExec {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: DbExec <mysql|pg> <-e SQL | -f file.sql>");
            System.exit(2);
        }
        String target = args[0];
        String sqlText;
        if ("-e".equals(args[1])) {
            sqlText = args[2];
        } else if ("-f".equals(args[1])) {
            sqlText = Files.readString(Path.of(args[2]), StandardCharsets.UTF_8);
        } else {
            System.err.println("unknown mode: " + args[1]);
            System.exit(2);
            return;
        }
        String url;
        String user;
        String password;
        if ("mysql".equals(target)) {
            url = envOr("MYSQL_URL",
                    "jdbc:mysql://127.0.0.1:3306/ai_db?useUnicode=true&characterEncoding=utf8"
                            + "&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
            user = envOr("MYSQL_USER", "root");
            password = System.getenv("MYSQL_PASSWORD");
        } else if ("pg".equals(target)) {
            url = envOr("PG_VECTOR_URL", "jdbc:postgresql://192.168.200.153:5432/spring_ai");
            user = envOr("PG_VECTOR_USERNAME", "postgres");
            password = System.getenv("PG_VECTOR_PASSWORD");
        } else {
            System.err.println("unknown target: " + target);
            System.exit(2);
            return;
        }
        if (password == null || password.isBlank()) {
            System.err.println("BLOCKED: required password env is missing for target=" + target);
            System.exit(2);
            return;
        }
        List<String> statements = split(sqlText);
        int failures = 0;
        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            for (String sql : statements) {
                System.out.println("-- SQL: " + sql.replaceAll("\\s+", " ").trim());
                try (Statement st = conn.createStatement()) {
                    boolean hasResultSet = st.execute(sql);
                    if (hasResultSet) {
                        try (ResultSet rs = st.getResultSet()) {
                            ResultSetMetaData meta = rs.getMetaData();
                            int cols = meta.getColumnCount();
                            StringBuilder header = new StringBuilder();
                            for (int i = 1; i <= cols; i++) {
                                if (i > 1) header.append('\t');
                                header.append(meta.getColumnLabel(i));
                            }
                            System.out.println(header);
                            int rows = 0;
                            while (rs.next()) {
                                StringBuilder line = new StringBuilder();
                                for (int i = 1; i <= cols; i++) {
                                    if (i > 1) line.append('\t');
                                    String value = rs.getString(i);
                                    line.append(value == null ? "NULL"
                                            : value.replace("\t", "\\t").replace("\n", "\\n"));
                                }
                                System.out.println(line);
                                rows++;
                            }
                            System.out.println("-- rows=" + rows);
                        }
                    } else {
                        System.out.println("-- affected=" + st.getUpdateCount());
                    }
                } catch (Exception ex) {
                    failures++;
                    System.out.println("-- ERROR: " + ex.getClass().getSimpleName()
                            + ": " + ex.getMessage());
                }
                System.out.println();
            }
        }
        System.out.println("-- DONE statements=" + statements.size() + " failures=" + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** 去掉 -- 注释行后按分号切分（DDL/查询场景足够；不支持存储过程体）。 */
    private static List<String> split(String text) {
        StringBuilder cleaned = new StringBuilder();
        for (String line : text.split("\r?\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("--") || trimmed.isEmpty()) {
                continue;
            }
            cleaned.append(line).append('\n');
        }
        List<String> out = new ArrayList<>();
        for (String part : cleaned.toString().split(";")) {
            if (!part.isBlank()) {
                out.add(part.strip());
            }
        }
        return out;
    }
}
