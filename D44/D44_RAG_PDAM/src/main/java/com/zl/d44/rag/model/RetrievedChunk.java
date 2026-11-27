package com.zl.d44.rag.model;

import com.zl.d44.knowledge.KnowledgeChunk;
import com.zl.d44.rag.support.CitationText;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一次向量检索命中的内部模型（检索通道与组装/引用之间的唯一数据契约）。
 *
 * <p>字段全部来自 {@code RetrievalHitView} 真实数据，不做补造；{@code toKnowledgeChunk()}
 * 把来源映射到 {@link KnowledgeChunk} 以复用 {@code CitationRegistry} 的引用分配与清洗能力。
 * 向量检索结果没有 section/sourceType/url 信息，映射时如实置 null，不伪造。</p>
 *
 * <p>生命周期字段（lifecycleStatus/effectiveAt/expiresAt）来自入库 metadata；
 * 历史数据缺失时为 null，证据评估按兼容处理（不主动过滤），详见
 * {@code EvidenceAssessmentService}。</p>
 *
 * @param chunkId         稳定 Chunk ID（向量主键）
 * @param documentId      文档标识
 * @param version         文档版本
 * @param chunkIndex      块序号
 * @param title           文档标题
 * @param pageNumbers     页码列表（可能为空）
 * @param paragraphIndex  段落索引（真实切分来源，可能为 null）
 * @param strategy        切分策略
 * @param content         Chunk 原文（不可信数据）
 * @param score           余弦相似度（可能为 null）
 * @param distance        余弦距离（可能为 null）
 * @param lifecycleStatus 文档证据生命周期状态（DRAFT/ACTIVE/DEPRECATED/EXPIRED，可能为 null）
 * @param effectiveAt     生效时间（可能为 null）
 * @param expiresAt       过期时间（可能为 null）
 */
public record RetrievedChunk(String chunkId,
                             String documentId,
                             String version,
                             Integer chunkIndex,
                             String title,
                             List<Integer> pageNumbers,
                             Integer paragraphIndex,
                             String strategy,
                             String content,
                             Double score,
                             Double distance,
                             String lifecycleStatus,
                             LocalDateTime effectiveAt,
                             LocalDateTime expiresAt) {

    /** 兼容构造器：无段落索引、无生命周期元数据的检索结果（历史存量数据）。 */
    public RetrievedChunk(String chunkId, String documentId, String version, Integer chunkIndex,
                          String title, List<Integer> pageNumbers, String strategy,
                          String content, Double score, Double distance) {
        this(chunkId, documentId, version, chunkIndex, title, pageNumbers, null, strategy, content,
                score, distance, null, null, null);
    }

    /** 兼容构造器：有段落索引、无生命周期元数据。 */
    public RetrievedChunk(String chunkId, String documentId, String version, Integer chunkIndex,
                          String title, List<Integer> pageNumbers, Integer paragraphIndex,
                          String strategy, String content, Double score, Double distance) {
        this(chunkId, documentId, version, chunkIndex, title, pageNumbers, paragraphIndex, strategy,
                content, score, distance, null, null, null);
    }

    /**
     * 映射为引用注册表可用的知识片段（默认引用片段上限）：页码取第一个（兼容单值），
     * 同时保留完整页码列表与段落索引；摘录为有限截取（来自原文不改写）；
     * 检索结果无章节/来源类型/URL，如实置 null。
     */
    public KnowledgeChunk toKnowledgeChunk() {
        return toKnowledgeChunk(CitationText.DEFAULT_EXCERPT_MAX_CHARS);
    }

    /**
     * 指定引用片段上限的映射（可配 {@code app.rag.citation.excerpt-max-chars}）。
     *
     * <p> {@code version} 随检索命中逐条透传为 {@code documentVersion}，
     * 保证同一 sourceId 的 V1/V2 片段各自保持真实版本，不被当前最新版本覆盖。</p>
     */
    public KnowledgeChunk toKnowledgeChunk(int excerptMaxChars) {
        Integer page = (pageNumbers == null || pageNumbers.isEmpty()) ? null : pageNumbers.get(0);
        List<Integer> pages = (pageNumbers == null || pageNumbers.isEmpty()) ? null : pageNumbers;
        return new KnowledgeChunk(documentId, version, chunkId, title, null, null, page, pages,
                paragraphIndex, CitationText.excerpt(content, excerptMaxChars), score, null);
    }

    /** 分数归一化：null 视为 0，保证排序稳定。 */
    public double normalizedScore() {
        return score == null ? 0.0d : score;
    }

    /** 内容长度（Unicode 码点），与预算口径一致。 */
    public int contentLength() {
        return content == null ? 0 : content.codePointCount(0, content.length());
    }
}
