package com.zl.d44.hybrid.keyword;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 关键词路单条命中视图（P1.3）。核心契约禁用无约束 Map——所有字段显式声明。
 *
 * <p>{@code score} 为 BM25 分数（非负、无上界），语义由响应层
 * {@code scoreSemantic="BM25"} 标注（本视图仅承载数据）。</p>
 *
 * @param rank          名次（1 开始，分数降序 → 同分 chunkId 升序）
 * @param chunkId       PG 主键 id（与向量路同一连接键）
 * @param documentId    文档标识（metadata 原值，可能 null）
 * @param version       文档版本
 * @param chunkIndex    块序号（PG metadata 实际值，0-based，不换算）
 * @param title         文档标题
 * @param pageNumbers   页码
 * @param paragraphIndex 段落索引
 * @param strategy      切分策略
 * @param content       Chunk 原文
 * @param score         BM25 分数
 * @param docLen        文档特征计数和（BM25 len）
 * @param matchedTerms  命中的查询 term（字典序）
 * @param lifecycleStatus 生命周期状态（可能 null）
 * @param effectiveAt   生效时间（可能 null）
 * @param expiresAt     过期时间（可能 null）
 */
public record KeywordMatchView(int rank,
                               String chunkId,
                               String documentId,
                               String version,
                               Integer chunkIndex,
                               String title,
                               List<Integer> pageNumbers,
                               Integer paragraphIndex,
                               String strategy,
                               String content,
                               double score,
                               int docLen,
                               List<String> matchedTerms,
                               String lifecycleStatus,
                               LocalDateTime effectiveAt,
                               LocalDateTime expiresAt) {

    public KeywordMatchView {
        pageNumbers = pageNumbers == null ? List.of() : List.copyOf(pageNumbers);
        matchedTerms = matchedTerms == null ? List.of() : List.copyOf(matchedTerms);
    }
}
