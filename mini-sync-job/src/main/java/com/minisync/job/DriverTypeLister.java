package com.minisync.job;

import com.minisync.connector.factory.DataSourceDriverFactory;
import com.minisync.plugin.LoadedPlugin;
import com.minisync.plugin.PluginDriverRegistry;
import com.minisync.spi.DataSourceDriver;
import com.minisync.spi.DataSourceDriverProvider;

/**
 * 列出当前进程已注册的全部数据源类型。
 *
 * <p>控制台的数据源类型下拉不写死 MySQL/PG，而是调用本类让引擎自报家门：
 * 新增驱动插件放进 {@code plugins/} 目录后，这里自动多一项，前端无需改动。
 * 这是「插件化」在界面上最直观的体现。</p>
 *
 * <p><b>输出协议（与 web/server.js 约定）：</b></p>
 * <pre>
 *   DRIVER_TYPES_BEGIN
 *   DRIVER_TYPE &lt;TYPE&gt; &lt;显示名&gt; &lt;来源jar&gt;
 *   DRIVER_TYPES_END
 * </pre>
 *
 * <p>每行以 {@code DRIVER_TYPE} 开头，方便 Node 侧用正则精确解析，
 * 避免把日志里碰巧出现的类型名误当成驱动。</p>
 *
 * <p>用法：{@code mvn exec:java -Dexec.mainClass=com.minisync.job.DriverTypeLister}</p>
 */
public final class DriverTypeLister {

    private DriverTypeLister() {
    }

    public static void main(String[] args) {
        // 触发一次显式预加载，把「扫描了哪个目录、加载成功/失败哪些 jar」全打出来
        PluginDriverRegistry registry = DataSourceDriverFactory.preloadPlugins();

        System.out.println("DRIVER_TYPES_BEGIN");
        System.out.println("DRIVER_PLUGIN_DIR " + registry.pluginDir());

        for (LoadedPlugin plugin : registry.loadedPlugins()) {
            DataSourceDriverProvider provider = registry.getProvider(plugin.type());
            String display = provider == null ? plugin.type() : provider.displayName();
            System.out.println("DRIVER_TYPE " + plugin.type() + " " + display + " " + plugin.pluginJarName());
        }

        for (String failure : registry.failures()) {
            System.out.println("DRIVER_FAILURE " + failure);
        }

        // 顺带做一次健全性检查：每种类型都能真造出实例，避免"注册成功但一用就炸"
        for (LoadedPlugin plugin : registry.loadedPlugins()) {
            try {
                DataSourceDriver driver = DataSourceDriverFactory.create(plugin.type());
                System.out.println("DRIVER_CREATABLE " + plugin.type()
                        + " " + driver.getClass().getName());
            } catch (Exception e) {
                System.out.println("DRIVER_CREATE_FAILED " + plugin.type() + " " + e.getMessage());
            }
        }

        System.out.println("DRIVER_TYPES_END");
    }
}
