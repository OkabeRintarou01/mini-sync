package com.minisync.job;

import com.minisync.connector.factory.DataSourceDriverFactory;
import com.minisync.connector.pool.DataSourcePool;
import com.minisync.job.entity.SyncJobEntity;
import com.minisync.job.executor.SyncJobExecutor;
import com.minisync.spi.DataSourceConfig;
import com.minisync.spi.DataSourceDriver;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * 命令行入口：手工触发一次同步，用于功能验证与性能压测。
 *
 * <p>用法：
 * <pre>
 *   java SyncBootstrap full     # 游标分页全量同步（生产实现）
 *   java SyncBootstrap offset   # limit offset 全量同步（对照实验用）
 *   java SyncBootstrap full 5000  # 指定批次大小
 * </pre>
 */
@Slf4j
public class SyncBootstrap {

    private static final String HOST = env("MINISYNC_DB_HOST", "127.0.0.1");
    private static final int PORT = Integer.parseInt(env("MINISYNC_DB_PORT", "3306"));
    private static final String USER = env("MINISYNC_DB_USER", "root");
    /** 密码必须从环境变量提供，源码不包含口令默认值。 */
    private static final String PASSWORD = requiredEnv("MINISYNC_DB_PWD");

    /** 取非敏感环境变量，为空时用默认值。 */
    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.trim().isEmpty()) ? fallback : v.trim();
    }

    private static String requiredEnv(String key) {
        String v = System.getenv(key);
        if (v == null || v.trim().isEmpty()) {
            throw new IllegalStateException("缺少环境变量 " + key + "；请先加载 .env 配置");
        }
        return v.trim();
    }

    public static void main(String[] args) {
        String mode = args.length > 0 ? args[0] : "full";
        int batchSize = args.length > 1 ? Integer.parseInt(args[1]) : 1000;

        SyncJobEntity job = new SyncJobEntity();
        job.setId(1L);
        job.setJobName("t_order 全量同步");
        job.setSourceTable("t_order");
        job.setTargetTable("t_order");
        job.setSyncType("FULL");
        job.setCursorField("id");
        job.setBatchSize(batchSize);

        DataSourcePool pool = new DataSourcePool();
        DataSourceDriver source = pool.getDriver(1L, "MYSQL", mysqlConfig("mini_sync_src"));
        DataSourceDriver target = pool.getDriver(2L, "MYSQL", mysqlConfig("mini_sync_dst"));

        Map<String, String> mapping = new HashMap<String, String>();

        SyncJobExecutor executor = new SyncJobExecutor(pool);

        log.info("========== 开始同步 ==========");
        log.info("模式={}, 批次大小={}", mode, batchSize);

        long start = System.currentTimeMillis();
        SyncJobExecutor.SyncResult result;
        if ("offset".equalsIgnoreCase(mode)) {
            result = executor.executeFullSyncByOffset(job, source, target, mapping);
        } else {
            result = executor.executeFullSync(job, source, target, mapping);
        }
        long cost = System.currentTimeMillis() - start;

        log.info("========== 同步完成 ==========");
        log.info("同步条数: {}", result.getTotalCount());
        log.info("总耗时  : {} ms  ({} 秒)", cost, String.format("%.2f", cost / 1000.0));
        log.info("==============================");

        pool.closeAll();
    }

    private static DataSourceConfig mysqlConfig(String db) {
        DataSourceConfig c = new DataSourceConfig();
        c.setHost(HOST);
        c.setPort(PORT);
        c.setDatabaseName(db);
        c.setUsername(USER);
        c.setPassword(PASSWORD);
        return c;
    }
}
