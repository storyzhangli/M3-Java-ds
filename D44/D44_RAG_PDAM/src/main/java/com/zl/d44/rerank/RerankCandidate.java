package com.zl.d44.rerank;

import com.zl.d44.rerank.RerankReasonCode;

/**
 * 重排候选（D2 裁决：输入=检索命中视图的纯内存投影，零回表）。
 *
 * @param chunkId      向量层 chunk 主键（UUID 字符串）
 * @param documentId   文档业务 ID（含实验前缀）
 * @param content      chunk 原文（<=720 码点）
 * @param originalRank 检索原始名次（1-based）
 * @param cosineScore  余弦相似度（null 合法，排序按 -1 处理）
 */
public record RerankCandidate(String chunkId, String documentId, String content,
                              int originalRank, Double cosineScore) {
    public RerankCandidate {
        if (chunkId == null || chunkId.isBlank()) {
            throw new IllegalArgumentException(RerankReasonCode.RERANK_CONFIG_INVALID.code() + ": chunkId 空");
        }
        if (content == null) {
            throw new IllegalArgumentException(RerankReasonCode.RERANK_CONFIG_INVALID.code() + ": content null");
        }
        if (originalRank < 1) {
            throw new IllegalArgumentException(RerankReasonCode.RERANK_CONFIG_INVALID.code() + ": originalRank<1");
        }
    }
}