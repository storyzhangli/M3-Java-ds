package com.zl.d43.rerank.experiment;

import java.util.List;

/** 单题单组单次运行明细（零全文；top5 仅 chunkId/logicalId）。 */
public record RerankRunDetail(String questionId,
                              String group,
                              int runIndex,
                              List<String> top5ChunkIds,
                              List<String> top5LogicalIds,
                              boolean hit,
                              double reciprocalRank,
                              double totalElapsedMs,
                              double rerankElapsedMs,
                              int rerankInputCount,
                              Integer rerankTotalTokens,
                              String reasonCode) {
}