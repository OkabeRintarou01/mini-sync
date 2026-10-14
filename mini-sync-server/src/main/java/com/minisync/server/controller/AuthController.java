package com.minisync.server.controller;

import com.minisync.server.common.R;
import com.minisync.server.entity.SysUser;
import com.minisync.server.service.AuthService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 登录相关接口。
 *
 * <p>迁移自 Node 版的 {@code POST /api/login} 与 {@code GET /api/logout}，
 * <b>返回结构完全一致</b>，所以前端不需要改动。</p>
 */
@RestController
@RequestMapping("/api")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 登录请求体 */
    public record LoginRequest(String username, String password) {
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest req) {
        if (req == null || req.username() == null || req.username().trim().isEmpty()
                || req.password() == null || req.password().isEmpty()) {
            return R.fail(400, "请输入账号和密码");
        }

        SysUser user = authService.checkLogin(req.username(), req.password());
        if (user == null) {
            // 回显账号名，便于区分"账号打错"还是"密码打错"。
            // 本地演示工具不存在撞库风险，可读性优先。
            return R.fail(401, "用户名或密码错误（账号：" + req.username().trim() + "）");
        }

        String token = authService.createSession(user);

        // 不返回 password 字段
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("id", user.getId());
        u.put("username", user.getUsername());
        u.put("nickname", user.getNickname());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", token);
        data.put("user", u);

        return R.of(200, null, data);
    }

    @RequestMapping("/logout")
    public Map<String, Object> logout(@RequestParam(required = false) String token) {
        authService.removeSession(token);
        return R.ok();
    }
}
