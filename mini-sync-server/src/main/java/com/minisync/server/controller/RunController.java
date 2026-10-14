package com.minisync.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.minisync.server.common.R;
import com.minisync.server.entity.DatasourceConfig;
import com.minisync.server.entity.SyncJob;
import com.minisync.server.entity.SyncLog;
import com.minisync.server.mapper.DatasourceConfigMapper;
import com.minisync.server.mapper.SyncJobMapper;
import com.minisync.server.mapper.SyncLogMapper;
import com.minisync.server.service.DatasourceProbeService;
import com.minisync.server.service.SyncRunStateService;
import com.minisync.server.service.SyncTaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 同步执行、历史、数据预览相关接口。
 *
 * <p><b>与 Node 版最大的行为差异：{@code /api/run} 改为异步。</b></p>
 *
 * <p>Node 版是同步阻塞：点一次要等几十秒，HTTP 一直挂着。
 * 现在触发后<b>立即返回</b> {@code runId}，前端拿着它轮询
 * {@code /api/run/status} 拿进度与日志。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class RunController {

    private final SyncJobMapper jobMapper;
    private final DatasourceConfigMapper dsMapper;
    private final SyncLogMapper logMapper;
    private final DatasourceProbeService probeService;
    private final SyncRunStateService runStateService;
    private final SyncTaskService syncTaskService;

    public RunController(SyncJobMapper jobMapper,
                         DatasourceConfigMapper dsMapper,
                         SyncLogMapper logMapper,
                         DatasourceProbeService probeService,
                         SyncRunStateService runStateService,
                         SyncTaskService syncTaskService) {
        this.jobMapper = jobMapper;
        this.dsMapper = dsMapper;
        this.logMapper = logMapper;
        this.probeService = probeService;
        this.runStateService = runStateService;
        this.syncTaskService = syncTaskService;
    }

    // ==================================================================
    // 触发同步（异步）
    // ==================================================================

    /**
     * 触发一次同步。
     *
     * <p>立刻返回 {@code runId}，同步在后台线程池执行。
     * 用 {@code /api/run/status?runId=...} 查询进度。</p>
     */
    @GetMapping("/run")
    public Map<String, Object> run(@RequestParam("jobId") Long jobId) {
        SyncJob job = jobMapper.selectById(jobId);
        if (job == null) {
            return R.fail(404, "任务不存在");
        }

        String syncType = (job.getSyncType() == null || job.getSyncType().trim().isEmpty())
                ? "FULL" : job.getSyncType().trim().toUpperCase();
        if ("INCREMENT".equals(syncType)
                && (job.getIncrementField() == null || job.getIncrementField().trim().isEmpty())) {
            return R.fail(400, "增量任务未配置「增量字段」，请先修改任务");
        }

        // 防止同一任务被并发触发：两个请求同时点，只有一个能拿到锁。
        // 否则同一份数据会被搬两遍，浪费资源还可能造成目标端行数翻倍。
        com.minisync.server.service.SyncRunStateService.RunState st =
                runStateService.create(jobId, job.getJobName(), syncType);
        if (!runStateService.tryLockJob(jobId, st.getRunId())) {
            return R.fail(409, "该任务正在执行中，请等待完成或查看进度");
        }

        // 提交到后台线程池。注意必须通过注入的 bean 调用，
        // @Async 才会生效（同类内部调用会绕过 Spring 代理）。
        syncTaskService.executeAsync(jobId, st.getRunId());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", st.getRunId());
        data.put("jobId", String.valueOf(jobId));
        data.put("status", SyncRunStateService.RUNNING);
        data.put("message", "任务已提交，请通过 /api/run/status?runId= 查询进度");
        return R.of(200, null, data);
    }

    /** 查询运行进度与日志 */
    @GetMapping("/run/status")
    public Map<String, Object> runStatus(@RequestParam("runId") String runId) {
        SyncRunStateService.RunState st = runStateService.get(runId);
        if (st == null) {
            return R.fail(404, "运行记录不存在或已过期");
        }
        return R.data(st.toMap());
    }

    /** 查询某任务最近一次运行（前端刷新页面后恢复显示用） */
    @GetMapping("/run/latest")
    public Map<String, Object> runLatest(@RequestParam("jobId") Long jobId) {
        SyncRunStateService.RunState st = runStateService.latestByJob(jobId);
        if (st == null) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("status", "NOT_FOUND");
            return R.data(empty);
        }
        return R.data(st.toMap());
    }

    // ==================================================================
    // 执行历史
    // ==================================================================

    @GetMapping("/logs")
    public Map<String, Object> logs(
            @RequestParam(value = "limit", required = false, defaultValue = "15") Integer limit) {
        List<SyncLog> list = logMapper.selectList(new LambdaQueryWrapper<SyncLog>()
                .orderByDesc(SyncLog::getId)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 200)));

        List<Map<String, Object>> data = new ArrayList<>(list.size());
        for (SyncLog l : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", String.valueOf(l.getId()));
            m.put("job_id", String.valueOf(l.getJobId()));
            m.put("job_name", l.getJobName());
            m.put("sync_mode", l.getSyncMode());
            m.put("total_count", l.getTotalCount());
            m.put("cost_ms", l.getCostMs());
            m.put("success", l.getSuccess());
            m.put("error_msg", l.getErrorMsg());
            m.put("create_time", l.getCreateTime());
            data.add(m);
        }
        return R.data(data);
    }

    // ==================================================================
    // 数据预览 / 清空表
    // ==================================================================

    /** 预览数据源的前若干行 */
    @GetMapping("/preview")
    public Map<String, Object> preview(@RequestParam("id") Long id,
                                       @RequestParam(value = "limit", required = false,
                                               defaultValue = "8") Integer limit) {
        DatasourceConfig ds = dsMapper.selectById(id);
        if (ds == null) {
            return R.fail(404, "数据源不存在");
        }
        try {
            String sql = "SELECT id, order_no, user_id, amount, status FROM t_order ORDER BY id";
            List<Map<String, Object>> rows = probeService.query(ds, sql);
            if (rows.size() > limit) {
                // 在内存里截断：不同方言的 LIMIT 语法不一致，
                // 统一在应用层截更稳（预取 8 行量很小，不影响性能）
                rows = new ArrayList<>(rows.subList(0, limit));
            }
            return R.data(rows);
        } catch (Exception e) {
            return R.fail(500, e.getMessage());
        }
    }

    /** 清空某数据源的 t_order 表 */
    @GetMapping("/truncate")
    public Map<String, Object> truncate(@RequestParam("id") Long id) {
        DatasourceConfig ds = dsMapper.selectById(id);
        if (ds == null) {
            return R.fail(404, "数据源不存在");
        }
        // SQLite 没有 TRUNCATE，用 DELETE 清空（保留表结构）
        String sql = "SQLITE".equalsIgnoreCase(ds.getDsType())
                ? "DELETE FROM t_order" : "TRUNCATE TABLE t_order";
        try {
            probeService.query(ds, sql);
            return R.ok("已清空 " + ds.getDsName() + " 的 t_order");
        } catch (Exception e) {
            return R.fail(500, "清空失败：" + e.getMessage());
        }
    }
}
