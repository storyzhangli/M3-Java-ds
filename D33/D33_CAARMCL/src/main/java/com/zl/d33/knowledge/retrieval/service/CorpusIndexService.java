package com.zl.d33.knowledge.retrieval.service;

import com.zl.d33.knowledge.KnowledgeChunk;
import com.zl.d33.knowledge.KnowledgeCorpus;
import com.zl.d33.knowledge.chunking.ChunkBoundaryType;
import com.zl.d33.knowledge.chunking.ChunkSource;
import com.zl.d33.knowledge.chunking.ChunkStrategy;
import com.zl.d33.knowledge.chunking.DocumentChunk;
import com.zl.d33.knowledge.vectorindex.dto.VectorIndexResultView;
import com.zl.d33.knowledge.vectorindex.service.DocumentVectorIndexService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 语料入库编排：把 classpath 真实语料转换为 D31 {@link DocumentChunk} 并调用既有
 * {@link DocumentVectorIndexService#indexChunks}。
 *
 * <p>本类<b>不实现任何入库、幂等、版本或删除逻辑</b>，只做"数据结构转换 + 按文档分组"，
 * 保证不重复建设；重复调用同样命中幂等短路。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.vectorstore.pgvector.enabled", havingValue = "true",
        matchIfMissing = true)
public class CorpusIndexService {

    /** 语料默认版本标识；后续可按语料迭代升级。 */
    public static final String DEFAULT_CORPUS_VERSION = "corpus-v1";

    private final KnowledgeCorpus corpus;

    private final DocumentVectorIndexService indexService;

    public CorpusIndexService(KnowledgeCorpus corpus, DocumentVectorIndexService indexService) {
        this.corpus = corpus;
        this.indexService = indexService;
    }

    /**
     * 按文档逐篇入库；每篇使用独立的稳定 ID 与任务行。
     *
     * @param version  版本，空则 {@link #DEFAULT_CORPUS_VERSION}
     * @param strategy 切分策略名，空则 FIXED_LENGTH
     * @return 每篇文档的入库结果
     */
    public List<VectorIndexResultView> indexCorpus(String version, String strategy) {
        return indexCorpus(version, strategy, "");
    }

    /**
     * 带 documentId 前缀的重载：用于集成测试或多环境隔离，生产入口不传前缀。
     *
     * <p>前缀只影响写入向量库的 documentId，不改变语料内容与文档标题，因此检索评估
     * 使用的仍是真实语料文本。</p>
     */
    public List<VectorIndexResultView> indexCorpus(String version, String strategy,
                                                   String documentIdPrefix) {
        String targetVersion = StringUtils.hasText(version)
                ? version.strip() : DEFAULT_CORPUS_VERSION;
        String targetStrategy = resolveStrategy(strategy);
        ChunkStrategy chunkStrategy = ChunkStrategy.valueOf(targetStrategy);
        String prefix = documentIdPrefix == null ? "" : documentIdPrefix;

        Map<String, List<KnowledgeChunk>> byDocument = new LinkedHashMap<>();
        for (KnowledgeChunk chunk : corpus.allChunks()) {
            byDocument.computeIfAbsent(chunk.documentId(), key -> new ArrayList<>()).add(chunk);
        }

        List<VectorIndexResultView> results = new ArrayList<>(byDocument.size());
        for (Map.Entry<String, List<KnowledgeChunk>> entry : byDocument.entrySet()) {
            String targetDocumentId = prefix + entry.getKey();
            List<KnowledgeChunk> sourceChunks = entry.getValue();
            List<DocumentChunk> documentChunks = new ArrayList<>(sourceChunks.size());
            for (int index = 0; index < sourceChunks.size(); index++) {
                KnowledgeChunk source = sourceChunks.get(index);
                String content = source.excerpt() == null ? "" : source.excerpt();
                documentChunks.add(new DocumentChunk(
                        targetDocumentId,
                        source.title(),
                        targetVersion,
                        index,
                        chunkStrategy,
                        content,
                        content.codePointCount(0, content.length()),
                        source.page() == null ? List.of() : List.of(source.page()),
                        List.of(new ChunkSource(source.page(), index)),
                        ChunkBoundaryType.SENTENCE));
            }
            VectorIndexResultView result = indexService.indexChunks(targetDocumentId, targetVersion,
                    targetStrategy, sourceChunks.get(0).title(), documentChunks);
            results.add(result);
            log.info("语料入库: documentId={}, version={}, chunks={}, status={}, idempotent={}",
                    targetDocumentId, targetVersion, documentChunks.size(),
                    result.getStatus(), result.isIdempotent());
        }
        return results;
    }

    /**
     * 切分策略解析；仅接受枚举中真实存在的两种策略。
     */
    private String resolveStrategy(String strategy) {
        String value = StringUtils.hasText(strategy)
                ? strategy.strip().toUpperCase(java.util.Locale.ROOT)
                : ChunkStrategy.FIXED_LENGTH.name();
        for (ChunkStrategy candidate : ChunkStrategy.values()) {
            if (candidate.name().equals(value)) {
                return value;
            }
        }
        throw new IllegalArgumentException("不支持的切分策略: " + strategy
                + "，可选值 FIXED_LENGTH / STRUCTURE_AWARE");
    }
}
