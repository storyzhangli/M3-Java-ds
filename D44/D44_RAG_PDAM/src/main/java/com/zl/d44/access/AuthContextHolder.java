package com.zl.d44.access;

import com.zl.d44.access.AuthContext;

/**
 * 请求级 AuthContext 持有器。
 *
 * <p>纪律（§3.3）：仅允许在 Servlet 请求线程内由过滤器 set，且必须在 finally 中
 * {@link #clear()}；异步/SSE 线程禁止依赖本 ThreadLocal 的传播——需要上下文的
 * 异步分支必须在请求线程内先 {@link #snapshot()}，再于任务起点 {@link #restore}
 * 并在任务结束 {@link #clear()}（单测覆盖）。</p>
 */
public final class AuthContextHolder {

    private static final ThreadLocal<AuthContext> CURRENT = new ThreadLocal<>();

    private AuthContextHolder() {
    }

    static void set(AuthContext context) {
        CURRENT.set(context);
    }

    public static AuthContext get() {
        return CURRENT.get();
    }

    /** 请求线程内的授权快照（可能为 null=关闭态/公开端点）。 */
    public static AuthContext snapshot() {
        return CURRENT.get();
    }

    /** 异步任务起点恢复快照；null 视为清除。 */
    public static void restore(AuthContext snapshot) {
        if (snapshot == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(snapshot);
        }
    }

    public static void clear() {
        CURRENT.remove();
    }
}
