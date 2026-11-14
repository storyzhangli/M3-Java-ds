package com.zl.d31;

import com.zl.d31.service.AiChatService;
import com.zl.d31.service.MockAiChatService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 默认 Mock 模式必须在 API Key 为空时启动，且不装配供应商 ChatModel。
 */
@SpringBootTest(properties = {
        "app.ai.mode=mock",
        "app.ai.api-key="
})
class MockWithoutApiKeyTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private AiChatService aiChatService;

    @Autowired
    private Environment environment;

    @Test
    void contextShouldLoadWithoutRealApiKey() {
        assertThat(environment.getProperty("app.ai.api-key")).isEmpty();
        assertThat(aiChatService).isInstanceOf(MockAiChatService.class);
        assertThat(applicationContext.getBeanNamesForType(ChatModel.class)).isEmpty();
    }
}
