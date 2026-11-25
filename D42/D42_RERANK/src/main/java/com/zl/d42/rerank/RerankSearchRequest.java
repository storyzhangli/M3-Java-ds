package com.zl.d42.rerank;

/**
 * 只读验证面请求（重排不改检索候选生成：先 similarity search，再端口重排）。
 *
 * @param candidateTopK 检索候选数（null→5，上限 50）
 * @param topN          重排输出数（null→5）
 * @param provider      local / bailian（null→local；bailian 需 remote 装配在位）
 */
public record RerankSearchRequest(String query,
                                  Integer candidateTopK,
                                  Integer topN,
                                  String provider,
                                  String documentId) {
}