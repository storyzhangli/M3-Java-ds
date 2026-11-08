package com.zl.d26.config;

import com.zl.d26.service.AiChatService;
import com.zl.d26.service.DeepSeekAiChatService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.retry.support.RetryTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 仅验证外部配置到 Provider Bean 的绑定，不发起任何网络请求。
 */
@SpringBootTest(properties = {
        "app.ai.mode=deepseek",
        "app.ai.model=custom-deepseek-model",
        "app.ai.base-url=https://gateway.example.test/v1",
        "app.ai.api-key=invalid-test-binding-value",
        "app.ai.temperature=0.3",
        "app.ai.thinking.reasoner-model=custom-reasoner-model"
})
class DeepSeekConfigurationBindingTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private Environment environment;

    @Autowired
    private AiProperties aiProperties;

    @Autowired
    private DeepSeekChatModel chatModel;

    @Autowired
    private AiChatService aiChatService;

    @Autowired
    private RetryTemplate retryTemplate;

    @Test
    void applicationPropertiesShouldConstructTheProviderModelWithoutVendorEnvironmentNames() {
        assertThat(aiProperties.getMode()).isEqualTo("deepseek");
        assertThat(aiProperties.getModel()).isEqualTo("custom-deepseek-model");
        assertThat(aiProperties.getBaseUrl()).isEqualTo("https://gateway.example.test/v1");
        assertThat(aiProperties.getTemperature()).isEqualTo(0.3);
        assertThat(aiProperties.getThinking().getReasonerModel())
                .isEqualTo("custom-reasoner-model");

        DeepSeekChatOptions options = (DeepSeekChatOptions) chatModel.getDefaultOptions();
        assertThat(options.getModel()).isEqualTo("custom-deepseek-model");
        assertThat(options.getTemperature()).isEqualTo(0.3);
        assertThat(chatModel.retryTemplate).isSameAs(retryTemplate);

        assertThat(aiChatService).isInstanceOf(DeepSeekAiChatService.class);
        assertThat(applicationContext.getBeanNamesForType(DeepSeekChatModel.class)).hasSize(1);
        assertThat(environment.getProperty("spring.ai.deepseek.api-key")).isNull();
        assertThat(environment.getProperty("spring.ai.deepseek.base-url")).isNull();
    }
}
