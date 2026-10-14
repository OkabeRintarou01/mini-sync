package com.minisync.server.store;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录会话的内存实现（单机开发用）。
 *
 * <p>限制：服务重启会话全丢、多实例不共享。
 * 生产请切到 {@link RedisSessionStore}。</p>
 *
 * <p>注意这里<b>手工实现了过期检查</b> —— 内存版没有 Redis 的 TTL，
 * 如果不判断过期时间，会话会永久有效，等于没有失效机制。</p>
 */
public class InMemorySessionStore implements SessionStore {

    /** token -> 会话条目（含过期时间戳） */
    private final Map<String, Entry> sessions = new ConcurrentHashMap<>();

    private static class Entry {
        final SessionUser user;
        final long expireAt;

        Entry(SessionUser user, long ttlSeconds) {
            this.user = user;
            this.expireAt = System.currentTimeMillis() + ttlSeconds * 1000L;
        }

        boolean expired() {
            return System.currentTimeMillis() > expireAt;
        }
    }

    @Override
    public void save(String token, SessionUser user, long ttlSeconds) {
        if (token == null || user == null) {
            return;
        }
        sessions.put(token, new Entry(user, ttlSeconds));
        // 顺手清理过期条目，避免内存里堆积僵尸会话
        sessions.entrySet().removeIf(e -> e.getValue().expired());
    }

    @Override
    public SessionUser get(String token) {
        if (token == null) {
            return null;
        }
        Entry e = sessions.get(token);
        if (e == null) {
            return null;
        }
        if (e.expired()) {
            sessions.remove(token);
            return null;
        }
        return e.user;
    }

    @Override
    public void remove(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    @Override
    public boolean isValid(String token) {
        return get(token) != null;
    }

    @Override
    public String backendName() {
        return "in-memory";
    }
}
