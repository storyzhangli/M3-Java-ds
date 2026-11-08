package com.zl.d26;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d26.service.AiChatResult;
import com.zl.d26.service.AiChatService;
import com.zl.d26.service.ChatInvocation;
import com.zl.d26.service.ThinkingSettings;
import com.zl.d26.support.AbstractPersistenceCleanupTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 实际下一轮模型入参中的 Memory 顺序与会话隔离回归。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChatMemoryOrderRegressionTest extends AbstractPersistenceCleanupTest {

    private static final String CHAT_URL = "/api/v1/ai/chat";
    private static final String OWNER = "ut_memory_order";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AiChatService aiChatService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void secondTurnShouldReceiveFirstUserAssistantPairInOrderAndNewSessionShouldBeEmpty()
            throws Exception {
        List<ChatInvocation> captured = new CopyOnWriteArrayList<>();
        when(aiChatService.chat(any(ChatInvocation.class), any(ThinkingSettings.class)))
                .thenAnswer(invocation -> {
                    ChatInvocation request = invocation.getArgument(0);
                    captured.add(request);
                    String answer = captured.size() == 1
                            ? "第一轮助手回答" : "后续助手回答";
                    return new AiChatResult("mock", "offline-mock", true, false, answer, null);
                });

        String sessionId = chat(Map.of(
                "message", "第一轮用户问题",
                "scene", "customer_service"));
        assertThat(sessionId).hasSize(32);

        String sameSessionId = chat(Map.of(
                "message", "第二轮用户问题",
                "scene", "customer_service",
                "sessionId", sessionId));
        assertThat(sameSessionId).isEqualTo(sessionId);
        assertThat(captured).hasSize(2);
        assertThat(captured.get(1).history())
                .extracting(ChatInvocation.Turn::role, ChatInvocation.Turn::content)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("user", "第一轮用户问题"),
                        org.assertj.core.groups.Tuple.tuple("assistant", "第一轮助手回答"));
        assertThat(captured.get(1).userMessage()).isEqualTo("第二轮用户问题");

        String isolatedSessionId = chat(Map.of(
                "message", "独立会话问题",
                "scene", "customer_service"));
        assertThat(isolatedSessionId).isNotEqualTo(sessionId).hasSize(32);
        assertThat(captured).hasSize(3);
        assertThat(captured.get(2).history()).isEmpty();
    }

    private String chat(Map<String, Object> payload) throws Exception {
        MvcResult result = mockMvc.perform(post(CHAT_URL)
                        .header("X-Dev-User", OWNER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("code").asInt()).isEqualTo(200);
        return body.path("data").path("sessionId").asText();
    }
}
