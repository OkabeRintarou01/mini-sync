package com.minisync.job;

import com.minisync.job.entity.SyncJobEntity;
import com.minisync.job.service.SyncExecutionService;
import com.minisync.spi.DataSourceConfig;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 命令行同步入口：按 jobId 从管理库读取任务配置并执行一次。
 *
 * <p><b>本类的职责被收窄了</b>：现在只负责</p>
 * <ol>
 *   <li>从管理库读任务配置与数据源配置（用 JDBC）</li>
 *   <li>提供水位落库的实现（用 JDBC）</li>
 *   <li>把执行交给 {@link SyncExecutionService}</li>
 * </ol>
 *
 * <p>真正的同步逻辑、水位推进策略、失败回写规则都在
 * {@link SyncExecutionService} 里，与 Spring Boot 服务端<b>共用同一份代码</b>，
 * 避免"命令行跑出来的结果和网页不一样"这类问题。</p>
 *
 * <p>支持两种模式，由 {@code sync_job.sync_type} 决定：</p>
 * <ul>
 *   <li>{@code FULL} —— 全量同步，主键游标分页，每次整表搬</li>
 *   <li>{@code INCREMENT} —— 增量同步，复合游标 {@code (增量字段, 主键)}，
 *       每批成功后把水位写回 {@code sync_job}，实现断点续传</li>
 * </ul>
 *
 * <p>用法：{@code java DynamicSyncBootstrap <jobId>}</p>
 */
@Slf4j
public class DynamicSyncBootstrap {

    // 管理库连接信息从环境变量读，代码里不落口令。
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
            throw new IllegalStateException("缺少环境变量 " + key
                    + "；请复制 .env.example 为 .env 并填写数据库口令");
        }
        return v.trim();
    }

    public static void main(String[] args) {
        if (args.length < 1 || args[0].trim().isEmpty()) {
            System.err.println("用法: DynamicSyncBootstrap <jobId>");
            System.exit(2);
            return;
        }

        int exitCode = 0;
        try {
            long jobId = Long.parseLong(args[0].trim());

            // ---------- 1. 读任务配置（含增量水位）----------
            SyncJobEntity job = loadJob(jobId);

            // ---------- 2. 读两端数据源 ----------
            long sourceId = job.getSourceDatasourceId();
            long targetId = job.getTargetDatasourceId();
            DataSourceConfig sourceCfg = loadDataSource(sourceId);
            DataSourceConfig targetCfg = loadDataSource(targetId);
            String sourceType = loadDataSourceType(sourceId);
            String targetType = loadDataSourceType(targetId);

            log.info("源数据源: id={}, type={}, db={}",
                    sourceId, sourceType, sourceCfg.getDatabaseName());
            log.info("目标数据源: id={}, type={}, db={}",
                    targetId, targetType, targetCfg.getDatabaseName());

            // ---------- 3. 交给共用服务执行 ----------
            SyncExecutionService service = new SyncExecutionService();
            SyncExecutionService.ExecutionResult result = service.execute(
                    job, sourceCfg, targetCfg, sourceType, targetType,
                    // CLI 的水位落库走 JDBC
                    (id, water, pk, quiet) -> saveWatermark(id, water, pk, quiet));

            if (!result.success()) {
                // 让 Node / 调用方通过退出码感知失败
                System.out.println("同步失败: " + result.errorMsg());
                exitCode = 1;
            }

        } catch (Exception e) {
            log.error("同步异常: {}", e.getMessage(), e);
            System.out.println("同步失败: " + e.getMessage());
            exitCode = 1;
        }

        System.exit(exitCode);
    }

    /**
     * 水位落库（JDBC 实现）。
     *
     * <p>类型对应关系（容易搞反，务必注意）：
     * {@code last_offset} 是水位字段的值（时间戳字符串）→ VARCHAR；
     * {@code last_pk} 是主键的值（数字）→ BIGINT。</p>
     *
     * @param quiet 逐批落库时为 true：不打 INFO 日志（每批一条会刷屏），失败只告警不抛出。
     *              数据已经写进目标端了，水位没记上最多导致下次多扫一段，
     *              而 upsert 幂等保证重复写入无害。
     */
    private static void saveWatermark(long jobId, String water, long pk, boolean quiet) {
        String sql = "UPDATE sync_job SET last_offset=?, last_pk=?, last_sync_time=NOW() WHERE id=?";
        try (Connection conn = DriverManager.getConnection(ADMIN_URL, ADMIN_USER, ADMIN_PWD);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, water);
            ps.setLong(2, pk);
            ps.setLong(3, jobId);
            int n = ps.executeUpdate();
            if (quiet) {
                log.debug("批次水位已落库: jobId={}, last_offset={}, last_pk={}", jobId, water, pk);
            } else {
                log.info("水位已落库: jobId={}, last_offset={}, last_pk={}, 影响行数={}",
                        jobId, water, pk, n);
            }
        } catch (SQLException e) {
            if (quiet) {
                log.warn("批次水位落库失败（不影响已写入的数据，仅影响续传精度）: {}", e.getMessage());
            } else {
                throw new IllegalStateException("水位落库失败: " + e.getMessage(), e);
            }
        }
    }

    // ==================================================================
    // 读配置
    // ==================================================================

    /** 读取完整任务配置。列名以 sql/admin.sql 为准。 */
    private static SyncJobEntity loadJob(long jobId) throws SQLException {
        String sql = "SELECT id, job_name, source_datasource_id, source_table, "
                + "target_datasource_id, target_table, sync_type, increment_field, "
                + "cursor_field, batch_size, last_offset, last_pk "
                + "FROM sync_job WHERE id = ?";
        try (Connection conn = DriverManager.getConnection(ADMIN_URL, ADMIN_USER, ADMIN_PWD);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("任务不存在: id=" + jobId);
                }
                SyncJobEntity j = new SyncJobEntity();
                j.setId(rs.getLong("id"));
                j.setJobName(rs.getString("job_name"));
                j.setSourceDatasourceId(rs.getLong("source_datasource_id"));
                j.setSourceTable(rs.getString("source_table"));
                j.setTargetDatasourceId(rs.getLong("target_datasource_id"));
                j.setTargetTable(rs.getString("target_table"));
                j.setSyncType(rs.getString("sync_type"));
                j.setIncrementField(rs.getString("increment_field"));
                j.setCursorField(rs.getString("cursor_field"));

                int bs = rs.getInt("batch_size");
                j.setBatchSize(rs.wasNull() || bs <= 0 ? 1000 : bs);

                j.setLastOffset(rs.getString("last_offset"));

                long pk = rs.getLong("last_pk");
                j.setLastPk(rs.wasNull() ? 0L : pk);

                return j;
            }
        }
    }

    /** 读取数据源连接配置 */
    private static DataSourceConfig loadDataSource(long id) throws SQLException {
        try (Connection conn = DriverManager.getConnection(ADMIN_URL, ADMIN_USER, ADMIN_PWD);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT host, port, database_name, username, password "
                     + "FROM datasource_config WHERE id = ?")) {
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
    private static String loadDataSourceType(long id) throws SQLException {
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
