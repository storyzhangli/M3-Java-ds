package com.zl.d34.knowledge.retrieval.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 单条检索命中（对外视图）。
 *
 * <p>所有字段均来自真实 {@code Document} 与其 metadata，不做任何补造：</p>
 * <ul>
 *   <li>{@code score} = Spring AI {@code Document#getScore()}，即 <b>余弦相似度</b>（1 - cosine distance）；</li>
 *   <li>{@code distance} = 框架写入 metadata 的原始余弦距离（{@code embedding <=> query}）；</li>
 *   <li>来源字段（documentId/version/chunkIndex/title/pageNumbers/strategy）来自 D31 入库 metadata；</li>
 *   <li>生命周期字段（lifecycleStatus/effectiveAt/expiresAt）来自 D34 入库 metadata，缺失为 null。</li>
 * </ul>
 *
 * @param rank            名次（1 开始）
 * @param chunkId         稳定 Chunk ID（即向量主键）
 * @param documentId      文档标识
 * @param version         文档版本
 * @param chunkIndex      块序号
 * @param title           文档标题
 * @param pageNumbers     页码（可能为空列表）
 * @param strategy        切分策略
 * @param content         Chunk 原文
 * @param score           余弦相似度（可能为 null，若适配器未返回）
 * @param distance        余弦距离（可能为 null，若 metadata 未携带）
 * @param scoreSemantic   分数语义说明（固定 COSINE_SIMILARITY）
 * @param distanceSemantic 距离语义说明（固定 COSINE_DISTANCE）
 * @param lifecycleStatus 文档证据生命周期状态（可能为 null）
 * @param effectiveAt     生效时间（可能为 null）
 * @param expiresAt       过期时间（可能为 null）
 */
public record RetrievalHitView(int rank,
                               String chunkId,
                               String documentId,
                               String version,
                               Integer chunkIndex,
                               String title,
                               List<Integer> pageNumbers,
                               String strategy,
                               String content,
                               Double score,
                               Double distance,
                               String scoreSemantic,
                               String distanceSemantic,
                               String lifecycleStatus,
                               LocalDateTime effectiveAt,
                               LocalDateTime expiresAt) {

    /** 兼容构造器：无生命周期元数据的历史命中。 */
    public RetrievalHitView(int rank, String chunkId, String documentId, String version,
                            Integer chunkIndex, String title, List<Integer> pageNumbers,
                            String strategy, String content, Double score, Double distance,
                            String scoreSemantic, String distanceSemantic) {
        this(rank, chunkId, documentId, version, chunkIndex, title, pageNumbers, strategy,
                content, score, distance, scoreSemantic, distanceSemantic, null, null, null);
    }
}
