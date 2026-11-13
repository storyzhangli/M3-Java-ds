package com.zl.d30.config;

import com.zl.d30.model.*;
import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.retry.policy.CompositeRetryPolicy;
import org.springframework.retry.policy.TimeoutRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResponseErrorHandler;
import reactor.netty.http.client.HttpClient;

/**
 * 模型调用超时与唯一重试责任层。
 *
 * <p>Spring AI DeepSeek 自动配置会注入这里的 RetryTemplate 和 ResponseErrorHandler。
 * 因此同步调用只有这一层重试；流式路径不自动重试。</p>
 */
@Configuration(proxyBeanMethods = false)
public class ModelCallConfiguration {

    /**
     * 自定义 RetryTemplate 覆盖 Spring AI 1.1.8 默认 10 次尝试和宽泛
     * ResourceAccessException 重试。Predicate 只批准明确分类的失败。
     */
    @Bean
    public RetryTemplate retryTemplate(ModelCallProperties properties,
                                       ModelFailureClassifier classifier) {
        ModelAttemptRetryPolicy attempts =
                new ModelAttemptRetryPolicy(properties.getMaxAttempts(), classifier);
        TimeoutRetryPolicy deadline =
                new TimeoutRetryPolicy(properties.getTotalDeadline().toMillis());
        CompositeRetryPolicy policy = new CompositeRetryPolicy();
        policy.setPolicies(new org.springframework.retry.RetryPolicy[]{attempts, deadline});
        policy.setOptimistic(false);
        RetryTemplate retryTemplate = RetryTemplate.builder()
                .customPolicy(policy)
                .customBackoff(new ModelRetryBackOffPolicy(classifier, properties))
                .withListener(new ModelRetryListener(classifier))
                .build();
        retryTemplate.setThrowLastExceptionOnExhausted(true);
        return retryTemplate;
    }

    @Bean
    public ResponseErrorHandler responseErrorHandler(ModelFailureClassifier classifier) {
        return new ClassifiedResponseErrorHandler(classifier);
    }

    /**
     * RestClient 的建连/读取超时。DeepSeek 自动配置使用 Boot 提供的 RestClient.Builder，
     * 因此该 Customizer 会实际进入同步 HTTP 调用链。
     */
    @Bean
    public RestClientCustomizer deepSeekRestClientTimeoutCustomizer(
            ModelCallProperties properties,
            ObjectProvider<ClientHttpRequestFactoryBuilder<?>> factoryBuilders) {
        ClientHttpRequestFactoryBuilder<?> builder =
                factoryBuilders.getIfAvailable(ClientHttpRequestFactoryBuilder::detect);
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withTimeouts(properties.getConnectTimeout(), properties.getResponseTimeout());
        ClientHttpRequestFactory factory = builder.build(settings);
        return restClientBuilder -> restClientBuilder.requestFactory(factory);
    }

    /**
     * WebClient 的建连和响应超时。Reactor Netty 是显式补充的 HTTP 客户端实现。
     */
    @Bean
    public WebClientCustomizer deepSeekWebClientTimeoutCustomizer(ModelCallProperties properties) {
        int connectTimeoutMillis = Math.toIntExact(properties.getConnectTimeout().toMillis());
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis)
                .responseTimeout(properties.getResponseTimeout());
        ReactorClientHttpConnector connector = new ReactorClientHttpConnector(httpClient);
        return webClientBuilder -> webClientBuilder.clientConnector(connector);
    }
}
