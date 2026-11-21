package com.zl.d38.hybrideval;

import java.util.List;

/**
 * 后端无关的统一检索命中视图（复制自 D37 评测器 Hit，判题字段零改动）。
 *
 * <p>score 语义随策略不同：PURE_VECTOR 为余弦相似度，HYBRID_RRF 为融合分——
 * 两者都不进质量投影（浮点排除），投影只取命中/相关/标签/排序顺序（P4.2）。</p>
 *
 * @param rank            名次（1 开始）
 * @param chunkId         后端命中的块标识（PG uuid）
 * @param documentId      真实文档标识
 * @param version         文档版本
 * @param chunkIndex      块序号
 * @param title           文档标题
 * @param pageNumbers     页码列表
 * @param content         片段原文
 * @param score           策略分数（余弦或 RRF）
 * @param distance        余弦距离（KEYWORD_ONLY 命中为 null）
 * @param logicalChunkIds 经页码反查得到的逻辑片段 ID（判题基准）
 */
public record Hit(int rank,
                  String chunkId,
                  String documentId,
                  String version,
                  Integer chunkIndex,
                  String title,
                  List<Integer> pageNumbers,
                  String content,
                  Double score,
                  Double distance,
                  List<String> logicalChunkIds) {
}
