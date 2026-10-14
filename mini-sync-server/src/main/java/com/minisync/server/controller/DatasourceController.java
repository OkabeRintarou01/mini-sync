package com.minisync.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.minisync.server.common.R;
import com.minisync.server.entity.DatasourceConfig;
import com.minisync.server.mapper.DatasourceConfigMapper;
import com.minisync.server.service.DatasourceProbeService;
import com.minisync.server.service.DriverRegistryService;
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

/**
 * 数据源管理接口。
 *
 * <p>迁移自 Node 版的 4 个接口，<b>请求参数与返回结构与原来完全一致</b>，
 * 前端无需改动：</p>
 * <ul>
 *   <li>{@code GET  /api/datasource/list}   列表（含实时行数探测）</li>
 *   <li>{@code POST /api/datasource/save}   新增/修改</li>
 *   <li>{@code GET  /api/datasource/delete} 删除</li>
 *   <li>{@code GET  /api/datasource/test}   测试连接</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/datasource")
public class DatasourceController {

    private final DatasourceConfigMapper dsMapper;
    private final DatasourceProbeService probeService;
    private final DriverRegistryService driverRegistryService;

    public DatasourceController(DatasourceConfigMapper dsMapper,
                                DatasourceProbeService probeService,
                                DriverRegistryService driverRegistryService) {
        this.dsMapper = dsMapper;
        this.probeService = probeService;
        this.driverRegistryService = driverRegistryService;
    }

    /**
     * 数据源列表。
     *
     * <p>会对每个数据源做一次实时探测（查 t_order 行数），用来在界面上显示
     * "在线 / 离线"和记录数。</p>
     *
     * <p><b>三种状态必须分开，不能一律显示 0：</b></p>
     * <ul>
     *   <li>{@code rowCount} 有值 → 在线且查到了</li>
     *   <li>{@code rowCount = null} → 探测失败，前端显示「—」，原因在 {@code rowCountError}</li>
     * </ul>
     *
     * <p>另外<b>绝不返回 password 字段</b>：数据源口令不应该出现在浏览器里。</p>
     */
    @GetMapping("/list")
    public Map<String, Object> list() {
        List<DatasourceConfig> all = dsMapper.selectList(
                new LambdaQueryWrapper<DatasourceConfig>().orderByAsc(DatasourceConfig::getId));

        List<Map<String, Object>> data = new ArrayList<>(all.size());
        for (DatasourceConfig ds : all) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", String.valueOf(ds.getId()));
            m.put("ds_name", ds.getDsName());
            m.put("ds_type", ds.getDsType());
            m.put("host", ds.getHost());
            m.put("port", ds.getPort());
            m.put("database_name", ds.getDatabaseName());
            m.put("username", ds.getUsername());
            // 刻意不放 password
            m.put("extra_params", ds.getExtraParams());
            m.put("remark", ds.getRemark());
            m.put("create_time", ds.getCreateTime());

            // 实时探测
            try {
                long n = probeService.countRows(ds, "t_order");
                m.put("rowCount", n);
                m.put("rowCountError", null);
                m.put("online", true);
            } catch (Exception e) {
                String err = String.valueOf(e.getMessage());
                m.put("rowCount", null);
                m.put("rowCountError", err.length() > 200 ? err.substring(0, 200) : err);
                m.put("online", false);
                log.warn("[datasource] 探测失败 id={} name={} : {}",
                        ds.getId(), ds.getDsName(), err);
            }
            data.add(m);
        }

        return R.data(data);
    }

    /** 保存请求体（字段名与前端表单一致，用下划线风格） */
    public record SaveRequest(
            Long id,
            String ds_name,
            String ds_type,
            String host,
            Integer port,
            String database_name,
            String username,
            String password,
            String remark) {
    }

    @PostMapping("/save")
    public Map<String, Object> save(@RequestBody SaveRequest b) {
        if (b.ds_name() == null || b.ds_name().trim().isEmpty()
                || b.database_name() == null || b.database_name().trim().isEmpty()) {
            return R.fail(400, "名称与数据库名不能为空");
        }

        String type = (b.ds_type() == null || b.ds_type().trim().isEmpty())
                ? "MYSQL" : b.ds_type().trim().toUpperCase();
        boolean isFileType = "SQLITE".equals(type);

        DatasourceConfig entity = new DatasourceConfig();
        entity.setId(b.id());
        entity.setDsName(b.ds_name().trim());
        entity.setDsType(type);
        // 文件型数据源（SQLite）没有 host/port/账号概念，统一置空
        entity.setHost(isFileType ? "" : (b.host() == null ? "127.0.0.1" : b.host().trim()));
        entity.setPort(isFileType ? 0 : (b.port() == null ? defaultPort(type) : b.port()));
        entity.setDatabaseName(b.database_name().trim());
        entity.setUsername(isFileType ? "" : (b.username() == null ? "" : b.username().trim()));
        entity.setRemark(b.remark());

        if (b.id() == null) {
            // 新建
            entity.setPassword(isFileType ? "" : (b.password() == null ? "" : b.password()));
            dsMapper.insert(entity);
            return R.ok("新增成功");
        }

        // 修改：密码留空表示沿用原值（前端不回填密码，所以空值不能当成"清空"）
        DatasourceConfig old = dsMapper.selectById(b.id());
        if (old == null) {
            return R.fail(404, "数据源不存在");
        }
        entity.setPassword(isFileType ? ""
                : (b.password() == null || b.password().isEmpty()
                        ? old.getPassword() : b.password()));
        dsMapper.updateById(entity);
        return R.ok("修改成功");
    }

    @GetMapping("/delete")
    public Map<String, Object> delete(@RequestParam("id") Long id) {
        dsMapper.deleteById(id);
        return R.ok("删除成功");
    }

    @GetMapping("/test")
    public Map<String, Object> test(@RequestParam("id") Long id) {
        DatasourceConfig ds = dsMapper.selectById(id);
        if (ds == null) {
            return R.fail(404, "数据源不存在");
        }
        try {
            long n = probeService.countRows(ds, "t_order");
            return R.ok("连接成功，t_order 现有 " + String.format("%,d", n) + " 行");
        } catch (Exception e) {
            return R.fail(500, "连接失败：" + e.getMessage());
        }
    }

    /** 驱动类型列表（插件化的直接体现） */
    @GetMapping("/drivertypes")
    public Map<String, Object> driverTypes() {
        return R.data(driverRegistryService.listTypes());
    }

    /**
     * 兼容路径：{@code /api/datasource/types} 与 {@code /api/drivertype/list} 等价。
     *
     * <p>前端用的是后者（沿用 Node 版约定）。这里保留一个别名，
     * 方便直接用 curl 调试时少记一个路径。</p>
     */
    @GetMapping("/types")
    public Map<String, Object> types() {
        return R.data(driverRegistryService.listTypes());
    }

    private int defaultPort(String type) {
        return "POSTGRESQL".equals(type) ? 5432 : 3306;
    }
}
