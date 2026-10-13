package com.minisync.spi;

/**
 * 数据源连接配置 —— <b>插件契约的一部分</b>。
 *
 * <p>刻意保持扁平：不同数据源的公共字段（host/port/库名/账号密码）放在这里，
 * 少量差异用可选字段承载，避免为每个数据源都搞一套配置类导致接口签名不一致。</p>
 *
 * <p><b>同一个字段在不同数据源里含义可能不同</b> —— 这一点必须清楚：</p>
 * <ul>
 *   <li>{@code databaseName}：MySQL/PG 是<b>逻辑库名</b>，SQLite 是<b>.db 文件路径</b></li>
 *   <li>{@code port}：MySQL 3306、PG 5432，SQLite 忽略</li>
 *   <li>{@code extraParams}：会被拼到 JDBC URL 上，语法由各驱动的 URL 格式决定</li>
 * </ul>
 *
 * <p>这种"字段复用、语义由驱动解释"的设计，是为了让工厂签名与接口保持稳定：
 * 新增数据源时不必新增配置类、不必改接口。</p>
 */
public class DataSourceConfig {

    private String host;

    private Integer port;

    /** MySQL/PG 为库名，SQLite 为数据库文件路径。语义由驱动解释。 */
    private String databaseName;

    /** JDBC 驱动类全限定名；为空时由驱动提供默认值。 */
    private String driverClass;

    private String username;

    private String password;

    /**
     * 额外连接参数，会被拼接到 JDBC URL 上。
     *
     * <p>例如 MySQL 的 {@code rewriteBatchedStatements=true}、
     * PG 的 {@code reWriteBatchedInserts=true}。</p>
     */
    private String extraParams;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public String getDriverClass() {
        return driverClass;
    }

    public void setDriverClass(String driverClass) {
        this.driverClass = driverClass;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getExtraParams() {
        return extraParams;
    }

    public void setExtraParams(String extraParams) {
        this.extraParams = extraParams;
    }
}
