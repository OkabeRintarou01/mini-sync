package com.minisync.connector.factory;

import com.minisync.plugin.PluginDriverRegistry;
import com.minisync.spi.DataSourceDriver;
import com.minisync.spi.DataSourceDriverProvider;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
/**
 * 数据源驱动工厂（工厂模式中的「工厂」角色）。
 *
 * <p><b>关键设计决策一：每次调用都返回「新实例」，不做任何缓存。</b></p>
 *
 * <p>原因：一个 Driver 实例内部持有唯一一个 {@code Connection}。
 * 如果工厂返回共享单例，那么当「源库」和「目标库」恰好是同一类型（比如都是 MySQL）时，
 * 两端会拿到同一个实例、共用同一个连接 —— 轻则数据写到自己身上，
 * 重则直接抛异常。这是本项目早期版本踩过的坑。</p>
 *
 * <p>连接复用应当由上层 {@code DataSourcePool} 按「数据源 ID」维度缓存，
 * 而不是由工厂按「类型」维度缓存。两者维度不同，职责必须分开。</p>
 *
 * <p><b>关键设计决策二：内置实现只是兜底，插件优先。</b></p>
 *
 * <p>改造前这里是一个 {@code switch(type)}，加一种数据库就得改这个文件、
 * 重新编译整个工程。改造后的取值顺序是：</p>
 *
 * <pre>
 *   create("MYSQL")
 *     │
 *     ├─ ① 插件注册表里有 MYSQL 的 provider？ ──有──→ provider.create("MYSQL")  ← 返回
 *     │
 *     └─ ② 没有 ──→ classpath 上有内置实现？ ──有──→ new MysqlDataSourceDriver()  ← 返回
 *                  └─ 也没有 ──→ 抛"未实现"，并提示是否缺插件 jar
 * </pre>
 *
 * <p>这样一来，MySQL 与 PostgreSQL 的驱动<b>已经可以完全从主工程里搬出去</b>，
 * 变成 plugins 目录下的两个独立 jar；主工程只留「兜底」能力，
 * 保证即使 plugins 目录被清空，程序仍能正常跑。</p>
 */
@Slf4j
public final class DataSourceDriverFactory {

    /** 单例注册表。volatile 保证多线程下看到的是完整初始化后的对象。 */
    private static volatile PluginDriverRegistry pluginRegistry;

    private DataSourceDriverFactory() {
        // 工具类，禁止实例化
    }

    // ==================================================================
    // 插件注册表
    // ==================================================================

    /**
     * 确保插件注册表已加载（懒加载 + volatile 单例）。
     *
     * <p>用懒加载而不是 static 块，是为了：</p>
     * <ul>
     *   <li>命令行入口可以先打印日志再触发加载，输出更可控</li>
     *   <li>单元测试可以用 {@link #installRegistryForTest} 注入一个测试注册表，
     *       不必依赖真实的 plugins 目录</li>
     * </ul>
     *
     * <p><b>加载失败时不应该让同步跑不起来</b>：目录不存在 / 不可读时，
     * 退化成"只有内置驱动"的空注册表，程序照常工作。</p>
     */
    private static PluginDriverRegistry registry() {
        PluginDriverRegistry r = pluginRegistry;
        if (r == null) {
            synchronized (DataSourceDriverFactory.class) {
                r = pluginRegistry;
                if (r == null) {
                    try {
                        r = PluginDriverRegistry.load();
                    } catch (IOException e) {
                        // 目录不可读不该让同步跑不起来，退化成"只有内置驱动"
                        log.warn("插件目录扫描失败，本次仅使用内置驱动: {}", e.getMessage());
                        r = emptyRegistry();
                    }
                    pluginRegistry = r;
                }
            }
        }
        return r;
    }

    /**
     * 显式加载插件（命令行入口应在启动时调用一次，把加载日志打全）。
     *
     * @return 注册表快照，调用方可读取 {@code loadedPlugins()/failures()}
     */
    public static PluginDriverRegistry preloadPlugins() {
        PluginDriverRegistry r;
        try {
            r = PluginDriverRegistry.load();
        } catch (IOException e) {
            log.warn("插件目录扫描失败: {}", e.getMessage());
            r = emptyRegistry();
        }
        pluginRegistry = r;

        if (r.loadedPlugins().isEmpty()) {
            log.info("未发现插件驱动，使用内置驱动（MySQL / PostgreSQL）");
        }
        if (!r.failures().isEmpty()) {
            for (String f : r.failures()) {
                log.warn("插件加载失败（已被忽略）: {}", f);
            }
        }
        return r;
    }

    /** 仅供测试使用：注入假注册表，避免依赖真实文件系统。 */
    static void installRegistryForTest(PluginDriverRegistry r) {
        pluginRegistry = r;
    }

    private static PluginDriverRegistry emptyRegistry() {
        try {
            return PluginDriverRegistry.load(
                    System.getProperty(PluginDriverRegistry.PLUGIN_DIR_PROPERTY,
                            "__minisync_no_such_dir__"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ==================================================================
    // 创建驱动
    // ==================================================================

    /**
     * 按类型创建驱动实例：<b>插件优先，内置兜底</b>。
     *
     * <p>每次返回新对象，调用方负责 connect / close 的生命周期管理。</p>
     *
     * @throws IllegalArgumentException 插件与内置实现都没有该类型时抛出
     */
    public static DataSourceDriver create(String rawType) {
        String type = PluginDriverRegistry.normalizeType(rawType);

        // ---------- ① 优先走插件 ----------
        PluginDriverRegistry reg = registry();
        DataSourceDriverProvider provider = reg.getProvider(type);
        if (provider != null) {
            DataSourceDriver driver = provider.create(type);
            if (driver == null) {
                throw new IllegalStateException(
                        "插件 provider 返回了 null 驱动，provider=" + provider.getClass().getName());
            }
            // 校验 provider 有没有说谎：它声明的 type 和 driver 的 getType 必须一致，
            // 否则 DataSourcePool 按 id 缓存时会张冠李戴
            String declared = driver.getType();
            if (!type.equals(PluginDriverRegistry.normalizeType(declared))) {
                throw new IllegalStateException(String.format(
                        "插件声明的类型(%s)与驱动 getType() 返回的类型(%s)不一致, provider=%s",
                        type, declared, provider.getClass().getName()));
            }
            log.debug("使用插件驱动: type={}, provider={}", type, provider.getClass().getName());
            return driver;
        }

        // ---------- ② 回落内置实现（classpath 上）----------
        DataSourceDriver builtin = createBuiltIn(type);
        if (builtin != null) {
            log.debug("使用内置驱动: type={}", type);
            return builtin;
        }

        // ---------- ③ 都没有 ----------
        throw new IllegalArgumentException(buildUnsupportedMessage(type, reg));
    }

    /**
     * classpath 上的内置驱动。
     *
     * <p>用 {@code ServiceLoader} 而不是 {@code new}，是为了主工程也能享受到 SPI 的好处：
     * 将来把 MySQL 驱动搬去插件 jar 后，只要把这段的显式实例化删掉，
     * 主工程 classpath 上的 META-INF/services 声明仍然能让它被找到。</p>
     *
     * <p>返回 {@code null} 表示没有内置实现（而不是抛异常），
     * 由调用方决定后续是回落还是报错。</p>
     */
    private static DataSourceDriver createBuiltIn(String type) {
        // 用系统/上下文类加载器扫 classpath 上的 SPI 声明
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = DataSourceDriverFactory.class.getClassLoader();
        }
        try {
            for (DataSourceDriverProvider p : ServiceLoader.load(DataSourceDriverProvider.class, cl)) {
                if (type.equals(PluginDriverRegistry.normalizeType(p.type()))) {
                    return p.create(type);
                }
            }
        } catch (ServiceConfigurationError e) {
            log.warn("classpath 上的驱动 SPI 配置有问题: {}", e.getMessage());
        }

        // 走到这里说明：classpath 上没有任何内置 driver provider。
        // 这正是插件化的目标状态 —— MySQL / PostgreSQL 驱动已搬到 plugins 目录，
        // 主工程只保留"类型已知"的常量用于错误提示。
        return null;
    }

    /** 组装"不支持该类型"的错误信息，把排查线索一并给出。 */
    private static String buildUnsupportedMessage(String type,
                                                  PluginDriverRegistry reg) {
        StringBuilder sb = new StringBuilder();
        sb.append("未实现的数据源类型: ").append(type).append("。");

        sb.append("已注册类型: ").append(reg.registeredTypes());
        if (reg.loadedPlugins().isEmpty()) {
            sb.append("\n排查方向：plugins 目录(").append(reg.pluginDir())
              .append(")下未发现驱动 jar，若需新类型请按 SPI 约定开发驱动 jar 放入该目录。");
        } else {
            sb.append("\n排查方向：检查 plugins 目录(").append(reg.pluginDir())
              .append(")下对应 jar 是否包含 META-INF/services/")
              .append(DataSourceDriverProvider.class.getName())
              .append("，且其中声明的 type() 与本类型一致。");
        }
        return sb.toString();
    }

    // ==================================================================
    // 兼容旧签名
    // ==================================================================

    /**
     * 兼容层：早期类型是枚举，调用写作 {@code create(DataSourceType.MYSQL)}。
     * 改造后类型改为字符串，本重载保留兼容，内部直接转字符串。
     *
     * <p>新代码请直接用 {@link #create(String)}。</p>
     */
    public static DataSourceDriver createFromName(String typeName) {
        return create(typeName);
    }
}
