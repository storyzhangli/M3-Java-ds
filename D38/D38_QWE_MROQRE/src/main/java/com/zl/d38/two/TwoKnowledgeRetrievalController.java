package com.zl.d38.two;

import com.zl.d38.common.Result;
import com.zl.d38.knowledge.retrieval.dto.RetrievalQueryRequest;
import com.zl.d38.knowledge.retrieval.dto.RetrievalResponse;
import com.zl.d38.knowledge.vectorindex.dto.VectorIndexResultView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * two_ 隔离链路 HTTP 入口：与本地 {@code KnowledgeRetrievalController} 平行的新端点。
 *
 * <p>路径前缀 /api/v1/two/…，请求/响应 DTO 与本地链路完全一致（同契约对比），
 * 但底层指向 two_ 向量空间（BAILIAN / qwen3.7-text-embedding / 1024 维 /
 * two_vector_store + two_ MySQL 状态表）。本地端点零改动。</p>
 *
 * <p>检索相似度阈值沿用 app.retrieval.similarity-threshold（默认 0.0）：
 * 百炼分数分布与本地哈希模型不同，禁止照搬本地 0.15 经验阈值下结论。</p>
 */
@Slf4j
@RestController
@ConditionalOnProperty(name = "app.vectorstore.two-pgvector.enabled", havingValue = "true",
        matchIfMissing = false)
public class TwoKnowledgeRetrievalController {

    private final TwoSpaceComponents components;

    public TwoKnowledgeRetrievalController(TwoSpaceComponents components) {
        this.components = components;
    }

    /**
     * two_ 语料入库（幂等）：重复调用命中幂等短路，0 次远程 Embedding 调用。
     */
    @PostMapping("/api/v1/two/knowledge/retrieval/corpus/index")
    public Result<List<VectorIndexResultView>> indexCorpus(
            @RequestParam(name = "version", defaultValue = TwoCorpusIndexService.DEFAULT_CORPUS_VERSION)
            String version,
            @RequestParam(name = "strategy", required = false) String strategy) {
        return Result.success(components.corpusIndexService().indexCorpus(version, strategy));
    }

    /**
     * two_ Top-K 相似度检索：查询向量化与入库向量同属 two_ 空间。
     */
    @PostMapping("/api/v1/two/knowledge/retrieval/search")
    public Result<RetrievalResponse> search(@RequestBody RetrievalQueryRequest request) {
        return Result.success(components.retrievalService().search(request));
    }
}