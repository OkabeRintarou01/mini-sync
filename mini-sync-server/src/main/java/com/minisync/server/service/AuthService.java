package com.minisync.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.minisync.server.entity.SysUser;
import com.minisync.server.mapper.SysUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录认证服务（演示级）。
 *
 * <p><b>当前实现是刻意简化的</b>，方便你把注意力放在同步引擎上：</p>
 * <ul>
 *   <li>密码<b>明文比对</b>（{@code sys_user.password} 存的就是明文）</li>
 *   <li>token 存在<b>本进程内存</b>里，服务重启即全部失效</li>
 *   <li>没有过期时间、没有刷新机制、没有多实例共享</li>
 * </ul>
 *
 * <p>生产实现应该换成：BCrypt 存哈希 + JWT 无状态令牌 + Redis 存黑名单。
 * 这些是后续"安全加固"阶段的事，不在本次迁移范围内 —— 本次迁移的目标是
 * <b>把接口搬到常驻服务上，行为保持不变</b>。</p>
 */
@Slf4j
@Service
public class AuthService {

    private final SysUserMapper sysUserMapper;

    /** token -> 用户信息。演示级存储，重启即清空。 */
    private final Map<String, SysUser> sessions = new ConcurrentHashMap<>();

    public AuthService(SysUserMapper sysUserMapper) {
        this.sysUserMapper = sysUserMapper;
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

    /** 创建会话并返回 token */
    public String createSession(SysUser user) {
        String token = "tk_" + System.currentTimeMillis() + "_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        sessions.put(token, user);
        return token;
    }

    /** 注销会话 */
    public void removeSession(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    /** 校验 token 是否有效（供后续接口鉴权使用） */
    public boolean isValid(String token) {
        return token != null && sessions.containsKey(token);
    }
}
