package com.zl.d43.rerank;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d43.access.AuthContextFilter;
import com.zl.d43.access.PrincipalRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * C18：access.enabled 态下重排端点处于 AuthContextFilter fail-closed 保护内
 * （/api/v1/* 通配覆盖 /api/v1/rerank/**，无凭据请求在过滤器层即被稳定拒绝，
 * 不触达下游链路；拒绝消息仅回显 reasonCode，零内部细节）。
 */
class RerankAccessBoundaryTest {

    private static final String ENDPOINT = "/api/v1/rerank/search";

    @Test
    @DisplayName("无 Authorization 头：AUTH_REQUIRED 拒绝，chain 零传递")
    void missingHeaderFailClosed() throws Exception {
        PrincipalRepository repo = mock(PrincipalRepository.class);
        AuthContextFilter filter = new AuthContextFilter(repo, new ObjectMapper());
        MockHttpServletRequest req = new MockHttpServletRequest("POST", ENDPOINT);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        String body = res.getContentAsString();
        assertTrue(body.contains("AUTH_REQUIRED"), body);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("非 Bearer 格式：AUTH_FORMAT_INVALID 拒绝")
    void malformedHeaderFailClosed() throws Exception {
        PrincipalRepository repo = mock(PrincipalRepository.class);
        AuthContextFilter filter = new AuthContextFilter(repo, new ObjectMapper());
        MockHttpServletRequest req = new MockHttpServletRequest("POST", ENDPOINT);
        req.addHeader("Authorization", "Basic dXNlcjpwYXNz");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        assertTrue(res.getContentAsString().contains("AUTH_FORMAT_INVALID"));
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("拒绝响应零凭据泄露：不回显任何 token 片段")
    void rejectBodyLeaksNothing() throws Exception {
        PrincipalRepository repo = mock(PrincipalRepository.class);
        AuthContextFilter filter = new AuthContextFilter(repo, new ObjectMapper());
        MockHttpServletRequest req = new MockHttpServletRequest("POST", ENDPOINT);
        req.addHeader("Authorization", "Bearer fixture-not-a-real-key-0000");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        String body = res.getContentAsString();
        assertTrue(!body.contains("fixture-not-a-real-key-0000"), "拒绝体泄出 token");
    }
}