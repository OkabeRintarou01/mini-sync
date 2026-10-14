package com.minisync.server.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

/**
 * 同步任务运行状态存储（内存）。
 *
 * <p><b>为什么需要它？</b></p>
 *
 * <p>改造前 {@code /api/run} 是<b>同步阻塞</b>的：HTTP 请求要一直等到整个同步跑完
 * （10 万条约几十秒）才返回。这有两个问题：</p>
 * <ol>
 *   <li>浏览器一直转圈，用户以为卡死了</li>
 *   <li>Nginx / 网关通常有 30~60 秒超时，长任务会被掐断</li>
 * </ol>
 *
 * <p>改成异步后：触发接口<b>立即</b>返回一个 {@code runId}，
 * 同步在后台线程池执行，前端拿着 runId 轮询进度。</p>
 *
 * <p><b>为什么先放内存而不是 Redis？</b></p>
 *
 * <p>当前是单实例部署，内存足够。但这有个明确限制：
 * <b>服务重启后进度会丢</b>，多实例部署时也读不到彼此的状态。
 * 后续引入 Redis 就是为了解决这两点 —— 届时只需替换本类的实现，
 * 调用方（Controller / 前端）不用改。</p>
 */
@Service
public class SyncRunStateService {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 运行状态枚举（用字符串，方便前端直接判断） */
    public static final String RUNNING = "RUNNING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";

    /**
     * 一次运行的记录。
     *
     * <p>用可变对象 + synchronized 方法保证可见性：后台线程写、HTTP 线程读。</p>
     */
    public static class RunState {
        private final String runId;
        private final Long jobId;
        private final String jobName;
        private final String syncType;
        private final long startTime;
        private volatile String status;
        private volatile int totalCount;
        private volatile long costMs;
        private volatile String watermark;
        private volatile String errorMsg;
        private volatile String endTime;
        /** 最近若干行日志，供前端展示 */
        private final List<String> logs = new ArrayList<>();

        RunState(String runId, Long jobId, String jobName, String syncType) {
            this.runId = runId;
            this.jobId = jobId;
            this.jobName = jobName;
            this.syncType = syncType;
            this.startTime = System.currentTimeMillis();
            this.status = RUNNING;
        }

        public String getRunId() {
            return runId;
        }

        public Long getJobId() {
            return jobId;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public void setTotalCount(int totalCount) {
            this.totalCount = totalCount;
        }

        public void setCostMs(long costMs) {
            this.costMs = costMs;
        }

        public void setWatermark(String watermark) {
            this.watermark = watermark;
        }

        public void setErrorMsg(String errorMsg) {
            this.errorMsg = errorMsg;
        }

        public void setEndTime(String endTime) {
            this.endTime = endTime;
        }

        public synchronized void addLog(String line) {
            logs.add("[" + LocalDateTime.now().format(FMT).substring(11) + "] " + line);
            // 只保留最近 200 行，防止长任务把内存吃满
            if (logs.size() > 200) {
                logs.remove(0);
            }
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("runId", runId);
            m.put("jobId", String.valueOf(jobId));
            m.put("jobName", jobName);
            m.put("syncType", syncType);
            m.put("status", status);
            m.put("totalCount", totalCount);
            m.put("costMs", costMs);
            m.put("watermark", watermark);
            m.put("errorMsg", errorMsg);
            m.put("startTime", LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(startTime),
                    java.time.ZoneId.systemDefault()).format(FMT));
            m.put("endTime", endTime);
            m.put("running", RUNNING.equals(status));
            synchronized (this) {
                m.put("logs", new ArrayList<>(logs));
            }
            return m;
        }
    }

    private final Map<String, RunState> runs = new ConcurrentHashMap<>();

    /** 每个任务的"当前运行中"记录，用于阻止同一任务并发执行 */
    private final Map<Long, String> runningByJob = new ConcurrentHashMap<>();

    public RunState create(Long jobId, String jobName, String syncType) {
        String runId = "run-" + jobId + "-" + System.currentTimeMillis();
        RunState st = new RunState(runId, jobId, jobName, syncType);
        runs.put(runId, st);
        return st;
    }

    /**
     * 尝试占用某个任务。
     *
     * <p>用 {@code putIfAbsent} 保证原子性：两个请求同时触发同一任务时，
     * 只有一个能拿到锁，另一个被拒绝 —— 避免同一份数据被并发搬两遍。</p>
     *
     * @return 成功返回 true
     */
    public boolean tryLockJob(Long jobId, String runId) {
        return runningByJob.putIfAbsent(jobId, runId) == null;
    }

    public void unlockJob(Long jobId) {
        runningByJob.remove(jobId);
    }

    /** 该任务当前是否正在运行 */
    public boolean isRunning(Long jobId) {
        return runningByJob.containsKey(jobId);
    }

    public RunState get(String runId) {
        return runs.get(runId);
    }

    /** 取某任务最近一次运行（用于前端刷新后恢复显示） */
    public RunState latestByJob(Long jobId) {
        RunState latest = null;
        for (RunState st : runs.values()) {
            if (jobId.equals(st.jobId)) {
                if (latest == null || st.startTime > latest.startTime) {
                    latest = st;
                }
            }
        }
        return latest;
    }

    /** 清理过旧的运行记录，避免无限增长 */
    public void purgeOlderThanMinutes(int minutes) {
        long cutoff = System.currentTimeMillis() - minutes * 60_000L;
        runs.entrySet().removeIf(e ->
                !RUNNING.equals(e.getValue().status) && e.getValue().startTime < cutoff);
    }
}
