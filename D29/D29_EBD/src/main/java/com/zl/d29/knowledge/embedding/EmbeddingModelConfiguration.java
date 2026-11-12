package com.zl.d29.knowledge.embedding;

import com.zl.d29.common.AiConfigurationException;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the active EmbeddingModel from externalized configuration.
 */
@Configuration(proxyBeanMethods = false)
public class EmbeddingModelConfiguration {

    @Bean
    public EmbeddingModelDescriptor embeddingModelDescriptor(
            EmbeddingProperties properties) {
        properties.requireValid();
        if (properties.getProvider() != EmbeddingProvider.LOCAL) {
            throw new AiConfigurationException(
                    "当前 D29 仅装配本地 Embedding；云端模型仅作为已核验切换目标");
        }
        return EmbeddingModelDescriptor.from(properties);
    }

    @Bean
    public EmbeddingModel embeddingModel(EmbeddingModelDescriptor descriptor,
                                         EmbeddingVectorValidator validator) {
        return new LocalHashNgramEmbeddingModel(descriptor, validator);
    }
}
