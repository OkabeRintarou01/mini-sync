package com.minisync.plugin.postgresql;

import com.minisync.spi.DataSourceConfig;
import com.minisync.spi.DataSourceDriver;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL 数据源驱动（插件版本）。
 *
 * <p>与 MySQL 驱动的<b>三处关键差异</b>（面试要能讲）：</p>
 * <ol>
 *   <li><b>Upsert 语法</b>：PG 用 {@code ON CONFLICT (id) DO UPDATE SET col=EXCLUDED.col}，
 *       MySQL 用 {@code ON DUPLICATE KEY UPDATE col=VALUES(col)}</li>
 *   <li><b>冲突列必须显式指定</b>：PG 不能像 MySQL 那样省略主键，
 *       不知道表结构就没有通用写法，这里约定以 {@code id} 为冲突列</li>
 *   <li><b>批量重写参数</b>：PG 需要 {@code reWriteBatchedInserts=true}，
 *       MySQL 对应 {@code rewriteBatchedStatements=true}</li>
 * </ol>
 */
@Slf4j
public class PostgresqlDataSourceDriver implements DataSourceDriver {

    private static final String DEFAULT_DRIVER_CLASS = "org.postgresql.Driver";

    /**
     * 默认连接参数。
     *
     * <p>{@code reWriteBatchedInserts=true} 的作用与 MySQL 的
     * {@code rewriteBatchedStatements} 等价：把多条 INSERT 重写为一条多值 INSERT。</p>
     */
    private static final String DEFAULT_PARAMS =
            "reWriteBatchedInserts=true";

    /**
     * PG 的 ON CONFLICT 必须显式给出冲突列。
     * 写死 id 是因为本项目的目标表统一以 id 为主键；
     * 若换主键，应通过 extraParams 扩展而非改这里。
     */
    private static final String CONFLICT_COLUMN = "id";

    private Connection connection;

    @Override
    public String getType() {
        return "POSTGRESQL";
    }

    @Override
    public void connect(DataSourceConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("数据源配置不能为空");
        }
        if (config.getHost() == null || config.getPort() == null || config.getDatabaseName() == null) {
            throw new IllegalArgumentException("PostgreSQL 配置缺少 host / port / databaseName");
        }

        String params = (config.getExtraParams() == null || config.getExtraParams().isEmpty())
                ? DEFAULT_PARAMS
                : DEFAULT_PARAMS + "&" + config.getExtraParams();

        String url = String.format("jdbc:postgresql://%s:%d/%s?%s",
                config.getHost(), config.getPort(), config.getDatabaseName(), params);

        String driverClass = (config.getDriverClass() == null || config.getDriverClass().isEmpty())
                ? DEFAULT_DRIVER_CLASS
                : config.getDriverClass();

        try {
            Class.forName(driverClass);
            this.connection = DriverManager.getConnection(url, config.getUsername(), config.getPassword());
            log.info("PostgreSQL 连接成功: {}", url);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("未找到 PostgreSQL 驱动类: " + driverClass, e);
        } catch (SQLException e) {
            throw new IllegalStateException("PostgreSQL 连接失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<Map<String, Object>> executeQuery(String sql, Object... params) {
        ensureConnected();
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            if (params != null) {
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 1, params[i]);
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int columnCount = meta.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<String, Object>();
                    for (int i = 1; i <= columnCount; i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    result.add(row);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询失败: " + e.getMessage() + ", sql=" + sql, e);
        }
        return result;
    }

    /**
     * 批量幂等写入，用 PG 的 {@code ON CONFLICT ... DO UPDATE} 实现 upsert。
     *
     * <p>{@code EXCLUDED} 是 PG 的伪表，指"本次冲突的那一行"，
     * 对应 MySQL 的 {@code VALUES(col)} 写法。</p>
     */
    @Override
    public int batchWrite(String tableName, List<Map<String, Object>> data) {
        ensureConnected();
        if (data == null || data.isEmpty()) {
            return 0;
        }

        Collection<String> columns = data.get(0).keySet();
        StringBuilder columnPart = new StringBuilder();
        StringBuilder placeholderPart = new StringBuilder();
        StringBuilder updatePart = new StringBuilder();

        int index = 0;
        for (String column : columns) {
            if (index > 0) {
                columnPart.append(", ");
                placeholderPart.append(", ");
                updatePart.append(", ");
            }
            columnPart.append(column);
            placeholderPart.append("?");
            updatePart.append(column).append("=EXCLUDED.").append(column);
            index++;
        }

        String sql = String.format(
                "INSERT INTO %s (%s) VALUES (%s) ON CONFLICT (%s) DO UPDATE SET %s",
                tableName, columnPart, placeholderPart, CONFLICT_COLUMN, updatePart);

        boolean originalAutoCommit = true;
        try {
            originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);

            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                for (Map<String, Object> row : data) {
                    int paramIndex = 1;
                    for (String column : columns) {
                        ps.setObject(paramIndex++, row.get(column));
                    }
                    ps.addBatch();
                }
                ps.executeBatch();
            }

            connection.commit();
            log.debug("批量写入完成(PG), table={}, rows={}", tableName, data.size());
            return data.size();

        } catch (SQLException e) {
            rollbackQuietly();
            throw new IllegalStateException("批量写入失败(PG), table=" + tableName + ": " + e.getMessage(), e);
        } finally {
            restoreAutoCommit(originalAutoCommit);
        }
    }

    @Override
    public String queryMaxValue(String tableName, String field) {
        ensureConnected();
        if (field == null || field.trim().isEmpty()) {
            return null;
        }

        String sql = String.format("SELECT MAX(%s) FROM %s", field, tableName);
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) {
                Object value = rs.getObject(1);
                return value == null ? null : String.valueOf(value);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询最大值失败(PG), sql=" + sql, e);
        }
        return null;
    }

    @Override
    public boolean testConnection() {
        try {
            return connection != null && !connection.isClosed() && connection.isValid(3);
        } catch (SQLException e) {
            log.warn("连接有效性检查失败: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                if (!connection.isClosed()) {
                    connection.close();
                }
            } catch (SQLException e) {
                log.warn("关闭连接失败: {}", e.getMessage());
            } finally {
                connection = null;
            }
        }
    }

    private void ensureConnected() {
        if (connection == null) {
            throw new IllegalStateException("数据源尚未连接，请先调用 connect()");
        }
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ex) {
            log.error("事务回滚失败", ex);
        }
    }

    private void restoreAutoCommit(boolean autoCommit) {
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException e) {
            log.warn("恢复 autoCommit 失败: {}", e.getMessage());
        }
    }
}
