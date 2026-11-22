package com.zl.d39.hybrid;

import com.zl.d39.access.AccessPolicyService;
import com.zl.d39.access.AuthContext;
import com.zl.d39.access.AuthContextHolder;
import com.zl.d39.common.ResultCode;
import com.zl.d39.hybrid.config.HybridProperties;
import com.zl.d39.hybrid.dto.HybridSearchResponse;
import com.zl.d39.hybrid.keyword.KeywordMatchView;
import com.zl.d39.hybrid.keyword.KeywordSearchService;
import com.zl.d39.knowledge.retrieval.RetrievalException;
import com.zl.d39.knowledge.retrieval.config.RetrievalProperties;
import com.zl.d39.knowledge.retrieval.dto.RetrievalHitView;
import com.zl.d39.knowledge.retrieval.dto.RetrievalQueryRequest;
import com.zl.d39.knowledge.retrieval.dto.RetrievalResponse;
import com.zl.d39.knowledge.retrieval.service.SimilarityRetrievalService;
import com.zl.d39.knowledge.retrieval.support.RetrievalHitMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索编排（P2.2）：<b>串行</b>执行「向量路 → 关键词路 → RRF 融合」，
 * 单线程确定性（§3.3），并行化属性能优化、会破坏计时分段语义，实验期禁止。
 *
 * <p>向量路只读复用既有 {@link SimilarityRetrievalService}（零修改），topK=candidateTopN，
 * threshold 沿用配置 0.0；关键词路同过滤口径。任一路 FAILED 整体上抛（不伪装 EMPTY）。</p>
 *
 * <p>装配：本类挂 {@code @ConditionalOnProperty}，仅在 {@code app.hybrid.enabled=true}
 * 时注册（H2 开关默认关闭）。</p>
 */
@Service
@ConditionalOnProperty(name = "app.hybrid.enabled", havingValue = "true")
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);

    /** 响应分数语义标注：融合分不是相似度。 */
    public static final String SCORE_SEMANTIC_RRF = "RRF";

    private final SimilarityRetrievalService similarityRetrievalService;

    private final KeywordSearchService keywordSearchService;

    private final RrfFusionService rrfFusionService;

    private final HybridProperties hybridProperties;

    private final RetrievalProperties retrievalProperties;

    /** D39：权限策略端口（关闭态 bean 缺席 → scope=null → 两路行为与骨架一致，零差异）。 */
    private final ObjectProvider<AccessPolicyService> accessPolicyProvider;

    public HybridSearchService(SimilarityRetrievalService similarityRetrievalService,
                               KeywordSearchService keywordSearchService,
                               RrfFusionService rrfFusionService,
                               HybridProperties hybridProperties,
                               RetrievalProperties retrievalProperties,
                               ObjectProvider<AccessPolicyService> accessPolicyProvider) {
        this.similarityRetrievalService = similarityRetrievalService;
        this.keywordSearchService = keywordSearchService;
        this.rrfFusionService = rrfFusionService;
        this.hybridProperties = hybridProperties;
        this.retrievalProperties = retrievalProperties;
        this.accessPolicyProvider = accessPolicyProvider;
    }

    /**
     * 混合检索入口。topK 解析与越界语义对齐既有链路（1..maxTopK=50，越界抛错非钳制）。
     */
    public HybridSearchResponse search(RetrievalQueryRequest request) {
        if (request == null) {
            throw new RetrievalException(ResultCode.PARAM_INVALID, "检索请求不能为空", "REQUEST_NULL");
        }
        long start = System.nanoTime();
        String query = request.query();
        if (query == null || query.isBlank()) {
            throw new RetrievalException(ResultCode.PARAM_INVALID, "查询内容不能为空", "QUERY_BLANK");
        }
        if (query.codePointCount(0, query.length()) > retrievalProperties.getMaxQueryLength()) {
            throw new RetrievalException(ResultCode.PARAM_INVALID,
                    "查询长度超过上限 " + retrievalProperties.getMaxQueryLength() + " 码点，拒绝静默截断",
                    "QUERY_TOO_LONG");
        }
        int effectiveTopK = resolveTopK(request.topK());
        int candidateTopN = hybridProperties.getCandidateTopN();

        // D39：权限范围解析（先于双路；RRF 融合输入已双侧过滤 → 融合结果闭包于授权集合）
        AccessPolicyService policy = accessPolicyProvider == null
                ? null : accessPolicyProvider.getIfAvailable();
        AccessPolicyService.PermissionScope permissionScope = null;
        if (policy != null) {
            AuthContext context = AuthContextHolder.get();
            if (context == null) {
                throw new RetrievalException(ResultCode.PARAM_INVALID,
                        "缺少服务端可信身份，检索被拒绝", "AUTH_CONTEXT_MISSING");
            }
            permissionScope = policy.scopeFor(context);
        }

        // 1. 向量路（只读复用；RetrievalException 原样上抛=FAILED）
        long vectorStart = System.nanoTime();
        RetrievalResponse vectorResponse = similarityRetrievalService.search(
                new RetrievalQueryRequest(query, candidateTopN,
                        request.documentId(), request.version(), request.strategy()));
        long vectorElapsedMillis = vectorResponse.elapsedMillis();
        long vectorWallMillis = (System.nanoTime() - vectorStart) / 1_000_000L;
        if (vectorWallMillis > vectorElapsedMillis) {
            // 框架计时不含 HTTP/序列化之外开销时取实测值，保证耗时口径不低估
            vectorElapsedMillis = vectorWallMillis;
        }

        // 2. 关键词路（EMPTY 合法；内部错误 FAILED 上抛并标注原因码）
        long keywordStart = System.nanoTime();
        List<KeywordMatchView> keywordHits;
        try {
            keywordHits = keywordSearchService.search(query, candidateTopN,
                    request.documentId(), request.version(), request.strategy(),
                    permissionScope);
        } catch (RetrievalException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("关键词检索内部异常: type={}", ex.getClass().getSimpleName());
            throw new RetrievalException(ResultCode.VECTOR_INDEX_FAILED,
                    "关键词检索执行失败", "KEYWORD_SEARCH_FAILED", ex);
        }
        long keywordElapsedMillis = (System.nanoTime() - keywordStart) / 1_000_000L;

        // 3. RRF 融合
        long fusionStart = System.nanoTime();
        List<FusionTraceEntry> trace = rrfFusionService.fuse(
                vectorResponse.hits(), keywordHits, hybridProperties.getRrfK(), effectiveTopK);
        long fusionElapsedMillis = (System.nanoTime() - fusionStart) / 1_000_000L;

        // 4. 融合命中视图：元数据优先取向量路视图；KEYWORD_ONLY 取倒排行；
        //    score=RRF、distance 仅向量路命中携带原始余弦距离
        Map<String, RetrievalHitView> vectorById = new HashMap<>();
        for (RetrievalHitView hit : vectorResponse.hits()) {
            vectorById.put(hit.chunkId(), hit);
        }
        Map<String, KeywordMatchView> keywordById = new HashMap<>();
        for (KeywordMatchView hit : keywordHits) {
            keywordById.put(hit.chunkId(), hit);
        }
        List<RetrievalHitView> fusedHits = new ArrayList<>(trace.size());
        int rank = 1;
        for (FusionTraceEntry entry : trace) {
            RetrievalHitView base = vectorById.get(entry.chunkId());
            if (base == null) {
                KeywordMatchView kw = keywordById.get(entry.chunkId());
                if (kw == null) {
                    throw new RetrievalException(ResultCode.SYSTEM_ERROR,
                            "融合命中缺少两路来源数据: " + entry.chunkId(), "FUSION_SOURCE_MISSING");
                }
                base = new RetrievalHitView(kw.rank(), kw.chunkId(), kw.documentId(),
                        kw.version(), kw.chunkIndex(), kw.title(), kw.pageNumbers(),
                        kw.paragraphIndex(), kw.strategy(), kw.content(),
                        null, null, "RRF", null,
                        kw.lifecycleStatus(), kw.effectiveAt(), kw.expiresAt());
            }
            fusedHits.add(new RetrievalHitView(rank++, base.chunkId(), base.documentId(),
                    base.version(), base.chunkIndex(), base.title(), base.pageNumbers(),
                    base.paragraphIndex(), base.strategy(), base.content(),
                    entry.rrfScore(), base.distance(), SCORE_SEMANTIC_RRF,
                    base.distance() == null ? null : RetrievalHitMapper.DISTANCE_SEMANTIC,
                    base.lifecycleStatus(), base.effectiveAt(), base.expiresAt()));
        }
        long totalElapsedMillis = (System.nanoTime() - start) / 1_000_000L;
        log.info("混合检索完成: query=[{}], topK={}, candidates={}, fused={}, vectorMs={}, keywordMs={}, fusionMs={}",
                truncate(query, 40), effectiveTopK, candidateTopN, fusedHits.size(),
                vectorElapsedMillis, keywordElapsedMillis, fusionElapsedMillis);

        var stats = keywordSearchService.currentIndex().stats();
        return new HybridSearchResponse(
                query, request.topK(), effectiveTopK, candidateTopN,
                vectorResponse.filterDescription(), SCORE_SEMANTIC_RRF,
                fusedHits, fusedHits.size(),
                vectorResponse.hits(), keywordHits, trace,
                vectorElapsedMillis, keywordElapsedMillis, fusionElapsedMillis,
                totalElapsedMillis, vectorResponse.queryEmbedding(),
                new HybridSearchResponse.IndexInfo(stats.docCount(), stats.termCount(),
                        stats.postingCount(), stats.contentUtf8Bytes(), stats.indexUtf8Bytes(),
                        stats.buildMillis(), stats.heapDeltaBytes(),
                        keywordSearchService.currentIndex().fingerprint()));
    }

    /** topK 解析：null 用默认；越界抛错（对齐既有 TOP_K_OUT_OF_RANGE 语义）。 */
    private int resolveTopK(Integer topK) {
        int value = topK == null ? retrievalProperties.getDefaultTopK() : topK;
        if (value < 1 || value > retrievalProperties.getMaxTopK()) {
            throw new RetrievalException(ResultCode.PARAM_INVALID,
                    "topK 必须在 1.." + retrievalProperties.getMaxTopK() + " 之间，实际传入 " + value,
                    "TOP_K_OUT_OF_RANGE");
        }
        return value;
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }
}
