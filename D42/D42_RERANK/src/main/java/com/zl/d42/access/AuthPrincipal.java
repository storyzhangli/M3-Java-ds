package com.zl.d42.access;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 凭据表行投影（d42_auth_principal，只读）。token 明文永不进入本对象——表里只有 SHA-256。
 */
public record AuthPrincipal(String tokenSha256,
                            String userId,
                            String tenantId,
                            String deptId,
                            List<String> deviceScopes,
                            String role,
                            boolean enabled,
                            LocalDateTime expiresAt) {

    public AuthPrincipal {
        deviceScopes = deviceScopes == null ? List.of() : List.copyOf(deviceScopes);
    }

    public AuthContext toContext() {
        return new AuthContext(userId, tenantId, deptId,
                List.copyOf(deviceScopes).stream().collect(java.util.stream.Collectors.toUnmodifiableSet()),
                role);
    }
}
