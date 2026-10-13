package com.minisync.job;

import com.minisync.connector.factory.DataSourceDriverFactory;
import com.minisync.spi.DataSourceDriver;
import com.minisync.spi.DataSourceDriverProvider;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 插件化驱动的单元测试。
 *
 * <p>重点验证四件事：</p>
 * <ol>
 *   <li>classpath 上的 SPI 声明能被发现（classpath 兜底路径）</li>
 *   <li>工厂按类型能造出驱动，且大小写不敏感</li>
 *   <li>工厂<b>每次返回新实例</b>（两端同类型时连接不能共享）</li>
 *   <li>未知类型给出可读的错误，而不是裸异常</li>
 * </ol>
 *
 * <p>这里不依赖真实的 plugins 目录 —— 那属于集成测试。
 * 单元测试只验证"契约 + 工厂行为"，跑得快、无外部依赖。</p>
 */
public class PluginDriverRegistryTest {

    /** classpath 上应能发现 mysql 与 pg 两个 provider（由插件 jar 提供） */
    @Test
    public void shouldDiscoverProvidersFromClasspath() {
        Iterator<DataSourceDriverProvider> it = ServiceLoader
                .load(DataSourceDriverProvider.class)
                .iterator();

        assertTrue(it.hasNext(), "classpath 上应至少有一个 DataSourceDriverProvider");

        while (it.hasNext()) {
            DataSourceDriverProvider p = it.next();
            assertNotNull(p.type(), "provider.type() 不能为 null");
            assertFalse(p.type().trim().isEmpty(), "provider.type() 不能为空白");
        }
    }

    @Test
    public void shouldCreateMySqlDriverFromType() {
        DataSourceDriver driver = DataSourceDriverFactory.create("MYSQL");
        assertNotNull(driver);
        assertEquals("MYSQL", driver.getType());
    }

    /** 大小写与前后的空白都应被容忍，不能因为用户输入 " mysql " 就报错 */
    @Test
    public void shouldNormalizeTypeCaseAndWhitespace() {
        assertEquals("MYSQL", DataSourceDriverFactory.create(" mysql ").getType());
        assertEquals("POSTGRESQL", DataSourceDriverFactory.create("PostgreSQL").getType());
    }

    /**
     * 工厂必须每次返回【新实例】。
     *
     * <p>这是早期踩过的坑：若工厂返回共享单例，源库与目标库同为 MySQL 时
     * 会共用同一个 Connection，数据写到自己身上。
     */
    @Test
    public void shouldReturnNewInstanceEveryTime() {
        DataSourceDriver a = DataSourceDriverFactory.create("MYSQL");
        DataSourceDriver b = DataSourceDriverFactory.create("MYSQL");
        assertNotSame(a, b, "工厂不允许缓存/复用驱动实例");
    }

    /** 未知类型要抛 IllegalArgumentException，且信息里带上已注册类型 */
    @Test
    public void shouldThrowReadableErrorForUnknownType() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> DataSourceDriverFactory.create("NOSUCHDB"));
        String msg = e.getMessage();
        assertNotNull(msg);
        assertTrue(msg.contains("NOSUCHDB"), "错误信息应包含出错的类型名");
        assertTrue(msg.contains("已注册类型"), "错误信息应提示已注册了哪些类型");
        assertTrue(msg.contains("plugins"), "错误信息应指向插件目录，便于排查");
    }

    /** 空类型必须被拒绝，而不是抛 NPE */
    @Test
    public void shouldRejectBlankType() {
        assertThrows(IllegalArgumentException.class,
                () -> DataSourceDriverFactory.create("   "));
        assertThrows(IllegalArgumentException.class,
                () -> DataSourceDriverFactory.create(null));
    }

    /**
     * provider 必须能被 ServiceLoader 实例化（有 public 无参构造器）。
     * 这条测试实际验证的是 jar 里能不能正常加载出类。
     */
    @Test
    public void shouldInstantiateProviderWithoutArgConstructor() {
        try {
            ServiceLoader.load(DataSourceDriverProvider.class).forEach(p -> {
                assertNotNull(p, "provider 实例不应为 null");
                // create(type) 应能正常造出驱动，不抛异常
                assertNotNull(p.create(p.type()));
            });
        } catch (ServiceConfigurationError e) {
            fail("SPI 配置有问题（大概率是缺 public 无参构造器）: " + e.getMessage());
        }
    }
}
