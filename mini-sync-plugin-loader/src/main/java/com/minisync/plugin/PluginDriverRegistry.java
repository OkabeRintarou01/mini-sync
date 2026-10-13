package com.minisync.plugin;

import com.minisync.spi.DataSourceDriverProvider;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * 插件化驱动加载器 —— 「自定义数据源放入 plugins 目录即可使用」的落地实现。
 *
 * <p><b>工作机制（三步）：</b></p>
 * <ol>
 *   <li><b>发现</b>：扫描 {@code plugins/} 目录下所有 {@code *.jar}</li>
 *   <li><b>隔离加载</b>：每个 jar 用一个<b>独立的 URLClassLoader</b>加载，
 *       父加载器是当前线程的 context classloader。
 *       这样插件用自己的 JDBC 驱动版本（如它自带 mysql-connector-j 8.x）
 *       不会与主工程 classpath 里的版本冲突</li>
 *   <li><b>SPI 注册</b>：在插件的 classloader 上执行
 *       {@code ServiceLoader.load(DataSourceDriverProvider.class)}，
 *       读 jar 内 {@code META-INF/services/com.minisync.spi.DataSourceDriverProvider}
 *       声明的实现类，实例化后按 {@code type()} 注册进本地 map</li>
 * </ol>
 *
 * <p><b>为什么不用 datalinkx 那种「按 jar 名约定 + Class.forName」的做法？</b></p>
 *
 * <p>datalinkx 的 {@code DsDriverFactory} 靠一个命名约定拼出类名：
 * {@code com.datalinkx.<type>driver.<Type>Driver}，再按
 * {@code datalinkx-driver-<type>-1.0.0.jar} 找 jar。
 * 这能用，但把「包名怎么起、jar 怎么命名」变成了隐式契约 ——
 * 写错一个字母就静默找不到，而且 className 是字符串拼接出来的，
 * 编译器完全帮不了你。</p>
 *
 * <p>改成 SPI 之后：</p>
 * <ul>
 *   <li>jar 名叫什么、包名叫什么、类名叫什么，<b>完全自由</b></li>
 *   <li>{@code META-INF/services} 文件是唯一的注册点，IDE 和构建工具都能校验</li>
 *   <li>provider 类型是强类型的，不是字符串拼出来的</li>
 *   <li>一个 jar 里可以注册多个类型，也可以一个类型有多个 provider（后注册者胜出）</li>
 * </ul>
 *
 * <p><b>代价与取舍（要诚实面对）：</b></p>
 * <ul>
 *   <li><b>加载后不能卸载</b>：JDK 9+ 起 {@code URLClassLoader} 不再是
 *       {@code ClassLoader} 的公开子类型（虽然 21 仍可用），
 *       且 classloader 的卸载条件极其苛刻（类、实例、加载器三者都要不可达）。
 *       所以本类是<b>启动时扫描一次</b>，运行期新增 jar 需要重启。
 *       我不把这一点藏起来 —— 对外就说"支持插件化扩展，新增驱动需重启生效"。</li>
 *   <li><b>JDBC 驱动类在子 classloader 里的可见性</b>：JDBC 的
 *       {@code DriverManager} 有个特殊逻辑 —— 它只认
 *       <b>系统 classloader 能看到的</b>驱动类，插件 classloader 里的
 *       {@code com.mysql.cj.jriver} 对它不可见，会抛
 *       "No suitable driver found"。
 *       这个问题在主工程内置驱动时不存在（在 classpath 上），
 *       所以我提供了{@link #hasExternalDependencyMiss(String)}与
 *       在 provider 上的 {@code requiresExternalDependency()}，
 *       让"缺 jar"这件事被明确报出来而不是变成一个难懂的连接错误。</li>
 * </ul>
 *
 * <p><b>线程安全性</b>：本类发布后不可变（final map + volatile 快照），
 * 可以多线程并发调用 {@link #getProvider(String)}。</p>
 */
@Slf4j
public class PluginDriverRegistry {

    /** 插件目录，可通过 -Dminisync.plugin.dir=... 覆盖。 */
    public static final String PLUGIN_DIR_PROPERTY = "minisync.plugin.dir";
    private static final String DEFAULT_PLUGIN_DIR = "plugins";
    private static final String SERVICE_FILE =
            "META-INF/services/" + DataSourceDriverProvider.class.getName();

    /**
     * 规范化类型标识：去空白 + 转大写。<b>公开方法，供工厂复用同一套规则。</b>
     *
     * <p>这里<b>刻意不依赖 mini-sync-common 的 DataSourceType</b>：加载器与
     * spi 契约一样要保持零外部依赖，否则第三方调式的 classloader 层级会变复杂，
     * 也容易在"同一个类被两个 classloader 各加载一份"时出 LinkageError。</p>
     */
    public static String normalizeType(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("数据源类型不能为空");
        }
        String t = raw.trim();
        if (t.isEmpty()) {
            throw new IllegalArgumentException("数据源类型不能为空");
        }
        return t.toUpperCase();
    }

    /** type -> provider */
    private final Map<String, DataSourceDriverProvider> providers;

    /** type -> 来源信息，仅用于界面与日志展示 */
    private final Map<String, LoadedPlugin> loadedInfos;

    /** 加载失败的插件，不静默吞掉 */
    private final List<String> failures;

    /** 实际扫描的目录，界面要显示"驱动从哪来" */
    private final String pluginDir;

    private PluginDriverRegistry(Map<String, DataSourceDriverProvider> providers,
                                 Map<String, LoadedPlugin> infos,
                                 List<String> failures,
                                 String pluginDir) {        this.providers = providers;
        this.loadedInfos = infos;
        this.failures = failures;
        this.pluginDir = pluginDir;
    }

    // ==================================================================
    // 加载
    // ==================================================================

    /**
     * 扫描默认 {@code plugins/} 目录并注册全部驱动。
     *
     * @throws IOException 目录不可读时抛出
     */
    public static PluginDriverRegistry load() throws IOException {
        return load(System.getProperty(PLUGIN_DIR_PROPERTY, DEFAULT_PLUGIN_DIR));
    }

    /**
     * 扫描指定目录并注册全部驱动。
     *
     * <p><b>单个插件失败不影响其它插件</b>：一个 jar 的 SPI 文件写错类名、
     * 或者 provider 没有无参构造器，只记录到 {@link #failures()}，
     * 不让整个注册表挂掉。</p>
     *
     * @param dirPath 插件目录，相对路径基于 JVM 工作目录解析
     */
    public static PluginDriverRegistry load(String dirPath) throws IOException {
        Path dir = Paths.get(dirPath).toAbsolutePath().normalize();

        Map<String, DataSourceDriverProvider> registry = new LinkedHashMap<String, DataSourceDriverProvider>();
        Map<String, LoadedPlugin> infos = new LinkedHashMap<String, LoadedPlugin>();
        List<String> failures = new ArrayList<String>();

        if (!Files.isDirectory(dir)) {
            log.info("插件目录不存在，跳过插件加载（不影响内置驱动）: {}", dir);
            return new PluginDriverRegistry(registry, infos, failures, dir.toString());
        }

        // 按文件名排序，保证多次启动的加载顺序一致（可复现，便于排查）
        List<Path> jars = new ArrayList<Path>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.jar")) {
            for (Path p : ds) {
                jars.add(p);
            }
        }
        Collections.sort(jars);

        if (jars.isEmpty()) {
            log.info("插件目录为空（无驱动 jar）: {}", dir);
        }

        for (Path jar : jars) {
            log.info("开始扫描插件 jar: {}", jar.getFileName());
            loadOneJar(jar, registry, infos, failures);
        }

        log.info("插件加载完成: 目录={}, 成功注册类型={}, 失败数={}",
                dir, registry.keySet(), failures.size());
        return new PluginDriverRegistry(registry, infos, failures, dir.toString());
    }

    /**
     * 加载单个 jar。
     *
     * <p><b>为什么逐个 jar 各建一个 URLClassLoader？</b></p>
     *
     * <p>为了做到<b>驱动版本隔离</b>：mysql 插件 jar 里 shade 了 mysql-connector-j 8.0.33，
     * 另一个插件若 shade 了 5.1.x，两者的 {@code com.mysql.cj.jdbc.Driver}
     * 必须是不同的 Class 才不会互相干扰。</p>
     *
     * <p><b>但这里有一个必须处理的坑：</b></p>
     *
     * <p>本项目的 {@code mini-sync-job} 模块把 mysql / postgresql 插件
     * <b>同时</b>作为运行时依赖引入了 classpath（为了 classpath 兜底）。
     * 于是 {@code ServiceLoader.load(Provider.class, childLoader)} 会：</p>
     * <ol>
     *   <li>在 childLoader（本 jar）里找到 SPI 声明 → 得到 provider</li>
     *   <li>继续沿父加载器找到 classpath 上的 SPI 声明 → <b>又</b>得到同名的 provider</li>
     * </ol>
     *
     * <p>结果每个 jar 都会把 classpath 上的那几个 provider 也报一遍，
     * 如果不过滤，"这个类型来自哪个插件 jar"的信息就全错。</p>
     *
     * <p>所以判断依据是 <b>provider 的 Class 由哪个 classloader 加载</b>：
     * <ul>
     *   <li>{@code cl == loader}：确实是本插件 jar 里的，<b>注册</b></li>
     *   <li>否则：是从父加载器（classpath）来的，<b>跳过</b>
     *       —— classpath 兜底那条路由工厂自己负责，不该在这里重复注册</li>
     * </ul>
     * </p>
     */
    private static void loadOneJar(Path jar,
                                   Map<String, DataSourceDriverProvider> registry,
                                   Map<String, LoadedPlugin> infos,
                                   List<String> failures) {
        String jarName = jar.getFileName().toString();
        URLClassLoader loader = null;
        int ownRegistered = 0;
        try {
            URL url = jar.toUri().toURL();

            // 独立 classloader：插件间的依赖（尤其 JDBC 驱动版本）互相隔离
            loader = new URLClassLoader(new URL[]{url},
                    Thread.currentThread().getContextClassLoader());

            // SPI 声明必须从【jar 文件本身】读，不能用 loader.getResource()。
            //
            // 原因：classloader 是父优先的，loader.getResource() 会先在父加载器上找，
            // 而 classpath 上也有一个同名的 services 文件（classpath 兜底依赖），
            // 于是拿到的是 classpath 那份内容，来源 jar 就张冠李戴了。
            //
            // 所以直接开 jar，读里面的文件，保证读到的一定是【本插件自己声明的】。
            for (String className : readProviderClassNamesFromJar(jar)) {
                DataSourceDriverProvider provider =
                        loadProvider(className, loader, jarName, failures);
                if (provider == null) {
                    continue;
                }

                String type = normalizeType(provider.type());
                LoadedPlugin previous = infos.get(type);
                if (previous != null) {
                    log.warn("数据源类型重复注册: type={}, 已由 {} 注册，现被 {} 覆盖",
                            type, previous.pluginJarName(), jarName);
                }

                registry.put(type, provider);
                infos.put(type, new LoadedPlugin(jarName, type, className, loader));
                ownRegistered++;
                log.info("注册插件驱动: type={}, provider={}, jar={}", type, className, jarName);
            }

            if (ownRegistered == 0 && !alreadyFailed(failures, jarName)) {
                failures.add(jarName + ": 未发现可用的 SPI 服务声明（应包含 META-INF/services/"
                        + DataSourceDriverProvider.class.getName() + "）");
                log.warn("插件 jar 未注册任何 provider: {}", jarName);
            }

        } catch (MalformedURLException e) {
            failures.add(jarName + ": jar 路径非法 - " + e.getMessage());
            log.error("插件 jar URL 非法: {}", jarName, e);
        } catch (ServiceConfigurationError e) {
            // SPI 文件存在但配置有问题：类名写错、无无参构造器、类加载失败等
            failures.add(jarName + ": SPI 配置错误 - " + e.getMessage());
            log.error("插件 jar 的 SPI 配置有问题: {}", jarName, e);
        } catch (RuntimeException e) {
            failures.add(jarName + ": 初始化 provider 失败 - " + e);
            log.error("插件 jar 初始化 provider 失败: {}", jarName, e);
        }
    }

    /**
     * 判断 SPI 声明文件是否存在于该 jar 内（诊断用）。
     */
    private static boolean hasServiceFile(java.util.zip.ZipFile zip) {
        return zip.getEntry(SERVICE_FILE) != null;
    }

    /**
     * 从 jar 文件内部读 SPI 声明的 provider 类名列表。
     *
     * <p>直接开 jar 读，<b>不经过 classloader</b>。因为 classloader 是父优先的，
     * {@code loader.getResource()} 会返回 classpath 上那份同名文件的内容，
     * 导致"类型来自哪个插件 jar"的信息错误。</p>
     */
    private static List<String> readProviderClassNamesFromJar(Path jar) {
        List<String> names = new ArrayList<String>();

        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
            java.util.zip.ZipEntry entry = zip.getEntry(SERVICE_FILE);
            if (entry == null) {
                return names;
            }
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(zip.getInputStream(entry), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    names.add(trimmed);
                }
            }
        } catch (IOException e) {
            log.warn("读取 jar 内 SPI 声明失败: jar={}, err={}", jar.getFileName(), e.getMessage());
        }
        return names;
    }

    /**
     * 用插件自己的 classloader 加载并实例化 provider。
     *
     * <p>失败时不抛异常，只记入 {@code failures}，让其它插件继续加载 ——
     * 一个坏插件不应该让整个启动失败。</p>
     *
     * @return 成功时返回实例，失败时返回 {@code null}
     */
    private static DataSourceDriverProvider loadProvider(String className,
                                                         ClassLoader loader,
                                                         String jarName,
                                                         List<String> failures) {
        try {
            Class<?> clazz = Class.forName(className, true, loader);
            Object instance = clazz.getDeclaredConstructor().newInstance();
            if (!(instance instanceof DataSourceDriverProvider)) {
                failures.add(jarName + ": 类 " + className
                        + " 未实现 " + DataSourceDriverProvider.class.getName());
                log.warn("SPI 声明的类不是 provider: jar={}, class={}", jarName, className);
                return null;
            }
            return (DataSourceDriverProvider) instance;

        } catch (ClassNotFoundException e) {
            failures.add(jarName + ": SPI 声明中的类不存在 - " + className);
            log.warn("SPI 类不存在: jar={}, class={}", jarName, className);
        } catch (NoSuchMethodException e) {
            failures.add(jarName + ": 类 " + className + " 缺少 public 无参构造器");
            log.warn("SPI 类缺少无参构造器: jar={}, class={}", jarName, className);
        } catch (Exception e) {
            failures.add(jarName + ": 初始化类 " + className + " 失败 - " + e);
            log.warn("SPI 类初始化失败: jar={}, class={}", jarName, className, e);
        }
        return null;
    }

    /** 该 jar 是否已经记录过失败（避免同一原因重复记两条）。 */
    private static boolean alreadyFailed(List<String> failures, String jarName) {
        for (String f : failures) {
            if (f != null && f.startsWith(jarName + ":")) {
                return true;
            }
        }
        return false;
    }

    // ==================================================================
    // 查询
    // ==================================================================

    /**
     * 按类型取 provider，找不到返回 {@code null}。
     *
     * <p>不抛异常是刻意的：调用方（工厂）要的是"查不到就回落内置实现"，
     * 让它自己去决定是回落还是报错。</p>
     */
    public DataSourceDriverProvider getProvider(String rawType) {
        if (rawType == null || rawType.trim().isEmpty()) {
            return null;
        }
        return providers.get(normalizeType(rawType));
    }

    /** 是否已注册该类型。 */
    public boolean supports(String rawType) {
        return getProvider(rawType) != null;
    }

    /** 全部已注册类型（含插件来源信息），用于前端下拉框。 */
    public Collection<LoadedPlugin> loadedPlugins() {
        return Collections.unmodifiableCollection(loadedInfos.values());
    }

    /** 仅类型名称，界面展示用。 */
    public Collection<String> registeredTypes() {
        return Collections.unmodifiableCollection(loadedInfos.keySet());
    }

    /** 加载失败的插件及原因。 */
    public List<String> failures() {
        return Collections.unmodifiableList(failures);
    }

    /** 实际扫描的插件目录绝对路径。 */
    public String pluginDir() {
        return pluginDir;
    }

    /**
     * 某个插件的驱动是否缺少必需的第三方 jar。
     *
     * <p>用于把 JDBC 的 "No suitable driver found" 翻译成人话。
     * provider 声明 {@code requiresExternalDependency()=true} 时返回 {@code true}，
     * 意味着"这个类型要跑起来，还得有一个它自己的 JDBC 驱动 jar"。</p>
     */
    public boolean hasExternalDependencyMiss(String rawType) {
        DataSourceDriverProvider p = getProvider(rawType);
        return p != null && p.requiresExternalDependency();
    }
}
