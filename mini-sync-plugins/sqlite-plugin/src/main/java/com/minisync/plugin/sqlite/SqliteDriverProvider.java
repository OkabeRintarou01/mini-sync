package com.minisync.plugin.sqlite;

import com.minisync.spi.DataSourceDriver;
import com.minisync.spi.DataSourceDriverProvider;

/**
 * SQLite 驱动插件的 SPI provider。
 *
 * <p>这个类的代码量与 {@code MysqlDriverProvider} 一模一样 ——
 * 这就是插件化的意义：<b>新增一种数据源的成本被压缩到"实现 driver + 写一个 provider + 加一行 services 文件"</b>，
 * 主工程的执行器、连接池、工厂、前端都不用动。</p>
 */
public class SqliteDriverProvider implements DataSourceDriverProvider {

    @Override
    public String type() {
        return "SQLITE";
    }

    @Override
    public DataSourceDriver create(String type) {
        return new SqliteDataSourceDriver();
    }

    @Override
    public String displayName() {
        return "SQLite";
    }

    @Override
    public boolean requiresExternalDependency() {
        return false;
    }
}
