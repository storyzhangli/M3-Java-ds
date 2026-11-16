package com.zl.d33.rag;

import com.zl.d33.knowledge.retrieval.service.SimilarityRetrievalService;
import com.zl.d33.rag.config.RagProperties;
import com.zl.d33.rag.dto.RagRequest;
import com.zl.d33.rag.model.RagResponse;
import com.zl.d33.rag.model.RagStatus;
import com.zl.d33.service.AiChatService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.zl.d33.rag.support.RagTestFixtures.hit;
import static com.zl.d33.rag.support.RagTestFixtures.response;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 九类场景 4：有结果但预算无法容纳 → NO_USABLE_CONTEXT，模型零调用。
 */
class RagNoUsableContextTest {

    @Test
    void hitsExistButBudgetCannotHoldAnything() {
        SimilarityRetrievalService retrievalMock = mock(SimilarityRetrievalService.class);
        AiChatService aiMock = mock(AiChatService.class);
        when(retrievalMock.search(any())).thenReturn(response("R2 阀门维护", List.of(
                hit(1, "chunk-r2-01", "DOC-R2", "R2 阀门维护手册",
                        "R2 阀门每季度润滑一次，更换密封圈需先泄压。", 0.90d))));
        // 预算 5 字符：连编号头 "[S1] 文档：…" 都无法容纳，组装后必然不可用
        RagProperties properties = new RagProperties();
        properties.setMaxContextChars(5);
        properties.validate();

        RagResponse response = RagAnswerableEndToEndTest
                .build(aiMock, retrievalMock, properties)
                .answer(new RagRequest("R2 阀门维护", null, null, null, null));

        assertEquals(RagStatus.NO_USABLE_CONTEXT, response.status());
        assertEquals(null, response.answer());
        assertEquals(1, response.retrievalHitCount());
        assertTrue(response.statusMessage().contains("无可用上下文"));
        // 模型零调用：组装结果不可用时不进入生成阶段
        verifyNoInteractions(aiMock);
    }
}
