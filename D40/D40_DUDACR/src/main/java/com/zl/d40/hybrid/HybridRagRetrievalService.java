package com.zl.d40.hybrid;

import com.zl.d40.hybrid.HybridSearchService;
import com.zl.d40.hybrid.dto.HybridSearchResponse;
import com.zl.d40.knowledge.retrieval.dto.RetrievalHitView;
import com.zl.d40.knowledge.retrieval.dto.RetrievalQueryRequest;
import com.zl.d40.knowledge.retrieval.service.SimilarityRetrievalService;
import com.zl.d40.rag.config.RagProperties;
import com.zl.d40.rag.dto.RagRequest;
import com.zl.d40.rag.model.RetrievedChunk;
import com.zl.d40.rag.service.RagRetrievalService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合 RAG 检索通道（ P2.4）：继承非 final 的 {@link RagRetrievalService}
 * 覆写 {@code retrieve}，把检索来源替换为「向量+关键词+RRF」；其余链路
 * （证据评估/组装/生成/校验）继续复用共享单例，零改既有类。
 *
 * <p>装配（R01 方案 B 先例，保主链路单候选）：本类<b>不注册为 Spring Bean</b>——
 * 它继承主链路注入点类型 {@link RagRetrievalService}，若进容器会让既有
 * {@code RagOrchestrator}（@Service，按 RagRetrievalService 类型注入）出现双候选歧义，
 * 违反 H1 主链路零变更。故由 {@code HybridConfiguration} 在装配 {@code HybridRagController}
 * 时手动 {@code new}（作为第二个 {@code RagOrchestrator} 的协作者），两者均不入容器。</p>
 *
 * <p>分数语义（§3.2 红线 + KP4 裁决 (a)）：映射 {@link RetrievedChunk} 时
 * {@code score} 回填<b>向量路原始余弦</b>（决策链 relevant-score=0.15 是余弦语义，
 * RRF 分不可喂入）；KEYWORD_ONLY 命中 {@code score=null}——
 * {@code RetrievedChunk.normalizedScore()} 对 null 返回 0.0，低于 0.15 被既有相关性
 * 门禁自然过滤（全滤则 LOW_RELEVANCE 拒答，属诚实结果非缺陷），<b>绝不伪造余弦值</b>。
 * 排序由 RRF 决定，进入决策链后的相关性过滤与二次排序沿用既有实现。</p>
 */
public class HybridRagRetrievalService extends RagRetrievalService {

    private final HybridSearchService hybridSearchService;

    private final RagProperties ragProperties;

    public HybridRagRetrievalService(SimilarityRetrievalService similarityRetrievalService,
                                     RagProperties properties,
                                     HybridSearchService hybridSearchService) {
        super(similarityRetrievalService, properties);
        this.ragProperties = properties;
        this.hybridSearchService = hybridSearchService;
    }

    @Override
    public List<RetrievedChunk> retrieve(RagRequest request) {
        // topK 解析逐字对齐父类：请求覆盖 > app.rag.top-k；仍为 null 时由检索服务回落
        // app.retrieval.default-top-k=5——对照组与实验组的 topK 语义完全一致（H3）
        Integer effectiveTopK = request.topK() != null ? request.topK() : ragProperties.getTopK();
        RetrievalQueryRequest queryRequest = new RetrievalQueryRequest(
                request.query(), effectiveTopK,
                request.documentId(), request.version(), request.strategy());
        HybridSearchResponse response = hybridSearchService.search(queryRequest);
        Map<String, FusionTraceEntry> traceById = new HashMap<>();
        for (FusionTraceEntry entry : response.fusionTrace()) {
            traceById.put(entry.chunkId(), entry);
        }
        List<RetrievedChunk> chunks = new ArrayList<>(response.hits().size());
        for (RetrievalHitView hit : response.hits()) {
            FusionTraceEntry trace = traceById.get(hit.chunkId());
            Double cosine = trace == null ? null : trace.vectorSimilarity();
            chunks.add(new RetrievedChunk(
                    hit.chunkId(), hit.documentId(), hit.version(), hit.chunkIndex(),
                    hit.title(), hit.pageNumbers() == null ? List.of() : hit.pageNumbers(),
                    hit.paragraphIndex(),
                    hit.strategy(), hit.content(), cosine, hit.distance(),
                    hit.lifecycleStatus(), hit.effectiveAt(), hit.expiresAt()));
        }
        return List.copyOf(chunks);
    }
}
