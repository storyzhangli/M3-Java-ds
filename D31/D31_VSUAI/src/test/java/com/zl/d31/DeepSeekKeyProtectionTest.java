package com.zl.d31;

import com.zl.d31.common.AiConfigurationException;
import com.zl.d31.config.AiProperties;
import com.zl.d31.service.DeepSeekAiChatService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 真实模式缺失/空白 Key 必须在任何模型交互前失败。
 */
class DeepSeekKeyProtectionTest {

    @Test
    void blankAndNullKeysShouldFailFastWithoutCallingModel() {
        ChatModel isolatedModel = mock(ChatModel.class);
        String[] invalidKeys = {null, "", "   "};

        for (String invalidKey : invalidKeys) {
            AiProperties properties = deepSeekProperties(invalidKey);
            DeepSeekAiChatService service = new DeepSeekAiChatService(isolatedModel, properties);

            assertThatThrownBy(() -> service.chat("hi"))
                    .isInstanceOf(AiConfigurationException.class)
                    .hasMessageContaining("AI_API_KEY");
            assertThatThrownBy(() -> service.streamChat("hi"))
                    .isInstanceOf(AiConfigurationException.class)
                    .hasMessageContaining("AI_API_KEY");
        }

        verify(isolatedModel, never()).call(any(org.springframework.ai.chat.prompt.Prompt.class));
        verify(isolatedModel, never()).stream(any(org.springframework.ai.chat.prompt.Prompt.class));
    }

    @Test
    void apiKeyMustNotAppearInPropertiesToString() {
        AiProperties properties = deepSeekProperties("sensitive-test-value");

        assertThat(properties.toString()).doesNotContain("sensitive-test-value");
    }

    private AiProperties deepSeekProperties(String apiKey) {
        AiProperties properties = new AiProperties();
        properties.setMode("deepseek");
        properties.setApiKey(apiKey);
        properties.setModel("deepseek-flash");
        properties.setBaseUrl("https://api.deepseek.com");
        properties.getThinking().setReasonerModel("deepseek-reasoner");
        return properties;
    }
}
