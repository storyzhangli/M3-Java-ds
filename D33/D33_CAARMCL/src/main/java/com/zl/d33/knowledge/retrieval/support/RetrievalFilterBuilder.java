package com.zl.d33.knowledge.retrieval.support;

import com.zl.d33.common.ResultCode;
import com.zl.d33.knowledge.retrieval.RetrievalException;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.util.StringUtils;

/**
 * 检索元数据过滤表达式构造器（纯逻辑，可单元测试）。
 *
 * <p>只允许对 D31 写入向量 metadata 的<b>真实字段</b>过滤：{@code documentId}、{@code version}、
 * {@code strategy}。三者都是字符串精确匹配（EQ），均可在 pgvector 的 jsonb metadata 上比较。</p>
 *
 * <p>安全边界：过滤值来自外部请求，必须先通过"长度 + 字符集"白名单校验再拼入表达式。
 * 单引号、空格、运算符等一律拒绝，避免构造出非预期的过滤表达式而静默返回错误结果。</p>
 */
public final class RetrievalFilterBuilder {

    /** 允许的过滤值长度上限。 */
    private static final int MAX_VALUE_LENGTH = 128;

    /** 白名单：字母、数字、点、下划线、冒号、连字符（覆盖 DOC-CNC-COOLING-001 / corpus-v1 / FIXED_LENGTH）。 */
    private static final String SAFE_VALUE_PATTERN = "[A-Za-z0-9._:-]+";

    public RetrievalFilterBuilder() {
    }

    /**
     * 构造过滤表达式；三个条件均为可选，全部为空时返回 {@code null}（不过滤）。
     */
    public Filter.Expression build(String documentId, String version, String strategy) {
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        FilterExpressionBuilder.Op expression = null;
        if (StringUtils.hasText(documentId)) {
            expression = builder.eq("documentId", requireSafe(documentId, "documentId"));
        }
        if (StringUtils.hasText(version)) {
            FilterExpressionBuilder.Op next = builder.eq("version", requireSafe(version, "version"));
            expression = expression == null ? next : builder.and(expression, next);
        }
        if (StringUtils.hasText(strategy)) {
            FilterExpressionBuilder.Op next = builder.eq("strategy", requireSafe(strategy, "strategy"));
            expression = expression == null ? next : builder.and(expression, next);
        }
        return expression == null ? null : expression.build();
    }

    /**
     * 过滤值白名单校验；任何越界字符都直接拒绝，不做转义猜测。
     */
    static String requireSafe(String value, String fieldName) {
        String trimmed = value == null ? null : value.strip();
        if (!StringUtils.hasText(trimmed) || trimmed.length() > MAX_VALUE_LENGTH
                || !trimmed.matches(SAFE_VALUE_PATTERN)) {
            throw new RetrievalException(ResultCode.PARAM_INVALID,
                    "过滤字段 " + fieldName + " 的值不合法：仅允许字母、数字、点、下划线、冒号、连字符，"
                            + "长度不超过 " + MAX_VALUE_LENGTH,
                    "FILTER_VALUE_INVALID");
        }
        return trimmed;
    }
}
