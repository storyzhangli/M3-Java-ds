package com.zl.d32.knowledge.retrieval;

import com.zl.d32.knowledge.retrieval.support.RetrievalFilterBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 元数据过滤构造：只允许真实字段，过滤值走白名单，防止表达式注入。
 */
class RetrievalFilterBuilderTest {

    private final RetrievalFilterBuilder builder = new RetrievalFilterBuilder();

    @Test
    @DisplayName("三个条件都为空时返回 null（不过滤）")
    void returnsNullWhenNoCondition() {
        assertThat(builder.build(null, null, null)).isNull();
        assertThat(builder.build("  ", "", "   ")).isNull();
    }

    @Test
    @DisplayName("仅 documentId：表达式包含 documentId 与真实值")
    void buildsDocumentIdFilter() {
        var expression = builder.build("DOC-CNC-COOLING-001", null, null);
        assertThat(expression).isNotNull();
        assertThat(expression.toString()).contains("documentId");
        assertThat(expression.toString()).contains("DOC-CNC-COOLING-001");
    }

    @Test
    @DisplayName("三个条件组合：表达式同时包含三个字段名")
    void buildsCombinedFilter() {
        var expression = builder.build("DOC-CNC-COOLING-001", "corpus-v1", "FIXED_LENGTH");
        assertThat(expression).isNotNull();
        String text = expression.toString();
        assertThat(text).contains("documentId").contains("version").contains("strategy");
    }

    @Test
    @DisplayName("版本与策略是允许的过滤字段")
    void buildsVersionAndStrategyFilter() {
        assertThat(builder.build(null, "corpus-v1", null).toString()).contains("version");
        assertThat(builder.build(null, null, "FIXED_LENGTH").toString()).contains("strategy");
    }

    @Test
    @DisplayName("单引号等非法字符被拒绝，errorCode=FILTER_VALUE_INVALID")
    void rejectsQuoteInjection() {
        assertThatThrownBy(() -> builder.build("DOC-1' OR documentId", null, null))
                .isInstanceOf(RetrievalException.class)
                .satisfies(ex -> org.assertj.core.api.Assertions.assertThat(
                        ((RetrievalException) ex).getErrorCode()).isEqualTo("FILTER_VALUE_INVALID"));
    }

    @Test
    @DisplayName("空格与超长过滤值被拒绝")
    void rejectsSpaceAndOversizedValue() {
        assertThatThrownBy(() -> builder.build("DOC 1", null, null))
                .isInstanceOf(RetrievalException.class)
                .hasMessageContaining("documentId");

        String oversized = "D".repeat(129);
        assertThatThrownBy(() -> builder.build(oversized, null, null))
                .isInstanceOf(RetrievalException.class)
                .hasMessageContaining("长度不超过 128");
    }
}
