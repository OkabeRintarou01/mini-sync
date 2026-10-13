package com.minisync.plugin.mysql;

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
 * MySQL 数据源驱动（插件版本）。
 *
 * <p>本类是<strong>从 mini-sync-connector 模块整体搬迁过来的</strong>，
 * 逻辑与主工程里的版本完全一致。这次搬迁本身就是一次验证：
 * <ul>
 *   <li>驱动只依赖 {@code mini-sync-spi} 与 {@code DataSourceDriver} 接口</li>
 *   <li>不依赖主工程的工厂、连接池、执行器</li>
 *   <li>MySQL JDBC 驱动被打进 jar 内部，自包含</li>
 * </ul>
 * 也就是说：<b>把 jar 从 plugins 目录删掉，主工程的内置驱动顶上；
 * 把 jar 放回去，插件的这份生效。两种形态可互换。</b></p>
 *
 * <p><b>本类刻意不使用任何 Java 9+ 的 API</b>，保证在 JDK 8 下也可编译，
 * 这样插件使用者不需要跟着主工程升级 JDK。</p>
 */
@Slf4j
public class MysqlDataSourceDriver implements DataSourceDriver {

    private static final String DEFAULT_DRIVER_CLASS = "com.mysql.cj.jdbc.Driver";

    /**
     * 默认连接参数。
     *
     * <p>{@code rewriteBatchedStatements=true} 是批量写入的性能关键：
     * 默认情况下 MySQL 驱动会把 addBatch 逐条发送（俗称"假批量"），
     * 开启后才会重写成一条多值 INSERT，性能差异可达数倍。</p>
     */
    private static final String DEFAULT_PARAMS =
            "useUnicode=true&characterEncoding=utf8"
            + "&useSSL=false&allowPublicKeyRetrieval=true"
            + "&rewriteBatchedStatements=true"
            + "&serverTimezone=Asia/Shanghai";

    private Connection connection;

    @Override
    public String getType() {
        return "MYSQL";
    }

    @Override
    public void connect(DataSourceConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("数据源配置不能为空");
        }
        if (config.getHost() == null || config.getPort() == null || config.getDatabaseName() == null) {
            throw new IllegalArgumentException("MySQL 配置缺少 host / port / databaseName");
        }

        String params = (config.getExtraParams() == null || config.getExtraParams().isEmpty())
                ? DEFAULT_PARAMS
                : DEFAULT_PARAMS + "&" + config.getExtraParams();

        String url = String.format("jdbc:mysql://%s:%d/%s?%s",
                config.getHost(), config.getPort(), config.getDatabaseName(), params);

        String driverClass = (config.getDriverClass() == null || config.getDriverClass().isEmpty())
                ? DEFAULT_DRIVER_CLASS
                : config.getDriverClass();

        try {
            Class.forName(driverClass);
            this.connection = DriverManager.getConnection(url, config.getUsername(), config.getPassword());
            log.info("MySQL 连接成功: {}", url);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("未找到 MySQL 驱动类: " + driverClass, e);
        } catch (SQLException e) {
            throw new IllegalStateException("MySQL 连接失败: " + e.getMessage(), e);
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
                    // 用 LinkedHashMap 保持列顺序，便于调试与字段映射
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
     * 批量幂等写入，使用 {@code INSERT ... ON DUPLICATE KEY UPDATE} 实现 upsert 语义。
     *
     * <p>要求目标表在业务主键上建有唯一索引或主键约束，
     * 否则 ON DUPLICATE KEY 不会生效，会退化成普通 INSERT。</p>
     */
    @Override
    public int batchWrite(String tableName, List<Map<String, Object>> data) {
        ensureConnected();
        if (data == null || data.isEmpty()) {
            return 0;
        }

        // 以第一行的列名作为整批的列集合
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
            // VALUES(col) 引用本次要插入的值，实现"存在则更新"
            updatePart.append(column).append("=VALUES(").append(column).append(")");
            index++;
        }

        String sql = String.format(
                "INSERT INTO %s (%s) VALUES (%s) ON DUPLICATE KEY UPDATE %s",
                tableName, columnPart, placeholderPart, updatePart);

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
            log.debug("批量写入完成, table={}, rows={}", tableName, data.size());
            return data.size();

        } catch (SQLException e) {
            rollbackQuietly();
            throw new IllegalStateException("批量写入失败, table=" + tableName + ": " + e.getMessage(), e);
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
            throw new IllegalStateException("查询最大值失败, sql=" + sql, e);
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
