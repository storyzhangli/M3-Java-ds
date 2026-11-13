package com.zl.d30.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.Assert;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * DeepSeek Provider Adapter 的模型 Bean 配置边界。
 *
 * <p>Spring AI 1.1.8 的供应商自动配置强制要求非空 Key，无法满足 Mock 模式无 Key 启动。
 * 本配置关闭该自动配置后，仅在 {@code app.ai.mode=deepseek} 时使用应用级
 * {@link AiProperties} 构造 DeepSeekChatModel。</p>
 *
 * <p>这是本项目唯一允许直接组装 DeepSeek API/Options 的配置边界；业务 Service 与
 * Controller 仍只依赖内部契约。</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.ai", name = "mode",
        havingValue = AiConstants.MODE_DEEPSEEK)
public class DeepSeekChatModelConfiguration {

    @Bean
    @ConditionalOnMissingBean(ChatModel.class)
    public DeepSeekChatModel deepSeekChatModel(
            AiProperties properties,
            ObjectProvider<RestClient.Builder> restClientBuilders,
            ObjectProvider<WebClient.Builder> webClientBuilders,
            ObjectProvider<ToolCallingManager> toolCallingManagers,
            ObjectProvider<ObservationRegistry> observationRegistries,
            RetryTemplate retryTemplate,
            ResponseErrorHandler responseErrorHandler) {
        Assert.hasText(properties.getApiKey(),
                "deepseek 模式缺少 app.ai.api-key，请通过 AI_API_KEY 环境变量注入");

        DeepSeekApi deepSeekApi = DeepSeekApi.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .restClientBuilder(restClientBuilders.getIfAvailable(RestClient::builder))
                .webClientBuilder(webClientBuilders.getIfAvailable(WebClient::builder))
                .responseErrorHandler(responseErrorHandler)
                .build();
        DeepSeekChatOptions defaultOptions = DeepSeekChatOptions.builder()
                .model(properties.getModel())
                .temperature(properties.getTemperature())
                .build();
        ToolCallingManager toolCallingManager = toolCallingManagers.getIfAvailable(
                () -> DefaultToolCallingManager.builder().build());
        ObservationRegistry observationRegistry = observationRegistries.getIfAvailable(
                () -> ObservationRegistry.NOOP);

        return DeepSeekChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .defaultOptions(defaultOptions)
                .toolCallingManager(toolCallingManager)
                .retryTemplate(retryTemplate)
                .observationRegistry(observationRegistry)
                .build();
    }
}
