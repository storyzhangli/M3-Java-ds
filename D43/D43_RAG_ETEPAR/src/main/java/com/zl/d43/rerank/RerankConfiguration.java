package com.zl.d43.rerank;

import com.alibaba.dashscope.rerank.TextReRank;
import java.nio.file.Path;

import com.zl.d43.rerank.BailianTextReranker;
import com.zl.d43.rerank.RerankCallLedger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * app.rerank.enabled=true 才装配（H2 默认关闭；关闭态容器零新 Bean，主链路零行为差异）。
 * 远程适配器额外要求 app.rerank.remote.enabled=true。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RerankProperties.class)
@ConditionalOnProperty(prefix = "app.rerank", name = "enabled", havingValue = "true")
public class RerankConfiguration {

    @Bean
    public LocalLexicalReranker localLexicalReranker(RerankProperties properties) {
        properties.requireValid();
        return new LocalLexicalReranker();
    }

    @Bean
    public RerankCallLedger rerankCallLedger(RerankProperties properties) {
        return new RerankCallLedger(Path.of(properties.getRemote().getLedgerPath()));
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.rerank.remote", name = "enabled", havingValue = "true")
    public BailianTextReranker bailianTextReranker(RerankProperties properties,
                                                   RerankCallLedger ledger) {
        properties.requireValid();
        return new BailianTextReranker(properties, new TextReRank(), ledger,
                () -> System.getenv("DASHSCOPE_API_KEY"));
    }
}