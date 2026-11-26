package com.zl.d43.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 最小闭环配置（前缀 {@code app.rag}）。
 *
 * <p>预算口径为 Unicode 码点字符，不伪称 Token：本项目无 Tokenizer，只能做可解释的
 * 字符级保守估算，误差边界在文档中声明。</p>
 */
@Data
@ConfigurationProperties(prefix = "app.rag")
public class RagProperties {

    /** 覆盖检索 Top-K；null 表示沿用 {@code app.retrieval.default-top-k}。 */
    private Integer topK;

    /** 组装上下文的最大字符预算（Unicode 码点，含编号头与换行）。 */
    private int maxContextChars = 4000;

    /** 命中的余弦相似度下限；0.0 表示不额外过滤（与检索阈值语义一致）。 */
    private double minScore = 0.0;

    /** 引用片段配置。 */
    private Citation citation = new Citation();

    /** 证据评估配置。 */
    private Evidence evidence = new Evidence();

    /**
     * 配置自检，非法组合立即失败。
     */
    public void validate() {
        if (topK != null && (topK < 1 || topK > 1000)) {
            throw new IllegalArgumentException("app.rag.top-k 必须在 1..1000 范围内");
        }
        if (maxContextChars < 1 || maxContextChars > 1_000_000) {
            throw new IllegalArgumentException("app.rag.max-context-chars 必须在 1..1000000 范围内");
        }
        if (Double.isNaN(minScore) || minScore < 0.0 || minScore > 1.0) {
            throw new IllegalArgumentException("app.rag.min-score 必须是 0.0..1.0 之间的余弦相似度下限");
        }
        if (citation == null) {
            throw new IllegalArgumentException("app.rag.citation 不能为 null");
        }
        citation.validate();
        if (evidence == null) {
            throw new IllegalArgumentException("app.rag.evidence 不能为 null");
        }
        evidence.validate();
    }

    /**
     * 引用片段配置（引用溯源契约）。
     */
    @Data
    public static class Citation {

        /** 引用片段上限（UTF-16 字符），默认 200，来自原文有限截取。 */
        private int excerptMaxChars = 200;

        void validate() {
            if (excerptMaxChars < 1 || excerptMaxChars > 10_000) {
                throw new IllegalArgumentException(
                        "app.rag.citation.excerpt-max-chars 必须在 1..10000 范围内");
            }
        }
    }

    /**
     * 证据评估门禁配置。
     *
     * <p>相关度阈值无实测依据时按可配置项处理，默认 0.15，不硬编码承诺值。</p>
     */
    @Data
    public static class Evidence {

        /** 相关证据的余弦相似度下限；低于该值的命中视为仅关键词相似（低相关）。 */
        private double relevantScore = 0.15;

        void validate() {
            if (Double.isNaN(relevantScore) || relevantScore < 0.0 || relevantScore > 1.0) {
                throw new IllegalArgumentException(
                        "app.rag.evidence.relevant-score 必须是 0.0..1.0 之间的余弦相似度下限");
            }
        }
    }
}
