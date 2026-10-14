package com.minisync.server.store;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行状态存储的内存实现。
 *
 * <p><b>适用场景</b>：单机开发、演示。零外部依赖，启动即可用。</p>
 *
 * <p><b>明确的能力边界</b>（要能讲清楚，而不是假装没有）：</p>
 * <ul>
 *   <li>❌ 服务重启 → 运行状态与日志全部丢失</li>
 *   <li>❌ 多实例部署 → 各实例只看得见自己的状态</li>
 *   <li>❌ 并发锁只在本进程内有效 → 跨实例拦不住</li>
 * </ul>
 *
 * <p>正因为有这些限制，生产部署才需要切到
 * {@link RedisRunStateStore}。</p>
 */
@Slf4j
public class InMemoryRunStateStore implements RunStateStore {

    /** 日志行数上限：只保留最近 N 行，防止长任务把内存吃满 */
    private static final int MAX_LOG_LINES = 200;

    /** runId -> 状态快照 */
    private final Map<String, SyncRunState> runs = new ConcurrentHashMap<>();

    /** jobId -> 当前持锁的 runId */
    private final Map<Long, String> locks = new ConcurrentHashMap<>();

    @Override
    public SyncRunState createRun(Long jobId, String jobName, String syncType) {
        String runId = "run-" + jobId + "-" + System.currentTimeMillis();
        SyncRunState st = SyncRunState.builder()
                .runId(runId)
                .jobId(jobId)
                .jobName(jobName)
                .syncType(syncType)
                .startTime(System.currentTimeMillis())
                .status(SyncRunState.RUNNING)
                .build();
        runs.put(runId, st);
        return st;
    }

    /**
     * {@inheritDoc}
     *
     * <p>用 {@code putIfAbsent} 保证原子性 —— 它是一个 CAS 操作，
     * 两个线程同时调用只有一个能成功放入。</p>
     *
     * <p><b>不要写成这样</b>：{@code if (!locks.containsKey(id)) { locks.put(id, runId); }}
     * —— 判断和放入之间有窗口期，两个线程可能都通过判断。</p>
     */
    @Override
    public boolean tryLockJob(Long jobId, String runId) {
        return locks.putIfAbsent(jobId, runId) == null;
    }

    @Override
    public void unlockJob(Long jobId, String runId) {
        // 只释放自己持有的锁：值对得上才删
        locks.computeIfPresent(jobId, (k, held) -> held.equals(runId) ? null : held);
    }

    @Override
    public boolean isRunning(Long jobId) {
        return locks.containsKey(jobId);
    }

    @Override
    public SyncRunState getRun(String runId) {
        return runs.get(runId);
    }

    @Override
    public SyncRunState latestByJob(Long jobId) {
        SyncRunState latest = null;
        for (SyncRunState st : runs.values()) {
            if (jobId.equals(st.getJobId())) {
                if (latest == null || st.getStartTime() > latest.getStartTime()) {
                    latest = st;
                }
            }
        }
        return latest;
    }

    @Override
    public synchronized void appendLog(String runId, String line) {
        SyncRunState st = runs.get(runId);
        if (st == null) {
            return;
        }
        List<String> logs = st.getLogs();
        logs.add(SyncRunState.logPrefix() + line);
        if (logs.size() > MAX_LOG_LINES) {
            logs = new ArrayList<>(logs.subList(logs.size() - MAX_LOG_LINES, logs.size()));
        }
        runs.put(runId, st.toBuilder().logs(logs).build());
    }

    @Override
    public void updateProgress(String runId, int totalCount) {
        SyncRunState st = runs.get(runId);
        if (st != null) {
            runs.put(runId, st.toBuilder().totalCount(totalCount).build());
        }
    }

    @Override
    public void finishRun(String runId, String status, int totalCount, long costMs,
                          String watermark, String errorMsg) {
        SyncRunState st = runs.get(runId);
        if (st == null) {
            return;
        }
        runs.put(runId, st.toBuilder()
                .status(status)
                .totalCount(totalCount)
                .costMs(costMs)
                .watermark(watermark)
                .errorMsg(errorMsg)
                .endTime(SyncRunState.formatMillis(System.currentTimeMillis()))
                .build());
    }

    @Override
    public void purgeExpired(int olderThanMinutes) {
        long cutoff = System.currentTimeMillis() - olderThanMinutes * 60_000L;
        runs.entrySet().removeIf(e ->
                !e.getValue().isRunning() && e.getValue().getStartTime() < cutoff);
    }

    @Override
    public String backendName() {
        return "in-memory";
    }
}
