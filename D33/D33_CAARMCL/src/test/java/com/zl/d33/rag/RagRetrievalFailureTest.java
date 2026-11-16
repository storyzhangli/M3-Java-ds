package com.zl.d33.rag;

import com.zl.d33.common.ResultCode;
import com.zl.d33.knowledge.retrieval.RetrievalException;
import com.zl.d33.knowledge.retrieval.service.SimilarityRetrievalService;
import com.zl.d33.rag.dto.RagRequest;
import com.zl.d33.rag.model.RagResponse;
import com.zl.d33.rag.model.RagStatus;
import com.zl.d33.service.AiChatService;
import org.junit.jupiter.api.Test;

import static com.zl.d33.rag.support.RagTestFixtures.defaultProperties;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 九类场景 6：检索基础设施异常 → RETRIEVAL_FAILED，不得伪装成空检索。
 */
class RagRetrievalFailureTest {

    @Test
    void retrievalFailureIsNotMaskedAsEmptyRetrieval() {
        SimilarityRetrievalService retrievalMock = mock(SimilarityRetrievalService.class);
        AiChatService aiMock = mock(AiChatService.class);
        when(retrievalMock.search(any())).thenThrow(new RetrievalException(
                ResultCode.VECTOR_INDEX_FAILED, "pgvector 连接失败", "SIMILARITY_SEARCH_FAILED"));

        RagResponse response = RagAnswerableEndToEndTest
                .build(aiMock, retrievalMock, defaultProperties())
                .answer(new RagRequest("R2 阀门维护", null, null, null, null));

        assertEquals(RagStatus.RETRIEVAL_FAILED, response.status());
        // 与空检索严格区分：不得返回 NO_HIT
        assertNotEquals(RagStatus.NO_HIT, response.status());
        assertEquals(null, response.answer());
        assertTrue(response.statusMessage().contains("检索执行失败"));
        // 检索失败同样不进入生成阶段
        verifyNoInteractions(aiMock);
    }
}
