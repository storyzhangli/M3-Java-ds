package com.zl.d40.two;

import com.zl.d40.knowledge.KnowledgeChunk;
import com.zl.d40.knowledge.KnowledgeCorpus;
import com.zl.d40.knowledge.chunking.ChunkBoundaryType;
import com.zl.d40.knowledge.chunking.ChunkSource;
import com.zl.d40.knowledge.chunking.ChunkStrategy;
import com.zl.d40.knowledge.chunking.DocumentChunk;
import com.zl.d40.knowledge.vectorindex.dto.VectorIndexResultView;
import com.zl.d40.knowledge.vectorindex.service.DocumentVectorIndexService;
import com.zl.d40.two.TwoPgVectorConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * two_ 隔离链路语料入库编排：与本地 {@code CorpusIndexService} 同构的
 * "数据结构转换 + 按文档分组"，但委托 two_ 空间的
 * {@link DocumentVectorIndexService} 实例（two_PgVectorStore + two_ MySQL 表）。
 *
 * <p>不实现任何入库/幂等/版本逻辑（全部复用既有编排服务）；不注册为 Spring Bean，
 * 由 {@link TwoPgVectorConfiguration} 手动构造。语料内容与本地链路完全一致
 * （同一 {@link KnowledgeCorpus}），保证双模型对比的输入公平性。</p>
 */
@Slf4j
public class TwoCorpusIndexService {

    /** 与本地链路一致的语料默认版本标识。 */
    public static final String DEFAULT_CORPUS_VERSION = "corpus-v1";

    private final KnowledgeCorpus corpus;

    private final DocumentVectorIndexService twoIndexService;

    public TwoCorpusIndexService(KnowledgeCorpus corpus,
                                 DocumentVectorIndexService twoIndexService) {
        this.corpus = corpus;
        this.twoIndexService = twoIndexService;
    }

    /**
     * 按文档逐篇入库到 two_ 向量空间；重复调用命中幂等短路（0 次远程 Embedding 调用）。
     */
    public List<VectorIndexResultView> indexCorpus(String version, String strategy) {
        String targetVersion = StringUtils.hasText(version)
                ? version.strip() : DEFAULT_CORPUS_VERSION;
        String targetStrategy = resolveStrategy(strategy);
        ChunkStrategy chunkStrategy = ChunkStrategy.valueOf(targetStrategy);

        Map<String, List<KnowledgeChunk>> byDocument = new LinkedHashMap<>();
        for (KnowledgeChunk chunk : corpus.allChunks()) {
            byDocument.computeIfAbsent(chunk.documentId(), key -> new ArrayList<>()).add(chunk);
        }

        List<VectorIndexResultView> results = new ArrayList<>(byDocument.size());
        for (Map.Entry<String, List<KnowledgeChunk>> entry : byDocument.entrySet()) {
            String documentId = entry.getKey();
            List<KnowledgeChunk> sourceChunks = entry.getValue();
            List<DocumentChunk> documentChunks = new ArrayList<>(sourceChunks.size());
            for (int index = 0; index < sourceChunks.size(); index++) {
                KnowledgeChunk source = sourceChunks.get(index);
                String content = source.excerpt() == null ? "" : source.excerpt();
                documentChunks.add(new DocumentChunk(
                        documentId,
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
            VectorIndexResultView result = twoIndexService.indexChunks(documentId, targetVersion,
                    targetStrategy, sourceChunks.get(0).title(), documentChunks);
            results.add(result);
            log.info("two_ 语料入库: documentId={}, version={}, chunks={}, status={}, idempotent={}",
                    documentId, targetVersion, documentChunks.size(),
                    result.getStatus(), result.isIdempotent());
        }
        return results;
    }

    private String resolveStrategy(String strategy) {
        String value = StringUtils.hasText(strategy)
                ? strategy.strip().toUpperCase(Locale.ROOT)
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