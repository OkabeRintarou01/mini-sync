package com.minisync.spi;

import java.util.List;
import java.util.Map;

/**
 * 数据源驱动统一接口（策略模式中的「策略」角色）—— <b>插件契约的核心</b>。
 *
 * <p>所有数据源（MySQL、PostgreSQL、以及未来任何第三方驱动）都实现本接口，
 * 把各自差异化的读写逻辑封装在实现类里。
 * 上层同步执行器只依赖这个接口，不关心具体是 MySQL 还是 SQLite。</p>
 *
 * <p><b>实现约定：</b></p>
 * <ul>
 *   <li>实现类必须是<b>无静态可变状态</b>的（自己持有的 Connection 除外）：
 *       同一个类可能被实例化多次，源库和目标库各持一个实例、一条连接</li>
 *   <li>实现类必须支持被<b>多次实例化</b>，禁止写成单例</li>
 *   <li>{@link #getType()} 返回的类型必须与
 *       {@link DataSourceDriverProvider#type()} 声明的完全一致（大小写不敏感）</li>
 * </ul>
 *
 * <p><b>为什么这个接口属于 spi 模块而不是 connector 模块？</b></p>
 *
 * <p>因为它就是<b>第三方开发者唯一需要面对的契约</b>。
 * 如果放在 connector 模块，那么每开发一个驱动插件就要把整个 connector
 * （含工厂、连接池、MySQL/PG 实现）拖进依赖树 —— 插件就再也"独立"不起来了。
 * 所以 spi 模块刻意做到<b>零依赖</b>：连 slf4j、lombok 都没有，
 * 只依赖 JDK。</p>
 */
public interface DataSourceDriver {

    /**
     * 数据源类型标识，如 {@code MYSQL}、{@code POSTGRESQL}、{@code SQLITE}。
     *
     * <p>要求全大写。框架会做大小写规范化后再比对，
     * 所以 {@code "mysql"} 也能工作，但请保持全大写以统一日志。</p>
     */
    String getType();

    /**
     * 建立连接。同一个实例重复调用应视为「获取连接」而非重复建立。
     *
     * @param config 连接配置（host/port/库名/账号密码/附加参数）
     * @throws IllegalArgumentException 配置缺失关键字段时抛出
     * @throws IllegalStateException    连接建立失败时抛出，message 需可读
     */
    void connect(DataSourceConfig config);

    /**
     * 执行查询，返回行数据列表。
     *
     * <p>每行是一个 {@code 列名 -> 值} 的 Map。
     * 实现方应保持列顺序（用 {@code LinkedHashMap}），便于上层做字段映射与排查。</p>
     *
     * @param sql    带 ? 占位符的 SQL
     * @param params 占位符参数，按顺序对应
     */
    List<Map<String, Object>> executeQuery(String sql, Object... params);

    /**
     * 批量幂等写入。
     *
     * <p>「幂等」的含义：同一条数据写入多次，结果与写入一次相同。
     * 这是增量同步允许重试、允许重跑的前提，也是"失败重放不产生重复数据"的基础。</p>
     *
     * <p><b>实现要求</b>：必须用目标库的 upsert 语法实现，不能是纯 INSERT —— </p>
     * <ul>
     *   <li>MySQL：{@code INSERT ... ON DUPLICATE KEY UPDATE col=VALUES(col)}</li>
     *   <li>PostgreSQL / SQLite：{@code INSERT ... ON CONFLICT (id) DO UPDATE SET col=EXCLUDED.col}</li>
     * </ul>
     *
     * @param tableName 目标表名
     * @param data      待写入数据，每行是一个 {@code 列名 -> 值} 的映射
     * @return 实际写入的行数（用于上层统计，请返回真实值而不是 data.size()）
     */
    int batchWrite(String tableName, List<Map<String, Object>> data);

    /**
     * 在目标端查询指定字段的最大值，用于增量同步的水位线回写。
     *
     * <p>任务失败时不能简单沿用源端水位，必须回查目标端真实进度，
     * 否则会造成数据永久丢失。</p>
     *
     * @param tableName 目标表名
     * @param field     水位字段名
     * @return 目标端该字段的最大值字符串；表为空或字段为空时返回 {@code null}
     */
    String queryMaxValue(String tableName, String field);

    /**
     * 判断当前连接是否可用。
     *
     * <p>连接池靠这个方法判断缓存是否失效。实现应做<b>轻量</b>检查
     * （如 {@code Connection.isValid(timeout)}），不要执行真实业务 SQL。</p>
     */
    boolean testConnection();

    /**
     * 关闭连接并释放资源。
     *
     * <p>必须保证幂等：重复调用不应抛异常，也不应在已关闭后再次访问连接。</p>
     */
    void close();
}
