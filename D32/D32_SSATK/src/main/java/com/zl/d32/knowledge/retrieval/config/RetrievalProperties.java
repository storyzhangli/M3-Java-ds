package com.zl.d32.knowledge.retrieval.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 相似度检索配置（前缀 {@code app.retrieval}）。
 *
 * <p>刻意不提供"默认相似度阈值"这一业务猜测：D32 的目标是建立可评估的检索基线，
 * 阈值必须来自真实标注与分数分布分析，而不是拍脑袋设置。</p>
 *
 * <p>{@code similarityThreshold} 的语义与 Spring AI {@code SearchRequest} 一致：
 * 它是 <b>余弦相似度</b>下限（0.0 表示接受全部，框架内部换算为距离阈值
 * {@code distance < 1 - threshold}）。</p>
 */
@Data
@ConfigurationProperties(prefix = "app.retrieval")
public class RetrievalProperties {

    /** 默认 Top-K；Spring AI 自身的 DEFAULT_TOP_K 为 4，本模块显式提升为 5。 */
    private int defaultTopK = 5;

    /** Top-K 上限，防止单次检索返回过多内容。 */
    private int maxTopK = 50;

    /** 查询文本最大长度（按 Unicode 码点计），与 Embedding 输入上限解耦。 */
    private int maxQueryLength = 2000;

    /** 余弦相似度下限；0.0 等价于"接受全部"，本次不预设业务阈值。 */
    private double similarityThreshold = 0.0;

    /**
     * 配置自检；非法组合在启动或首次使用时立即失败，不进入检索路径。
     */
    public void validate() {
        if (defaultTopK < 1 || defaultTopK > maxTopK) {
            throw new IllegalArgumentException("app.retrieval.default-top-k 必须在 1..max-top-k 范围内");
        }
        if (maxTopK < 1 || maxTopK > 1000) {
            throw new IllegalArgumentException("app.retrieval.max-top-k 必须在 1..1000 范围内");
        }
        if (maxQueryLength < 1 || maxQueryLength > 100_000) {
            throw new IllegalArgumentException("app.retrieval.max-query-length 必须在 1..100000 范围内");
        }
        if (Double.isNaN(similarityThreshold) || similarityThreshold < 0.0
                || similarityThreshold > 1.0) {
            throw new IllegalArgumentException(
                    "app.retrieval.similarity-threshold 必须是 0.0..1.0 之间的余弦相似度下限");
        }
    }
}
