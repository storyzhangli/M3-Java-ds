package com.zl.d28.history;

import com.zl.d28.config.AiProperties;
import com.zl.d28.config.ModelCallProperties;
import com.zl.d28.controller.ChatController;
import com.zl.d28.controller.ChatStreamController;
import com.zl.d28.conversation.RoleConversationService;
import com.zl.d28.dto.ChatRequest;
import com.zl.d28.history.config.HistoryProperties;
import com.zl.d28.history.model.ChatSession;
import com.zl.d28.history.model.HistoryMessage;
import com.zl.d28.knowledge.KnowledgeChatOrchestrator;
import com.zl.d28.model.ModelFailureClassifier;
import com.zl.d28.prompt.PromptTemplateService;
import com.zl.d28.prompt.model.PromptTemplateEntity;
import com.zl.d28.service.ThinkingSettings;
import com.zl.d28.service.ThinkingSettingsResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * D28 Memory 读取失败回归：轮次已开始后，历史窗口读取失败必须收口 PENDING 用户消息。
 */
@ExtendWith(MockitoExtension.class)
class ChatMemoryReadFailureRegressionTest {

    @Mock
    private ChatHistoryService historyService;

    @Mock
    private KnowledgeChatOrchestrator orchestrator;

    @Mock
    private RoleConversationService roleConversationService;

    @Mock
    private ThinkingSettingsResolver thinkingSettingsResolver;

    @Mock
    private HttpServletRequest servletRequest;

    @Test
    void plainSyncHistoryReadFailureShouldFailTheStartedTurn() {
        ChatRequest request = request(null);
        ChatHistoryService.TurnStart turn = turn();
        when(historyService.resolveOwner(null)).thenReturn("ut_read_failure");
        when(historyService.startTurn("ut_read_failure", null, "", "", 0, "普通同步问题"))
                .thenReturn(turn);
        when(historyService.getContextWindow("session-read-failure"))
                .thenThrow(new IllegalStateException("history read failed"));
        when(thinkingSettingsResolver.resolve(request)).thenReturn(ThinkingSettings.legacyDefault());

        ChatController controller = new ChatController(
                orchestrator, roleConversationService, thinkingSettingsResolver,
                new AiProperties(), historyService, new HistoryProperties());

        assertThatThrownBy(() -> controller.chat(request, servletRequest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("history read failed");

        verify(historyService).failTurn(turn, "IllegalStateException", "IllegalStateException");
        verifyNoInteractions(orchestrator);
    }

    @Test
    void rolePreparationHistoryReadFailureShouldFailTheStartedTurn() {
        ChatRequest request = request("programming");
        ChatHistoryService.TurnStart turn = turn();
        PromptTemplateEntity template = new PromptTemplateEntity();
        template.setScene("programming");
        template.setTemplateId("programming");
        template.setVersion(1);

        PromptTemplateService promptTemplateService = mock(PromptTemplateService.class);
        when(promptTemplateService.resolveEnabledRoleTemplate("programming")).thenReturn(template);
        when(promptTemplateService.render(eq(template), isNull())).thenReturn("系统提示词");
        when(historyService.startTurn(
                "ut_read_failure", null, "programming", "programming", 1, "角色同步问题"))
                .thenReturn(turn);
        when(historyService.getContextWindow("session-read-failure"))
                .thenThrow(new IllegalStateException("history read failed"));

        RoleConversationService service =
                new RoleConversationService(promptTemplateService, historyService);

        assertThatThrownBy(() -> service.prepare(request, "ut_read_failure"))
                .isInstanceOf(IllegalStateException.class);
        verify(historyService).failTurn(turn, "IllegalStateException", "IllegalStateException");
    }

    @Test
    void streamHistoryReadFailureShouldFailTheStartedTurnBeforeStartFrame() {
        ChatRequest request = request(null);
        ChatHistoryService.TurnStart turn = turn();
        when(historyService.resolveOwner(null)).thenReturn("ut_read_failure");
        when(historyService.startTurn("ut_read_failure", null, "", "", 0, "普通同步问题"))
                .thenReturn(turn);
        when(historyService.getContextWindow("session-read-failure"))
                .thenThrow(new IllegalStateException("history read failed"));
        when(thinkingSettingsResolver.resolve(request)).thenReturn(ThinkingSettings.legacyDefault());

        ModelCallProperties modelCallProperties = new ModelCallProperties();
        ChatStreamController controller = new ChatStreamController(
                orchestrator, new AiProperties(), roleConversationService,
                thinkingSettingsResolver, historyService, new HistoryProperties(),
                modelCallProperties, new ModelFailureClassifier(modelCallProperties));

        assertThatThrownBy(() -> controller.streamChat(
                request, servletRequest, mock(HttpServletResponse.class)))
                .isInstanceOf(IllegalStateException.class);

        verify(historyService).failTurn(turn, "IllegalStateException", "IllegalStateException");
        verify(orchestrator, never()).stream(any(), any(), any(Boolean.class));
    }

    private ChatRequest request(String scene) {
        ChatRequest request = new ChatRequest();
        request.setMessage(scene == null ? "普通同步问题" : "角色同步问题");
        request.setScene(scene);
        return request;
    }

    private ChatHistoryService.TurnStart turn() {
        ChatSession session = new ChatSession();
        session.setSessionId("session-read-failure");
        HistoryMessage user = new HistoryMessage();
        user.setContent("问题");
        return new ChatHistoryService.TurnStart(session, user);
    }
}
