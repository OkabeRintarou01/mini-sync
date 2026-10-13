package com.minisync.job;

import com.minisync.connector.pool.DataSourcePool;
import com.minisync.job.entity.SyncJobEntity;
import com.minisync.job.executor.SyncJobExecutor;
import com.minisync.spi.DataSourceConfig;
import com.minisync.spi.DataSourceDriver;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/**
 * 插件化验证入口：MySQL → SQLite 同步。
 *
 * <p><b>这个类本身不实现任何驱动，也不引用任何 SQLite 专属代码。</b>
 * 它只做三件事：建一个 SQLite 文件、声明"目标类型是 SQLITE"、然后跑同步。
 * 能不能连上 SQLite、怎么 upsert，全部由 plugins 目录下那个
 * <b>主工程完全不知道存在</b>的 jar 决定。</p>
 *
 * <p>这就是插件化的验收标准：<b>新增一种数据源，主工程零改动。</b></p>
 *
 * <p>用法：{@code mvn exec:java -Dexec.mainClass=com.minisync.job.PluginSyncBootstrap}</p>
 */
@Slf4j
public class PluginSyncBootstrap {

    /** 演示用的 SQLite 文件路径，放在 data 目录下 */
    private static final String SQLITE_DB =
            "D:\\mini-sync\\data\\plugin-demo.db";

    public static void main(String[] args) throws Exception {
        int batchSize = args.length > 0 ? Integer.parseInt(args[0]) : 5000;

        // ---------- 1. 建 SQLite 文件与表（目标表不带自增，id 由源端带入） ----------
        Path dbPath = Paths.get(SQLITE_DB);
        Files.createDirectories(dbPath.getParent());

        String url = "jdbc:sqlite:" + SQLITE_DB;
        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement()) {
            st.executeUpdate("DROP TABLE IF EXISTS t_order");
            st.executeUpdate(
                    "CREATE TABLE t_order ("
                    + "id BIGINT PRIMARY KEY, "
                    + "order_no VARCHAR(64), "
                    + "user_id BIGINT, "
                    + "amount NUMERIC(10,2), "
                    + "status SMALLINT, "
                    + "remark VARCHAR(255), "
                    + "create_time TIMESTAMP, "
                    + "update_time TIMESTAMP)");
        }

        SyncJobEntity job = new SyncJobEntity();
        job.setId(99L);
        job.setJobName("MySQL -> SQLite（插件驱动）");
        job.setSourceTable("t_order");
        job.setTargetTable("t_order");
        job.setSyncType("FULL");
        job.setCursorField("id");
        job.setBatchSize(batchSize);

        DataSourcePool pool = new DataSourcePool();

        // ---------- 2. 源：MySQL，走插件 ----------
        DataSourceConfig mysqlCfg = new DataSourceConfig();
        mysqlCfg.setHost(env("MINISYNC_DB_HOST", "127.0.0.1"));
        mysqlCfg.setPort(Integer.parseInt(env("MINISYNC_DB_PORT", "3306")));
        mysqlCfg.setDatabaseName(env("MINISYNC_SRC_DB", "mini_sync_src"));
        mysqlCfg.setUsername(env("MINISYNC_DB_USER", "root"));
        mysqlCfg.setPassword(requiredEnv("MINISYNC_DB_PWD"));

        // ---------- 3. 目标：SQLite，同样走插件 ----------
        DataSourceConfig sqliteCfg = new DataSourceConfig();
        sqliteCfg.setDatabaseName(SQLITE_DB);

        log.info("========== 插件化同步验证开始 ==========");
        log.info("源: MySQL(mini_sync_src.t_order)  目标: SQLITE({})", SQLITE_DB);

        DataSourceDriver source = pool.getDriver(9001L, "MYSQL", mysqlCfg);
        DataSourceDriver target = pool.getDriver(9002L, "SQLITE", sqliteCfg);

        log.info("源驱动类 = {}", source.getClass().getName());
        log.info("目标驱动类 = {}", target.getClass().getName());

        SyncJobExecutor executor = new SyncJobExecutor(pool);
        Map<String, String> mapping = new HashMap<String, String>();

        long start = System.currentTimeMillis();
        SyncJobExecutor.SyncResult result = executor.executeFullSync(job, source, target, mapping);
        long cost = System.currentTimeMillis() - start;

        log.info("========== 同步完成 ==========");
        log.info("同步条数: {}", result.getTotalCount());
        log.info("总耗时  : {} ms", cost);
        log.info("============================");

        // ---------- 4. 校验 SQLite 里真实落了多少行 ----------
        int actual = 0;
        try (Connection c = DriverManager.getConnection(url);
             Statement st = c.createStatement();
             java.sql.ResultSet rs =
                     st.executeQuery("SELECT COUNT(*) FROM t_order")) {
            if (rs.next()) {
                actual = rs.getInt(1);
            }
        }
        log.info("目标表实际行数: {}（同步条数 {}，{}）",
                actual, result.getTotalCount(),
                actual == result.getTotalCount() ? "一致" : "不一致，需排查");

        pool.closeAll();
        System.exit(actual == result.getTotalCount() ? 0 : 1);
    }

    /** 取非敏感环境变量，为空时用默认值。 */
    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.trim().isEmpty()) ? fallback : v.trim();
    }

    /** 敏感变量必须显式配置，源码不提供口令默认值。 */
    private static String requiredEnv(String key) {
        String v = System.getenv(key);
        if (v == null || v.trim().isEmpty()) {
            throw new IllegalStateException("缺少环境变量 " + key + "；请先加载 .env 配置");
        }
        return v.trim();
    }
}
