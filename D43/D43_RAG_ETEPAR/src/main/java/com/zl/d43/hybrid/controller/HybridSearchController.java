package com.zl.d43.hybrid.controller;

import com.zl.d43.access.AccessGuard;
import com.zl.d43.common.Result;
import com.zl.d43.hybrid.HybridSearchService;
import com.zl.d43.hybrid.dto.HybridSearchResponse;
import com.zl.d43.hybrid.keyword.InvertedIndex;
import com.zl.d43.hybrid.keyword.KeywordSearchService;
import com.zl.d43.knowledge.retrieval.dto.RetrievalQueryRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 混合检索入口（P2.3）：仅在 {@code app.hybrid.enabled=true} 时装配。
 *
 * <p>Result 包装、HTTP 恒 200、code 判成败口径与既有端点一致；
 * 响应 hits 分数语义为 RRF（见 {@link HybridSearchService#SCORE_SEMANTIC_RRF}）。</p>
 *
 * <p>search=FILTER_REQUIRED（HybridSearchService 双路前置过滤+RRF 闭包）；
 * rebuild=ADMIN_ONLY。关闭态零差异。</p>
 */
@Slf4j
@RestController
@ConditionalOnProperty(name = "app.hybrid.enabled", havingValue = "true")
public class HybridSearchController {

    private final HybridSearchService hybridSearchService;

    private final KeywordSearchService keywordSearchService;

    private final ObjectProvider<AccessGuard> accessGuardProvider;

    public HybridSearchController(HybridSearchService hybridSearchService,
                                  KeywordSearchService keywordSearchService,
                                  ObjectProvider<AccessGuard> accessGuardProvider) {
        this.hybridSearchService = hybridSearchService;
        this.keywordSearchService = keywordSearchService;
        this.accessGuardProvider = accessGuardProvider;
    }

    /**
     * 混合检索：向量 + BM25 关键词 + RRF 融合 Top-K。
     */
    @PostMapping("/api/v1/hybrid/retrieval/search")
    public Result<HybridSearchResponse> search(@RequestBody RetrievalQueryRequest request) {
        return Result.success(hybridSearchService.search(request));
    }

    /**
     * 显式重建内存倒排（P1.2：仅重载只读行集重建内存结构，零写库；指纹用于确定性核对）。
     */
    @PostMapping("/api/v1/hybrid/index/rebuild")
    public Result<RebuildView> rebuild() {
        AccessGuard guard = accessGuardProvider.getIfAvailable();
        if (guard != null) {
            guard.requireAdmin();
        }
        log.info("触发倒排重建（只读加载，零写入）");
        InvertedIndex.IndexStats stats = keywordSearchService.rebuild();
        String fingerprint = keywordSearchService.currentIndex().fingerprint();
        return Result.success(new RebuildView(stats.docCount(), stats.termCount(),
                stats.postingCount(), stats.buildMillis(), fingerprint));
    }

    /** 重建结果视图（record 强约束）。 */
    public record RebuildView(int docCount,
                              long termCount,
                              long postingCount,
                              long buildMillis,
                              String fingerprint) {
    }
}
