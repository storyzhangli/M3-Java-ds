package com.zl.d33.rag;

import com.zl.d33.knowledge.retrieval.service.SimilarityRetrievalService;
import com.zl.d33.rag.config.RagProperties;
import com.zl.d33.rag.dto.RagRequest;
import com.zl.d33.rag.model.RagResponse;
import com.zl.d33.rag.model.RagStatus;
import com.zl.d33.service.AiChatService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.zl.d33.rag.support.RagTestFixtures.answer;
import static com.zl.d33.rag.support.RagTestFixtures.contentOf;
import static com.zl.d33.rag.support.RagTestFixtures.hit;
import static com.zl.d33.rag.support.RagTestFixtures.response;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 九类场景 9：预算超限截断（可复现、可观测），来源映射不丢。
 */
class RagBudgetOverrunTest {

    @Test
    void contextIsTruncatedWithinBudgetAndSourceMappingSurvives() {
        SimilarityRetrievalService retrievalMock = mock(SimilarityRetrievalService.class);
        AiChatService aiMock = mock(AiChatService.class);
        when(retrievalMock.search(any())).thenReturn(response("R2 阀门维护", List.of(
                hit(1, "chunk-r2-01", "DOC-R2", "R2 阀门维护手册",
                        contentOf("阀门润滑要点，", 500), 0.95d),
                hit(2, "chunk-r2-02", "DOC-R2", "R2 阀门维护手册",
                        contentOf("密封圈更换步骤，", 500), 0.85d))));
        when(aiMock.chat(any(com.zl.d33.service.ChatInvocation.class),
                any(com.zl.d33.service.ThinkingSettings.class)))
                .thenReturn(answer("按资料操作即可 [S1]。"));

        RagProperties properties = new RagProperties();
        properties.setMaxContextChars(600);
        properties.validate();

        RagResponse response = RagAnswerableEndToEndTest
                .build(aiMock, retrievalMock, properties)
                .answer(new RagRequest("R2 阀门维护", null, null, null, null));

        assertEquals(RagStatus.SUCCESS, response.status());
        assertTrue(response.contextUsedChars() <= 600, "上下文不得超过预算");
        assertTrue(response.contextTruncatedChars() > 0, "必须记录截断字符数");
        // 截断不丢来源：两条命中仍全部出现在来源列表
        assertEquals(2, response.sources().size());
        assertEquals("chunk-r2-01", response.sources().get(0).chunkId());
        assertEquals("chunk-r2-02", response.sources().get(1).chunkId());
    }
}
