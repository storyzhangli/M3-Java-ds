package com.zl.d33.rag;

import com.zl.d33.knowledge.retrieval.service.SimilarityRetrievalService;
import com.zl.d33.rag.dto.RagRequest;
import com.zl.d33.rag.model.RagResponse;
import com.zl.d33.rag.model.RagStatus;
import com.zl.d33.service.AiChatService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.zl.d33.rag.support.RagTestFixtures.defaultProperties;
import static com.zl.d33.rag.support.RagTestFixtures.response;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 九类场景 3：空检索返回 NO_HIT，且可核验 ChatModel 零调用。
 */
class RagEmptyRetrievalTest {

    @Test
    void emptyRetrievalReturnsNoHitAndNeverCallsModel() {
        SimilarityRetrievalService retrievalMock = mock(SimilarityRetrievalService.class);
        AiChatService aiMock = mock(AiChatService.class);
        when(retrievalMock.search(any())).thenReturn(response("不存在的设备", List.of()));

        RagResponse response = RagAnswerableEndToEndTest
                .build(aiMock, retrievalMock, defaultProperties())
                .answer(new RagRequest("不存在的设备", null, null, null, null));

        assertEquals(RagStatus.NO_HIT, response.status());
        assertEquals(null, response.answer());
        assertEquals(0, response.retrievalHitCount());
        assertTrue(response.statusMessage().contains("无命中"));
        // 检索确实执行了一次，但模型零调用
        verify(retrievalMock).search(any());
        verifyNoInteractions(aiMock);
    }
}
