package com.zl.d39.access;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AuthContextFilter fail-closed 全矩阵单测（C02 / T1–T4 的服务端镜像面）。
 *
 * <p>断言口径：HTTP 恒 200 + 统一 Result JSON（与既有端点错误语义一致），
 * reasonCode 出现在 msg 中；任何分支响应体不得含 token 明文或其哈希（C20）；
 * 拒绝分支绝不进入 FilterChain（不放行、不降级）。</p>
 */
class AuthContextFilterTest {

    private static final String TOKEN = "d39-tok-UnitToken-0123456789abcdef";

    private final PrincipalRepository repository = Mockito.mock(PrincipalRepository.class);

    private final AuthContextFilter filter =
            new AuthContextFilter(repository, new ObjectMapper());

    private MockHttpServletResponse run(MockHttpServletRequest request, FilterChain chain)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    private static MockHttpServletRequest request(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/knowledge/retrieval/search");
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }

    private static FilterChain recordingChain(AtomicBoolean entered,
                                              AtomicReference<AuthContext> inside) {
        return (req, resp) -> {
            entered.set(true);
            inside.set(AuthContextHolder.get());
        };
    }

    private static AuthPrincipal validPrincipal() {
        return new AuthPrincipal(AuthTokenHasher.sha256Hex(TOKEN), "userA", "T1", "D1",
                List.of("DEV-A"), AuthContext.ROLE_USER, true, null);
    }

    private static String bodyOf(MockHttpServletResponse response) throws IOException {
        return response.getContentAsString();
    }

    @Test
    @DisplayName("无 Authorization：AUTH_REQUIRED，链路不放行")
    void missingHeaderRejected() throws Exception {
        AtomicBoolean entered = new AtomicBoolean();
        MockHttpServletResponse response = run(request(null),
                recordingChain(entered, new AtomicReference<>()));
        assertFalse(entered.get());
        assertTrue(bodyOf(response).contains("AUTH_REQUIRED"));
    }

    @Test
    @DisplayName("非 Bearer 格式与空 token：AUTH_FORMAT_INVALID")
    void malformedHeaderRejected() throws Exception {
        AtomicBoolean entered = new AtomicBoolean();
        assertTrue(run(request("Token " + TOKEN), recordingChain(entered, new AtomicReference<>()))
                .getContentAsString().contains("AUTH_FORMAT_INVALID"));
        assertTrue(run(request("Bearer "), recordingChain(entered, new AtomicReference<>()))
                .getContentAsString().contains("AUTH_FORMAT_INVALID"));
        assertFalse(entered.get());
    }

    @Test
    @DisplayName("哈希未命中：AUTH_INVALID；响应不含 token 明文（C20）")
    void unknownTokenRejected() throws Exception {
        Mockito.when(repository.findByTokenHash(Mockito.anyString())).thenReturn(List.of());
        AtomicBoolean entered = new AtomicBoolean();
        String body = bodyOf(run(request("Bearer " + TOKEN),
                recordingChain(entered, new AtomicReference<>())));
        assertTrue(body.contains("AUTH_INVALID"));
        assertFalse(body.contains(TOKEN), "响应体不得回显 token 明文");
        assertFalse(entered.get());
    }

    @Test
    @DisplayName("enabled=false：AUTH_DISABLED，即便其余字段合法")
    void disabledPrincipalRejected() throws Exception {
        AuthPrincipal p = validPrincipal();
        Mockito.when(repository.findByTokenHash(Mockito.anyString()))
                .thenReturn(List.of(new AuthPrincipal(p.tokenSha256(), p.userId(), p.tenantId(),
                        p.deptId(), p.deviceScopes(), p.role(), false, p.expiresAt())));
        AtomicBoolean entered = new AtomicBoolean();
        assertTrue(bodyOf(run(request("Bearer " + TOKEN),
                recordingChain(entered, new AtomicReference<>()))).contains("AUTH_DISABLED"));
        assertFalse(entered.get());
    }

    @Test
    @DisplayName("expiresAt 在过去：AUTH_EXPIRED（边界由数据夹具控制，单测固定过去时刻）")
    void expiredPrincipalRejected() throws Exception {
        AuthPrincipal p = validPrincipal();
        Mockito.when(repository.findByTokenHash(Mockito.anyString()))
                .thenReturn(List.of(new AuthPrincipal(p.tokenSha256(), p.userId(), p.tenantId(),
                        p.deptId(), p.deviceScopes(), p.role(), true,
                        LocalDateTime.now().minusMinutes(1))));
        AtomicBoolean entered = new AtomicBoolean();
        assertTrue(bodyOf(run(request("Bearer " + TOKEN),
                recordingChain(entered, new AtomicReference<>()))).contains("AUTH_EXPIRED"));
        assertFalse(entered.get());
    }

    @Test
    @DisplayName("凭据存储不可达：AUTH_STORE_UNAVAILABLE——吞错降级为无过滤被禁止（H5）")
    void storeFailureFailClosed() throws Exception {
        Mockito.when(repository.findByTokenHash(Mockito.anyString()))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        AtomicBoolean entered = new AtomicBoolean();
        assertTrue(bodyOf(run(request("Bearer " + TOKEN),
                recordingChain(entered, new AtomicReference<>()))).contains("AUTH_STORE_UNAVAILABLE"));
        assertFalse(entered.get());
    }

    @Test
    @DisplayName("device_scopes 损坏：IllegalStateException 同样 fail-closed 拒绝")
    void corruptedScopesFailClosed() throws Exception {
        Mockito.when(repository.findByTokenHash(Mockito.anyString()))
                .thenThrow(new IllegalStateException("device_scopes 非数组"));
        AtomicBoolean entered = new AtomicBoolean();
        assertTrue(bodyOf(run(request("Bearer " + TOKEN),
                recordingChain(entered, new AtomicReference<>()))).contains("AUTH_STORE_UNAVAILABLE"));
        assertFalse(entered.get());
    }

    @Test
    @DisplayName("成功：链路内可见 AuthContext 与请求属性，返回后 ThreadLocal 必清（finally）")
    void successEstablishesContextAndClearsAfter() throws Exception {
        Mockito.when(repository.findByTokenHash(AuthTokenHasher.sha256Hex(TOKEN)))
                .thenReturn(List.of(validPrincipal()));
        AtomicBoolean entered = new AtomicBoolean();
        AtomicReference<AuthContext> inside = new AtomicReference<>();
        MockHttpServletRequest servletRequest = request("Bearer " + TOKEN);
        MockHttpServletResponse response = run(servletRequest, recordingChain(entered, inside));
        assertTrue(entered.get());
        assertEquals("", bodyOf(response));
        assertNotNull(inside.get());
        assertEquals("userA", inside.get().userId());
        assertEquals("T1", inside.get().tenantId());
        assertTrue(inside.get().deviceScopes().contains("DEV-A"));
        // 请求属性通道（非 ThreadLocal 消费点）
        assertNotNull(servletRequest.getAttribute(AuthContextFilter.REQUEST_ATTRIBUTE));
        // finally 清理：链路外绝不残留
        assertNull(AuthContextHolder.get());
    }

    @Test
    @DisplayName("拒绝分支同样保证 ThreadLocal 无残留（防线程池串号）")
    void rejectionLeavesNoContext() throws Exception {
        Mockito.when(repository.findByTokenHash(Mockito.anyString())).thenReturn(List.of());
        run(request("Bearer nope"), (req, resp) -> {
            throw new AssertionError("拒绝分支不得进入链路");
        });
        assertNull(AuthContextHolder.get());
    }
}
