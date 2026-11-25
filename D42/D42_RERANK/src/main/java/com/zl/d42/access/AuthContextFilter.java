package com.zl.d42.access;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d42.access.AuthPrincipal;
import com.zl.d42.access.AuthTokenHasher;
import com.zl.d42.common.Result;
import com.zl.d42.common.ResultCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 服务端可信身份过滤器（仅 app.access.enabled=true 装配，H2）。
 *
 * <p>解析 {@code Authorization: Bearer <token>} → SHA-256 → 凭据表 → AuthContext。
 * fail-closed（H5）：缺失 AUTH_REQUIRED、未命中 AUTH_INVALID、禁用 AUTH_DISABLED、
 * 过期 AUTH_EXPIRED、存储不可达 AUTH_STORE_UNAVAILABLE——一律 4001 稳定拒绝，
 * 不返回部分结果、不降级为无过滤。只回显稳定 reasonCode，不泄内部细节（H8）。</p>
 *
 * <p>日志卫生：任何分支都不打印 token 明文或其哈希（C20）。</p>
 */
public class AuthContextFilter extends OncePerRequestFilter {

    /** 请求属性键：供非 ThreadLocal 消费点（如 SSE 装配断言）读取。 */
    public static final String REQUEST_ATTRIBUTE = "d42.auth.context";

    private static final Logger log = LoggerFactory.getLogger(AuthContextFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final PrincipalRepository principalRepository;

    private final ObjectMapper objectMapper;

    public AuthContextFilter(PrincipalRepository principalRepository, ObjectMapper objectMapper) {
        this.principalRepository = principalRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        AuthContext established = null;
        try {
            String header = request.getHeader("Authorization");
            if (header == null || header.isBlank()) {
                reject(request, response, "AUTH_REQUIRED");
                return;
            }
            if (!header.startsWith(BEARER_PREFIX) || header.length() <= BEARER_PREFIX.length()) {
                reject(request, response, "AUTH_FORMAT_INVALID");
                return;
            }
            String token = header.substring(BEARER_PREFIX.length()).strip();
            List<AuthPrincipal> rows;
            try {
                rows = principalRepository.findByTokenHash(AuthTokenHasher.sha256Hex(token));
            } catch (DataAccessException | IllegalStateException ex) {
                log.warn("凭据存储不可达，fail-closed 拒绝，异常类型={}", ex.getClass().getSimpleName());
                reject(request, response, "AUTH_STORE_UNAVAILABLE");
                return;
            }
            if (rows.isEmpty()) {
                reject(request, response, "AUTH_INVALID");
                return;
            }
            AuthPrincipal principal = rows.get(0);
            if (!principal.enabled()) {
                reject(request, response, "AUTH_DISABLED");
                return;
            }
            if (principal.expiresAt() != null && principal.expiresAt().isBefore(LocalDateTime.now())) {
                reject(request, response, "AUTH_EXPIRED");
                return;
            }
            established = principal.toContext();
            AuthContextHolder.set(established);
            request.setAttribute(REQUEST_ATTRIBUTE, established);
            chain.doFilter(request, response);
        } finally {
            AuthContextHolder.clear();
        }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response,
                        String reasonCode) throws IOException {
        log.info("访问拒绝: reason={}, path={}", reasonCode, request.getRequestURI());
        // 不暴露受保护资源是否存在差异，统一稳定提示（OWASP Access Control 口径）
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getOutputStream(),
                Result.fail(ResultCode.PARAM_INVALID.getCode(),
                        "身份凭据无效或缺失，拒绝访问：" + reasonCode));
    }
}
