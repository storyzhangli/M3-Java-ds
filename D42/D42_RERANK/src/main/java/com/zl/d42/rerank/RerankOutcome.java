package com.zl.d42.rerank;

import java.util.List;

/**
 * 重排执行结果（H8：失败绝不包装成"看起来成功"的空结果；
 * 真实空候选必须带 RERANK_EMPTY_CANDIDATES，空结果不等于失败）。
 *
 * @param reasonCode null=成功；非 null=显式失败/退化
 */
public record RerankOutcome(List<RerankedHit> hits, RerankStageStats stats, String reasonCode) {

    public RerankOutcome {
        hits = List.copyOf(hits);
    }

    public boolean failed() {
        return reasonCode != null
                && !RerankReasonCode.RERANK_EMPTY_CANDIDATES.code().equals(reasonCode);
    }
}