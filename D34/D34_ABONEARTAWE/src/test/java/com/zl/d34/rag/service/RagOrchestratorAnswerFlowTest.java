package com.zl.d34.rag.service;

import com.zl.d34.equipment.EquipmentClarificationPolicy;
import com.zl.d34.equipment.EquipmentInformationExtractor;
import com.zl.d34.knowledge.retrieval.RetrievalException;
import com.zl.d34.rag.RagTestFixtures;
import com.zl.d34.rag.config.RagProperties;
import com.zl.d34.rag.dto.RagRequest;
import com.zl.d34.rag.model.RagDecision;
import com.zl.d34.rag.model.RagReasonCode;
import com.zl.d34.rag.model.RagResponse;
import com.zl.d34.rag.model.RagStatus;
import com.zl.d34.service.AiChatResult;
import com.zl.d34.service.AiChatService;
import com.zl.d34.service.ChatInvocation;
import com.zl.d34.service.ThinkingSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
/**
 * 编排层全链路测试：七态互斥、拒答/澄清/冲突分支模型零调用、生成后校验兜底。
 */
class RagOrchestratorAnswerFlowTest {

    private RagRetrievalService retrievalService;

    private AiChatService aiChatService;

    private RagOrchestrator orchestrator;

    private RagProperties properties;

    @BeforeEach
    void setUp() {
        retrievalService = mock(RagRetrievalService.class);
        aiChatService = mock(AiChatService.class);
        properties = new RagProperties();
        properties.validate();
        ContextAssemblyService assemblyService = new ContextAssemblyService(properties);
        EvidenceAssessmentService evidenceService = new EvidenceAssessmentService(
                new EquipmentClarificationPolicy(), properties);
        GenerationService generationService = new GenerationService(aiChatService);
        PostGenerationValidator validator = new PostGenerationValidator();
        EquipmentInformationExtractor extractor = new EquipmentInformationExtractor();
        orchestrator = new RagOrchestrator(retrievalService, evidenceService, assemblyService,
                generationService, validator, extractor, properties);
    }

    private RagRequest request(String query) {
        return new RagRequest(query, null, null, null, null);
    }

    @Test
    @DisplayName("空检索 → NO_HIT + REFUSE/NO_EVIDENCE + 模型零调用")
    void emptyRetrievalRefusesWithoutModel() {
        when(retrievalService.retrieve(any())).thenReturn(List.of());
        RagResponse response = orchestrator.answer(request(RagTestFixtures.QUERY_FULL));
        assertThat(response.status()).isEqualTo(RagStatus.NO_HIT);
        assertThat(response.decision()).isEqualTo(RagDecision.REFUSE);
        assertThat(response.reasonCode()).isEqualTo(RagReasonCode.NO_EVIDENCE);
        assertThat(response.answer()).isNull();
        verify(aiChatService, never()).chat(any(ChatInvocation.class), any(ThinkingSettings.class));
    }

    @Test
    @DisplayName("低相关 → NO_USABLE_CONTEXT + REFUSE/LOW_RELEVANCE + 模型零调用")
    void lowRelevanceRefusesWithoutModel() {
        when(retrievalService.retrieve(any())).thenReturn(List.of(RagTestFixtures.chunkLowScore()));
        RagResponse response = orchestrator.answer(request(RagTestFixtures.QUERY_FULL));
        assertThat(response.status()).isEqualTo(RagStatus.NO_USABLE_CONTEXT);
        assertThat(response.decision()).isEqualTo(RagDecision.REFUSE);
        assertThat(response.reasonCode()).isEqualTo(RagReasonCode.LOW_RELEVANCE);
        verify(aiChatService, never()).chat(any(ChatInvocation.class), any(ThinkingSettings.class));
    }

    @Test
    @DisplayName("缺身份 → CLARIFY_REQUIRED + CLARIFY/MISSING_REQUIRED_CONTEXT + 模型零调用")
    void clarifyWithoutModel() {
        when(retrievalService.retrieve(any())).thenReturn(List.of(RagTestFixtures.chunkR2Active()));
        RagResponse response = orchestrator.answer(request(RagTestFixtures.QUERY_NO_IDENTITY));
        assertThat(response.status()).isEqualTo(RagStatus.CLARIFY_REQUIRED);
        assertThat(response.decision()).isEqualTo(RagDecision.CLARIFY);
        assertThat(response.reasonCode()).isEqualTo(RagReasonCode.MISSING_REQUIRED_CONTEXT);
        assertThat(response.decisionDetail().clarificationQuestion()).contains("设备型号");
        assertThat(response.answer()).isNull();
        verify(aiChatService, never()).chat(any(ChatInvocation.class), any(ThinkingSettings.class));
    }

    @Test
    @DisplayName("证据冲突 → EVIDENCE_CONFLICT + CONFLICT + 冲突分组 + 模型零调用")
    void conflictWithoutModel() {
        when(retrievalService.retrieve(any())).thenReturn(List.of(
                RagTestFixtures.chunkR2Active(), RagTestFixtures.chunkR2Conflict()));
        RagResponse response = orchestrator.answer(request(RagTestFixtures.QUERY_FULL));
        assertThat(response.status()).isEqualTo(RagStatus.EVIDENCE_CONFLICT);
        assertThat(response.decision()).isEqualTo(RagDecision.CONFLICT);
        assertThat(response.reasonCode()).isEqualTo(RagReasonCode.EVIDENCE_CONFLICT);
        assertThat(response.decisionDetail().conflictGroups()).hasSize(1);
        assertThat(response.answer()).isNull();
        verify(aiChatService, never()).chat(any(ChatInvocation.class), any(ThinkingSettings.class));
    }

    @Test
    @DisplayName("证据充分 → SUCCESS + ANSWER，引用与充分性如实返回")
    void answerFlowSucceeds() {
        when(retrievalService.retrieve(any())).thenReturn(List.of(RagTestFixtures.chunkR2Active()));
        when(aiChatService.chat(any(ChatInvocation.class), any(ThinkingSettings.class))).thenReturn(new AiChatResult(
                "mock", "mock-model", true, false,
                "压力阈值应设定为 2.5MPa，过高会导致主轴过热。[S1]", null));
        RagResponse response = orchestrator.answer(request(RagTestFixtures.QUERY_FULL));
        assertThat(response.status()).isEqualTo(RagStatus.SUCCESS);
        assertThat(response.decision()).isEqualTo(RagDecision.ANSWER);
        assertThat(response.answer()).contains("[S1]");
        assertThat(response.citedSourceIds()).containsExactly("S1");
        assertThat(response.decisionDetail().sufficiency())
                .isEqualTo("DIRECT");
        assertThat(response.sources()).hasSize(1);
    }

    @Test
    @DisplayName("生成答案含证据外参数 → GENERATION_FAILED + REFUSE/UNSUPPORTED_CLAIM")
    void postGenerationValidationBlocks() {
        when(retrievalService.retrieve(any())).thenReturn(List.of(RagTestFixtures.chunkR2Active()));
        when(aiChatService.chat(any(ChatInvocation.class), any(ThinkingSettings.class))).thenReturn(new AiChatResult(
                "mock", "mock-model", true, false,
                "建议将压力阈值调整为 5.5MPa。[S1]", null));
        RagResponse response = orchestrator.answer(request(RagTestFixtures.QUERY_FULL));
        assertThat(response.status()).isEqualTo(RagStatus.GENERATION_FAILED);
        assertThat(response.decision()).isEqualTo(RagDecision.REFUSE);
        assertThat(response.reasonCode()).isEqualTo(RagReasonCode.UNSUPPORTED_CLAIM);
        assertThat(response.answer()).isNull();
    }

    @Test
    @DisplayName("生成异常 → GENERATION_FAILED + 受控失败")
    void generationExceptionIsControlled() {
        when(retrievalService.retrieve(any())).thenReturn(List.of(RagTestFixtures.chunkR2Active()));
        when(aiChatService.chat(any(ChatInvocation.class), any(ThinkingSettings.class))).thenThrow(new IllegalStateException("模型超时"));
        RagResponse response = orchestrator.answer(request(RagTestFixtures.QUERY_FULL));
        assertThat(response.status()).isEqualTo(RagStatus.GENERATION_FAILED);
        assertThat(response.decision()).isEqualTo(RagDecision.REFUSE);
        assertThat(response.statusMessage()).contains("模型超时");
    }

    @Test
    @DisplayName("检索异常 → RETRIEVAL_FAILED（与空检索严格区分）")
    void retrievalFailureDistinctFromEmpty() {
        when(retrievalService.retrieve(any()))
                .thenThrow(new RetrievalException(com.zl.d34.common.ResultCode.VECTOR_INDEX_FAILED,
                        "pgvector 连接失败", "SIMILARITY_SEARCH_FAILED"));
        RagResponse response = orchestrator.answer(request(RagTestFixtures.QUERY_FULL));
        assertThat(response.status()).isEqualTo(RagStatus.RETRIEVAL_FAILED);
        assertThat(response.statusMessage()).contains("pgvector 连接失败");
        verify(aiChatService, never()).chat(any(ChatInvocation.class), any(ThinkingSettings.class));
    }

    @Test
    @DisplayName("生命周期过滤后正常评估：DEPRECATED 旧版拒绝，ACTIVE 新版进入上下文")
    void lifecycleFilteringInFullFlow() {
        when(retrievalService.retrieve(any())).thenReturn(List.of(
                RagTestFixtures.chunkR2Deprecated(), RagTestFixtures.chunkR2Active()));
        when(aiChatService.chat(any(ChatInvocation.class), any(ThinkingSettings.class))).thenReturn(new AiChatResult(
                "mock", "mock-model", true, false,
                "压力阈值应设定为 2.5MPa，过高会导致主轴过热。[S1]", null));
        RagResponse response = orchestrator.answer(request(RagTestFixtures.QUERY_FULL));
        assertThat(response.status()).isEqualTo(RagStatus.SUCCESS);
        assertThat(response.decision()).isEqualTo(RagDecision.ANSWER);
        assertThat(response.sources()).hasSize(1);
        verify(aiChatService).chat(any(ChatInvocation.class), any(ThinkingSettings.class));
    }
}
