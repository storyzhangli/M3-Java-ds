package com.zl.d43.rerank.experiment;

import com.zl.d43.experiment.ExperimentQuestion;
import com.zl.d43.experiment.ReconstructedDocument;
import com.zl.d43.experiment.RetrievalExperimentParameters;
import com.zl.d43.knowledge.KnowledgeChunk;
import com.zl.d43.knowledge.KnowledgeCorpus;
import com.zl.d43.knowledge.chunking.ChunkSourceDocument;
import com.zl.d43.knowledge.chunking.ChunkStrategy;
import com.zl.d43.knowledge.chunking.ChunkingResult;
import com.zl.d43.knowledge.chunking.DocumentChunk;
import com.zl.d43.knowledge.chunking.DocumentChunker;
import com.zl.d43.knowledge.chunking.DocumentPageText;
import com.zl.d43.knowledge.chunking.TextCleaner;
import com.zl.d43.knowledge.embedding.EmbeddingModelDescriptor;
import com.zl.d43.knowledge.retrieval.dto.RetrievalHitView;
import com.zl.d43.knowledge.retrieval.dto.RetrievalQueryRequest;
import com.zl.d43.knowledge.retrieval.dto.RetrievalResponse;
import com.zl.d43.knowledge.retrieval.service.QueryEmbeddingService;
import com.zl.d43.knowledge.retrieval.service.SimilarityRetrievalService;
import com.zl.d43.knowledge.vectorindex.config.VectorIndexProperties;
import com.zl.d43.knowledge.vectorindex.service.DocumentVectorIndexService;
import com.zl.d43.knowledge.vectorindex.support.ContentHasher;
import com.zl.d43.knowledge.vectorindex.support.StableChunkIdGenerator;
import com.zl.d43.rerank.BailianTextReranker;
import com.zl.d43.rerank.LocalLexicalReranker;
import com.zl.d43.rerank.RerankCandidate;
import com.zl.d43.rerank.RerankOutcome;
import com.zl.d43.rerank.RerankPort;
import com.zl.d43.rerank.RerankReasonCode;
import com.zl.d43.rerank.RerankRequest;
import com.zl.d43.rerank.RerankedHit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.zl.d43.rerank.experiment.RerankDecisionEvaluator;
import com.zl.d43.rerank.experiment.RerankExperimentGroup;
import com.zl.d43.rerank.experiment.RerankGroupSummary;
import com.zl.d43.rerank.experiment.RerankRunDetail;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Rerank 价值评估实验运行器（R0–R3 冻结矩阵，真实 PG 检索链路）。
 *
 * <p>与 D36 RetrievalExperimentRunner 同构：独立 Chunking/Retrieval 参数实例，
 * 不触碰生产 Bean；索引命名空间 d43rr-base-* / rr-v1，与既有实验行隔离。
 * R3 远程组在装配缺失（mock 轮）时记 BLOCKED=RERANK_REMOTE_DISABLED，零外呼。</p>
 */
@Service
@ConditionalOnProperty(name = "app.vectorstore.pgvector.enabled", havingValue = "true")
public class RerankValueExperimentRunner {

    public static final String DOC_PREFIX = "d43rr-base-";
    public static final String INDEX_VERSION = "rr-v1";
    public static final int CHUNK_SIZE = 480;
    public static final int OVERLAP = 60;

    private final KnowledgeCorpus corpus;
    private final TextCleaner textCleaner;
    private final DocumentVectorIndexService indexService;
    private final org.springframework.ai.vectorstore.VectorStore vectorStore;
    private final QueryEmbeddingService queryEmbeddingService;
    private final EmbeddingModelDescriptor descriptor;
    private final StableChunkIdGenerator chunkIdGenerator;
    private final ContentHasher contentHasher;
    private final VectorIndexProperties vectorIndexProperties;
    private final LocalLexicalReranker localReranker;
    private final ObjectProvider<BailianTextReranker> remoteRerankerProvider;

    public RerankValueExperimentRunner(KnowledgeCorpus corpus,
                                       TextCleaner textCleaner,
                                       DocumentVectorIndexService indexService,
                                       org.springframework.ai.vectorstore.VectorStore vectorStore,
                                       QueryEmbeddingService queryEmbeddingService,
                                       EmbeddingModelDescriptor descriptor,
                                       StableChunkIdGenerator chunkIdGenerator,
                                       ContentHasher contentHasher,
                                       VectorIndexProperties vectorIndexProperties,
                                       LocalLexicalReranker localReranker,
                                       ObjectProvider<BailianTextReranker> remoteRerankerProvider) {
        this.corpus = corpus;
        this.textCleaner = textCleaner;
        this.indexService = indexService;
        this.vectorStore = vectorStore;
        this.queryEmbeddingService = queryEmbeddingService;
        this.descriptor = descriptor;
        this.chunkIdGenerator = chunkIdGenerator;
        this.contentHasher = contentHasher;
        this.vectorIndexProperties = vectorIndexProperties;
        this.localReranker = localReranker;
        this.remoteRerankerProvider = remoteRerankerProvider;
    }

    /** 实验产出聚合（供 ResultWriter 与断言）。 */
    public record Outcome(List<RerankRunDetail> runs,
                          List<RerankGroupSummary> summaries,
                          List<RerankDecisionRecord> decisions,
                          List<Map<String, Object>> chunkIdMapping,
                          Map<String, Object> indexInfo) {
    }

    public Outcome run(List<ExperimentQuestion> questions, int repeatRuns) {
        IndexBuild built = buildIndex();
        List<RerankRunDetail> runs = new ArrayList<>();
        List<RerankGroupSummary> summaries = new ArrayList<>();

        for (RerankExperimentGroup group : RerankExperimentGroup.values()) {
            if (group == RerankExperimentGroup.R3
                    && remoteRerankerProvider.getIfAvailable() == null) {
                summaries.add(RerankGroupSummary.blocked(group,
                        RerankReasonCode.RERANK_REMOTE_DISABLED.code()));
                continue;
            }
            summaries.add(runGroup(group, questions, repeatRuns, built, runs));
        }

        RerankGroupSummary baseline = summaries.stream()
                .filter(s -> s.group().equals(RerankExperimentGroup.R0.name()))
                .findFirst().orElseThrow();
        List<RerankGroupSummary> arms = summaries.stream()
                .filter(s -> !s.group().equals(R0_NAME)).toList();
        List<RerankDecisionRecord> decisions = RerankDecisionEvaluator.evaluate(baseline, arms);

        Map<String, Object> indexInfo = new LinkedHashMap<>(built.info());
        indexInfo.put("repeatRuns", repeatRuns);
        indexInfo.put("questionCount", questions.size());
        return new Outcome(List.copyOf(runs), List.copyOf(summaries),
                decisions, built.mapping(), Map.copyOf(indexInfo));
    }

    private static final String R0_NAME = RerankExperimentGroup.R0.name();

    private record IndexBuild(Map<String, ReconstructedDocument> docByExperimentId,
                              List<Map<String, Object>> mapping,
                              int indexedChunks,
                              Map<String, Object> info) {
    }

    /** 冻结基线索引：重建 → 480/60 重切 → 幂等入库 → chunkId 映射登记。 */
    private IndexBuild buildIndex() {
        List<ReconstructedDocument> docs = rebuildWithNamespace(corpus.allChunks());
        Map<String, ReconstructedDocument> byExpId = new LinkedHashMap<>();
        for (ReconstructedDocument d : docs) {
            byExpId.put(d.source().documentId(), d);
        }
        RetrievalExperimentParameters base = new RetrievalExperimentParameters(
                "RERANK-BASE", CHUNK_SIZE, OVERLAP, 20, 0.0, "rerank 冻结基线索引");
        DocumentChunker chunker = new DocumentChunker(textCleaner, base.toChunkingProperties());

        List<Map<String, Object>> mapping = new ArrayList<>();
        int total = 0;
        for (ReconstructedDocument doc : docs) {
            ChunkingResult chunking = chunker.chunk(doc.source(), ChunkStrategy.FIXED_LENGTH);
            List<DocumentChunk> reindexed = reindexZeroBased(chunking.chunks());
            indexService.indexChunks(doc.source().documentId(), INDEX_VERSION,
                    ChunkStrategy.FIXED_LENGTH.name(), doc.source().title(), reindexed);
            for (DocumentChunk chunk : reindexed) {
                String vectorChunkId = chunkIdGenerator.generate(
                        chunk.documentId(), INDEX_VERSION,
                        ChunkStrategy.FIXED_LENGTH.name(), chunk.chunkIndex(),
                        contentHasher.hash(chunk.content()),
                        vectorIndexProperties.getSplitVersion(), descriptor.vectorSpaceId());
                List<String> logicalIds = doc.mapPagesToLogicalChunkIds(chunk.pageNumbers());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("vectorChunkId", vectorChunkId);
                row.put("experimentDocumentId", chunk.documentId());
                row.put("chunkIndex", chunk.chunkIndex());
                row.put("logicalChunkIds", logicalIds);
                mapping.add(row);
            }
            total += reindexed.size();
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("documentIdPrefix", DOC_PREFIX);
        info.put("version", INDEX_VERSION);
        info.put("strategy", ChunkStrategy.FIXED_LENGTH.name());
        info.put("chunkSize", CHUNK_SIZE);
        info.put("overlap", OVERLAP);
        info.put("embedding", "local-hash-ngram-v1/384/COSINE");
        info.put("indexedDocuments", docs.size());
        info.put("indexedChunks", total);
        return new IndexBuild(Map.copyOf(byExpId), List.copyOf(mapping), total, Map.copyOf(info));
    }

    private RerankGroupSummary runGroup(RerankExperimentGroup group,
                                        List<ExperimentQuestion> questions,
                                        int repeatRuns,
                                        IndexBuild built,
                                        List<RerankRunDetail> runsOut) {
        RetrievalExperimentParameters params = new RetrievalExperimentParameters(
                group.name(), CHUNK_SIZE, OVERLAP, group.candidateTopK(), 0.0,
                group.description());
        SimilarityRetrievalService retrieval = new SimilarityRetrievalService(
                vectorStore, queryEmbeddingService, params.toRetrievalProperties());
        RerankPort port = switch (group) {
            case R0 -> null;
            case R1, R2 -> localReranker;
            case R3 -> remoteRerankerProvider.getIfAvailable();
        };

        searchOnce(retrieval, questions.get(0), group); // 预热一次，不计入统计

        List<Double> mrrs = new ArrayList<>();
        List<Double> hits = new ArrayList<>();
        List<Double> noises = new ArrayList<>();
        List<Double> totals = new ArrayList<>();
        List<Double> reranks = new ArrayList<>();
        int failCount = 0;
        int runCount = 0;
        boolean divergenceFree = true;

        for (ExperimentQuestion q : questions) {
            List<String> firstTop5 = null;
            for (int runIndex = 1; runIndex <= repeatRuns; runIndex++) {
                long t0 = System.nanoTime();
                RetrievalResponse response = searchOnce(retrieval, q, group);
                List<RetrievalHitView> views = response.hits();
                List<RerankCandidate> cands = new ArrayList<>();
                for (RetrievalHitView h : views) {
                    if (!h.documentId().startsWith(DOC_PREFIX)) {
                        throw new IllegalStateException("命名空间隔离破坏: " + h.documentId());
                    }
                    cands.add(new RerankCandidate(h.chunkId(), h.documentId(),
                            h.content() == null ? "" : h.content(), h.rank(), h.score()));
                }
                double rerankMs = 0.0;
                Integer tokens = null;
                String reason = null;
                List<RerankedHit> ordered = new ArrayList<>();
                Map<String, RetrievalHitView> viewByChunk = new LinkedHashMap<>();
                for (RetrievalHitView h : views) {
                    viewByChunk.put(h.chunkId(), h);
                }
                if (port == null) {
                    int lim = Math.min(group.finalTopK(), cands.size());
                    for (int i = 0; i < lim; i++) {
                        RerankCandidate c = cands.get(i);
                        ordered.add(new RerankedHit(c.chunkId(), i + 1,
                                c.originalRank(), c.cosineScore() == null ? -1 : c.cosineScore(),
                                c.cosineScore()));
                    }
                } else {
                    long r0 = System.nanoTime();
                    int effTopN = cands.isEmpty()
                            ? group.finalTopK()
                            : Math.min(group.finalTopK(), cands.size());
                    RerankOutcome outcome = port.rerank(new RerankRequest(
                            q.id(), q.question(), cands, effTopN));
                    rerankMs = (System.nanoTime() - r0) / 1_000_000.0;
                    tokens = outcome.stats().totalTokens();
                    reason = outcome.reasonCode();
                    if (outcome.failed()) {
                        failCount++;
                    } else {
                        ordered = outcome.hits();
                    }
                }
                List<String> top5Chunks = ordered.stream().map(RerankedHit::chunkId).toList();
                if (firstTop5 == null) {
                    firstTop5 = top5Chunks;
                } else if (!firstTop5.equals(top5Chunks)) {
                    divergenceFree = false;
                }
                List<String> logicalIds = new ArrayList<>();
                for (RerankedHit hit : ordered) {
                    RetrievalHitView view = viewByChunk.get(hit.chunkId());
                    if (view == null) {
                        continue;
                    }
                    ReconstructedDocument doc = built.docByExperimentId().get(view.documentId());
                    for (String lid : doc.mapPagesToLogicalChunkIds(view.pageNumbers())) {
                        if (!logicalIds.contains(lid)) {
                            logicalIds.add(lid);
                        }
                    }
                }
                double rr = RerankMetricsCalculator.mrrAtK(q.expectedChunkIds(), logicalIds, 5);
                double totalMs = (System.nanoTime() - t0) / 1_000_000.0;
                int noise = 0;
                for (String lid : logicalIds) {
                    if (!q.expectedChunkIds().contains(lid)) {
                        noise++;
                    }
                }
                mrrs.add(rr);
                hits.add(rr > 0 ? 1.0 : 0.0);
                noises.add((double) noise);
                totals.add(totalMs);
                reranks.add(rerankMs);
                runCount++;
                runsOut.add(new RerankRunDetail(q.id(), group.name(), runIndex,
                        top5Chunks, List.copyOf(logicalIds), rr > 0, rr,
                        totalMs, rerankMs, cands.size(), tokens, reason));
            }
        }
        return new RerankGroupSummary(group.name(), "EXECUTED", questions.size(), runCount,
                RerankMetricsCalculator.mean(mrrs), RerankMetricsCalculator.mean(hits),
                RerankMetricsCalculator.mean(noises),
                RerankMetricsCalculator.percentile(totals, 0.50),
                RerankMetricsCalculator.percentile(totals, 0.95),
                RerankMetricsCalculator.percentile(reranks, 0.95),
                failCount, divergenceFree, null);
    }

    private RetrievalResponse searchOnce(SimilarityRetrievalService retrieval,
                                         ExperimentQuestion question,
                                         RerankExperimentGroup group) {
        return retrieval.search(new RetrievalQueryRequest(
                question.question(), group.candidateTopK(), null,
                INDEX_VERSION, ChunkStrategy.FIXED_LENGTH.name()));
    }

    /** 与 D36 ReconstructedDocument.rebuild 同构，命名空间参数化为 d43rr-base-/rr-v1。 */
    static List<ReconstructedDocument> rebuildWithNamespace(List<KnowledgeChunk> corpusChunks) {
        Map<String, List<KnowledgeChunk>> byDocument = new LinkedHashMap<>();
        for (KnowledgeChunk chunk : corpusChunks) {
            byDocument.computeIfAbsent(chunk.documentId(), k -> new ArrayList<>()).add(chunk);
        }
        List<ReconstructedDocument> documents = new ArrayList<>();
        for (Map.Entry<String, List<KnowledgeChunk>> entry : byDocument.entrySet()) {
            String originalDocumentId = entry.getKey();
            List<KnowledgeChunk> chunks = entry.getValue().stream()
                    .sorted((a, b) -> Integer.compare(requirePage(a), requirePage(b)))
                    .toList();
            List<DocumentPageText> pages = new ArrayList<>();
            Map<Integer, String> mapping = new LinkedHashMap<>();
            String title = null;
            for (KnowledgeChunk chunk : chunks) {
                int page = requirePage(chunk);
                if (mapping.containsKey(page)) {
                    throw new IllegalStateException("重复页码 " + page + " 文档 " + originalDocumentId);
                }
                pages.add(new DocumentPageText(page, chunk.excerpt()));
                mapping.put(page, chunk.chunkId());
                title = chunk.title();
            }
            ChunkSourceDocument source = new ChunkSourceDocument(
                    DOC_PREFIX + originalDocumentId, title, INDEX_VERSION, pages);
            documents.add(new ReconstructedDocument(source, originalDocumentId, Map.copyOf(mapping)));
        }
        return List.copyOf(documents);
    }

    private static int requirePage(KnowledgeChunk chunk) {
        if (chunk.page() == null) {
            throw new IllegalStateException("片段缺少物理页码: " + chunk.chunkId());
        }
        return chunk.page();
    }

    static List<DocumentChunk> reindexZeroBased(List<DocumentChunk> chunks) {
        List<DocumentChunk> result = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk c = chunks.get(i);
            result.add(new DocumentChunk(
                    c.documentId(), c.title(), c.version(), i, c.strategy(), c.content(),
                    c.characterCount(), c.pageNumbers(), c.sources(), c.endBoundary(),
                    c.lifecycleStatus(), c.effectiveAt(), c.expiresAt()));
        }
        return List.copyOf(result);
    }
}