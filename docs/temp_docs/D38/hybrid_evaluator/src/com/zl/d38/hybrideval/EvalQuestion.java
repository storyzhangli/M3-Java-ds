package com.zl.d38.hybrideval;

import java.util.List;

/**
 * 评测问题（复制自 D37 评测器 EvalQuestion，零改动，仅换包名；
 * 字段名与冻结 schema 严格一致）。
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
