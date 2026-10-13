package com.minisync.job.executor;

import com.minisync.connector.pool.DataSourcePool;
import com.minisync.job.entity.SyncJobEntity;
import com.minisync.spi.DataSourceDriver;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 批式同步执行器 —— 全量同步与增量同步的核心实现。
 *
 * <p>本类是面试提问的密集区，因此每个关键决策都写明了「为什么」。</p>
 */
@Slf4j
public class SyncJobExecutor {

    private final DataSourcePool dataSourcePool;

    /** 默认批次大小。过大会推高内存占用，过小会增加往返次数。 */
    private static final int DEFAULT_BATCH_SIZE = 1000;

    public SyncJobExecutor(DataSourcePool dataSourcePool) {
        this.dataSourcePool = dataSourcePool;
    }

    // ==================================================================
    // 全量同步
    // ==================================================================

    /**
     * 全量同步：使用「主键游标分页」而非 limit offset。
     *
     * <p><b>为什么不用 limit offset？</b>
     * {@code LIMIT 1000000, 1000} 时，MySQL 必须先定位并跳过前 100 万行，
     * 再返回 1000 行。offset 越大代价越高，SQL 复杂度是 O(offset + limit)。
     * 而 {@code WHERE id > ? ORDER BY id LIMIT ?} 直接借助主键索引定位起点，
     * 每次都是 O(limit)，与翻到第几页无关。</p>
     *
     * <p><b>前提条件</b>：源表必须有自增或单调递增的主键，
     * 且同步期间主键不会被更新（否则会有行被跳过）。</p>
     */
    public SyncResult executeFullSync(SyncJobEntity job,
                                      DataSourceDriver source,
                                      DataSourceDriver target,
                                      Map<String, String> fieldMapping) {

        String cursorField = resolveCursorField(job);
        int batchSize = resolveBatchSize(job);

        String sql = String.format(
                "SELECT * FROM %s WHERE %s > ? ORDER BY %s ASC LIMIT ?",
                job.getSourceTable(), cursorField, cursorField);

        long cursor = 0L;
        int total = 0;

        while (true) {
            List<Map<String, Object>> batch = source.executeQuery(sql, cursor, batchSize);
            if (batch.isEmpty()) {
                break;
            }

            List<Map<String, Object>> mapped = convertFields(batch, fieldMapping);
            target.batchWrite(job.getTargetTable(), mapped);
            total += batch.size();

            Object lastValue = batch.get(batch.size() - 1).get(cursorField);
            if (lastValue == null) {
                throw new IllegalStateException("游标字段 " + cursorField + " 出现空值，无法继续分页");
            }
            cursor = ((Number) lastValue).longValue();

            log.info("全量同步进行中, jobId={}, 已同步={}", job.getId(), total);

            if (batch.size() < batchSize) {
                break;
            }
        }

        return SyncResult.success(total, null);
    }

    /**
     * 【对照实验用】limit offset 分页的全量同步。
     *
     * <p><b>本方法仅用于性能对比，不作为生产实现。</b>
     * 保留它是为了能现场演示「游标分页 vs offset 分页」的差距，
     * 这是简历中性能优化数据的来源。</p>
     *
     * <p>{@code LIMIT 1000 OFFSET 90000} 要求 MySQL 先定位并跳过前 9 万行，
     * 实际扫描行数远大于返回行数，偏移量越大越慢。</p>
     */
    public SyncResult executeFullSyncByOffset(SyncJobEntity job,
                                              DataSourceDriver source,
                                              DataSourceDriver target,
                                              Map<String, String> fieldMapping) {

        int batchSize = resolveBatchSize(job);
        String sql = String.format(
                "SELECT * FROM %s ORDER BY id LIMIT ? OFFSET ?", job.getSourceTable());

        int offset = 0;
        int total = 0;

        while (true) {
            long t0 = System.currentTimeMillis();
            List<Map<String, Object>> batch = source.executeQuery(sql, batchSize, offset);
            long queryCost = System.currentTimeMillis() - t0;

            if (batch.isEmpty()) {
                break;
            }

            // 打印每批的查询耗时，便于观察 offset 增大时的劣化趋势
            log.info("[offset模式] offset={}, 本批查询耗时={} ms", offset, queryCost);

            List<Map<String, Object>> mapped = convertFields(batch, fieldMapping);
            target.batchWrite(job.getTargetTable(), mapped);
            total += batch.size();
            offset += batchSize;

            if (batch.size() < batchSize) {
                break;
            }
        }

        return SyncResult.success(total, null);
    }

    // ==================================================================
    // 增量同步
    // ==================================================================

    /**
     * 增量同步：使用「复合游标 (水位字段, 主键)」避免边界数据丢失或重复。
     *
     * <p><b>为什么单字段水位不够？</b>
     * 若仅按 {@code update_time >= ?} 翻页，当同一时间戳存在多行且跨批次时，
     * 要么因 {@code >=} 重复读取同一批，要么因 {@code >} 跳过尚未读取的同行。
     * 典型表现是任务卡死或数据错乱。</p>
     *
     * <p><b>解决方案</b>：把主键作为水位的第二排序键与第二个过滤条件：</p>
     * <pre>
     *   WHERE (update_time &gt; ?)
     *      OR (update_time = ? AND id &gt; ?)
     *   ORDER BY update_time ASC, id ASC
     * </pre>
     * <p>这样能严格在全序 (update_time, id) 上单调推进，不重不漏。</p>
     */
    public SyncResult executeIncrementSync(SyncJobEntity job,
                                           DataSourceDriver source,
                                           DataSourceDriver target,
                                           Map<String, String> fieldMapping) {

        String waterField = job.getIncrementField();
        if (waterField == null || waterField.trim().isEmpty()) {
            throw new IllegalStateException("增量同步必须配置增量字段");
        }
        String pkField = resolveCursorField(job);
        int batchSize = resolveBatchSize(job);

        // 上一次的水位：时间 + 主键。首次同步使用全范围起点。
        String lastWater = job.getLastOffset();
        long lastPk = job.getLastPk() == null ? 0L : job.getLastPk();

        int total = 0;
        String maxWaterSynced = lastWater;
        long maxPkSynced = lastPk;

        // 复合游标的查询条件
        String sql = String.format(
                "SELECT * FROM %s "
                + "WHERE (%s > ?) OR (%s = ? AND %s > ?) "
                + "ORDER BY %s ASC, %s ASC LIMIT ?",
                job.getSourceTable(),
                waterField, waterField, pkField,
                waterField, pkField);

        while (true) {
            List<Map<String, Object>> batch;
            if (lastWater == null) {
                // 首次全范围读取，不带水位条件
                String firstSql = String.format(
                        "SELECT * FROM %s ORDER BY %s ASC, %s ASC LIMIT ?",
                        job.getSourceTable(), waterField, pkField);
                batch = source.executeQuery(firstSql, batchSize);
            } else {
                batch = source.executeQuery(sql, lastWater, lastWater, lastPk, batchSize);
            }

            if (batch.isEmpty()) {
                break;
            }

            try {
                List<Map<String, Object>> mapped = convertFields(batch, fieldMapping);
                target.batchWrite(job.getTargetTable(), mapped);
            } catch (Exception e) {
                // ===== 关键：失败时回写「目标端真实水位」而非源端水位 =====
                // 若此处沿用源端水位，那么本批已读但未写入的数据
                // 在下次任务中将被跳过，造成永久丢失。
                // 回查目标端最大值可以保证 at-least-once（宁可重复，不可丢失）。
                String targetWater = safeQueryTargetWater(target, job, waterField);
                throw new IncrementSyncException(
                        "增量批次写入失败，已回写目标端水位=" + targetWater, targetWater, maxPkSynced, e);
            }

            total += batch.size();

            Map<String, Object> lastRow = batch.get(batch.size() - 1);
            Object waterValue = lastRow.get(waterField);
            Object pkValue = lastRow.get(pkField);
            if (waterValue == null || pkValue == null) {
                throw new IllegalStateException("水位字段或主键出现空值，无法推进游标");
            }

            lastWater = String.valueOf(waterValue);
            lastPk = ((Number) pkValue).longValue();
            maxWaterSynced = lastWater;
            maxPkSynced = lastPk;

            log.info("增量同步进行中, jobId={}, 已同步={}, 当前水位={}/{}",
                    job.getId(), total, lastWater, lastPk);

            if (batch.size() < batchSize) {
                break;
            }
        }

        return SyncResult.success(total, new Watermark(maxWaterSynced, maxPkSynced));
    }

    /**
     * 查询目标端水位，查询自身失败时降级返回 null（由调用方决定如何处理）。
     */
    private String safeQueryTargetWater(DataSourceDriver target, SyncJobEntity job, String field) {
        try {
            return target.queryMaxValue(job.getTargetTable(), field);
        } catch (Exception e) {
            log.error("回查目标端水位失败, table={}, field={}", job.getTargetTable(), field, e);
            return null;
        }
    }

    // ==================================================================
    // 字段映射
    // ==================================================================

    /**
     * 按映射关系重命名列。
     *
     * <p>映射形如 {@code {"src_name": "dst_name"}}。
     * 映射为空时原样返回，进入「同名同步」模式。</p>
     */
    List<Map<String, Object>> convertFields(List<Map<String, Object>> source,
                                            Map<String, String> fieldMapping) {
        if (fieldMapping == null || fieldMapping.isEmpty()) {
            return source;
        }

        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>(source.size());
        for (Map<String, Object> row : source) {
            Map<String, Object> converted = new HashMap<String, Object>();
            boolean anyMapped = false;
            for (Map.Entry<String, String> entry : fieldMapping.entrySet()) {
                if (row.containsKey(entry.getKey())) {
                    converted.put(entry.getValue(), row.get(entry.getKey()));
                    anyMapped = true;
                }
            }
            // 映射中未涉及的列保持原名，避免静默丢列
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (!fieldMapping.containsKey(entry.getKey())) {
                    converted.put(entry.getKey(), entry.getValue());
                }
            }
            if (!anyMapped && converted.isEmpty()) {
                converted.putAll(row);
            }
            result.add(converted);
        }
        return result;
    }

    // ==================================================================
    // 工具方法
    // ==================================================================

    private String resolveCursorField(SyncJobEntity job) {
        String field = job.getCursorField();
        return (field == null || field.trim().isEmpty()) ? "id" : field;
    }

    private int resolveBatchSize(SyncJobEntity job) {
        Integer size = job.getBatchSize();
        return (size == null || size <= 0) ? DEFAULT_BATCH_SIZE : size;
    }

    // ==================================================================
    // 结果对象
    // ==================================================================

    /** 同步水位：水位字段值 + 主键值，两者共同定位进度。 */
    @Data
    public static class Watermark {
        private final String water;
        private final long pk;

        public Watermark(String water, long pk) {
            this.water = water;
            this.pk = pk;
        }
    }

    @Data
    public static class SyncResult {
        private final boolean success;
        private final int totalCount;
        private final Watermark watermark;

        private SyncResult(boolean success, int totalCount, Watermark watermark) {
            this.success = success;
            this.totalCount = totalCount;
            this.watermark = watermark;
        }

        public static SyncResult success(int totalCount, Watermark watermark) {
            return new SyncResult(true, totalCount, watermark);
        }
    }

    /** 增量同步异常，携带失败时应回写的目标端水位。 */
    public static class IncrementSyncException extends RuntimeException {
        private final String targetWatermark;
        private final long lastPk;

        public IncrementSyncException(String message, String targetWatermark, long lastPk, Throwable cause) {
            super(message, cause);
            this.targetWatermark = targetWatermark;
            this.lastPk = lastPk;
        }

        public String getTargetWatermark() {
            return targetWatermark;
        }

        public long getLastPk() {
            return lastPk;
        }
    }
}
