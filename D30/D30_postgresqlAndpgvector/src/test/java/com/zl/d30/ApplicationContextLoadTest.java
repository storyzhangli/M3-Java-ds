package com.zl.d30;

import com.zl.d30.config.AiConstants;
import com.zl.d30.config.AiProperties;
import com.zl.d30.config.ModelCallProperties;
import com.zl.d30.model.ClassifiedResponseErrorHandler;
import com.zl.d30.service.AiChatService;
import com.zl.d30.service.DeepSeekAiChatService;
import com.zl.d30.service.MockAiChatService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResponseErrorHandler;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验收点：Spring 应用上下文可在默认 mock 模式、无真实密钥、无外网条件下正常启动，
 * 且 DeepSeek 自动装配 Bean 与外部化配置绑定正确。
 */
@SpringBootTest
class ApplicationContextLoadTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private AiChatService aiChatService;

    @Autowired
    private Environment environment;

    @Autowired
    private RetryTemplate retryTemplate;

    @Autowired
    private ResponseErrorHandler responseErrorHandler;

    @Autowired
    private ModelCallProperties modelCallProperties;

    @Autowired
    private AiProperties aiProperties;

    @Test
    void contextShouldLoad() {
        assertThat(applicationContext).isNotNull();
    }

    @Test
    void mockModeShouldNotCreateVendorChatModelBean() {
        assertThat(applicationContext.getBeanNamesForType(ChatModel.class)).isEmpty();
    }

    @Test
    void mockServiceShouldBeActiveAndDeepSeekServiceAbsentByDefault() {
        assertThat(aiChatService).isInstanceOf(MockAiChatService.class);
        // 真实模式实现不应出现在 mock 上下文里，从 Bean 装配层面阻断外网调用路径
        assertThat(applicationContext.getBeanNamesForType(DeepSeekAiChatService.class)).isEmpty();
    }

    @Test
    void externalizedPropertiesShouldBindToExpectedDefaults() {
        assertThat(environment.getProperty("app.ai.mode")).isEqualTo(AiConstants.MODE_MOCK);
        assertThat(aiProperties.getModel()).isEqualTo("deepseek-flash");
        assertThat(aiProperties.getBaseUrl()).isEqualTo("https://api.deepseek.com");
        assertThat(aiProperties.getThinking().getReasonerModel()).isEqualTo("deepseek-reasoner");
        // Surefire 固定测试属性为显式无效值；测试不读取或输出宿主真实凭据。
        assertThat(environment.getProperty("app.ai.api-key"))
                .isEqualTo("invalid-test-key-not-for-authentication");
        assertThat(environment.getProperty("spring.ai.deepseek.api-key")).isNull();
        assertThat(environment.getProperty("spring.ai.retry.max-attempts"))
                .isEqualTo("1");
        assertThat(modelCallProperties.getMaxAttempts()).isEqualTo(2);
        assertThat(modelCallProperties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(modelCallProperties.getResponseTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(modelCallProperties.getTotalDeadline()).isEqualTo(Duration.ofSeconds(90));
        assertThat(modelCallProperties.getStreamTotalTimeout()).isEqualTo(Duration.ofSeconds(180));
        assertThat(responseErrorHandler).isInstanceOf(ClassifiedResponseErrorHandler.class);
    }

    @Test
    void singleRetryTemplateShouldStillBeAvailable() {
        assertThat(retryTemplate).isNotNull();
        assertThat(applicationContext.getBeanNamesForType(RetryTemplate.class)).hasSize(1);
    }
}
