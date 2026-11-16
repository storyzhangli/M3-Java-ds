package com.zl.d33.rag;

import com.zl.d33.knowledge.retrieval.service.SimilarityRetrievalService;
import com.zl.d33.rag.dto.RagRequest;
import com.zl.d33.rag.model.RagResponse;
import com.zl.d33.rag.model.RagStatus;
import com.zl.d33.service.AiChatService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static com.zl.d33.rag.support.RagTestFixtures.answer;
import static com.zl.d33.rag.support.RagTestFixtures.defaultProperties;
import static com.zl.d33.rag.support.RagTestFixtures.hit;
import static com.zl.d33.rag.support.RagTestFixtures.response;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 九类场景 8：重复 Chunk（同 chunkId）不重复拼接、不重复生成来源。
 */
class RagDuplicateChunkTest {

    @Test
    void duplicateChunkIdsAreDeduplicated() {
        SimilarityRetrievalService retrievalMock = mock(SimilarityRetrievalService.class);
        AiChatService aiMock = mock(AiChatService.class);
        // chunkId 重复三次（低分在前、高分在后），另有一条独立命中
        when(retrievalMock.search(any())).thenReturn(response("R2 阀门维护", List.of(
                hit(1, "chunk-r2-01", "DOC-R2", "R2 阀门维护手册", "旧版内容：每季度润滑一次。", 0.50d),
                hit(2, "chunk-r2-01", "DOC-R2", "R2 阀门维护手册", "新版内容：每季度润滑两次。", 0.91d),
                hit(3, "chunk-r2-01", "DOC-R2", "R2 阀门维护手册", "重复内容：每季度润滑两次。", 0.91d),
                hit(4, "chunk-r2-02", "DOC-R2", "R2 阀门维护手册", "更换密封圈需先泄压。", 0.82d))));
        when(aiMock.chat(any(com.zl.d33.service.ChatInvocation.class),
                any(com.zl.d33.service.ThinkingSettings.class)))
                .thenReturn(answer("每季度润滑两次 [S1]，更换密封圈需先泄压 [S2]。"));

        RagResponse response = RagAnswerableEndToEndTest
                .build(aiMock, retrievalMock, defaultProperties())
                .answer(new RagRequest("R2 阀门维护", null, null, null, null));

        assertEquals(RagStatus.SUCCESS, response.status());
        assertEquals(4, response.retrievalHitCount());
        // 去重后仅 2 条进入上下文与来源
        assertEquals(2, response.contextChunkCount());
        assertEquals(2, response.sources().size());

        // 资料块中重复 chunkId 只拼接一次，且保留高分版本内容
        ArgumentCaptor<com.zl.d33.service.ChatInvocation> captor =
                ArgumentCaptor.forClass(com.zl.d33.service.ChatInvocation.class);
        verify(aiMock).chat(captor.capture(), any(com.zl.d33.service.ThinkingSettings.class));
        String systemPrompt = captor.getValue().systemPrompt();
        assertEquals(1, countOccurrences(systemPrompt, "[S1] 文档："));
        assertEquals(1, countOccurrences(systemPrompt, "[S2] 文档："));
        assertEquals(true, systemPrompt.contains("新版内容：每季度润滑两次。"));
        assertEquals(false, systemPrompt.contains("旧版内容：每季度润滑一次。"));
    }

    private int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
