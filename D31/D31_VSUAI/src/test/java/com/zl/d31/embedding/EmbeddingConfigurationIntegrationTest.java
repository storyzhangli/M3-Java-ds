package com.zl.d31.embedding;

import com.zl.d31.knowledge.embedding.EmbeddingModelDescriptor;
import com.zl.d31.knowledge.embedding.EmbeddingVectorValidator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class EmbeddingConfigurationIntegrationTest {

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private EmbeddingModelDescriptor descriptor;

    @Autowired
    private EmbeddingVectorValidator vectorValidator;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void springShouldExposeConfiguredLocalModelAndMetadataEndpoint() throws Exception {
        assertThat(descriptor.modelName()).isEqualTo("local-hash-ngram-v1");
        assertThat(descriptor.dimensions()).isEqualTo(384);
        assertThat(descriptor.keyRequired()).isFalse();

        float[] vector = embeddingModel.embed(new Document("设备型号 PIXMA MP500，检查 USB 连接。"));
        vectorValidator.validate(vector, 384);

        mockMvc.perform(get("/api/v1/embeddings/model"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.provider").value("LOCAL"))
                .andExpect(jsonPath("$.data.modelName").value("local-hash-ngram-v1"))
                .andExpect(jsonPath("$.data.modelVersion").value("2026.11"))
                .andExpect(jsonPath("$.data.dimensions").value(384))
                .andExpect(jsonPath("$.data.apiKey").doesNotExist());
    }
}
