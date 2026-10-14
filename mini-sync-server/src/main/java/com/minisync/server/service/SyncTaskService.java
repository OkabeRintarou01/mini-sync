package com.minisync.server.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.minisync.job.entity.SyncJobEntity;
import com.minisync.job.service.SyncExecutionService;
import com.minisync.server.entity.DatasourceConfig;
import com.minisync.server.entity.SyncJob;
import com.minisync.server.entity.SyncLog;
import com.minisync.server.mapper.DatasourceConfigMapper;
import com.minisync.server.mapper.SyncJobMapper;
import com.minisync.server.mapper.SyncLogMapper;
import com.minisync.server.store.RunStateStore;
import com.minisync.server.store.SyncRunState;
import com.minisync.spi.DataSourceConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 异步同步执行服务。
 *
 * <p>职责：把「管理库里的任务配置」翻译成「同步引擎要的对象」，
 * 然后在<b>后台线程</b>里调用共用的 {@link SyncExecutionService} 执行。</p>
 *
 * <p>整个链路（与命令行入口完全一致，只是触发方式不同）：</p>
 * <pre>
 *   HTTP 触发 → 读任务/数据源配置 → 组装 SyncJobEntity + DataSourceConfig
 *            → SyncExecutionService.execute(...)   ← 与 CLI 共用
 *            → 结果写入 sync_log（MySQL，永久）+ 运行状态（可 Redis，临时）
 * </pre>
 *
 * <p>注意运行状态走的是 {@link RunStateStore} 抽象，而不是直接操作内存 ——
 * 这样切换 Redis 时本类不用改。</p>
 */
@Slf4j
@Service
public class SyncTaskService {

    private final SyncJobMapper jobMapper;
    private final DatasourceConfigMapper dsMapper;
    private final SyncLogMapper logMapper;
    private final RunStateStore runStateStore;

    public SyncTaskService(SyncJobMapper jobMapper,
                           DatasourceConfigMapper dsMapper,
                           SyncLogMapper logMapper,
                           RunStateStore runStateStore) {
        this.jobMapper = jobMapper;
        this.dsMapper = dsMapper;
        this.logMapper = logMapper;
        this.runStateStore = runStateStore;
    }

    /**
     * 在后台线程执行同步。
     *
     * <p><b>为什么不能是 public 且被同类内部调用？</b>
     * {@code @Async} 依赖 Spring 代理生效，同类内部直接调用会绕过代理、
     * 退化成同步执行。所以这个方法由 Controller 直接调用。</p>
     */
    @Async("syncExecutor")
    public void executeAsync(Long jobId, String runId) {
        if (runStateStore.getRun(runId) == null) {
            log.warn("运行记录不存在，忽略: runId={}", runId);
            return;
        }

        try {
            runStateStore.appendLog(runId, "开始执行任务 " + jobId);

            SyncJob job = jobMapper.selectById(jobId);
            if (job == null) {
                throw new IllegalStateException("任务不存在: id=" + jobId);
            }

            DatasourceConfig src = dsMapper.selectById(job.getSourceDatasourceId());
            DatasourceConfig tgt = dsMapper.selectById(job.getTargetDatasourceId());
            if (src == null || tgt == null) {
                throw new IllegalStateException("任务的数据源不存在（可能已被删除）");
            }

            runStateStore.appendLog(runId,
                    "方向: " + src.getDsType() + "(" + src.getDatabaseName() + ") → "
                            + tgt.getDsType() + "(" + tgt.getDatabaseName() + ")");
            runStateStore.appendLog(runId,
                    "模式: " + job.getSyncType()
                            + ("INCREMENT".equalsIgnoreCase(job.getSyncType())
                                    ? "，增量字段 " + job.getIncrementField() : ""));

            // 管理库实体 → 引擎实体
            SyncJobEntity entity = toEngineEntity(job);

            SyncExecutionService engine = new SyncExecutionService();
            SyncExecutionService.ExecutionResult result = engine.execute(
                    entity,
                    toDriverConfig(src),
                    toDriverConfig(tgt),
                    src.getDsType(),
                    tgt.getDsType(),
                    // 水位落库：这里用 MyBatis-Plus 写回管理库
                    (id, water, pk, quiet) -> saveWatermark(id, water, pk, quiet));

            runStateStore.appendLog(runId, "同步条数: " + result.totalCount());
            runStateStore.appendLog(runId, "总耗时: " + result.costMs() + " ms");
            if (result.watermark() != null) {
                runStateStore.appendLog(runId, "水位已落库: " + result.watermark());
            }
            if (!result.success()) {
                runStateStore.appendLog(runId, "失败原因: " + result.errorMsg());
            }

            // 写入终态：状态/条数/耗时/水位/错误一并落库
            runStateStore.finishRun(runId,
                    result.success() ? SyncRunState.SUCCESS : SyncRunState.FAILED,
                    result.totalCount(), result.costMs(),
                    result.watermark(), result.errorMsg());

            writeSyncLog(job, result);

        } catch (Exception e) {
            log.error("异步同步执行异常: jobId={}", jobId, e);
            runStateStore.appendLog(runId, "异常: " + e.getMessage());
            runStateStore.finishRun(runId, SyncRunState.FAILED, 0, 0L, null, e.getMessage());
        } finally {
            // 释放锁必须带 runId：只有锁还属于本次运行时才会被删掉。
            // 否则可能出现"任务 A 超时后 B 拿到锁，A 跑完把 B 的锁删了"的连锁问题。
            runStateStore.unlockJob(jobId, runId);
        }
    }

    /**
     * 水位落库（MyBatis-Plus 实现）。
     *
     * <p>必须用 {@code LambdaUpdateWrapper.set} 而不是 {@code updateById}：
     * 后者会<b>忽略 null 字段</b>，而这里恰恰需要能写入 null。
     * 同理，重置水位也必须用 wrapper —— 阶段一就是靠这个才把水位清干净的。</p>
     */
    private void saveWatermark(Long jobId, String water, long pk, boolean quiet) {
        jobMapper.update(null, new LambdaUpdateWrapper<SyncJob>()
                .eq(SyncJob::getId, jobId)
                .set(SyncJob::getLastOffset, water)
                .set(SyncJob::getLastPk, pk)
                .set(SyncJob::getLastSyncTime, java.time.LocalDateTime.now()));
        if (!quiet) {
            log.info("水位已落库: jobId={}, last_offset={}, last_pk={}", jobId, water, pk);
        } else {
            log.debug("批次水位已落库: jobId={}, last_offset={}, last_pk={}", jobId, water, pk);
        }
    }

    private void writeSyncLog(SyncJob job, SyncExecutionService.ExecutionResult r) {
        try {
            SyncLog entity = new SyncLog();
            entity.setJobId(job.getId());
            entity.setJobName(job.getJobName());
            entity.setSyncMode(r.syncType());
            entity.setTotalCount(r.totalCount());
            entity.setCostMs(r.costMs());
            entity.setSuccess(r.success() ? 1 : 0);
            entity.setErrorMsg(r.errorMsg());
            logMapper.insert(entity);
        } catch (Exception e) {
            // 日志写入失败不该影响同步结果本身
            log.error("写 sync_log 失败: {}", e.getMessage());
        }
    }

    /** 管理库实体 → 同步引擎实体 */
    private SyncJobEntity toEngineEntity(SyncJob j) {
        SyncJobEntity e = new SyncJobEntity();
        e.setId(j.getId());
        e.setJobName(j.getJobName());
        e.setSourceDatasourceId(j.getSourceDatasourceId());
        e.setSourceTable(j.getSourceTable());
        e.setTargetDatasourceId(j.getTargetDatasourceId());
        e.setTargetTable(j.getTargetTable());
        e.setSyncType(j.getSyncType());
        e.setIncrementField(j.getIncrementField());
        e.setCursorField(j.getCursorField());
        e.setBatchSize(j.getBatchSize());
        e.setLastOffset(j.getLastOffset());
        e.setLastPk(j.getLastPk());
        return e;
    }

    /** 管理库实体 → 驱动连接配置 */
    private DataSourceConfig toDriverConfig(DatasourceConfig ds) {
        DataSourceConfig c = new DataSourceConfig();
        c.setHost(ds.getHost());
        c.setPort(ds.getPort());
        c.setDatabaseName(ds.getDatabaseName());
        c.setUsername(ds.getUsername());
        c.setPassword(ds.getPassword());
        c.setExtraParams(ds.getExtraParams());
        return c;
    }
}
