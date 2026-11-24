package com.zl.d41.access;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d41.access.AuthPrincipal;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 凭据表只读仓库（主 MySQL 数据源，JdbcTemplate——无第二 DataSource，H1/§3.7 教训）。
 *
 * <p>存储不可达（DataAccessException）原样上抛，由过滤器映射 fail-closed
 * AUTH_STORE_UNAVAILABLE；绝不吞错后按“无权限”或“无过滤”降级（H5）。</p>
 */
public class PrincipalRepository {

    private static final String SELECT_BY_HASH =
            "SELECT token_sha256, user_id, tenant_id, dept_id, device_scopes, role, enabled, expires_at"
                    + " FROM d41_auth_principal WHERE token_sha256 = ?";

    private final JdbcTemplate jdbcTemplate;

    private final ObjectMapper mapper = new ObjectMapper();

    public PrincipalRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 命中返回唯一行；未命中 empty。 */
    public List<AuthPrincipal> findByTokenHash(String tokenSha256) {
        return jdbcTemplate.query(SELECT_BY_HASH, (rs, rowNum) -> new AuthPrincipal(
                        rs.getString("token_sha256"),
                        rs.getString("user_id"),
                        rs.getString("tenant_id"),
                        rs.getString("dept_id"),
                        parseDeviceScopes(rs.getString("device_scopes")),
                        rs.getString("role"),
                        rs.getBoolean("enabled"),
                        rs.getTimestamp("expires_at") == null
                                ? null : rs.getTimestamp("expires_at").toLocalDateTime()),
                tokenSha256);
    }

    private List<String> parseDeviceScopes(String json) throws DataAccessException {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = mapper.readTree(json);
            if (!node.isArray()) {
                // 数据损坏视同凭据不可用（fail-closed），不做宽容猜测
                throw new IllegalStateException("device_scopes 非数组");
            }
            java.util.ArrayList<String> values = new java.util.ArrayList<>();
            for (JsonNode item : node) {
                values.add(item.asText());
            }
            return values;
        } catch (Exception ex) {
            throw new DataAccessException("凭据表 device_scopes 解析失败", ex) {
            };
        }
    }

    public LocalDateTime now() {
        return LocalDateTime.now();
    }
}
