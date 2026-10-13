package com.minisync.job;

import com.minisync.connector.pool.DataSourcePool;
import com.minisync.job.entity.SyncJobEntity;
import com.minisync.job.executor.SyncJobExecutor;
import com.minisync.spi.DataSourceConfig;
import com.minisync.spi.DataSourceDriver;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * 跨数据源同步验证：MySQL → PostgreSQL / PostgreSQL → MySQL。
 *
 * <p>本测试用于证明 {@code DataSourceDriver} 抽象的有效性：
 * 同步执行器完全不知道两端是什么数据库，仅依赖接口。</p>
 *
 * <p>用法：
 * <pre>
 *   java CrossDbBootstrap mysql2pg
 *   java CrossDbBootstrap pg2mysql
 * </pre>
 */
@Slf4j
public class CrossDbBootstrap {

    public static void main(String[] args) {
        String direction = args.length > 0 ? args[0] : "mysql2pg";

        SyncJobEntity job = new SyncJobEntity();
        job.setId(2L);
        job.setJobName("跨库同步 " + direction);
        job.setSyncType("FULL");
        job.setCursorField("id");
        job.setBatchSize(1000);

        boolean mysqlIsSource = "mysql2pg".equalsIgnoreCase(direction);

        // 两个方向都读写 t_order；MySQL 端按方向选择 src 或 dst，避免自我覆盖
        job.setSourceTable("t_order");
        job.setTargetTable("t_order");

        DataSourcePool pool = new DataSourcePool();

        // mysql2pg: 源=src库  目标=PG
        // pg2mysql: 源=PG     目标=dst库
        String mysqlDb = mysqlIsSource ? "mini_sync_src" : "mini_sync_dst";
        DataSourceDriver mysql = pool.getDriver(
                mysqlIsSource ? 1L : 2L, "MYSQL", mysqlConfig(mysqlDb));
        DataSourceDriver pg = pool.getDriver(3L, "POSTGRESQL", pgConfig());

        DataSourceDriver source = mysqlIsSource ? mysql : pg;
        DataSourceDriver target = mysqlIsSource ? pg : mysql;

        log.info("========== 跨库同步开始 ==========");
        log.info("方向: {}  (源={}  目标={})",
                direction,
                mysqlIsSource ? "MySQL" : "PostgreSQL",
                mysqlIsSource ? "PostgreSQL" : "MySQL");

        SyncJobExecutor executor = new SyncJobExecutor(pool);
        Map<String, String> mapping = new HashMap<String, String>();

        long start = System.currentTimeMillis();
        SyncJobExecutor.SyncResult result =
                executor.executeFullSync(job, source, target, mapping);
        long cost = System.currentTimeMillis() - start;

        log.info("========== 跨库同步完成 ==========");
        log.info("同步条数: {}", result.getTotalCount());
        log.info("总耗时  : {} ms", cost);
        log.info("==================================");

        pool.closeAll();
    }

    private static DataSourceConfig mysqlConfig(String db) {
        DataSourceConfig c = new DataSourceConfig();
        c.setHost("127.0.0.1");
        c.setPort(3306);
        c.setDatabaseName(db);
        c.setUsername("root");
        c.setPassword("s123456");
        return c;
    }

    private static DataSourceConfig pgConfig() {
        DataSourceConfig c = new DataSourceConfig();
        c.setHost("127.0.0.1");
        c.setPort(5432);
        c.setDatabaseName("mini_sync_pg");
        c.setUsername("postgres");
        c.setPassword("s123456");
        return c;
    }
}
