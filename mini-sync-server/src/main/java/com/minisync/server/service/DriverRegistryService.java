package com.minisync.server.service;

import com.minisync.connector.factory.DataSourceDriverFactory;
import com.minisync.plugin.LoadedPlugin;
import com.minisync.plugin.PluginDriverRegistry;
import com.minisync.spi.DataSourceDriverProvider;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 驱动注册表服务 —— 把插件化机制暴露给 Web 层。
 *
 * <p>控制台的数据源类型下拉<b>不写死</b> MySQL/PG，而是问引擎
 * "你现在认识哪些类型"。新增一个驱动插件放进 {@code plugins/} 目录，
 * 下拉里自动多一项，前端不用改。</p>
 *
 * <p>启动时（{@link PostConstruct}）主动扫描一次插件目录，
 * 把加载结果打到日志里，方便排查"插件为什么没生效"。</p>
 */
@Slf4j
@Service
public class DriverRegistryService {

    private final PluginDriverRegistry registry;

    /**
     * 构造时切换插件目录并加载。
     *
     * <p>先设系统属性再调用 {@code preloadPlugins()}，
     * 让共享的加载器按我们解析出的目录扫描，
     * 而不是依赖进程工作目录。</p>
     */
    public DriverRegistryService() {
        String dir = resolvePluginDir();
        System.setProperty(PluginDriverRegistry.PLUGIN_DIR_PROPERTY, dir);
        log.info("插件目录解析结果: {}", dir);
        this.registry = DataSourceDriverFactory.preloadPlugins();
    }

    /**
     * 定位插件目录。
     *
     * <p><b>为什么不能直接用相对的 {@code plugins}？</b></p>
     *
     * <p>相对路径是相对<b>进程工作目录</b>解析的。而从 IDE 或
     * {@code mvn -pl mini-sync-server spring-boot:run} 启动时，
     * 工作目录是<b>模块目录</b> {@code mini-sync-server/}，
     * 于是会去找 {@code mini-sync-server/plugins}（不存在），
     * 结果所有插件类型都用不了 —— 表现为"未实现的数据源类型: SQLITE，已注册类型: []"。</p>
     *
     * <p>所以按优先级依次尝试：</p>
     * <ol>
     *   <li>显式系统属性 {@code -Dminisync.plugin.dir=...}（部署时用）</li>
     *   <li>当前工作目录下的 {@code plugins/}</li>
     *   <li>从 class 位置向上找项目根（开发期用，能兼容 IDE 与 Maven 两种启动方式）</li>
     * </ol>
     */
    private static String resolvePluginDir() {
        String explicit = System.getProperty(PluginDriverRegistry.PLUGIN_DIR_PROPERTY);
        if (explicit != null && !explicit.trim().isEmpty()) {
            return explicit.trim();
        }

        // ① 工作目录下的 plugins/
        java.nio.file.Path cwdPlugins =
                java.nio.file.Paths.get("plugins").toAbsolutePath().normalize();
        if (java.nio.file.Files.isDirectory(cwdPlugins)) {
            return cwdPlugins.toString();
        }

        // ② 从 class 文件位置逐级向上找含 plugins/ 的目录
        try {
            java.nio.file.Path p = java.nio.file.Paths.get(
                    DriverRegistryService.class.getProtectionDomain()
                            .getCodeSource().getLocation().toURI()).normalize();
            for (int i = 0; i < 4 && p != null; i++) {
                java.nio.file.Path candidate = p.resolve("plugins");
                if (java.nio.file.Files.isDirectory(candidate)) {
                    return candidate.toString();
                }
                p = p.getParent();
            }
        } catch (Exception e) {
            log.debug("从 class 位置推断插件目录失败: {}", e.getMessage());
        }

        // ③ 兜底：交给默认逻辑（会打日志说明目录不存在）
        return "plugins";
    }

    @PostConstruct
    public void logRegistry() {
        log.info("驱动插件目录: {}", registry.pluginDir());
        if (registry.loadedPlugins().isEmpty()) {
            log.warn("未发现任何插件驱动，仅能使用 classpath 上的兜底实现");
        }
        for (LoadedPlugin p : registry.loadedPlugins()) {
            log.info("已注册驱动: type={}, provider={}, jar={}",
                    p.type(), p.providerClass(), p.pluginJarName());
        }
        for (String f : registry.failures()) {
            log.warn("插件加载失败（已忽略）: {}", f);
        }
    }

    /**
     * 列出全部已注册驱动类型。
     *
     * <p>返回结构与 Node 版 {@code /api/drivertype/list} 一致：
     * {@code [{type, display, jar}]}，前端下拉直接用。</p>
     */
    public List<Map<String, Object>> listTypes() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (LoadedPlugin p : registry.loadedPlugins()) {
            DataSourceDriverProvider provider = registry.getProvider(p.type());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", p.type());
            m.put("display", provider == null ? p.type() : provider.displayName());
            m.put("jar", p.pluginJarName());
            list.add(m);
        }
        return list;
    }

    /** 检查某类型是否已注册（用于保存数据源前校验） */
    public boolean supports(String type) {
        return registry.supports(type);
    }

    public PluginDriverRegistry raw() {
        return registry;
    }
}
