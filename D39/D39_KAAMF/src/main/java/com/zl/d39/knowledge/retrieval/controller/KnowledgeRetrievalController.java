package com.zl.d39.knowledge.retrieval.controller;

import com.zl.d39.common.Result;
import com.zl.d39.knowledge.retrieval.dto.RetrievalQueryRequest;
import com.zl.d39.knowledge.retrieval.dto.RetrievalResponse;
import com.zl.d39.knowledge.retrieval.service.CorpusIndexService;
import com.zl.d39.knowledge.retrieval.service.SimilarityRetrievalService;
import com.zl.d39.knowledge.vectorindex.dto.VectorIndexResultView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 相似度检索入口。
 *
 * <p>只提供两个端点：检索与语料入库准备。检索只返回向量命中，不生成大模型答案，
 * 也不接入完整 RAG 问答链（边界）。</p>
 */
@Slf4j
@RestController
@ConditionalOnProperty(name = "app.vectorstore.pgvector.enabled", havingValue = "true",
        matchIfMissing = true)
public class KnowledgeRetrievalController {

    private final SimilarityRetrievalService retrievalService;

    private final CorpusIndexService corpusIndexService;

    public KnowledgeRetrievalController(SimilarityRetrievalService retrievalService,
                                        CorpusIndexService corpusIndexService) {
        this.retrievalService = retrievalService;
        this.corpusIndexService = corpusIndexService;
    }

    /**
     * Top-K 相似度检索。
     */
    @PostMapping("/api/v1/knowledge/retrieval/search")
    public Result<RetrievalResponse> search(@RequestBody RetrievalQueryRequest request) {
        return Result.success(retrievalService.search(request));
    }

    /**
     * 把 classpath 真实语料按文档幂等写入向量库（复用 D31 入库能力，仅用于准备检索数据）。
     */
    @PostMapping("/api/v1/knowledge/retrieval/corpus/index")
    public Result<List<VectorIndexResultView>> indexCorpus(
            @RequestParam(required = false) String version,
            @RequestParam(required = false) String strategy) {
        log.info("触发语料入库: version={}, strategy={}", version, strategy);
        return Result.success(corpusIndexService.indexCorpus(version, strategy));
    }
}
