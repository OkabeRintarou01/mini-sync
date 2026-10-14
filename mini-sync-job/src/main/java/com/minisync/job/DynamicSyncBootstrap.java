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
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * 动态同步入口：按 jobId 从管理库读取完整任务配置并执行。
 *
 * <p>支持两种模式，由 {@code sync_job.sync_type} 决定：</p>
 * <ul>
 *   <li>{@code FULL} —— 全量同步，主键游标分页，每次整表搬</li>
 *   <li>{@code INCREMENT} —— 增量同步，复合游标 {@code (增量字段, 主键)}，
 *       每批成功后把水位写回 {@code sync_job}，实现断点续传</li>
 * </ul>
 *
 * <p><b>为什么任务配置从库里读，而不是命令行传？</b></p>
 *
 * <p>增量同步需要 increment_field / last_offset / last_pk / cursor_field 四项配置。
 * 如果继续用位置参数，命令行会变成一长串难以核对的值，传错一个位置就静默跑错。
 * 让引擎自己按 jobId 读库，调用方只需要回答"跑哪个任务"。</p>
 *
 * <p><b>水位落库的两种情形：</b></p>
 * <ol>
 *   <li><b>成功</b>：把本次推进到的 {@code (水位, 主键)} 写入 sync_job，
 *       下次任务从这里继续</li>
 *   <li><b>失败</b>：回查<b>目标端</b>真实写入进度再落库，而不是沿用源端水位 ——
 *       否则"本批已读但未写成功"的数据会被永久跳过。
 *       这保证了 at-least-once（宁可重复，不可丢失）；配合驱动层 upsert 幂等，
 *       重复写不会产生脏数据</li>
 * </ol>
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

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args[0].trim().isEmpty()) {
            System.err.println("用法: DynamicSyncBootstrap <jobId>");
            System.exit(2);
            return;
        }
        long jobId = Long.parseLong(args[0].trim());

        // ---------- 1. 读取完整任务配置（含增量水位）----------
        SyncJobEntity job = loadJob(jobId);
        String syncType = (job.getSyncType() == null || job.getSyncType().trim().isEmpty())
                ? "FULL" : job.getSyncType().trim().toUpperCase();
        job.setSyncType(syncType);

        if ("INCREMENT".equals(syncType)
                && (job.getIncrementField() == null || job.getIncrementField().trim().isEmpty())) {
            throw new IllegalStateException(
                    "任务 " + jobId + " 是增量模式，但未配置 increment_field（增量字段）");
        }

        log.info("任务: id={}, name={}, 模式={}, 批次={}, 源表={}, 目标表={}",
                job.getId(), job.getJobName(), syncType, job.getBatchSize(),
                job.getSourceTable(), job.getTargetTable());
        if ("INCREMENT".equals(syncType)) {
            log.info("增量配置: 字段={}, 起始水位=(last_offset={}, last_pk={})",
                    job.getIncrementField(), job.getLastOffset(), job.getLastPk());
        }

        // ---------- 2. 两端数据源 ----------
        long sourceId = job.getSourceDatasourceId();
        long targetId = job.getTargetDatasourceId();
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

        SyncJobExecutor executor = new SyncJobExecutor(pool);
        Map<String, String> mapping = new HashMap<String, String>();

        log.info("========== 开始同步 ==========");
        log.info("方向: {} -> {}", sourceType, targetType);

        long start = System.currentTimeMillis();
        try {
            if ("INCREMENT".equals(syncType)) {
                runIncrement(executor, job, source, target, mapping);
            } else {
                runFull(executor, job, source, target, mapping);
            }
        } catch (Exception e) {
            // 把失败原因同时写进日志和 stdout：
            // Node 控制台会从 stdout 里抓「同步失败: ...」写进 sync_log，界面上才能看到原因
            log.error("同步失败: {}", e.getMessage(), e);
            System.out.println("同步失败: " + e.getMessage());
            pool.closeAll();
            System.exit(1);
            return;
        }
        long cost = System.currentTimeMillis() - start;

        // 以下两行日志的【格式】被 web/server.js 用正则解析，
        // 用来在界面显示「同步条数 / 耗时」，不要改动空格与冒号：
        //   /同步条数:\s*(\d+)/
        //   /总耗时\s*:\s*(\d+)\s*ms/
        log.info("总耗时  : {} ms", cost);
        log.info("==============================");

        pool.closeAll();
    }

    // ==================================================================
    // 全量
    // ==================================================================

    private static void runFull(SyncJobExecutor executor, SyncJobEntity job,
                                DataSourceDriver source, DataSourceDriver target,
                                Map<String, String> mapping) {
        SyncJobExecutor.SyncResult r = executor.executeFullSync(job, source, target, mapping);
        log.info("同步条数: {}", r.getTotalCount());

        // 全量同步【不维护水位】：它的语义是"每次搬整表"，没有"从哪继续"的概念。
        // 断点续传只对增量任务有意义。
    }

    // ==================================================================
    // 增量 + 水位落库
    // ==================================================================

    private static void runIncrement(SyncJobExecutor executor, SyncJobEntity job,
                                     DataSourceDriver source, DataSourceDriver target,
                                     Map<String, String> mapping) throws SQLException {
        try {
            SyncJobExecutor.SyncResult r = executor.executeIncrementSync(
                    job, source, target, mapping,
                    // 逐批落库：进程被强杀时，已完成批次的水位不会丢，
                    // 下次从断点继续而不是从头重扫。
                    (water, pk) -> saveWatermarkQuietly(job.getId(), water, pk));

            log.info("同步条数: {}", r.getTotalCount());

            // ----- 收尾：把最终水位再落一次（覆盖逐批落库的最后一批） -----
            SyncJobExecutor.Watermark w = r.getWatermark();
            if (w != null && w.getWater() != null) {
                saveWatermark(job.getId(), w.getWater(), w.getPk());
            } else {
                // 表为空、或已同步到最新：没有新水位可推进。
                // 这里必须"保持原值"，不能被 null 覆盖，否则下次会从头重扫整表。
                log.info("本次无可推进的水位（无新增数据），保持原水位不变");
            }

        } catch (SyncJobExecutor.IncrementSyncException e) {
            // ===== 关键设计：失败时回写【目标端真实水位】，而不是源端水位 =====
            //
            // 若沿用源端水位，那么"已从源端读出、但写入目标端失败"的这一批数据，
            // 在下次任务中会被直接跳过 —— 造成永久丢失。
            //
            // 回查目标端真实进度后，下次会从"目标端确实写到哪"继续，
            // 本批数据被重新读取、重新写入。配合 upsert 幂等，重复写无害。
            // 这就是 at-least-once。
            if (e.getTargetWatermark() != null) {
                saveWatermark(job.getId(), e.getTargetWatermark(), e.getLastPk());
                log.warn("增量失败，已按【目标端真实进度】回写水位: last_offset={}, last_pk={}",
                        e.getTargetWatermark(), e.getLastPk());
            } else {
                log.warn("增量失败，且无法回查目标端水位，保持原水位不变"
                        + "（下次将重跑本批，属正常）: {}", e.getMessage());
            }
            throw e;
        }
    }

    /**
     * 水位落库。
     *
     * <p>类型对应关系（容易搞反，务必注意）：
     * {@code last_offset} 是水位字段的值（时间戳字符串）→ VARCHAR；
     * {@code last_pk} 是主键的值（数字）→ BIGINT。</p>
     */
    private static void saveWatermark(long jobId, String water, long pk) throws SQLException {
        String sql = "UPDATE sync_job SET last_offset=?, last_pk=?, last_sync_time=NOW() WHERE id=?";
        try (Connection conn = DriverManager.getConnection(ADMIN_URL, ADMIN_USER, ADMIN_PWD);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, water);
            ps.setLong(2, pk);
            ps.setLong(3, jobId);
            int n = ps.executeUpdate();
            log.info("水位已落库: jobId={}, last_offset={}, last_pk={}, 影响行数={}",
                    jobId, water, pk, n);
        }
    }

    /**
     * 逐批落库用的「安静版」：不打 INFO 日志（每批一条会刷屏），失败只告警不抛出。
     *
     * <p>为什么失败不抛出：数据已经写进目标端了，水位没记上最多导致下次多扫一段，
     * 而 upsert 幂等保证重复写入无害。为了"进度精度"中断整个同步并不划算。</p>
     */
    private static void saveWatermarkQuietly(long jobId, String water, long pk) {
        String sql = "UPDATE sync_job SET last_offset=?, last_pk=?, last_sync_time=NOW() WHERE id=?";
        try (Connection conn = DriverManager.getConnection(ADMIN_URL, ADMIN_USER, ADMIN_PWD);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, water);
            ps.setLong(2, pk);
            ps.setLong(3, jobId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("批次水位落库失败（不影响已写入的数据，仅影响续传精度）: {}", e.getMessage());
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
