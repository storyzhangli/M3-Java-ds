package com.zl.d44.hybrid.config;

import com.zl.d44.common.ResultCode;
import com.zl.d44.knowledge.retrieval.RetrievalException;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 混合检索实验分支配置（前缀 {@code app.hybrid}）。
 *
 * <p>开关语义（H2）：{@code app.hybrid.enabled} 默认 false，且本类挂
 * {@code @ConditionalOnProperty}（不设 matchIfMissing）——关闭时 hybrid 全部 Bean 不装配，
 * 应用行为与既有 HEAD 完全一致。</p>
 *
 * <p>参数冻结纪律（P0）：以下默认值即 探测门禁裁决后的冻结值
 * （docs/evidence/D44/hybrid/probe/hybrid-params.json），实验期间禁止看结果调参；
 * 配置覆盖仅用于独立消融切片（OPT），不进入主矩阵。</p>
 */
@Data
@ConfigurationProperties(prefix = "app.hybrid")
@ConditionalOnProperty(name = "app.hybrid.enabled", havingValue = "true")
public class HybridProperties {

    /** 分支总开关；仅当显式 true 时 hybrid 装配存在（默认 false）。 */
    private boolean enabled = false;

    /**
     * 关键词特征子集（冻结 w+b+t）。c: 单码点区分度极低默认停用；
     * 子集即消融开关（OPT B1 使用 w）。取值：w/c/b/t 以 + 连接。
     */
    private String featureSubset = "w+b+t";

    /** BM25 词频饱和参数 k1（冻结 1.2，Robertson & Zaragoza 2009 经验值）。 */
    private double bm25K1 = 1.2d;

    /** BM25 文档长度归一参数 b（冻结 0.75）。 */
    private double bm25B = 0.75d;

    /** RRF 平滑常数 k（冻结 60，Cormack et al. SIGIR 2009 经验值）。 */
    private int rrfK = 60;

    /**
     * 每路候选数 candidateTopN（冻结 10）。合法域对齐既有 maxTopK=50；
     * 融合后按请求 topK 截断。
     */
    private int candidateTopN = 10;

    /**
     * 装配期校验：任何越界参数直接 fail-fast，不允许静默使用非法值跑实验。
     */
    public void validate() {
        if (bm25K1 <= 0 || !Double.isFinite(bm25K1)) {
            throw invalid("app.hybrid.bm25-k1 必须为正有限数，实际 " + bm25K1);
        }
        if (bm25B < 0 || bm25B > 1 || !Double.isFinite(bm25B)) {
            throw invalid("app.hybrid.bm25-b 必须在 0..1，实际 " + bm25B);
        }
        if (rrfK < 1) {
            throw invalid("app.hybrid.rrf-k 必须 >= 1，实际 " + rrfK);
        }
        if (candidateTopN < 1 || candidateTopN > 50) {
            throw invalid("app.hybrid.candidate-top-n 必须在 1..50（对齐 maxTopK），实际 " + candidateTopN);
        }
        if (featureSubset == null || featureSubset.isBlank()) {
            throw invalid("app.hybrid.feature-subset 不能为空");
        }
        for (String token : featureSubset.split("\\+")) {
            String trimmed = token.strip();
            if (!trimmed.isEmpty() && !"w".equals(trimmed) && !"c".equals(trimmed)
                    && !"b".equals(trimmed) && !"t".equals(trimmed)) {
                throw invalid("app.hybrid.feature-subset 含非法特征 " + trimmed + "（允许 w/c/b/t，+ 连接）");
            }
        }
    }

    private RetrievalException invalid(String message) {
        return new RetrievalException(ResultCode.AI_CONFIG_ERROR, message, "HYBRID_PARAM_INVALID");
    }
}
