package com.minisync.server.store;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次同步运行的完整状态（值对象）。
 *
 * <p><b>为什么做成不可变的值对象，而不是像原来那样的可变对象？</b></p>
 *
 * <p>因为现在有两套存储实现：</p>
 * <ul>
 *   <li><b>内存版</b>：持有一个对象引用，可以原地改字段</li>
 *   <li><b>Redis 版</b>：每次读写都是序列化/反序列化，根本"原地改"不了</li>
 * </ul>
 *
 * <p>如果沿用可变对象，两套实现的行为就会不一致。所以统一成
 * 「读出来是快照、改完再存回去」的模型 —— 这也是分布式的自然形态。</p>
 *
 * <p>构造用 {@link Builder}，避免十几个参数的构造器。</p>
 */
public class SyncRunState {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 运行中 */
    public static final String RUNNING = "RUNNING";
    /** 成功 */
    public static final String SUCCESS = "SUCCESS";
    /** 失败 */
    public static final String FAILED = "FAILED";
    /** 查不到（前端用它判断"没有运行记录"） */
    public static final String NOT_FOUND = "NOT_FOUND";

    private final String runId;
    private final Long jobId;
    private final String jobName;
    private final String syncType;
    private final long startTime;
    private final String status;
    private final int totalCount;
    private final long costMs;
    private final String watermark;
    private final String errorMsg;
    private final String endTime;
    private final List<String> logs;

    private SyncRunState(Builder b) {
        this.runId = b.runId;
        this.jobId = b.jobId;
        this.jobName = b.jobName;
        this.syncType = b.syncType;
        this.startTime = b.startTime;
        this.status = b.status;
        this.totalCount = b.totalCount;
        this.costMs = b.costMs;
        this.watermark = b.watermark;
        this.errorMsg = b.errorMsg;
        this.endTime = b.endTime;
        this.logs = b.logs == null ? new ArrayList<>() : new ArrayList<>(b.logs);
    }

    public String getRunId() {
        return runId;
    }

    public Long getJobId() {
        return jobId;
    }

    public String getJobName() {
        return jobName;
    }

    public String getSyncType() {
        return syncType;
    }

    public long getStartTime() {
        return startTime;
    }

    public String getStatus() {
        return status;
    }

    public int getTotalCount() {
        return totalCount;
    }

    public long getCostMs() {
        return costMs;
    }

    public String getWatermark() {
        return watermark;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public String getEndTime() {
        return endTime;
    }

    public List<String> getLogs() {
        return new ArrayList<>(logs);
    }

    public boolean isRunning() {
        return RUNNING.equals(status);
    }

    /** 转成前端要的 JSON 结构（字段名与 Node 版保持一致） */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", runId);
        m.put("jobId", jobId == null ? null : String.valueOf(jobId));
        m.put("jobName", jobName);
        m.put("syncType", syncType);
        m.put("status", status);
        m.put("totalCount", totalCount);
        m.put("costMs", costMs);
        m.put("watermark", watermark);
        m.put("errorMsg", errorMsg);
        m.put("startTime", formatMillis(startTime));
        m.put("endTime", endTime);
        m.put("running", isRunning());
        m.put("logs", getLogs());
        return m;
    }

    public static String formatMillis(long millis) {
        if (millis <= 0) {
            return null;
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
                .format(FMT);
    }

    /** 日志行前缀，如 {@code [00:18:41]} */
    public static String logPrefix() {
        return "[" + LocalDateTime.now().format(FMT).substring(11) + "] ";
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.runId = runId;
        b.jobId = jobId;
        b.jobName = jobName;
        b.syncType = syncType;
        b.startTime = startTime;
        b.status = status;
        b.totalCount = totalCount;
        b.costMs = costMs;
        b.watermark = watermark;
        b.errorMsg = errorMsg;
        b.endTime = endTime;
        b.logs = getLogs();
        return b;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String runId;
        private Long jobId;
        private String jobName;
        private String syncType;
        private long startTime = System.currentTimeMillis();
        private String status = RUNNING;
        private int totalCount;
        private long costMs;
        private String watermark;
        private String errorMsg;
        private String endTime;
        private List<String> logs = new ArrayList<>();

        public Builder runId(String v) {
            this.runId = v;
            return this;
        }

        public Builder jobId(Long v) {
            this.jobId = v;
            return this;
        }

        public Builder jobName(String v) {
            this.jobName = v;
            return this;
        }

        public Builder syncType(String v) {
            this.syncType = v;
            return this;
        }

        public Builder startTime(long v) {
            this.startTime = v;
            return this;
        }

        public Builder status(String v) {
            this.status = v;
            return this;
        }

        public Builder totalCount(int v) {
            this.totalCount = v;
            return this;
        }

        public Builder costMs(long v) {
            this.costMs = v;
            return this;
        }

        public Builder watermark(String v) {
            this.watermark = v;
            return this;
        }

        public Builder errorMsg(String v) {
            this.errorMsg = v;
            return this;
        }

        public Builder endTime(String v) {
            this.endTime = v;
            return this;
        }

        public Builder logs(List<String> v) {
            this.logs = v;
            return this;
        }

        public SyncRunState build() {
            return new SyncRunState(this);
        }
    }
}
