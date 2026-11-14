package com.zl.d31.knowledge.vectorstore;

import com.zl.d31.common.ResultCode;
import com.zl.d31.knowledge.chunking.DocumentChunk;
import com.zl.d31.knowledge.embedding.EmbeddingException;
import com.zl.d31.knowledge.embedding.EmbeddingModelDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 知识 Chunk 向量持久化与 COSINE 相似度检索服务。
 *
 * <p>写入时把 D29 的 {@link DocumentChunk} 转换为 Spring AI Document（id 为 UUID，
 * 溯源信息全部进入 metadata），由注入的 PgVectorStore 使用同一
 * LocalHashNgramEmbeddingModel（确定性 384 维）生成向量后持久化；检索时按
 * COSINE 相似度返回 topK 命中。</p>
 */
@Service
@ConditionalOnProperty(name = "app.vectorstore.pgvector.enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeVectorStoreService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeVectorStoreService.class);

    private static final int DEFAULT_TOP_K = 4;
    private static final int MAX_TOP_K = 50;

    private final VectorStore vectorStore;
    private final EmbeddingModelDescriptor descriptor;

    public KnowledgeVectorStoreService(VectorStore vectorStore,
                                       EmbeddingModelDescriptor descriptor) {
        this.vectorStore = vectorStore;
        this.descriptor = descriptor;
    }

    /**
     * 将 Chunk 列表持久化到 pgvector 向量库，返回实际写入条数。
     */
    public int storeChunks(List<DocumentChunk> chunks) {
        requireChunks(chunks);
        List<Document> documents = new ArrayList<>(chunks.size());
        for (DocumentChunk chunk : chunks) {
            documents.add(toVectorDocument(chunk));
        }
        vectorStore.add(documents);
        log.info("向量库写入完成: chunks={}, vectorSpace={}, dimensions={}",
                documents.size(), descriptor.vectorSpaceId(), descriptor.dimensions());
        return documents.size();
    }

    /**
     * COSINE 相似度检索；topK 传 0 或负数时使用默认值，上限 50。
     */
    public List<VectorSearchHit> search(String query, int topK, Double similarityThreshold) {
        if (query == null || query.isBlank()) {
            throw new EmbeddingException(ResultCode.EMBEDDING_INPUT_INVALID, "检索 Query 不能为空");
        }
        int effectiveTopK = topK <= 0 ? DEFAULT_TOP_K : Math.min(topK, MAX_TOP_K);
        double threshold = similarityThreshold == null
                ? SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL
                : similarityThreshold;
        SearchRequest request = SearchRequest.builder()
                .query(query.strip())
                .topK(effectiveTopK)
                .similarityThreshold(threshold)
                .build();
        List<Document> hits = vectorStore.similaritySearch(request);
        List<VectorSearchHit> result = hits.stream().map(VectorSearchHit::from).toList();
        log.info("向量检索完成: query=[{}], topK={}, hits={}",
                truncate(query.strip(), 40), effectiveTopK, result.size());
        return result;
    }

    private void requireChunks(List<DocumentChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            throw new EmbeddingException(ResultCode.EMBEDDING_INPUT_INVALID,
                    "待写入向量库的 Chunk 列表不能为空");
        }
        for (DocumentChunk chunk : chunks) {
            if (chunk == null || chunk.content() == null || chunk.content().isBlank()) {
                throw new EmbeddingException(ResultCode.EMBEDDING_INPUT_INVALID,
                        "存在空 Chunk 或空内容，拒绝写入向量库");
            }
        }
    }

    private Document toVectorDocument(DocumentChunk chunk) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("documentId", chunk.documentId());
        metadata.put("title", chunk.title());
        metadata.put("version", chunk.version());
        metadata.put("chunkIndex", chunk.chunkIndex());
        metadata.put("strategy", chunk.strategy() == null ? null : chunk.strategy().name());
        metadata.put("pageNumbers", chunk.pageNumbers());
        metadata.put("characterCount", chunk.characterCount());
        metadata.put("vectorSpaceId", descriptor.vectorSpaceId());
        metadata.put("modelName", descriptor.modelName());
        return new Document(UUID.randomUUID().toString(), chunk.content(), metadata);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }
}
