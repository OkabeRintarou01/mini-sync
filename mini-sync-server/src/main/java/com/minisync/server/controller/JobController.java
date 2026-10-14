package com.minisync.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.minisync.server.common.R;
import com.minisync.server.entity.DatasourceConfig;
import com.minisync.server.entity.SyncJob;
import com.minisync.server.mapper.DatasourceConfigMapper;
import com.minisync.server.mapper.SyncJobMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 同步任务管理接口。
 *
 * <p>迁移自 Node 版，请求/返回结构与原来一致：</p>
 * <ul>
 *   <li>{@code GET  /api/job/list}            列表（带源/目标数据源名称）</li>
 *   <li>{@code POST /api/job/save}            新增/修改（含 sync_type / increment_field）</li>
 *   <li>{@code GET  /api/job/delete}          删除</li>
 *   <li>{@code GET  /api/job/resetwatermark}  重置水位</li>
 * </ul>
 *
 * <p><b>重置水位</b>是阶段一新增的能力：把 {@code last_offset} 置 NULL、
 * {@code last_pk} 置 0，让增量任务下次从表头重新开始。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/job")
public class JobController {

    private final SyncJobMapper jobMapper;
    private final DatasourceConfigMapper dsMapper;

    public JobController(SyncJobMapper jobMapper, DatasourceConfigMapper dsMapper) {
        this.jobMapper = jobMapper;
        this.dsMapper = dsMapper;
    }

    /**
     * 任务列表。
     *
     * <p>用一次查询把数据源信息一并取出（避免 N+1），
     * 再在内存里拼装 sourceName / sourceType 等前端要用的字段。</p>
     */
    @GetMapping("/list")
    public Map<String, Object> list() {
        List<SyncJob> jobs = jobMapper.selectList(
                new LambdaQueryWrapper<SyncJob>().orderByDesc(SyncJob::getId));

        // 一次取出全部数据源，做成 id -> 实体 的索引
        Map<Long, DatasourceConfig> dsIndex = dsMapper.selectList(null).stream()
                .collect(Collectors.toMap(DatasourceConfig::getId, Function.identity()));

        List<Map<String, Object>> data = new ArrayList<>(jobs.size());
        for (SyncJob j : jobs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", String.valueOf(j.getId()));
            m.put("job_name", j.getJobName());
            m.put("source_datasource_id", str(j.getSourceDatasourceId()));
            m.put("source_table", j.getSourceTable());
            m.put("target_datasource_id", str(j.getTargetDatasourceId()));
            m.put("target_table", j.getTargetTable());
            m.put("sync_type", j.getSyncType());
            m.put("increment_field", j.getIncrementField());
            m.put("cursor_field", j.getCursorField());
            m.put("batch_size", j.getBatchSize());
            // 水位：last_offset 是时间戳字符串，last_pk 是数字
            m.put("last_offset", j.getLastOffset());
            m.put("last_pk", j.getLastPk());
            m.put("last_sync_time", j.getLastSyncTime());
            m.put("status", j.getStatus());

            DatasourceConfig s = dsIndex.get(j.getSourceDatasourceId());
            DatasourceConfig t = dsIndex.get(j.getTargetDatasourceId());
            m.put("sourceName", s == null ? null : s.getDsName());
            m.put("sourceType", s == null ? null : s.getDsType());
            m.put("targetName", t == null ? null : t.getDsName());
            m.put("targetType", t == null ? null : t.getDsType());

            data.add(m);
        }
        return R.data(data);
    }

    /** 保存请求体（字段名与前端一致） */
    public record SaveRequest(
            Long id,
            String job_name,
            Long source_datasource_id,
            String source_table,
            Long target_datasource_id,
            String target_table,
            Integer batch_size,
            Integer status,
            String sync_type,
            String increment_field) {
    }

    @PostMapping("/save")
    public Map<String, Object> save(@RequestBody SaveRequest b) {
        if (b.job_name() == null || b.job_name().trim().isEmpty()
                || b.source_datasource_id() == null || b.target_datasource_id() == null) {
            return R.fail(400, "任务名与源/目标数据源不能为空");
        }

        // 注意：数据库列名是 sync_type（不是 sync_mode）
        String syncType = "INCREMENT".equalsIgnoreCase(
                b.sync_type() == null ? "FULL" : b.sync_type().trim()) ? "INCREMENT" : "FULL";
        String incField = "INCREMENT".equals(syncType)
                ? (b.increment_field() == null ? null : b.increment_field().trim()) : null;

        if ("INCREMENT".equals(syncType) && (incField == null || incField.isEmpty())) {
            return R.fail(400, "增量模式必须填写「增量字段」，如 update_time");
        }

        SyncJob e = new SyncJob();
        e.setId(b.id());
        e.setJobName(b.job_name().trim());
        e.setSourceDatasourceId(b.source_datasource_id());
        e.setSourceTable(emptyTo(b.source_table(), "t_order"));
        e.setTargetDatasourceId(b.target_datasource_id());
        e.setTargetTable(emptyTo(b.target_table(), "t_order"));
        e.setSyncType(syncType);
        e.setIncrementField(incField);
        e.setBatchSize(b.batch_size() == null || b.batch_size() <= 0 ? 1000 : b.batch_size());
        e.setStatus(b.status() == null ? 1 : b.status());

        if (b.id() == null) {
            // 新建时给 cursor_field 一个默认值（复合游标的第二分量）
            e.setCursorField("id");
            jobMapper.insert(e);
            return R.ok("新增成功");
        }

        // 修改时不动 cursor_field 与水位字段：
        // 前者是同步策略，后者是进度状态，都不该被"改任务名"这种操作清掉。
        jobMapper.updateById(e);
        return R.ok("修改成功");
    }

    @GetMapping("/delete")
    public Map<String, Object> delete(@RequestParam("id") Long id) {
        jobMapper.deleteById(id);
        return R.ok("删除成功");
    }

    /**
     * 重置水位：让增量任务下次从表头重新开始。
     *
     * <p>三个字段都要清：{@code last_offset} / {@code last_pk} / {@code last_sync_time}。
     * 只清前两个的话，界面上会残留一个"上次同步时间"，让人以为还有进度。</p>
     */
    @GetMapping("/resetwatermark")
    public Map<String, Object> resetWatermark(@RequestParam("id") Long id) {
        SyncJob job = jobMapper.selectById(id);
        if (job == null) {
            return R.fail(404, "任务不存在");
        }

        SyncJob patch = new SyncJob();
        patch.setId(id);
        // 用 UpdateWrapper 才能把字段显式置为 NULL —— updateById 会忽略 null 字段
        jobMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SyncJob>()
                .eq(SyncJob::getId, id)
                .set(SyncJob::getLastOffset, null)
                .set(SyncJob::getLastPk, 0L)
                .set(SyncJob::getLastSyncTime, null));

        log.info("已重置任务水位: id={}, name={}", id, job.getJobName());
        return R.ok("已重置「" + job.getJobName() + "」的水位");
    }

    private static String str(Long v) {
        return v == null ? null : String.valueOf(v);
    }

    private static String emptyTo(String v, String def) {
        return (v == null || v.trim().isEmpty()) ? def : v.trim();
    }
}
