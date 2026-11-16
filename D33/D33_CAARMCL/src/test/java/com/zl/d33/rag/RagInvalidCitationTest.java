package com.zl.d33.rag;

import com.zl.d33.knowledge.retrieval.service.SimilarityRetrievalService;
import com.zl.d33.rag.dto.RagRequest;
import com.zl.d33.rag.model.RagResponse;
import com.zl.d33.rag.model.RagStatus;
import com.zl.d33.service.AiChatService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.zl.d33.rag.support.RagTestFixtures.answer;
import static com.zl.d33.rag.support.RagTestFixtures.defaultProperties;
import static com.zl.d33.rag.support.RagTestFixtures.hit;
import static com.zl.d33.rag.support.RagTestFixtures.response;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 九类场景 7：模型返回未分配编号（如 [S99]）→ 非法引用被清理，来源不出现虚假条目。
 */
class RagInvalidCitationTest {

    @Test
    void unknownCitationMarkersAreStrippedAndNoFakeSourceLeaks() {
        SimilarityRetrievalService retrievalMock = mock(SimilarityRetrievalService.class);
        AiChatService aiMock = mock(AiChatService.class);
        when(retrievalMock.search(any())).thenReturn(response("R2 阀门维护", List.of(
                hit(1, "chunk-r2-01", "DOC-R2", "R2 阀门维护手册", "R2 阀门每季度润滑一次。", 0.91d),
                hit(2, "chunk-r2-02", "DOC-R2", "R2 阀门维护手册", "更换密封圈需先泄压。", 0.82d))));
        when(aiMock.chat(any(com.zl.d33.service.ChatInvocation.class),
                any(com.zl.d33.service.ThinkingSettings.class)))
                .thenReturn(answer(
                        "每季度润滑一次 [S1]。额外补充：参考 [S99] 的说明，另见 [S5] 与 [S2]。"));

        RagResponse response = RagAnswerableEndToEndTest
                .build(aiMock, retrievalMock, defaultProperties())
                .answer(new RagRequest("R2 阀门维护", null, null, null, null));

        assertEquals(RagStatus.SUCCESS, response.status());
        assertTrue(response.answer().contains("[S1]"));
        assertTrue(response.answer().contains("[S2]"));
        assertFalse(response.answer().contains("[S99]"));
        assertFalse(response.answer().contains("[S5]"));
        assertEquals(2, response.droppedUnknownMarkers());
        assertEquals(List.of("S1", "S2"), response.citedSourceIds());
        assertEquals(2, response.sources().size());
        assertEquals(2, response.citedSources().size());
    }
}
