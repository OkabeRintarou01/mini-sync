package com.minisync.server.store;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 运行状态存储的 Redis 实现。
 *
 * <p>解决内存版的两个硬伤：<b>重启丢状态</b>、<b>多实例看不见彼此</b>。</p>
 *
 * <h3>Key 设计</h3>
 * <pre>
 *   sync:run:{runId}              Hash   一次运行的状态字段
 *   sync:run:{runId}:logs         List   日志行（LTRIM 只留最近 200 行）
 *   sync:job:{jobId}:latest       String 该任务最近一次 runId
 *   sync:lock:job:{jobId}         String 任务锁（值为持有者的 runId，带 TTL）
 * </pre>
 *
 * <p>所有 key 都设 TTL —— 这些是<b>临时状态</b>，不该永久占用 Redis 内存。
 * 执行历史（永久记录）在 MySQL 的 {@code sync_log} 表里，两者职责不同。</p>
 *
 * <h3>两个必须讲清楚的设计点</h3>
 *
 * <p><b>1. 锁必须带 TTL</b><br>
 * 如果持有锁的进程崩溃且没来得及释放，没有 TTL 的话任务会被<b>永久卡住</b>，
 * 只能人工去 Redis 删 key。加了 TTL 后最多卡 TTL 那么久就自动恢复。
 * 这里取 30 分钟（远超单次同步的正常耗时）。</p>
 *
 * <p><b>2. 释放锁必须"值匹配才删"</b><br>
 * 考虑这个时序：<br>
 * <pre>
 *   t0  实例 A 拿到锁（TTL 30min），开始跑一个很慢的任务
 *   t30 锁超时自动释放
 *   t31 实例 B 拿到锁，开始跑同一任务
 *   t32 A 终于跑完了，执行"释放锁"
 *       → 如果直接 DEL，就把 B 的锁删掉了！
 *       → 此时实例 C 又能拿到锁，同一个任务被并发执行
 * </pre>
 * 所以释放时要先比对值是不是自己的 runId。这个"比对+删除"必须原子，
 * 否则比对通过后、删除前又可能被超时打断。用 Lua 脚本实现。</p>
 */
@Slf4j
public class RedisRunStateStore implements RunStateStore {

    /** 日志行数上限（与内存版保持一致） */
    private static final int MAX_LOG_LINES = 200;

    /** 运行状态保留时长：1 天。够用户回看，又不至于堆积 */
    private static final long RUN_TTL_HOURS = 24;

    /** 任务锁 TTL：30 分钟。远超单次同步耗时，又能兜住进程崩溃 */
    private static final long LOCK_TTL_MINUTES = 30;

    private static final String K_RUN = "sync:run:";
    private static final String K_RUN_LOGS = "sync:run:%s:logs";
    private static final String K_JOB_LATEST = "sync:job:%d:latest";
    private static final String K_LOCK = "sync:lock:job:%d";

    /**
     * 释放锁的 Lua 脚本：只有当锁的值等于自己的 runId 时才删除。
     *
     * <p>为什么用 Lua 而不是"先 GET 再 DEL"？因为那两步之间不是原子的：
     * GET 拿到自己的值 → 锁超时释放 → 别人拿到锁 → 我 DEL 掉了别人的锁。
     * Redis 执行 Lua 脚本是原子的，中途不会插入其他命令。</p>
     */
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT =
            new DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "  return redis.call('del', KEYS[1]) "
                    + "else "
                    + "  return 0 "
                    + "end",
                    Long.class);

    private final StringRedisTemplate redis;

    public RedisRunStateStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // ==================================================================
    // 运行状态
    // ==================================================================

    @Override
    public SyncRunState createRun(Long jobId, String jobName, String syncType) {
        String runId = "run-" + jobId + "-" + System.currentTimeMillis();
        long now = System.currentTimeMillis();

        SyncRunState st = SyncRunState.builder()
                .runId(runId)
                .jobId(jobId)
                .jobName(jobName)
                .syncType(syncType)
                .startTime(now)
                .status(SyncRunState.RUNNING)
                .build();

        saveRun(st);
        return st;
    }

    private void saveRun(SyncRunState st) {
        String key = K_RUN + st.getRunId();
        Map<String, String> hash = new java.util.LinkedHashMap<>();
        hash.put("runId", nz(st.getRunId()));
        hash.put("jobId", st.getJobId() == null ? "" : String.valueOf(st.getJobId()));
        hash.put("jobName", nz(st.getJobName()));
        hash.put("syncType", nz(st.getSyncType()));
        hash.put("startTime", String.valueOf(st.getStartTime()));
        hash.put("status", nz(st.getStatus()));
        hash.put("totalCount", String.valueOf(st.getTotalCount()));
        hash.put("costMs", String.valueOf(st.getCostMs()));
        hash.put("watermark", nz(st.getWatermark()));
        hash.put("errorMsg", nz(st.getErrorMsg()));
        hash.put("endTime", nz(st.getEndTime()));

        redis.opsForHash().putAll(key, hash);
        redis.expire(key, RUN_TTL_HOURS, TimeUnit.HOURS);

        // 记录"该任务最近一次运行"，供 /api/run/latest 查询
        if (st.getJobId() != null) {
            String latestKey = String.format(K_JOB_LATEST, st.getJobId());
            redis.opsForValue().set(latestKey, st.getRunId(), RUN_TTL_HOURS, TimeUnit.HOURS);
        }
    }

    @Override
    public SyncRunState getRun(String runId) {
        Map<Object, Object> h = redis.opsForHash().entries(K_RUN + runId);
        if (h == null || h.isEmpty()) {
            return null;
        }
        List<String> logs = redis.opsForList()
                .range(String.format(K_RUN_LOGS, runId), 0, -1);
        return fromHash(h, logs);
    }

    @Override
    public SyncRunState latestByJob(Long jobId) {
        String runId = redis.opsForValue().get(String.format(K_JOB_LATEST, jobId));
        return runId == null ? null : getRun(runId);
    }

    private SyncRunState fromHash(Map<Object, Object> h, List<String> logs) {
        return SyncRunState.builder()
                .runId(str(h, "runId"))
                .jobId(longOrNull(str(h, "jobId")))
                .jobName(str(h, "jobName"))
                .syncType(str(h, "syncType"))
                .startTime(longOr(str(h, "startTime"), 0L))
                .status(str(h, "status"))
                .totalCount((int) longOr(str(h, "totalCount"), 0L))
                .costMs(longOr(str(h, "costMs"), 0L))
                .watermark(str(h, "watermark"))
                .errorMsg(str(h, "errorMsg"))
                .endTime(str(h, "endTime"))
                .logs(logs == null ? new ArrayList<>() : logs)
                .build();
    }

    // ==================================================================
    // 日志与进度
    // ==================================================================

    @Override
    public void appendLog(String runId, String line) {
        String key = String.format(K_RUN_LOGS, runId);
        redis.opsForList().rightPush(key, SyncRunState.logPrefix() + line);
        // 只保留最近 N 行：LTRIM 让 Redis 自己截断，不需要像内存版那样手工 subList
        redis.opsForList().trim(key, -MAX_LOG_LINES, -1);
        redis.expire(key, RUN_TTL_HOURS, TimeUnit.HOURS);
    }

    @Override
    public void updateProgress(String runId, int totalCount) {
        String key = K_RUN + runId;
        if (Boolean.TRUE.equals(redis.hasKey(key))) {
            redis.opsForHash().put(key, "totalCount", String.valueOf(totalCount));
        }
    }

    @Override
    public void finishRun(String runId, String status, int totalCount, long costMs,
                          String watermark, String errorMsg) {
        String key = K_RUN + runId;
        if (!Boolean.TRUE.equals(redis.hasKey(key))) {
            return;
        }
        Map<String, String> patch = new java.util.LinkedHashMap<>();
        patch.put("status", nz(status));
        patch.put("totalCount", String.valueOf(totalCount));
        patch.put("costMs", String.valueOf(costMs));
        patch.put("watermark", nz(watermark));
        patch.put("errorMsg", nz(errorMsg));
        patch.put("endTime", nz(SyncRunState.formatMillis(System.currentTimeMillis())));
        redis.opsForHash().putAll(key, patch);
    }

    // ==================================================================
    // 任务锁
    // ==================================================================

    /**
     * {@inheritDoc}
     *
     * <p>{@code SET key value NX EX ttl} 是 Redis 官方的分布式锁获取方式：
     * NX 保证"不存在才设置"（原子），EX 保证自动过期（防止死锁）。</p>
     *
     * <p><b>为什么不能用"先 GET 判断再 SET"？</b>
     * 那两步之间其他实例可能已经设置了锁，会互相覆盖。</p>
     */
    @Override
    public boolean tryLockJob(Long jobId, String runId) {
        String key = String.format(K_LOCK, jobId);
        Boolean ok = redis.opsForValue()
                .setIfAbsent(key, runId, LOCK_TTL_MINUTES, TimeUnit.MINUTES);
        return Boolean.TRUE.equals(ok);
    }

    @Override
    public void unlockJob(Long jobId, String runId) {
        String key = String.format(K_LOCK, jobId);
        Long deleted = redis.execute(RELEASE_LOCK_SCRIPT,
                Collections.singletonList(key), runId);
        if (deleted != null && deleted > 0) {
            log.debug("已释放任务锁: jobId={}, runId={}", jobId, runId);
        } else {
            // 走到这里说明锁已经不属于自己了（多半是超时后被别人接管）
            log.warn("跳过释放任务锁（锁已不属于本次运行）: jobId={}, runId={}", jobId, runId);
        }
    }

    @Override
    public boolean isRunning(Long jobId) {
        return Boolean.TRUE.equals(redis.hasKey(String.format(K_LOCK, jobId)));
    }

    // ==================================================================
    // 其它
    // ==================================================================

    @Override
    public void purgeExpired(int olderThanMinutes) {
        // Redis 版不需要手工清理：所有 key 都设了 TTL，到点自动删。
        // 保留这个方法是为了接口统一（内存版需要它）。
        log.debug("Redis 版依赖 TTL 自动过期，无需手工清理");
    }

    @Override
    public String backendName() {
        return "redis";
    }

    /** 诊断用：列出当前所有运行中的任务锁 */
    public Set<String> listLocks() {
        return redis.keys("sync:lock:job:*");
    }

    // ---------- 小工具 ----------

    private static String nz(String v) {
        return v == null ? "" : v;
    }

    private static String str(Map<Object, Object> h, String k) {
        Object v = h.get(k);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v);
        return s.isEmpty() ? null : s;
    }

    private static Long longOrNull(String v) {
        if (v == null || v.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long longOr(String v, long def) {
        Long l = longOrNull(v);
        return l == null ? def : l;
    }
}
