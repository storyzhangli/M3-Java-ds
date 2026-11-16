package com.zl.d33.rag;

import com.zl.d33.knowledge.retrieval.service.SimilarityRetrievalService;
import com.zl.d33.rag.dto.RagRequest;
import com.zl.d33.rag.model.RagResponse;
import com.zl.d33.rag.model.RagStatus;
import com.zl.d33.service.AiChatService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.zl.d33.rag.support.RagTestFixtures.defaultProperties;
import static com.zl.d33.rag.support.RagTestFixtures.hit;
import static com.zl.d33.rag.support.RagTestFixtures.response;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 九类场景 5：模型超时/生成异常 → GENERATION_FAILED，不伪装成功。
 */
class RagGenerationTimeoutTest {

    @Test
    void generationFailureMapsToGenerationFailed() {
        SimilarityRetrievalService retrievalMock = mock(SimilarityRetrievalService.class);
        AiChatService aiMock = mock(AiChatService.class);
        when(retrievalMock.search(any())).thenReturn(response("R2 阀门维护", List.of(
                hit(1, "chunk-r2-01", "DOC-R2", "R2 阀门维护手册",
                        "R2 阀门每季度润滑一次，更换密封圈需先泄压。", 0.90d))));
        // 模拟模型调用超时（真实超时由 ModelCallProperties 的 totalDeadline 治理）
        when(aiMock.chat(any(com.zl.d33.service.ChatInvocation.class),
                any(com.zl.d33.service.ThinkingSettings.class)))
                .thenThrow(new RuntimeException("simulated model timeout after 30s"));

        RagResponse response = RagAnswerableEndToEndTest
                .build(aiMock, retrievalMock, defaultProperties())
                .answer(new RagRequest("R2 阀门维护", null, null, null, null));

        assertEquals(RagStatus.GENERATION_FAILED, response.status());
        assertEquals(null, response.answer());
        assertTrue(response.statusMessage().contains("生成执行失败"));
        assertTrue(response.statusMessage().contains("simulated model timeout"));
        // 检索与来源信息仍完整返回，不因生成失败而丢失
        assertEquals(1, response.retrievalHitCount());
        assertEquals(1, response.sources().size());
    }
}
