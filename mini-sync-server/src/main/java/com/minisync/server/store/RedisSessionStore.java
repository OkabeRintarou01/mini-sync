package com.minisync.server.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

/**
 * 登录会话的 Redis 实现。
 *
 * <p>Key 设计：{@code sync:session:{token}} -> JSON 序列化的 {@link SessionUser}</p>
 *
 * <p><b>为什么用 String + JSON，而不是 Redis Hash？</b></p>
 * <ul>
 *   <li>会话是"整体读写"的（不会只改其中某个字段），Hash 的按字段操作优势用不上</li>
 *   <li>String + TTL 更简单直观</li>
 * </ul>
 *
 * <p><b>TTL 用 Redis 原生的</b>，不像内存版那样手工判断过期 ——
 * Redis 到点自动删除，不需要任何清理逻辑。</p>
 */
@Slf4j
public class RedisSessionStore implements SessionStore {

    private static final String KEY_PREFIX = "sync:session:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisSessionStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(String token, SessionUser user, long ttlSeconds) {
        if (token == null || user == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(user);
            redis.opsForValue().set(KEY_PREFIX + token, json, ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            // 会话写失败不该让登录整体失败吗？—— 应该失败。
            // 因为"登录成功但会话没存下"会导致用户下一步操作就 401，比直接报错更困惑。
            throw new IllegalStateException("写入会话失败: " + e.getMessage(), e);
        }
    }

    @Override
    public SessionUser get(String token) {
        if (token == null) {
            return null;
        }
        String json = redis.opsForValue().get(KEY_PREFIX + token);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, SessionUser.class);
        } catch (Exception e) {
            log.warn("反序列化会话失败，按无效会话处理: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public void remove(String token) {
        if (token != null) {
            redis.delete(KEY_PREFIX + token);
        }
    }

    @Override
    public boolean isValid(String token) {
        return token != null && Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + token));
    }

    @Override
    public String backendName() {
        return "redis";
    }
}
