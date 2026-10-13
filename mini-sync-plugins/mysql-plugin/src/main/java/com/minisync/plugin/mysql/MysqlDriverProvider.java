package com.minisync.plugin.mysql;

import com.minisync.spi.DataSourceDriver;
import com.minisync.spi.DataSourceDriverProvider;

/**
 * MySQL 驱动插件的 SPI provider。
 *
 * <p>第三方开发新数据源时，照这个类抄一份、改 {@link #type()} 和
 * {@link #create(String)} 就能得到自己的插件。</p>
 */
public class MysqlDriverProvider implements DataSourceDriverProvider {

    @Override
    public String type() {
        return "MYSQL";
    }

    /**
     * <b>每次必须返回新实例</b>：driver 持有 Connection，
     * 源库与目标库同为 MySQL 时两端需要各自的驱动与连接。
     */
    @Override
    public DataSourceDriver create(String type) {
        return new MysqlDataSourceDriver();
    }

    @Override
    public String displayName() {
        return "MySQL";
    }

    /**
     * MySQL JDBC 驱动已 shade 进本插件 jar，运行时不缺依赖。
     * 返回 {@code false} 表示：连接失败就是连接失败，不要去提示用户补 jar。
     */
    @Override
    public boolean requiresExternalDependency() {
        return false;
    }
}
