package com.zl.d33.rag;

import com.zl.d33.knowledge.retrieval.service.SimilarityRetrievalService;
import com.zl.d33.rag.dto.RagRequest;
import com.zl.d33.rag.model.RagResponse;
import com.zl.d33.rag.model.RagStatus;
import com.zl.d33.rag.service.ContextAssemblyService;
import com.zl.d33.rag.service.GenerationService;
import com.zl.d33.rag.service.RagOrchestrator;
import com.zl.d33.rag.service.RagRetrievalService;
import com.zl.d33.service.AiChatService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static com.zl.d33.rag.support.RagTestFixtures.answer;
import static com.zl.d33.rag.support.RagTestFixtures.defaultProperties;
import static com.zl.d33.rag.support.RagTestFixtures.hit;
import static com.zl.d33.rag.support.RagTestFixtures.response;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 九类场景 1：文档可答 + 来源可追溯（端到端正常流）。
 */
class RagAnswerableEndToEndTest {

    @Test
    void documentAnswerableAndSourcesTraceable() {
        SimilarityRetrievalService retrievalMock = mock(SimilarityRetrievalService.class);
        AiChatService aiMock = mock(AiChatService.class);
        when(retrievalMock.search(any())).thenReturn(response("R2 阀门如何维护", List.of(
                hit(1, "chunk-r2-01", "DOC-R2", "R2 阀门维护手册", "R2 阀门每季度润滑一次。", 0.91d),
                hit(2, "chunk-r2-02", "DOC-R2", "R2 阀门维护手册", "更换密封圈需先泄压。", 0.82d))));
        when(aiMock.chat(any(com.zl.d33.service.ChatInvocation.class),
                any(com.zl.d33.service.ThinkingSettings.class)))
                .thenReturn(answer("根据资料，R2 阀门每季度润滑一次 [S1]，更换密封圈需先泄压 [S2]。"));

        RagOrchestrator orchestrator = build(aiMock, retrievalMock, defaultProperties());

        RagResponse response = orchestrator.answer(
                new RagRequest("R2 阀门如何维护", null, null, null, null));

        assertEquals(RagStatus.SUCCESS, response.status());
        assertNotNull(response.answer());
        assertTrue(response.answer().contains("[S1]"));
        assertTrue(response.answer().contains("[S2]"));
        assertEquals(List.of("S1", "S2"), response.citedSourceIds());
        assertEquals(2, response.sources().size());
        assertEquals("DOC-R2", response.sources().get(0).documentId());
        assertEquals("chunk-r2-01", response.sources().get(0).chunkId());
        assertEquals(2, response.retrievalHitCount());
        assertEquals(2, response.contextChunkCount());
        assertEquals("COSINE_SIMILARITY", response.scoreSemantic());
        assertEquals("deepseek-flash", response.generationModel());
        assertEquals(false, response.mocked());

        // 提示词必须包含防注入约束与真实资料编号，用户问题不被拼进系统提示词
        ArgumentCaptor<com.zl.d33.service.ChatInvocation> captor =
                ArgumentCaptor.forClass(com.zl.d33.service.ChatInvocation.class);
        verify(aiMock).chat(captor.capture(), any(com.zl.d33.service.ThinkingSettings.class));
        com.zl.d33.service.ChatInvocation invocation = captor.getValue();
        assertTrue(invocation.systemPrompt().contains("不可信数据"));
        assertTrue(invocation.systemPrompt().contains("[S1] 文档：R2 阀门维护手册"));
        assertTrue(invocation.systemPrompt().contains("每季度润滑一次"));
        assertEquals("R2 阀门如何维护", invocation.userMessage());
        verify(retrievalMock).search(any());
    }

    static RagOrchestrator build(AiChatService aiMock,
                                 SimilarityRetrievalService retrievalMock,
                                 com.zl.d33.rag.config.RagProperties properties) {
        RagRetrievalService retrievalService = new RagRetrievalService(retrievalMock, properties);
        ContextAssemblyService assembly = new ContextAssemblyService(properties);
        GenerationService generation = new GenerationService(aiMock);
        return new RagOrchestrator(retrievalService, assembly, generation, properties);
    }
}
