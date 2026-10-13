package com.minisync.spi;

/**
 * 数据源驱动提供者 —— 插件化加载的<b>唯一契约</b>。
 *
 * <p>第三方开发者要接入一种新的数据源，只需要：</p>
 * <ol>
 *   <li>依赖 {@code mini-sync-spi} 这一个 jar（注意：不是整个 mini-sync）</li>
 *   <li>实现 {@link DataSourceDriver}，把具体的 JDBC 读写逻辑写进去</li>
 *   <li>再实现本接口，把「类型标识 → 驱动实例」的对应关系声明出来</li>
 *   <li>在 {@code META-INF/services/com.minisync.spi.DataSourceDriverProvider}
 *       中写上本实现类的全限定名</li>
 *   <li>打成 jar 丢进 {@code plugins/} 目录，重启即生效</li>
 * </ol>
 *
 * <p><b>为什么要有这一层，而不是让插件直接实现 DataSourceDriver？</b></p>
 *
 * <p>因为 {@link DataSourceDriver} 的实例是<b>持有一个 Connection 的有状态对象</b>，
 * 而 SPI 的 {@code ServiceLoader} 机制假定 provider 是<b>可以被反复实例化的无状态工厂</b>。
 * 如果插件直接把 driver 注册进去，就会出现「一个 driver 实例被多个任务共享连接」的问题 ——
 * 这正是本项目早期踩过的坑。</p>
 *
 * <p>所以拆成两层：
 * <ul>
 *   <li>{@code DataSourceDriverProvider}：无状态，声明「我支持哪些类型」，
 *       由 {@code ServiceLoader} 发现，JVM 生命周期内只有一个实例</li>
 *   <li>{@code DataSourceDriver}：有状态，每次同步都需要新实例，
 *       由 provider 通过 {@link #create(String)} 现造</li>
 * </ul>
 * </p>
 *
 * <p><b>实现约束：</b></p>
 * <ul>
 *   <li>实现类必须有一个<b> public 无参构造器</b>（ServiceLoader 的要求）</li>
 *   <li>{@link #create(String)} 必须<b>每次返回新对象</b>，禁止返回缓存/单例</li>
 *   <li>驱动实例的 connect / close 生命周期由 mini-sync 的连接池负责，
 *       插件不要自己持有静态连接</li>
 * </ul>
 */
public interface DataSourceDriverProvider {

    /**
     * 本 provider 支持的数据源类型标识。
     *
     * <p>要求：全大写、无空白。会被 {@code DataSourceType.normalize()} 规范化后
     * 与前端传入的类型比对，所以大小写不敏感。</p>
     *
     * <p>约定 {@code type} 也是前端界面上的展示值，
     * 例如 {@code "MYSQL"}、{@code "POSTGRESQL"}、{@code "SQLITE"}。</p>
     */
    String type();

    /**
     * 创建一个该类型的驱动实例。
     *
     * <p><b>必须每次返回新对象。</b>因为源库和目标库可能是同一种类型，
     * 两端需要各自持有独立的 Connection。</p>
     *
     * @param type 规范化后的类型标识，通常等于 {@link #type()}；
     *             传入是为了让一个 provider 可以支撑多个相近类型
     *             （例如一个 provider 同时支持 MYSQL 与 MARIADB）
     */
    DataSourceDriver create(String type);

    /**
     * 可读的类型名称，用于日志与前端的友好展示。
     *
     * <p>默认实现返回 {@link #type()}，插件一般不覆盖。
     * 例如 {@code "SQLITE"} 可以显示成 {@code "SQLite"}。</p>
     */
    default String displayName() {
        return type();
    }

    /**
     * 声明本驱动是否需要外部依赖。
     *
     * <p>用于启动时给出友好提示：返回 {@code false} 时若 ClassNotFound，
     * 加载器会把"缺少驱动 jar"的信息打到日志里，而不是丢一个难懂的堆栈。</p>
     *
     * <p>默认 {@code true}。绝大多数 JDBC 驱动（mysql、postgresql）都是外部依赖。</p>
     */
    default boolean requiresExternalDependency() {
        return true;
    }
}
