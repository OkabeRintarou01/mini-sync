package com.minisync.plugin.sqlite;

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
 * SQLite 数据源驱动 —— 一个<b>主工程完全不知道它存在</b>的第三方插件。
 *
 * <p>它的存在是为了验证插件化机制的有效性：
 * 插件 jar 放进 {@code plugins/} 目录后，控制台的数据源类型下拉里就会多出 SQLite，
 * 而 mini-sync 主工程的<b>任何一行代码都没有改动</b>。</p>
 *
 * <p><b>与 MySQL / PG 驱动的一处关键差异 —— upsert 语义：</b></p>
 *
 * <p>SQLite 的 upsert 写作 {@code INSERT ... ON CONFLICT DO UPDATE}，
 * 但有一个致命限制：<b>冲突目标列可以被省略（做"任意唯一键冲突都更新"），
 * 而 DO UPDATE 不能省略</b>。这意味着 SQLite 无法表达
 * "冲突时仅当某列更优才更新"这类条件更新 —— 因为它的冲突检测是
 * <b>语句级</b>的，没有 MySQL 的 {@code VALUES()} 行级上下文可用于比较。</p>
 *
 * <p>换句话说：MySQL / PG 的 upsert 是"同键整行覆盖"，
 * SQLite 的也是"同键整行覆盖"，但 SQLite 不支持在主键之外的自定义唯一键组合冲突处理，
 * 也没有 {@code VALUES()} 伪行可以做"新旧值比较后决定是否更新"。</p>
 *
 * <p>这类"看似相同、实则语义有差异"的细节，正是插件化必须<b>逐个驱动验证</b>
 * 而不能假定 JDBC 通用的原因。</p>
 */
@Slf4j
public class SqliteDataSourceDriver implements DataSourceDriver {

    private static final String DEFAULT_DRIVER_CLASS = "org.sqlite.JDBC";

    private Connection connection;

    @Override
    public String getType() {
        return "SQLITE";
    }

    /**
     * SQLite 的 databaseName 是<strong>文件路径</strong>，不是库名。
     *
     * <p>这是插件化要注意的又一个差异：MySQL 的 databaseName 是逻辑库名，
     * SQLite 的 databaseName 是磁盘上的 .db 文件路径。
     * 同一个 {@code DataSourceConfig} 字段，在不同驱动里含义完全不同 ——
     * 这也是{@code DataSourceConfig} 保持扁平、不加类型特定字段的原因：
     * 语义由驱动自己解释。</p>
     */
    @Override
    public void connect(DataSourceConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("数据源配置不能为空");
        }
        if (config.getDatabaseName() == null || config.getDatabaseName().isEmpty()) {
            throw new IllegalArgumentException("SQLite 配置必须指定数据库文件路径（databaseName）");
        }

        String url = "jdbc:sqlite:" + config.getDatabaseName();

        try {
            Class.forName(DEFAULT_DRIVER_CLASS);
            this.connection = DriverManager.getConnection(url, config.getUsername(), config.getPassword());
            log.info("SQLite 连接成功: {}", url);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("未找到 SQLite 驱动类: " + DEFAULT_DRIVER_CLASS, e);
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 连接失败: " + e.getMessage(), e);
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
     * SQLite 的 upsert：{@code INSERT ... ON CONFLICT DO UPDATE SET}。
     *
     * <p>SQLite 的 ON CONFLICT <b>可以省略冲突目标</b>（等价于"命中任意唯一约束都算冲突"），
     * 但 {@code DO UPDATE} 不可省略。这里显式写 {@code id}，与 PG 保持一致，便于对照。</p>
     *
     * <p><b>与 MySQL/PG 的一个实现差异</b>：SQLite JDBC 驱动对
     * {@code addBatch/executeBatch} 的支持历史上不完整（部分版本会把批量静默退化成逐条，
     * 甚至抛 "batch must be executed with executeBatch"），
     * 所以这里退回逐条 execute + 单事务包裹。牺牲一点吞吐，换行为可预期。
     * 这也是"不能假定 JDBC 行为一致"的实例。</p>
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
            updatePart.append(column).append("=excluded.").append(column);
            index++;
        }

        String sql = String.format(
                "INSERT INTO %s (%s) VALUES (%s) ON CONFLICT(id) DO UPDATE SET %s",
                tableName, columnPart, placeholderPart, updatePart);

        boolean originalAutoCommit = true;
        try {
            originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);

            int written = 0;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                for (Map<String, Object> row : data) {
                    int paramIndex = 1;
                    for (String column : columns) {
                        ps.setObject(paramIndex++, row.get(column));
                    }
                    written += ps.executeUpdate();
                }
            }

            connection.commit();
            log.debug("批量写入完成(SQLite), table={}, rows={}", tableName, written);
            return written;

        } catch (SQLException e) {
            rollbackQuietly();
            throw new IllegalStateException("批量写入失败(SQLite), table=" + tableName + ": " + e.getMessage(), e);
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
