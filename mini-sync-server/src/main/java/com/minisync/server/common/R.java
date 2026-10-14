package com.minisync.server.common;

import java.util.HashMap;
import java.util.Map;

/**
 * 统一响应结构 —— 与 Node 版控制台的返回格式保持一致。
 *
 * <p><b>为什么要"保持一致"而不是重新设计？</b></p>
 *
 * <p>因为前端 {@code index.html} 里的代码是这样判断的：</p>
 * <pre>
 *   const r = await fetch(...).then(x =&gt; x.json());
 *   if (r.code === 200) { ... } else { toast(r.msg) }
 * </pre>
 *
 * <p>只要后端返回 {@code {code, msg, data}} 这个形状，
 * <b>前端一行都不用改</b>就能切到 Spring Boot。
 * 这是"逐步替换"策略的关键 —— 迁移期间新旧两套可以并存。</p>
 */
public final class R {

    private R() {
    }

    public static Map<String, Object> ok() {
        return of(200, "操作成功", null);
    }

    public static Map<String, Object> ok(String msg) {
        return of(200, msg, null);
    }

    public static Map<String, Object> ok(String msg, Object data) {
        return of(200, msg, data);
    }

    public static Map<String, Object> data(Object data) {
        return of(200, null, data);
    }

    public static Map<String, Object> fail(String msg) {
        return of(500, msg, null);
    }

    public static Map<String, Object> fail(int code, String msg) {
        return of(code, msg, null);
    }

    public static Map<String, Object> of(int code, String msg, Object data) {
        Map<String, Object> m = new HashMap<>(4);
        m.put("code", code);
        if (msg != null) {
            m.put("msg", msg);
        }
        if (data != null) {
            m.put("data", data);
        }
        return m;
    }
}
