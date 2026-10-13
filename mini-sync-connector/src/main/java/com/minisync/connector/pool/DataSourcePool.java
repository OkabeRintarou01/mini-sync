package com.minisync.connector.pool;

import com.minisync.spi.DataSourceConfig;
import com.minisync.spi.DataSourceDriver;
import com.minisync.connector.factory.DataSourceDriverFactory;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据源连接缓存。
 *
 * <p><b>缓存维度是「数据源 ID」，不是「数据源类型」</b> —— 这是与工厂类的关键区别。
 * 同一个类型（如 MySQL）可能对应多个不同的数据源（源库、目标库），
 * 它们必须各自持有独立连接，因此按 ID 隔离。</p>
 */
@Slf4j
public class DataSourcePool {

    private final ConcurrentHashMap<Long, DataSourceDriver> pool =
            new ConcurrentHashMap<Long, DataSourceDriver>();

    /**
     * 获取（或创建）指定数据源的驱动实例。
     *
     * <p>缓存命中且连接有效时直接复用；连接已失效则关闭旧实例并重建。</p>
     */
    public DataSourceDriver getDriver(Long datasourceId, String type, DataSourceConfig config) {
        if (datasourceId == null) {
            throw new IllegalArgumentException("数据源 ID 不能为空");
        }

        DataSourceDriver cached = pool.get(datasourceId);
        if (cached != null && cached.testConnection()) {
            return cached;
        }

        // 命中但已失效，先清理掉，避免连接泄漏
        if (cached != null) {
            log.info("数据源连接已失效，重建中, datasourceId={}", datasourceId);
            removeDriver(datasourceId);
        }

        // 注意：工厂每次返回新实例，这里不会发生跨数据源的实例共享
        DataSourceDriver driver = DataSourceDriverFactory.create(type);
        driver.connect(config);
        pool.put(datasourceId, driver);
        log.info("数据源连接已建立并缓存, datasourceId={}, type={}", datasourceId, type);
        return driver;
    }

    /**
     * 移除并关闭指定数据源的连接。
     */
    public void removeDriver(Long datasourceId) {
        DataSourceDriver driver = pool.remove(datasourceId);
        if (driver != null) {
            driver.close();
        }
    }

    /**
     * 关闭全部连接，用于应用停机时释放资源。
     */
    public void closeAll() {
        for (Long id : pool.keySet()) {
            removeDriver(id);
        }
    }
}
