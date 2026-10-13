package com.minisync.plugin.postgresql;

import com.minisync.spi.DataSourceDriver;
import com.minisync.spi.DataSourceDriverProvider;

/** PostgreSQL 驱动插件的 SPI provider。 */
public class PostgresqlDriverProvider implements DataSourceDriverProvider {

    @Override
    public String type() {
        return "POSTGRESQL";
    }

    @Override
    public DataSourceDriver create(String type) {
        return new PostgresqlDataSourceDriver();
    }

    @Override
    public String displayName() {
        return "PostgreSQL";
    }

    @Override
    public boolean requiresExternalDependency() {
        return false;
    }
}
