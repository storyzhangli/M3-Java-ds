package com.zl.d31;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d31.history.ChatHistoryService;
import com.zl.d31.model.ModelFailureCategory;
import com.zl.d31.model.NonRetryableModelException;
import com.zl.d31.model.RetryableModelException;
import com.zl.d31.service.AiChatService;
import com.zl.d31.service.ChatInvocation;
import com.zl.d31.service.ThinkingSettings;
import com.zl.d31.support.AbstractPersistenceCleanupTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 同步模型异常映射与历史终态回归。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChatModelFailureRegressionTest extends AbstractPersistenceCleanupTest {

    private static final String CHAT_URL = "/api/v1/ai/chat";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ChatHistoryService historyService;

    @MockitoBean
    private AiChatService aiChatService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void authenticationFailureShouldKeepExisting5001AndPersistFailedTurn() throws Exception {
        when(aiChatService.chat(any(ChatInvocation.class), any(ThinkingSettings.class)))
                .thenThrow(new NonRetryableModelException(
                        ModelFailureCategory.AUTHENTICATION_FAILED, 401));

        callAndExpect("ut_chat_failure_auth", "鉴权失败回归", 5001);

        assertFailedWithoutAssistant("ut_chat_failure_auth", "NonRetryableModelException");
    }

    @Test
    void transientFailureShouldKeepExisting5002AndNotEnterNextContext() throws Exception {
        when(aiChatService.chat(any(ChatInvocation.class), any(ThinkingSettings.class)))
                .thenThrow(new RetryableModelException(
                        ModelFailureCategory.UPSTREAM_UNAVAILABLE, 503, null));

        callAndExpect("ut_chat_failure_transient", "临时故障回归", 5002);

        assertFailedWithoutAssistant("ut_chat_failure_transient", "RetryableModelException");
    }

    private void callAndExpect(String owner, String message, int code) throws Exception {
        mockMvc.perform(post(CHAT_URL)
                        .header("X-Dev-User", owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("message", message))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private void assertFailedWithoutAssistant(String owner, String errorCode) {
        String sessionId = jdbcTemplate.queryForObject("""
                SELECT session_id FROM chat_session
                WHERE owner_id = ? ORDER BY created_at DESC LIMIT 1
                """, String.class, owner);
        String status = jdbcTemplate.queryForObject("""
                SELECT status FROM chat_message
                WHERE session_id = ? AND role = 'user'
                ORDER BY sequence_no DESC LIMIT 1
                """, String.class, sessionId);
        String storedErrorCode = jdbcTemplate.queryForObject("""
                SELECT error_code FROM chat_message
                WHERE session_id = ? AND role = 'user'
                ORDER BY sequence_no DESC LIMIT 1
                """, String.class, sessionId);
        Integer assistantRows = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM chat_message
                WHERE session_id = ? AND role = 'assistant'
                """, Integer.class, sessionId);

        assertThat(status).isEqualTo("FAILED");
        assertThat(storedErrorCode).isEqualTo(errorCode);
        assertThat(assistantRows).isZero();
        assertThat(historyService.getContextWindow(sessionId)).isEmpty();
    }
}
