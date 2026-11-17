package com.zl.d34.rag.config;

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
    }
}
