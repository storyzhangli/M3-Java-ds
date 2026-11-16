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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 九类场景 2：无关问题不得强行拼答案（全部命中低于 min-score → NO_USABLE_CONTEXT）。
 */
class RagIrrelevantQuestionTest {

    @Test
    void irrelevantHitsBelowMinScoreDoNotForceAnswer() {
        SimilarityRetrievalService retrievalMock = mock(SimilarityRetrievalService.class);
        AiChatService aiMock = mock(AiChatService.class);
        when(retrievalMock.search(any())).thenReturn(response("今天天气如何", List.of(
                hit(1, "chunk-irrelevant-01", "DOC-R2", "R2 阀门维护手册",
                        "R2 阀门每季度润滑一次。", 0.01d))));
        // min-score=0.05：命中分数 0.01 全被过滤，不得进入生成阶段
        var properties = defaultProperties();
        properties.setMinScore(0.05d);
        properties.validate();

        RagResponse response = RagAnswerableEndToEndTest
                .build(aiMock, retrievalMock, properties)
                .answer(new RagRequest("今天天气如何", null, null, null, null));

        assertEquals(RagStatus.NO_USABLE_CONTEXT, response.status());
        assertEquals(null, response.answer());
        assertEquals(1, response.retrievalHitCount());
        assertEquals(0, response.contextChunkCount());
        assertTrue(response.statusMessage().contains("无可用上下文"));
        // 模型零调用：不凭常识补造回答
        verifyNoInteractions(aiMock);
    }
}
