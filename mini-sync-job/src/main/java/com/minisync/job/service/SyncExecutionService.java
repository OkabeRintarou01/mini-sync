package com.minisync.job.service;

import com.minisync.connector.pool.DataSourcePool;
import com.minisync.job.entity.SyncJobEntity;
import com.minisync.job.executor.SyncJobExecutor;
import com.minisync.spi.DataSourceConfig;
import com.minisync.spi.DataSourceDriver;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * 同步执行服务 —— 全量/增量的统一业务逻辑。
 *
 * <p><b>为什么要有这个类？</b></p>
 *
 * <p>阶段一之前，"按 jobId 跑一次同步"的完整逻辑写在
 * {@code DynamicSyncBootstrap.main()} 里。命令行用没问题，
 * 但 Spring Boot 常驻服务没法调用一个 {@code static void main}：
 * 那里面会 {@code System.exit()}，而且每次都要重新读配置。</p>
 *
 * <p>所以把可复用的部分抽到这里：<b>接收已经准备好的任务与驱动，执行同步并落水位</b>。
 * 然后：</p>
 * <ul>
 *   <li>命令行入口 {@code DynamicSyncBootstrap} 负责"读配置 + 建连接"，
 *       再调本服务执行 —— 保持原有 CLI 行为不变</li>
 *   <li>Spring Boot 的 {@code SyncTaskService} 负责"读配置 + 建连接"（用 MyBatis-Plus），
 *       同样调本服务执行</li>
 * </ul>
 *
 * <p>这样<b>两种入口共用同一份同步逻辑</b>，不会出现"命令行和网页跑出来的结果不一样"
 * 这种最难排查的问题。</p>
 *
 * <p><b>线程安全</b>：本类无可变字段，每次执行用独立的 {@link DataSourcePool}，
 * 可以被多线程并发调用（常驻服务的后台线程池会这么做）。</p>
 */
@Slf4j
public class SyncExecutionService {

    /** 同步结果（比 SyncJobExecutor.SyncResult 多了耗时与水位的描述信息） */
    public record ExecutionResult(
            boolean success,
            String syncType,
            int totalCount,
            long costMs,
            String watermark,
            String errorMsg) {
    }

    /** 水位落库回调。由调用方实现（命令行走 JDBC，服务端走 MyBatis-Plus）。 */
    @FunctionalInterface
    public interface WatermarkSaver {
        /**
         * @param jobId 任务 ID
         * @param water 水位值（时间戳字符串）
         * @param pk    主键值
         * @param quiet 是否为"逐批静默落库"（不打 INFO 日志，失败只告警）
         */
        void save(long jobId, String water, long pk, boolean quiet);
    }

    /**
     * 执行一次同步。
     *
     * @param job            任务配置（含 syncType / incrementField / lastOffset / lastPk）
     * @param sourceCfg      源库连接配置
     * @param targetCfg      目标库连接配置
     * @param sourceType     源库类型，如 MYSQL
     * @param targetType     目标库类型，如 POSTGRESQL
     * @param watermarkSaver 水位落库回调；传 null 表示不落水位（仅测试用）
     */
    public ExecutionResult execute(SyncJobEntity job,
                                   DataSourceConfig sourceCfg,
                                   DataSourceConfig targetCfg,
                                   String sourceType,
                                   String targetType,
                                   WatermarkSaver watermarkSaver) {

        String syncType = (job.getSyncType() == null || job.getSyncType().trim().isEmpty())
                ? "FULL" : job.getSyncType().trim().toUpperCase();

        if ("INCREMENT".equals(syncType)
                && (job.getIncrementField() == null || job.getIncrementField().trim().isEmpty())) {
            throw new IllegalStateException(
                    "任务 " + job.getId() + " 是增量模式，但未配置 increment_field（增量字段）");
        }

        log.info("任务: id={}, name={}, 模式={}, 批次={}, 源表={}, 目标表={}",
                job.getId(), job.getJobName(), syncType, job.getBatchSize(),
                job.getSourceTable(), job.getTargetTable());
        if ("INCREMENT".equals(syncType)) {
            log.info("增量配置: 字段={}, 起始水位=(last_offset={}, last_pk={})",
                    job.getIncrementField(), job.getLastOffset(), job.getLastPk());
        }

        // 每次执行用独立的连接池：执行完就关，不跨任务复用连接。
        // 常驻服务里这仍然比"每次起 JVM"便宜得多（省掉进程启动与 Maven）。
        DataSourcePool pool = new DataSourcePool();
        long start = System.currentTimeMillis();

        try {
            DataSourceDriver source = pool.getDriver(
                    job.getSourceDatasourceId(), sourceType, sourceCfg);
            DataSourceDriver target = pool.getDriver(
                    job.getTargetDatasourceId(), targetType, targetCfg);

            log.info("========== 开始同步 ==========");
            log.info("方向: {} -> {}", sourceType, targetType);

            SyncJobExecutor executor = new SyncJobExecutor(pool);
            Map<String, String> mapping = new HashMap<String, String>();

            int total;
            String watermarkDesc = null;

            if ("INCREMENT".equals(syncType)) {
                IncrementOutcome outcome = runIncrement(
                        executor, job, source, target, mapping, watermarkSaver);
                total = outcome.total;
                watermarkDesc = outcome.watermark;
            } else {
                total = runFull(executor, job, source, target, mapping);
            }

            long cost = System.currentTimeMillis() - start;
            log.info("总耗时  : {} ms", cost);
            log.info("==============================");

            return new ExecutionResult(true, syncType, total, cost, watermarkDesc, null);

        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("同步失败: {}", e.getMessage(), e);
            return new ExecutionResult(false, syncType, 0, cost, null, e.getMessage());
        } finally {
            // 无论成败都要关闭连接，否则常驻服务会泄漏连接
            pool.closeAll();
        }
    }

    // ==================================================================
    // 全量
    // ==================================================================

    private int runFull(SyncJobExecutor executor, SyncJobEntity job,
                        DataSourceDriver source, DataSourceDriver target,
                        Map<String, String> mapping) {
        SyncJobExecutor.SyncResult r = executor.executeFullSync(job, source, target, mapping);
        log.info("同步条数: {}", r.getTotalCount());
        // 全量同步不维护水位：它的语义是"每次搬整表"，没有"从哪继续"的概念。
        return r.getTotalCount();
    }

    // ==================================================================
    // 增量
    // ==================================================================

    private record IncrementOutcome(int total, String watermark) {
    }

    private IncrementOutcome runIncrement(SyncJobExecutor executor, SyncJobEntity job,
                                          DataSourceDriver source, DataSourceDriver target,
                                          Map<String, String> mapping,
                                          WatermarkSaver saver) {
        try {
            SyncJobExecutor.SyncResult r = executor.executeIncrementSync(
                    job, source, target, mapping,
                    // 逐批落库：进程被强杀时，已完成批次的水位不会丢，
                    // 下次从断点继续而不是从头重扫。
                    saver == null ? null
                            : (water, pk) -> saver.save(job.getId(), water, pk, true));

            log.info("同步条数: {}", r.getTotalCount());

            // 收尾：把最终水位再落一次（覆盖逐批落库的最后一批）
            SyncJobExecutor.Watermark w = r.getWatermark();
            if (w != null && w.getWater() != null && saver != null) {
                saver.save(job.getId(), w.getWater(), w.getPk(), false);
                return new IncrementOutcome(r.getTotalCount(),
                        w.getWater() + " / " + w.getPk());
            }

            // 表为空、或已同步到最新：保持原值，不能被 null 覆盖，
            // 否则下次会从头重扫整表。
            log.info("本次无可推进的水位（无新增数据），保持原水位不变");
            return new IncrementOutcome(r.getTotalCount(), null);

        } catch (SyncJobExecutor.IncrementSyncException e) {
            // ===== 关键设计：失败时回写【目标端真实水位】，而不是源端水位 =====
            //
            // 若沿用源端水位，那么"已从源端读出、但写入目标端失败"的这一批数据，
            // 在下次任务中会被直接跳过 —— 造成永久丢失。
            //
            // 回查目标端真实进度后，下次会从"目标端确实写到哪"继续，
            // 本批数据被重新读取、重新写入。配合 upsert 幂等，重复写无害。
            // 这就是 at-least-once。
            if (e.getTargetWatermark() != null && saver != null) {
                saver.save(job.getId(), e.getTargetWatermark(), e.getLastPk(), false);
                log.warn("增量失败，已按【目标端真实进度】回写水位: last_offset={}, last_pk={}",
                        e.getTargetWatermark(), e.getLastPk());
            } else {
                log.warn("增量失败，且无法回查目标端水位，保持原水位不变"
                        + "（下次将重跑本批，属正常）: {}", e.getMessage());
            }
            throw e;
        }
    }
}
