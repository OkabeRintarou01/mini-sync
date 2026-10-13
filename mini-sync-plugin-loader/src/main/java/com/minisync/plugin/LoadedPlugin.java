package com.minisync.plugin;

/**
 * 一个已成功加载的插件驱动。
 *
 * <p>这是<b>不可变值对象</b>：pluginJarName / providerClass / driverClassLoader
 * 在加载完成后就不会变。之所以把这三样都记录下来，是为了：</p>
 * <ul>
 *   <li>{@link #pluginJarName()}：界面与日志要显示"这个驱动来自哪个 jar"</li>
 *   <li>{@link #providerClass()}：诊断"为什么这个类型没注册上"</li>
 *   <li>{@link #driverClassLoader()}：后续若要做热重载，
 *       反射造出来的驱动实例持有的是这个加载器的引用，必须能拿到它才能正确卸载</li>
 * </ul>
 *
 * @param pluginJarName   插件 jar 的文件名，如 {@code minisync-mysql-plugin-1.0.0.jar}
 * @param type            规范化后的数据源类型，如 {@code MYSQL}
 * @param providerClass   provider 实现类的全限定名
 * @param driverClassLoader 加载该插件的独立 ClassLoader
 */
public record LoadedPlugin(
        String pluginJarName,
        String type,
        String providerClass,
        ClassLoader driverClassLoader) {

    /** 紧凑构造器：提早失败，避免把半成品对象传出去。 */
    public LoadedPlugin {
        if (type == null || type.trim().isEmpty()) {
            throw new IllegalArgumentException("插件类型不能为空");
        }
    }
}
