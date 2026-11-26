package com.zl.d43.access;

import com.zl.d43.access.AuthContextFilter;

import java.util.Set;

/**
 * 请求级可信身份上下文。
 *
 * <p>不可变 record：只由 {@link AuthContextFilter} 从服务端凭据表解析构建，
 * 请求体内任何 userId/role/tenantId/admin 声明一律不参与构建（T2 断言面）。</p>
 *
 * @param userId       业务用户（enabled 时同时是会话 owner，D6）
 * @param tenantId     租户（硬边界，H7）
 * @param deptId       部门（ADMIN 不 bypass 租户，仅 bypass 部门/设备）
 * @param deviceScopes 可见设备集合（服务端解析，空=仅哨兵文档）
 * @param role         USER / ADMIN（唯一管理员判定来源）
 */
public record AuthContext(String userId,
                          String tenantId,
                          String deptId,
                          Set<String> deviceScopes,
                          String role) {

    public static final String ROLE_USER = "USER";
    public static final String ROLE_ADMIN = "ADMIN";

    public AuthContext {
        deviceScopes = deviceScopes == null ? Set.of() : Set.copyOf(deviceScopes);
    }

    public boolean isAdmin() {
        return ROLE_ADMIN.equals(role);
    }
}
