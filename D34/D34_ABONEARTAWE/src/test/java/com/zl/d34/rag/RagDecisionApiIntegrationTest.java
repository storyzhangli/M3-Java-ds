package com.zl.d34.rag;

import com.zl.d34.rag.service.RagRetrievalService;
import com.zl.d34.service.AiChatService;
import com.zl.d34.service.ChatInvocation;
import com.zl.d34.service.ThinkingSettings;
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
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Level 3 MockMvc 集成测试：HTTP 层决策/状态/原因码透出形状。
 *
 * <p>测试环境 pgvector.enabled=false（application-test.yml），检索通道与 AI 端口均以
 * {@code @MockBean} 隔离，本测试验证的是控制器→编排器→评估门禁的装配与 JSON 契约。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Timeout(120)
class RagDecisionApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RagRetrievalService retrievalService;

    @MockBean
    private AiChatService aiChatService;

    @BeforeEach
    void setUpMocks() {
        when(aiChatService.chat(any(ChatInvocation.class), any(ThinkingSettings.class)))
                .thenReturn(new com.zl.d34.service.AiChatResult(
                        "mock", "mock-model", true, false,
                        "压力阈值应设定为 2.5MPa，过高会导致主轴过热。[S1]", null));
    }

    private String body(String query) {
        return "{\"query\":\"" + query + "\"}";
    }

    @Test
    @DisplayName("HTTP 200：空检索透出 NO_HIT + REFUSE/NO_EVIDENCE")
    void httpEmptyRetrievalShape() throws Exception {
        when(retrievalService.retrieve(any())).thenReturn(List.of());
        mockMvc.perform(post("/api/v1/rag/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(RagTestFixtures.QUERY_FULL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("NO_HIT"))
                .andExpect(jsonPath("$.data.decision").value("REFUSE"))
                .andExpect(jsonPath("$.data.reasonCode").value("NO_EVIDENCE"))
                .andExpect(jsonPath("$.data.answer").doesNotExist());
    }

    @Test
    @DisplayName("HTTP 200：低相关透出 NO_USABLE_CONTEXT + REFUSE/LOW_RELEVANCE")
    void httpLowRelevanceShape() throws Exception {
        when(retrievalService.retrieve(any())).thenReturn(List.of(RagTestFixtures.chunkLowScore()));
        mockMvc.perform(post("/api/v1/rag/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(RagTestFixtures.QUERY_FULL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NO_USABLE_CONTEXT"))
                .andExpect(jsonPath("$.data.decision").value("REFUSE"))
                .andExpect(jsonPath("$.data.reasonCode").value("LOW_RELEVANCE"))
                .andExpect(jsonPath("$.data.decisionDetail.reason").exists());
    }

    @Test
    @DisplayName("HTTP 200：澄清透出 CLARIFY_REQUIRED + 澄清问题")
    void httpClarifyShape() throws Exception {
        when(retrievalService.retrieve(any())).thenReturn(List.of(RagTestFixtures.chunkR2Active()));
        MvcResult result = mockMvc.perform(post("/api/v1/rag/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(RagTestFixtures.QUERY_NO_IDENTITY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLARIFY_REQUIRED"))
                .andExpect(jsonPath("$.data.decision").value("CLARIFY"))
                .andExpect(jsonPath("$.data.reasonCode").value("MISSING_REQUIRED_CONTEXT"))
                .andExpect(jsonPath("$.data.decisionDetail.clarificationQuestion").isNotEmpty())
                .andExpect(jsonPath("$.data.answer").doesNotExist())
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("设备型号");
    }

    @Test
    @DisplayName("HTTP 200：冲突透出 EVIDENCE_CONFLICT + 冲突分组")
    void httpConflictShape() throws Exception {
        when(retrievalService.retrieve(any())).thenReturn(List.of(
                RagTestFixtures.chunkR2Active(), RagTestFixtures.chunkR2Conflict()));
        mockMvc.perform(post("/api/v1/rag/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(RagTestFixtures.QUERY_FULL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("EVIDENCE_CONFLICT"))
                .andExpect(jsonPath("$.data.decision").value("CONFLICT"))
                .andExpect(jsonPath("$.data.reasonCode").value("EVIDENCE_CONFLICT"))
                .andExpect(jsonPath("$.data.decisionDetail.conflictGroups[0].topic").exists())
                .andExpect(jsonPath("$.data.answer").doesNotExist());
    }

    @Test
    @DisplayName("HTTP 200：证据充分透出 SUCCESS + ANSWER + 来源与引用")
    void httpAnswerShape() throws Exception {
        when(retrievalService.retrieve(any())).thenReturn(List.of(RagTestFixtures.chunkR2Active()));
        mockMvc.perform(post("/api/v1/rag/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(RagTestFixtures.QUERY_FULL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.decision").value("ANSWER"))
                .andExpect(jsonPath("$.data.answer").isNotEmpty())
                .andExpect(jsonPath("$.data.citedSourceIds[0]").value("S1"))
                .andExpect(jsonPath("$.data.decisionDetail.sufficiency").value("DIRECT"))
                .andExpect(jsonPath("$.data.sources[0].id").value("S1"));
    }
}
