package com.minisync.server.service;

import com.minisync.connector.factory.DataSourceDriverFactory;
import com.minisync.server.entity.DatasourceConfig;
import com.minisync.spi.DataSourceDriver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 数据源探查服务 —— 在<b>任意数据源</b>上执行一条 SQL。
 *
 * <p><b>为什么要单独抽这个服务？</b></p>
 *
 * <p>控制台需要做几件"看一眼数据"的事：查连接是否可用、统计行数、预览前几行。
 * 这些操作都要连到<b>用户配置的那个数据源</b>上 —— 可能是 MySQL、PostgreSQL，
 * 也可能是某个第三方插件提供的类型（如 SQLite）。</p>
 *
 * <p>关键是：<b>这些库的 JDBC 驱动不一定在主工程 classpath 上</b>
 * （SQLite / ClickHouse 的驱动只存在于插件 jar 里）。
 * 所以不能自己写 JDBC 连接，必须走 {@link DataSourceDriverFactory}
 * 让插件机制去选驱动 —— 这样新加插件类型时，控制台<b>自动就能用</b>。</p>
 *
 * <p><b>与 Node 版的对比</b>：Node 版对 MySQL 走本地 {@code mysql.exe}、
 * 对 PG 走 {@code docker exec psql}，其它类型只能起 Maven 调 Java（约 17 秒）。
 * 现在在常驻进程里直接走驱动，所有类型都是毫秒级。</p>
 */
@Slf4j
@Service
public class DatasourceProbeService {

    /**
     * 在指定数据源上执行一条 SQL。
     *
     * @param ds    数据源配置
     * @param sql   要执行的 SQL
     * @return 行数据列表，每行是「列名 → 值」的映射
     */
    public List<Map<String, Object>> query(DatasourceConfig ds, String sql) {
        DataSourceDriver driver = null;
        try {
            driver = DataSourceDriverFactory.create(ds.getDsType());
            driver.connect(toDriverConfig(ds));
            return driver.executeQuery(sql);
        } finally {
            // 无论成败都要关闭：这是"即用即连"的场景，不做连接复用。
            // 常驻服务里连一次的成本是毫秒级，不值得为它引入连接池的复杂度。
            if (driver != null) {
                try {
                    driver.close();
                } catch (Exception e) {
                    log.warn("关闭数据源连接失败: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * 测试连接并返回行数。
     *
     * @throws RuntimeException 连接失败时抛出，message 可直接展示给用户
     */
    public long countRows(DatasourceConfig ds, String table) {
        table = (table == null || table.trim().isEmpty()) ? "t_order" : table.trim();
        List<Map<String, Object>> rows = query(ds, "SELECT COUNT(*) AS c FROM " + table);
        if (rows.isEmpty()) {
            return 0L;
        }
        Object v = rows.get(0).get("c");
        return v == null ? 0L : ((Number) v).longValue();
    }

    /** 把管理库里的数据源配置转成驱动需要的连接配置 */
    private com.minisync.spi.DataSourceConfig toDriverConfig(DatasourceConfig ds) {
        com.minisync.spi.DataSourceConfig c = new com.minisync.spi.DataSourceConfig();
        c.setHost(ds.getHost());
        c.setPort(ds.getPort());
        c.setDatabaseName(ds.getDatabaseName());
        c.setUsername(ds.getUsername());
        c.setPassword(ds.getPassword());
        c.setExtraParams(ds.getExtraParams());
        return c;
    }
}
