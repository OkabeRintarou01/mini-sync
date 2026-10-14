package com.minisync.server.controller;

import com.minisync.server.entity.DatasourceConfig;
import com.minisync.server.entity.SyncJob;
import com.minisync.server.mapper.DatasourceConfigMapper;
import com.minisync.server.mapper.SyncJobMapper;
import com.minisync.server.mapper.SyncLogMapper;
import com.minisync.server.mapper.SysUserMapper;
import com.minisync.server.service.AuthService;
import com.minisync.server.store.RunStateStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查接口。
 *
 * <p>不只报告进程活着，还顺手报告两件事：</p>
 * <ol>
 *   <li><b>管理库连接与 MyBatis-Plus 是否正常</b> —— 如果 Mapper 查不出数据，
 *       说明数据源配置有问题，比"服务起来了但一用就报错"更早暴露</li>
 *   <li><b>共享状态用的是哪套后端</b>（内存 / Redis）—— 排查
 *       "为什么多实例看不到彼此进度"时，第一眼看的就是这里</li>
 * </ol>
 *
 * <p>访问：{@code GET /api/health}</p>
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class HealthController {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SysUserMapper sysUserMapper;
    private final DatasourceConfigMapper datasourceConfigMapper;
    private final SyncJobMapper syncJobMapper;
    private final SyncLogMapper syncLogMapper;
    private final RunStateStore runStateStore;
    private final AuthService authService;

    public HealthController(SysUserMapper sysUserMapper,
                            DatasourceConfigMapper datasourceConfigMapper,
                            SyncJobMapper syncJobMapper,
                            SyncLogMapper syncLogMapper,
                            RunStateStore runStateStore,
                            AuthService authService) {
        this.sysUserMapper = sysUserMapper;
        this.datasourceConfigMapper = datasourceConfigMapper;
        this.syncJobMapper = syncJobMapper;
        this.syncLogMapper = syncLogMapper;
        this.runStateStore = runStateStore;
        this.authService = authService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "UP");
        m.put("service", "mini-sync-server");
        m.put("time", LocalDateTime.now().format(FMT));

        // 共享状态后端：in-memory（单机）还是 redis（多实例）
        Map<String, Object> stores = new LinkedHashMap<>();
        stores.put("runState", runStateStore.backendName());
        stores.put("session", authService.sessionBackend());
        m.put("stores", stores);

        // 验证 MyBatis-Plus 能真正读写管理库
        try {
            Map<String, Object> db = new LinkedHashMap<>();
            db.put("connected", true);
            db.put("users", sysUserMapper.selectCount(null));
            db.put("datasources", datasourceConfigMapper.selectCount(null));
            db.put("jobs", syncJobMapper.selectCount(null));
            db.put("logs", syncLogMapper.selectCount(null));
            m.put("adminDb", db);
        } catch (Exception e) {
            log.error("健康检查：管理库访问失败", e);
            m.put("status", "DEGRADED");
            m.put("adminDb", Map.of("connected", false, "error", String.valueOf(e.getMessage())));
        }
        return m;
    }

    /**
     * 抽查一条数据源记录，确认字段映射正确。
     *
     * <p>专门用来验证下划线列名 → 驼峰属性的映射
     * （{@code ds_name} → {@code dsName}），
     * 避免"能连上但取出来的字段全是 null"这种隐蔽问题。</p>
     */
    @GetMapping("/health/db-sample")
    public Map<String, Object> dbSample() {
        Map<String, Object> m = new LinkedHashMap<>();
        DatasourceConfig ds = datasourceConfigMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DatasourceConfig>()
                        .last("LIMIT 1"));
        m.put("datasource", ds);
        SyncJob job = syncJobMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SyncJob>()
                        .last("LIMIT 1"));
        m.put("job", job);
        return m;
    }
}
