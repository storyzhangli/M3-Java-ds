package com.zl.d37.evaluator;

import java.util.List;

/**
 * 评测问题（从冻结 JSONL 反序列化，字段名与冻结 schema 严格一致）。
 */
public record EvalQuestion(
        String questionId,
        String scenario,
        String question,
        String expectedDecision,
        List<String> expectedDocumentIds,
        List<String> expectedChunkIds,
        List<String> mustNotReturnDocumentIds,
        List<String> expectedTerms,
        String annotatedAt,
        String annotator,
        String notes) {
}
