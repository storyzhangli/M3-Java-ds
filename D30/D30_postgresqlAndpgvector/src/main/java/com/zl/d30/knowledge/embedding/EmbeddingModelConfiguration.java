package com.zl.d30.knowledge.embedding;

import com.zl.d30.common.AiConfigurationException;
import com.zl.d30.knowledge.embedding.EmbeddingProperties;
import com.zl.d30.knowledge.embedding.EmbeddingProvider;
import com.zl.d30.knowledge.embedding.EmbeddingVectorValidator;
import com.zl.d30.knowledge.embedding.LocalHashNgramEmbeddingModel;
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
                    "当前仅装配本地 Embedding；云端模型仅作为已核验切换目标");
        }
        return EmbeddingModelDescriptor.from(properties);
    }

    @Bean
    public EmbeddingModel embeddingModel(EmbeddingModelDescriptor descriptor,
                                         EmbeddingVectorValidator validator) {
        return new LocalHashNgramEmbeddingModel(descriptor, validator);
    }
}
