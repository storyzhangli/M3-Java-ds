package com.zl.d38.experiment;

import com.zl.d38.knowledge.KnowledgeChunk;
import com.zl.d38.knowledge.KnowledgeCorpus;
import com.zl.d38.knowledge.chunking.ChunkStrategy;
import com.zl.d38.knowledge.chunking.ChunkingResult;
import com.zl.d38.knowledge.chunking.DocumentChunk;
import com.zl.d38.knowledge.chunking.DocumentChunker;
import com.zl.d38.knowledge.chunking.TextCleaner;
import com.zl.d38.knowledge.embedding.EmbeddingModelDescriptor;
import com.zl.d38.knowledge.retrieval.dto.RetrievalHitView;
import com.zl.d38.knowledge.retrieval.dto.RetrievalQueryRequest;
import com.zl.d38.knowledge.retrieval.dto.RetrievalResponse;
import com.zl.d38.knowledge.retrieval.service.QueryEmbeddingService;
import com.zl.d38.knowledge.retrieval.service.SimilarityRetrievalService;
import com.zl.d38.knowledge.vectorindex.config.VectorIndexProperties;
import com.zl.d38.knowledge.vectorindex.service.DocumentVectorIndexService;
import com.zl.d38.knowledge.vectorindex.support.ContentHasher;
import com.zl.d38.knowledge.vectorindex.support.StableChunkIdGenerator;
import com.zl.d38.knowledge.chunking.ChunkingProperties;
import com.zl.d38.knowledge.retrieval.config.RetrievalProperties;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索参数对照实验运行器（真实链路）。
 *
 * <p>仅在 pgvector 启用时装配（test profile 关闭 pgvector，故离线测试不装配本类）。
 * 生产组件零改动：运行器通过独立的 {@link ChunkingProperties}
 * 与 {@link RetrievalProperties} 实例化切分器与检索器，
 * 不修改任何全局 Bean。</p>
 *
 * <p>关键口径：{@link DocumentChunker} 产出的 chunkIndex 为 1-based，而
 * {@link DocumentVectorIndexService#indexChunks} 的 normalize 要求 0-based 连续，
 * 故运行器在入库前重新编号为 0-based（仅实验侧适配，不改生产类）。</p>
 */
@Service
@ConditionalOnProperty(name = "app.vectorstore.pgvector.enabled", havingValue = "true",
        matchIfMissing = true)
public class RetrievalExperimentRunner {

    private final KnowledgeCorpus corpus;
    private final TextCleaner textCleaner;
    private final DocumentVectorIndexService indexService;
    private final VectorStore vectorStore;
    private final QueryEmbeddingService queryEmbeddingService;
    private final EmbeddingModelDescriptor descriptor;
    private final StableChunkIdGenerator chunkIdGenerator;
    private final ContentHasher contentHasher;
    private final VectorIndexProperties vectorIndexProperties;

    public RetrievalExperimentRunner(KnowledgeCorpus corpus,
                                     TextCleaner textCleaner,
                                     DocumentVectorIndexService indexService,
                                     VectorStore vectorStore,
                                     QueryEmbeddingService queryEmbeddingService,
                                     EmbeddingModelDescriptor descriptor,
                                     StableChunkIdGenerator chunkIdGenerator,
                                     ContentHasher contentHasher,
                                     VectorIndexProperties vectorIndexProperties) {
        this.corpus = corpus;
        this.textCleaner = textCleaner;
        this.indexService = indexService;
        this.vectorStore = vectorStore;
        this.queryEmbeddingService = queryEmbeddingService;
        this.descriptor = descriptor;
        this.chunkIdGenerator = chunkIdGenerator;
        this.contentHasher = contentHasher;
        this.vectorIndexProperties = vectorIndexProperties;
    }

    /**
     * 执行完整五组实验。
     */
    public ExperimentRunResult run(RetrievalExperimentMatrix matrix,
                                   List<ExperimentQuestion> questions) {
        List<ExperimentRunResult.GroupRunResult> groupResults = new ArrayList<>();
        for (ExperimentGroup group : ExperimentGroup.values()) {
            groupResults.add(runGroup(group, matrix, questions));
        }
        return new ExperimentRunResult(matrix, List.copyOf(groupResults));
    }

    /**
     * 执行单组实验。
     */
    public ExperimentRunResult.GroupRunResult runGroup(ExperimentGroup group,
                                                       RetrievalExperimentMatrix matrix,
                                                       List<ExperimentQuestion> questions) {
        RetrievalExperimentParameters params = group.toParameters();
        params.requireValid();

        List<KnowledgeChunk> corpusChunks = corpus.allChunks();
        List<ReconstructedDocument> docs = ReconstructedDocument.rebuild(corpusChunks, group);
        Map<String, ReconstructedDocument> docByExperimentId = new LinkedHashMap<>();
        for (ReconstructedDocument doc : docs) {
            docByExperimentId.put(doc.source().documentId(), doc);
        }

        DocumentChunker chunker = new DocumentChunker(textCleaner, params.toChunkingProperties());

        int indexedChunkCount = 0;
        List<ChunkIdMappingRecord.Entry> mappingEntries = new ArrayList<>();
        for (ReconstructedDocument doc : docs) {
            ChunkingResult chunkingResult;
            try {
                chunkingResult = chunker.chunk(doc.source(), ChunkStrategy.FIXED_LENGTH);
            } catch (RuntimeException ex) {
                throw new ExperimentException(ExperimentReasonCode.CHUNKING_FAILED,
                        "组 " + group.name() + " 重新切分失败: " + doc.originalDocumentId(), ex);
            }
            List<DocumentChunk> reindexed = reindexZeroBased(chunkingResult.chunks());

            try {
                indexService.indexChunks(doc.source().documentId(), group.experimentVersion(),
                        ChunkStrategy.FIXED_LENGTH.name(), doc.source().title(), reindexed);
            } catch (RuntimeException ex) {
                throw new ExperimentException(ExperimentReasonCode.INDEX_FAILED,
                        "组 " + group.name() + " 入库失败: " + doc.source().documentId(), ex);
            }

            for (DocumentChunk chunk : reindexed) {
                String vectorChunkId = computeVectorChunkId(group, chunk);
                List<String> logicalIds = doc.mapPagesToLogicalChunkIds(chunk.pageNumbers());
                mappingEntries.add(new ChunkIdMappingRecord.Entry(
                        vectorChunkId, chunk.documentId(), chunk.chunkIndex(),
                        List.copyOf(chunk.pageNumbers()), logicalIds));
            }
            indexedChunkCount += reindexed.size();
        }

        SimilarityRetrievalService retrieval = new SimilarityRetrievalService(
                vectorStore, queryEmbeddingService, params.toRetrievalProperties());

        // 组内预热一次（不计入统计）。
        searchOnce(retrieval, questions.get(0), group);

        List<QuestionExperimentRecord> questionRecords = new ArrayList<>();
        List<Double> allLatencies = new ArrayList<>();
        for (ExperimentQuestion question : questions) {
            List<QuestionRunRecord> runs = new ArrayList<>();
            for (int runIndex = 1; runIndex <= matrix.repeatRuns(); runIndex++) {
                long start = System.nanoTime();
                RetrievalResponse response = searchOnce(retrieval, question, group);
                double elapsedMs = (System.nanoTime() - start) / 1_000_000.0;

                List<ReturnedHitRecord> hitRecords = new ArrayList<>();
                for (RetrievalHitView hit : response.hits()) {
                    hitRecords.add(toReturnedHit(hit, group, docByExperimentId));
                }
                runs.add(new QuestionRunRecord(runIndex, elapsedMs,
                        response.effectiveTopK(), response.hitCount(), hitRecords));
                allLatencies.add(elapsedMs);
            }
            questionRecords.add(QuestionExperimentRecord.build(question, runs));
        }

        GroupExperimentSummary summary = GroupExperimentSummary.build(
                group, params, indexedChunkCount, questionRecords, allLatencies);
        if (params.topK() > indexedChunkCount) {
            summary.reasonCodes().add(ExperimentReasonCode.K_EXCEEDS_INDEXED_CHUNKS.code());
        }
        ChunkIdMappingRecord mapping = new ChunkIdMappingRecord(
                group.name(), List.copyOf(mappingEntries));
        return new ExperimentRunResult.GroupRunResult(summary, questionRecords, mapping);
    }

    private RetrievalResponse searchOnce(SimilarityRetrievalService retrieval,
                                         ExperimentQuestion question,
                                         ExperimentGroup group) {
        try {
            RetrievalQueryRequest request = new RetrievalQueryRequest(
                    question.question(), group.topK(), null,
                    group.experimentVersion(), "FIXED_LENGTH");
            return retrieval.search(request);
        } catch (RuntimeException ex) {
            throw new ExperimentException(ExperimentReasonCode.SEARCH_FAILED,
                    "组 " + group.name() + " 检索失败: " + question.id(), ex);
        }
    }

    private ReturnedHitRecord toReturnedHit(RetrievalHitView hit,
                                            ExperimentGroup group,
                                            Map<String, ReconstructedDocument> docByExperimentId) {
        if (!hit.documentId().startsWith(group.documentIdPrefix())) {
            throw new ExperimentException(ExperimentReasonCode.GROUP_ISOLATION_CONFLICT,
                    "组 " + group.name() + " 检索到非本组命名空间文档: " + hit.documentId());
        }
        ReconstructedDocument doc = docByExperimentId.get(hit.documentId());
        if (doc == null) {
            throw new ExperimentException(ExperimentReasonCode.GROUP_ISOLATION_CONFLICT,
                    "组 " + group.name() + " 命中文档未登记: " + hit.documentId());
        }
        List<String> logicalIds = doc.mapPagesToLogicalChunkIds(hit.pageNumbers());
        return new ReturnedHitRecord(hit.rank(), hit.chunkId(), hit.documentId(),
                hit.chunkIndex(), logicalIds, hit.score(), hit.distance());
    }

    private String computeVectorChunkId(ExperimentGroup group, DocumentChunk chunk) {
        return chunkIdGenerator.generate(
                chunk.documentId(),
                group.experimentVersion(),
                ChunkStrategy.FIXED_LENGTH.name(),
                chunk.chunkIndex(),
                contentHasher.hash(chunk.content()),
                vectorIndexProperties.getSplitVersion(),
                descriptor.vectorSpaceId());
    }

    /**
     * 把 1-based 切分产物重新编号为 0-based 连续（实验侧适配，保留其余字段）。
     */
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
