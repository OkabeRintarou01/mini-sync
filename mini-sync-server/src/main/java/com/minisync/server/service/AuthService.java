package com.minisync.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.minisync.server.entity.SysUser;
import com.minisync.server.mapper.SysUserMapper;
import com.minisync.server.store.SessionStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 登录认证服务（演示级）。
 *
 * <p><b>当前实现是刻意简化的</b>，方便把注意力放在同步引擎上：</p>
 * <ul>
 *   <li>密码<b>明文比对</b>（{@code sys_user.password} 存的就是明文）</li>
 *   <li>没有刷新机制、没有权限模型、接口未做统一鉴权拦截</li>
 * </ul>
 *
 * <p><b>但会话存储已经换成可切换的了</b>：通过 {@link SessionStore} 抽象，
 * 可以运行在内存模式（单机开发）或 Redis 模式（多实例、重启不掉线）。
 * 这是本次加 Redis 的三处改动之一。</p>
 *
 * <p>生产实现还应补上：BCrypt 存哈希 + JWT 无状态令牌 + 接口级鉴权拦截。</p>
 */
@Slf4j
@Service
public class AuthService {

    /**
     * 会话有效期：8 小时。
     *
     * <p>会话<b>必须</b>有有效期，否则 token 一旦泄露就永久有效。
     * 内存版靠手工比对时间戳实现，Redis 版直接用原生 TTL。</p>
     */
    private static final long SESSION_TTL_SECONDS = 8 * 3600L;

    private final SysUserMapper sysUserMapper;
    private final SessionStore sessionStore;

    public AuthService(SysUserMapper sysUserMapper, SessionStore sessionStore) {
        this.sysUserMapper = sysUserMapper;
        this.sessionStore = sessionStore;
    }

    /**
     * 登录校验。
     *
     * @return 成功返回用户；失败返回 null
     */
    public SysUser checkLogin(String username, String password) {
        if (username == null || username.trim().isEmpty()
                || password == null || password.isEmpty()) {
            return null;
        }
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username.trim())
                .eq(SysUser::getPassword, password)
                .last("LIMIT 1"));
        if (user == null) {
            log.info("登录失败: username={}", username);
        }
        return user;
    }

    /**
     * 创建会话并返回 token。
     *
     * <p>只把 id / username / nickname 存进会话，<b>不存密码</b> ——
     * 会话数据会写进 Redis，没必要把口令再复制一份到缓存里。
     * Redis 的访问控制与持久化策略通常比业务库宽松，多存一份等于扩大泄露面。</p>
     */
    public String createSession(SysUser user) {
        String token = "tk_" + System.currentTimeMillis() + "_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

        SessionStore.SessionUser su = new SessionStore.SessionUser(
                user.getId(), user.getUsername(), user.getNickname());
        sessionStore.save(token, su, SESSION_TTL_SECONDS);

        log.debug("会话已创建: username={}, backend={}",
                user.getUsername(), sessionStore.backendName());
        return token;
    }

    /** 注销会话。幂等：token 不存在也不报错。 */
    public void removeSession(String token) {
        sessionStore.remove(token);
    }

    /** 校验 token 是否有效（供后续接口鉴权使用） */
    public boolean isValid(String token) {
        return sessionStore.isValid(token);
    }

    /** 当前会话后端（用于健康检查显示） */
    public String sessionBackend() {
        return sessionStore.backendName();
    }
}
