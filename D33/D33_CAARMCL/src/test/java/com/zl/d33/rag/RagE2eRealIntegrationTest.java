package com.zl.d33.rag;

import com.zl.d33.knowledge.KnowledgeChunk;
import com.zl.d33.knowledge.KnowledgeCorpus;
import com.zl.d33.knowledge.retrieval.service.CorpusIndexService;
import com.zl.d33.knowledge.vectorindex.dto.VectorIndexResultView;
import com.zl.d33.knowledge.vectorindex.service.DocumentVectorIndexService;
import com.zl.d33.rag.dto.RagRequest;
import com.zl.d33.rag.model.RagResponse;
import com.zl.d33.rag.model.RagStatus;
import com.zl.d33.rag.service.RagOrchestrator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.EnabledIf;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Level 5 真实端到端：真实 PostgreSQL+pgvector + 真实 ChatModel（DeepSeek）。
 *
 * <p>门控：仅当环境变量 {@code D33_RAG_E2E=true} 时运行；同时要求环境变量
 * {@code PG_VECTOR_PASSWORD}（PG 口令）与 {@code AI_API_KEY}（DeepSeek Key）。
 * 三者任一缺失时本测试跳过，Level 5 标记为阻塞，禁止用 Mock 冒充真实端到端。</p>
 *
 * <p>数据隔离：语料以 {@code e2e-rag-} 前缀独立入库，测试结束后按 documentId
 * 清理，不污染真实语料数据。</p>
 */
@SpringBootTest(properties = {
        "app.ai.mode=deepseek",
        "app.vectorstore.pgvector.enabled=true"
})
@EnabledIf(expression = "#{systemEnvironment['D33_RAG_E2E'] == 'true'}",
        loadContext = false)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RagE2eRealIntegrationTest {

    static final String E2E_VERSION = "e2e-rag-v1";

    static final String E2E_PREFIX = "e2e-rag-";

    @Autowired
    private CorpusIndexService corpusIndexService;

    @Autowired
    private DocumentVectorIndexService vectorIndexService;

    @Autowired
    private RagOrchestrator orchestrator;

    @Autowired
    private KnowledgeCorpus corpus;

    private static final Set<String> E2E_DOC_IDS = new HashSet<>();

    @BeforeAll
    static void beforeAll() {
        // 凭据缺失时快速失败并给出明确提示，而不是在 Spring 装配阶段报晦涩错误
        String password = System.getenv("PG_VECTOR_PASSWORD");
        String apiKey = System.getenv("AI_API_KEY");
        assertNotNull(password, "缺少环境变量 PG_VECTOR_PASSWORD，无法连接真实 pgvector");
        assertNotNull(apiKey, "缺少环境变量 AI_API_KEY，无法调用真实 ChatModel");
    }

    @Test
    @Order(1)
    void indexRealCorpusWithE2ePrefix() {
        List<VectorIndexResultView> results = corpusIndexService
                .indexCorpus(E2E_VERSION, "FIXED_LENGTH", E2E_PREFIX);
        assertFalse(results.isEmpty(), "语料入库结果不能为空");
        for (VectorIndexResultView result : results) {
            assertNotNull(result.getDocumentId());
            E2E_DOC_IDS.add(result.getDocumentId());
        }
    }

    @Test
    @Order(2)
    void realRagAnswerWithTraceableSources() {
        assertFalse(E2E_DOC_IDS.isEmpty(), "必须先执行语料入库");
        // 用真实语料的第一条内容构造可答问题，避免依赖手工语料知识
        KnowledgeChunk first = corpus.allChunks().get(0);
        String question = first.title() + "的维护要点是什么？";

        RagResponse response = orchestrator.answer(new RagRequest(question, null, null, null, null));

        assertEquals(RagStatus.SUCCESS, response.status(), "真实链路应成功生成");
        assertNotNull(response.answer());
        assertFalse(response.answer().isBlank());
        assertTrue(response.retrievalHitCount() > 0, "真实向量检索应有命中");
        assertFalse(response.sources().isEmpty(), "进入上下文的来源不能为空");
        assertFalse(response.generationModel().isBlank(), "应记录真实模型标识");
        assertEquals(false, response.mocked(), "禁止 Mock 冒充真实生成");
        // 来源必须来自 e2e 前缀入库的数据，杜绝串库
        for (var source : response.sources()) {
            assertTrue(source.documentId().startsWith(E2E_PREFIX),
                    "来源必须来自 e2e 隔离前缀数据");
        }
    }

    @Test
    @Order(3)
    void cleanupE2eData() {
        for (String documentId : E2E_DOC_IDS) {
            vectorIndexService.deleteByDocument(documentId);
        }
    }
}
