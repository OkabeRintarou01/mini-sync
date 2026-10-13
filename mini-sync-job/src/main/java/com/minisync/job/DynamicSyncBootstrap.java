package com.minisync.job;

import com.minisync.connector.pool.DataSourcePool;
import com.minisync.job.entity.SyncJobEntity;
import com.minisync.job.executor.SyncJobExecutor;
import com.minisync.spi.DataSourceConfig;
import com.minisync.spi.DataSourceDriver;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

/**
 * 动态同步入口：从管理库读取数据源配置，按源/目标自动选择驱动执行同步。
 *
 * <p>这是控制台「新建数据源 → 选两个数据源同步」功能的后端支撑。
 * 与前两个入口的区别：数据源连接信息来自数据库而非硬编码，
 * 源/目标类型可以任意组合（MySQL↔MySQL、MySQL↔PostgreSQL）。</p>
 *
 * <p>用法：
 * <pre>
 *   java DynamicSyncBootstrap cursor 1000 1 2 t_order t_order
 *   参数依次为：模式 批次大小 源数据源ID 目标数据源ID 源表 目标表
 * </pre>
 */
@Slf4j
public class DynamicSyncBootstrap {

    // 管理库连接信息从环境变量读，代码里不落口令。
    // 提供默认值只是为了 clone 下来能直接跑（本地开发库），
    // 生产上通过部署配置注入即可，不需要改代码。
    private static final String ADMIN_URL =
            "jdbc:mysql://" + env("MINISYNC_ADMIN_HOST", "127.0.0.1")
            + ":" + env("MINISYNC_ADMIN_PORT", "3306")
            + "/" + env("MINISYNC_ADMIN_DB", "mini_sync_admin")
            + "?useUnicode=true&characterEncoding=utf8&useSSL=false"
            + "&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
    private static final String ADMIN_USER = env("MINISYNC_DB_USER", "root");
    private static final String ADMIN_PWD = requiredEnv("MINISYNC_DB_PWD");

    /** 取非敏感环境变量，为空时用默认值。 */
    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.trim().isEmpty()) ? fallback : v.trim();
    }

    /** 敏感变量必须显式配置，不在源码中提供口令默认值。 */
    private static String requiredEnv(String key) {
        String v = System.getenv(key);
        if (v == null || v.trim().isEmpty()) {
            throw new IllegalStateException("缺少环境变量 " + key + "；请复制 .env.example 为 .env 并填写数据库口令");
        }
        return v.trim();
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "cursor";
        int batchSize = args.length > 1 ? Integer.parseInt(args[1]) : 1000;
        long sourceId = args.length > 2 ? Long.parseLong(args[2]) : 1L;
        long targetId = args.length > 3 ? Long.parseLong(args[3]) : 2L;
        String sourceTable = args.length > 4 ? args[4] : "t_order";
        String targetTable = args.length > 5 ? args[5] : "t_order";

        // ---------- 从管理库读取两端数据源配置 ----------
        DataSourceConfig sourceCfg = loadDataSource(sourceId);
        DataSourceConfig targetCfg = loadDataSource(targetId);
        String sourceType = loadDataSourceType(sourceId);
        String targetType = loadDataSourceType(targetId);

        log.info("源数据源: id={}, type={}, db={}", sourceId, sourceType, sourceCfg.getDatabaseName());
        log.info("目标数据源: id={}, type={}, db={}", targetId, targetType, targetCfg.getDatabaseName());

        DataSourcePool pool = new DataSourcePool();

        // 关键：按类型走工厂路由。工厂不缓存实例，
        // 因此即使源和目标都是 MySQL，也会拿到两个独立实例、两条独立连接。
        DataSourceDriver source = pool.getDriver(sourceId, sourceType, sourceCfg);
        DataSourceDriver target = pool.getDriver(targetId, targetType, targetCfg);

        SyncJobEntity job = new SyncJobEntity();
        job.setId(0L);
        job.setJobName("动态同步 " + sourceType + " -> " + targetType);
        job.setSourceTable(sourceTable);
        job.setTargetTable(targetTable);
        job.setSyncType("FULL");
        job.setCursorField("id");
        job.setBatchSize(batchSize);

        SyncJobExecutor executor = new SyncJobExecutor(pool);
        Map<String, String> mapping = new HashMap<String, String>();

        log.info("========== 开始同步 ==========");
        log.info("方向: {} -> {}", sourceType, targetType);

        long start = System.currentTimeMillis();
        SyncJobExecutor.SyncResult result =
                executor.executeFullSync(job, source, target, mapping);
        long cost = System.currentTimeMillis() - start;

        log.info("========== 同步完成 ==========");
        log.info("同步条数: {}", result.getTotalCount());
        log.info("总耗时  : {} ms", cost);
        log.info("==============================");

        pool.closeAll();
    }

    /** 读取数据源连接配置 */
    private static DataSourceConfig loadDataSource(long id) throws Exception {
        try (Connection conn = DriverManager.getConnection(ADMIN_URL, ADMIN_USER, ADMIN_PWD);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT host, port, database_name, username, password FROM datasource_config WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("数据源不存在: id=" + id);
                }
                DataSourceConfig c = new DataSourceConfig();
                c.setHost(rs.getString("host"));
                c.setPort(rs.getInt("port"));
                c.setDatabaseName(rs.getString("database_name"));
                c.setUsername(rs.getString("username"));
                c.setPassword(rs.getString("password"));
                return c;
            }
        }
    }

    /** 读取数据源类型 */
    private static String loadDataSourceType(long id) throws Exception {
        try (Connection conn = DriverManager.getConnection(ADMIN_URL, ADMIN_USER, ADMIN_PWD);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT ds_type FROM datasource_config WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("数据源不存在: id=" + id);
                }
                return rs.getString("ds_type");
            }
        }
    }
}
