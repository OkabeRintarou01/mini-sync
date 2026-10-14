package com.minisync.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minisync.server.store.InMemoryRunStateStore;
import com.minisync.server.store.InMemorySessionStore;
import com.minisync.server.store.RedisRunStateStore;
import com.minisync.server.store.RedisSessionStore;
import com.minisync.server.store.RunStateStore;
import com.minisync.server.store.SessionStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 存储实现的选择配置。
 *
 * <p><b>核心思路：按配置切换实现，两种环境都能跑。</b></p>
 *
 * <pre>
 *   minisync.redis.enabled=false（默认） → 内存实现
 *       适用：单机开发、演示。零外部依赖。
 *
 *   minisync.redis.enabled=true          → Redis 实现
 *       适用：多实例部署、需要"重启不丢状态"
 * </pre>
 *
 * <p><b>为什么默认是内存版？</b></p>
 *
 * <p>因为"加一个功能"不应该让原本能跑的项目变得跑不起来。
 * 默认内存版意味着：clone 下来不装 Redis 也能启动；
 * 而一旦部署到多实例环境，改一行配置就获得跨实例能力。</p>
 *
 * <p><b>为什么不用 {@code @ConditionalOnProperty} 直接标注实现类？</b></p>
 *
 * <p>那样两个实现类都要变成 Spring Bean，还得处理"RedisTemplate 不存在时
 * 依赖注入失败"的问题。集中在这里用 {@code @Bean} 方法判断，
 * 逻辑一眼可见，也不会有条件装配的隐藏顺序问题。</p>
 */
@Slf4j
@Configuration
public class StoreConfig {

    /**
     * 运行状态存储。
     *
     * <p>选择依据是配置项 {@code minisync.redis.enabled}。</p>
     */
    @Bean
    public RunStateStore runStateStore(
            org.springframework.core.env.Environment env,
            org.springframework.beans.factory.ObjectProvider<RedisConnectionFactory> redisFactoryProvider) {

        if (isRedisEnabled(env)) {
            RedisConnectionFactory factory = redisFactoryProvider.getIfAvailable();
            if (factory != null) {
                log.info("运行状态存储: Redis（支持多实例共享，重启不丢）");
                return new RedisRunStateStore(new StringRedisTemplate(factory));
            }
            log.warn("已启用 Redis 但连接工厂不可用，回退到内存实现");
        }
        log.info("运行状态存储: 内存（单机模式；重启会丢失进度，多实例不可见）");
        return new InMemoryRunStateStore();
    }

    /**
     * 登录会话存储。
     */
    @Bean
    public SessionStore sessionStore(
            org.springframework.core.env.Environment env,
            org.springframework.beans.factory.ObjectProvider<RedisConnectionFactory> redisFactoryProvider,
            ObjectMapper objectMapper) {

        if (isRedisEnabled(env)) {
            RedisConnectionFactory factory = redisFactoryProvider.getIfAvailable();
            if (factory != null) {
                log.info("登录会话存储: Redis（重启不掉线，多实例共享）");
                return new RedisSessionStore(new StringRedisTemplate(factory), objectMapper);
            }
            log.warn("已启用 Redis 但连接工厂不可用，回退到内存实现");
        }
        log.info("登录会话存储: 内存（单机模式；重启后需要重新登录）");
        return new InMemorySessionStore();
    }

    private boolean isRedisEnabled(org.springframework.core.env.Environment env) {
        return Boolean.parseBoolean(
                env.getProperty("minisync.redis.enabled", "false"));
    }

    /**
     * 启动时探测一次 Redis 连通性。
     *
     * <p>不这么做的话，配置写错（端口/密码）要等到第一次触发同步
     * 才会暴露，排查成本高。启动时就 ping 一下，日志里能直接看到结果。</p>
     */
    @Bean
    @ConditionalOnProperty(name = "minisync.redis.enabled", havingValue = "true")
    public String redisHealthProbe(RedisConnectionFactory factory) {
        try {
            String pong = factory.getConnection().ping();
            log.info("Redis 连通性检查: {}", pong);
        } catch (Exception e) {
            // 只告警不阻断启动：Redis 挂了应该降级，而不是让整个服务起不来
            log.error("Redis 连接失败（服务仍会启动，但共享状态不可用）: {}", e.getMessage());
        }
        return "redis-health-probe";
    }
}
