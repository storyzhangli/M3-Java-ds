package com.zl.d33.rag.model;

import com.zl.d33.knowledge.KnowledgeChunk;

import java.util.List;

/**
 * 一次向量检索命中的内部模型（检索通道与组装/引用之间的唯一数据契约）。
 *
 * <p>字段全部来自 {@code RetrievalHitView} 真实数据，不做补造；{@code toKnowledgeChunk()}
 * 把来源映射到 {@link KnowledgeChunk} 以复用 {@code CitationRegistry} 的引用分配与清洗能力。
 * 向量检索结果没有 section/sourceType/url 信息，映射时如实置 null，不伪造。</p>
 *
 * @param chunkId     稳定 Chunk ID（向量主键）
 * @param documentId  文档标识
 * @param version     文档版本
 * @param chunkIndex  块序号
 * @param title       文档标题
 * @param pageNumbers 页码列表（可能为空）
 * @param strategy    切分策略
 * @param content     Chunk 原文（不可信数据）
 * @param score       余弦相似度（可能为 null）
 * @param distance    余弦距离（可能为 null）
 */
public record RetrievedChunk(String chunkId,
                             String documentId,
                             String version,
                             Integer chunkIndex,
                             String title,
                             List<Integer> pageNumbers,
                             String strategy,
                             String content,
                             Double score,
                             Double distance) {

    /**
     * 映射为引用注册表可用的知识片段；页码取第一个（检索结果无章节与来源类型）。
     */
    public KnowledgeChunk toKnowledgeChunk() {
        Integer page = (pageNumbers == null || pageNumbers.isEmpty()) ? null : pageNumbers.get(0);
        return new KnowledgeChunk(documentId, chunkId, title, null, null, page, content, null);
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
