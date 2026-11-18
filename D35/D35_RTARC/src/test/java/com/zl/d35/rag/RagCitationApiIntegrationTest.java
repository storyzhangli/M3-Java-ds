package com.zl.d35.rag;

import com.zl.d35.rag.service.CitationExistenceValidator;
import com.zl.d35.rag.service.RagRetrievalService;
import com.zl.d35.service.AiChatResult;
import com.zl.d35.service.AiChatService;
import com.zl.d35.service.ChatInvocation;
import com.zl.d35.service.ThinkingSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Level 3 MockMvc 集成测试：引用溯源返回契约与入参校验。
 *
 * <p>检索通道与 AI 端口以 {@code @MockBean} 隔离；存在性核验器 mock 为"全部存在"，
 * 主链路 SUCCESS 不被 MySQL 关联表缺失干扰（删除竞态已在单测单独覆盖）。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Timeout(120)
class RagCitationApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RagRetrievalService retrievalService;

    @MockBean
    private AiChatService aiChatService;

    @MockBean
    private CitationExistenceValidator citationExistenceValidator;

    @BeforeEach
    void setUpMocks() {
        when(aiChatService.chat(any(ChatInvocation.class), any(ThinkingSettings.class)))
                .thenReturn(new AiChatResult("mock", "mock-model", true, false,
                        "压力阈值应设定为 2.5MPa，过高会导致主轴过热。[S1]", null));
        when(citationExistenceValidator.validate(any()))
                .thenReturn(CitationExistenceValidator.ExistenceResult.ok());
    }

    private String body(String query) {
        return "{\"query\":\"" + query + "\"}";
    }

    @Test
    @DisplayName("D35-011 主链路 SUCCESS：引用溯源字段透出（sourceId/pageNumbers/paragraphIndex/documentVersion）")
    void successCarriesCitationTraceFields() throws Exception {
        when(retrievalService.retrieve(any())).thenReturn(List.of(
                CitationTestFixtures.chunkR2Active()));
        mockMvc.perform(post("/api/v1/rag/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(CitationTestFixtures.QUERY_FULL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.citedSourceIds[0]").value("S1"))
                .andExpect(jsonPath("$.data.citedSources[0].id").value("S1"))
                .andExpect(jsonPath("$.data.citedSources[0].sourceId").value("doc-r2"))
                .andExpect(jsonPath("$.data.citedSources[0].documentVersion").value("V2.0"))
                .andExpect(jsonPath("$.data.citedSources[0].pageNumbers[0]").value(15))
                .andExpect(jsonPath("$.data.citedSources[0].paragraphIndex").value(3))
                .andExpect(jsonPath("$.data.citedSources[0].excerpt").isNotEmpty());
    }

    @Test
    @DisplayName("D35-012 入参核验：空 query 被 Bean Validation 拦截（HTTP 200 + 业务码 4001）")
    void blankQueryRejected() throws Exception {
        mockMvc.perform(post("/api/v1/rag/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4001))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("D35-013 SSE 与同步同语义：citedSources 与 sources 消费同一溯源结构（含 documentVersion）")
    void sseAndSyncShareSameCitationShape() throws Exception {
        when(retrievalService.retrieve(any())).thenReturn(List.of(
                CitationTestFixtures.chunkR2Active()));
        mockMvc.perform(post("/api/v1/rag/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(CitationTestFixtures.QUERY_FULL)))
                .andExpect(status().isOk())
                // 同步 API 的 sources（全部来源）与 citedSources（实际引用）字段口径一致
                .andExpect(jsonPath("$.data.sources[0].sourceId").value("doc-r2"))
                .andExpect(jsonPath("$.data.sources[0].documentVersion").value("V2.0"))
                .andExpect(jsonPath("$.data.sources[0].pageNumbers[0]").value(15))
                .andExpect(jsonPath("$.data.sources[0].paragraphIndex").value(3))
                .andExpect(jsonPath("$.data.citedSources[0].documentVersion").value("V2.0"));
    }
}
