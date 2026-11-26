package com.zl.d43.rerank;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 台账单行字段（§6.6：零 Key 零全文零签名 URL；group 由调用层注入，适配器路径为 null 如实留空）。
 */
public record RerankLedgerEntry(long ts, String questionId, String group, String model,
                                String status, String reasonCode, Integer totalTokens,
                                long elapsedMs, String requestId, String querySha256,
                                int candidateCount) {

    public Map<String, Object> toOrderedMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ts", ts);
        m.put("questionId", questionId);
        m.put("group", group);
        m.put("model", model);
        m.put("status", status);
        m.put("reasonCode", reasonCode);
        m.put("totalTokens", totalTokens);
        m.put("elapsedMs", elapsedMs);
        m.put("requestId", requestId);
        m.put("querySha256", querySha256);
        m.put("candidateCount", candidateCount);
        return m;
    }
}