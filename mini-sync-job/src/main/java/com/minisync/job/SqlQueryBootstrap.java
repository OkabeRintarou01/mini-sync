package com.minisync.job;

import com.minisync.connector.factory.DataSourceDriverFactory;
import com.minisync.connector.pool.DataSourcePool;
import com.minisync.spi.DataSourceConfig;
import com.minisync.spi.DataSourceDriver;

import java.util.List;
import java.util.Map;

/**
 * 通用 SQL 查询入口 —— 给控制台用的"轻量取数"工具。
 *
 * <p>控制台需要做三件与同步无关的事：查行数、预览前 N 行、清空表。
 * 这三件事以前在 Node 侧用 {@code mysql.exe} / {@code docker exec psql} 拼命令实现，
 * 于是每加一种数据源就要改 Node 代码 —— 这与插件化的目标矛盾。</p>
 *
 * <p>改成走 Java 引擎后，<b>新插件自动获得这三个能力</b>，Node 侧不再需要
 * 按类型分支。这正是"对扩展开放"该有的样子。</p>
 *
 * <p><b>为什么不直接连 JDBC 而要复用 Driver/Factory？</b>
 * 因为驱动是插件，主工程 classpath 上不保证有对应 JDBC 驱动
 * （SQLite、ClickHouse 这些都只在插件 jar 里）。
 * 复用工厂才能让控制台用上插件带来的能力。</p>
 *
 * <p><b>输出协议</b>（Node 侧解析）：</p>
 * <pre>
 *   QUERY_BEGIN
 *   ROW &lt;col1&gt;\t&lt;col2&gt;\t...
 *   ROW ...
 *   QUERY_END cols=&lt;逗号分隔的列名&gt;
 * </pre>
 *
 * <p><b>用法：</b></p>
 * <pre>
 *   java SqlQueryBootstrap &lt;类型&gt; &lt;host&gt; &lt;port&gt; &lt;库名|文件路径&gt; &lt;用户&gt; &lt;密码&gt; &lt;SQL&gt;
 *   java SqlQueryBootstrap SQLITE - - D:\mini-sync\data\plugin-demo.db - - "SELECT COUNT(*) AS c FROM t_order"
 * </pre>
 */
public final class SqlQueryBootstrap {

    private SqlQueryBootstrap() {
    }

    /** 单个参数里不允许出现换行，防止破坏行式输出协议 */
    private static final String REPLACEMENT_NEWLINE = "\\n";

    public static void main(String[] args) throws Exception {
        if (args.length < 7) {
            System.err.println("用法: <类型> <host|-> <port|-> <库名|文件路径> <用户|-> <密码|-> <SQL|@文件路径> [limit]");
            System.out.println("QUERY_END cols= error=usage: 参数不足 " + args.length + "/7");
            System.exit(2);
            return;
        }

        String type = args[0];
        String host = "-".equals(args[1]) ? null : args[1];
        Integer port = "-".equals(args[2]) ? null : Integer.parseInt(args[2]);
        String database = args[3];
        String username = "-".equals(args[4]) ? null : args[4];
        String password = "-".equals(args[5]) ? null : args[5];

        // SQL 可以直接给，也可以用 @文件路径 的形式给。
        //
        // 为什么需要 @文件 这种写法？
        // 因为调用链是 Node -> mvn.cmd(batch) -> java。
        // batch 对命令行里的引号处理很不可靠，含空格的 SQL（如 SELECT COUNT(*) AS c）
        // 会被拆成多个参数。把 SQL 写进临时文件、只传路径（无空格无特殊字符），
        // 就能彻底绕开三层 shell 的转义问题。
        String sql = readSql(args[6]);
        int limit = args.length > 7 ? Integer.parseInt(args[7]) : 0;
        if (limit > 0) {
            sql = wrapWithLimit(sql, limit);
        }

        DataSourceConfig cfg = new DataSourceConfig();
        cfg.setHost(host);
        cfg.setPort(port);
        cfg.setDatabaseName(database);
        cfg.setUsername(username);
        cfg.setPassword(password);

        System.out.println("QUERY_BEGIN");
        StringBuilder cols = new StringBuilder();

        DataSourcePool pool = new DataSourcePool();
        try {
            DataSourceDriver driver = pool.getDriver(Long.MAX_VALUE, type, cfg);
            List<Map<String, Object>> rows = driver.executeQuery(sql);

            for (Map<String, Object> row : rows) {
                if (cols.length() == 0) {
                    for (String k : row.keySet()) {
                        if (cols.length() > 0) cols.append(',');
                        cols.append(k);
                    }
                }
                StringBuilder line = new StringBuilder("ROW ");
                boolean first = true;
                for (Object v : row.values()) {
                    if (!first) line.append('\t');
                    line.append(flatten(v));
                    first = false;
                }
                System.out.println(line);
            }
        } catch (Exception e) {
            // 错误也走协议输出，让 Node 侧能区分"查询失败"和"零行结果"
            System.out.println("QUERY_END cols= error=" + flatten(String.valueOf(e.getMessage())));
            System.exit(1);
            return;
        } finally {
            pool.closeAll();
        }

        System.out.println("QUERY_END cols=" + cols);
    }

    /**
     * 读 SQL：{@code @路径} 表示从文件读，否则原样返回。
     *
     * <p>用 {@code @} 前缀而不是另加参数，是为了保持参数个数固定，
     * 调用方不必为两种模式写不同的分支。</p>
     */
    private static String readSql(String arg) throws Exception {
        if (arg == null || !arg.startsWith("@")) {
            return arg;
        }
        java.nio.file.Path p = java.nio.file.Paths.get(arg.substring(1));
        byte[] bytes = java.nio.file.Files.readAllBytes(p);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8).trim();
    }

    /** 把任意值压成单行文本（null → 空串，换行转义，防止破坏行协议） */
    private static String flatten(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v);
        return s.replace("\r\n", REPLACEMENT_NEWLINE)
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('\t', ' ');
    }

    /**
     * 给 SQL 包一层 limit。
     *
     * <p>不同数据库方言不同，这里用一个稳妥的做法：包成子查询再取前 N 行。
     * 只处理 SELECT，其它语句（TRUNCATE 等）原样返回。</p>
     */
    private static String wrapWithLimit(String sql, int limit) {
        String trimmed = sql.trim().replaceAll(";+$", "");
        if (!trimmed.toLowerCase().startsWith("select")) {
            return sql;
        }
        return "SELECT * FROM (" + trimmed + ") __minisync_limit LIMIT " + limit;
    }
}
