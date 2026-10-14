package com.minisync.server.store;

/**
 * 登录会话存储抽象。
 *
 * <p><b>现状问题</b>：会话原来存在 {@code AuthService} 的一个
 * {@code ConcurrentHashMap} 里，导致：</p>
 * <ul>
 *   <li><b>服务重启 → 所有人被登出</b>（开发时每次改代码重启都要重新登录）</li>
 *   <li><b>多实例部署 → A 发的 token，B 不认识</b>（用户请求被负载均衡到 B 就 401）</li>
 * </ul>
 *
 * <p>会话天然适合放 Redis：</p>
 * <ul>
 *   <li>它是<b>临时数据</b>，丢了顶多重新登录，不影响业务正确性</li>
 *   <li>它有<b>天然的生命周期</b>（过期时间），正好用 Redis 的 TTL</li>
 *   <li>它需要<b>跨实例共享</b></li>
 * </ul>
 *
 * <p>对比一下：数据源配置就不能放 Redis —— 那丢了要重新配，
 * 而且它是审计对象，必须持久化。</p>
 */
public interface SessionStore {

    /**
     * 保存会话。
     *
     * @param ttlSeconds 过期秒数。会话必须有有效期，
     *                   否则 token 泄露后永久有效
     */
    void save(String token, SessionUser user, long ttlSeconds);

    /** 按 token 取用户，不存在或已过期返回 null */
    SessionUser get(String token);

    /** 删除会话（登出）。幂等：token 不存在也不报错 */
    void remove(String token);

    /** token 是否有效 */
    boolean isValid(String token);

    /** 当前生效的实现名（in-memory / redis） */
    String backendName();

    /**
     * 会话里保存的用户信息。
     *
     * <p><b>刻意不含密码字段。</b>会话数据会写进 Redis，
     * 不该把口令复制一份到缓存里 —— Redis 的访问控制和持久化策略
     * 通常比业务库宽松，多存一份等于扩大泄露面。</p>
     */
    class SessionUser {
        private Long id;
        private String username;
        private String nickname;

        public SessionUser() {
        }

        public SessionUser(Long id, String username, String nickname) {
            this.id = id;
            this.username = username;
            this.nickname = nickname;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getNickname() {
            return nickname;
        }

        public void setNickname(String nickname) {
            this.nickname = nickname;
        }
    }
}
