package com.zl.d35.rag.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 引用片段有限截取单测（D35-005）。
 */
class CitationTextTest {

    @Test
    @DisplayName("短于上限：原样返回，不追加省略号")
    void shortContentKeptVerbatim() {
        assertThat(CitationText.excerpt("主轴异响处理", 200)).isEqualTo("主轴异响处理");
    }

    @Test
    @DisplayName("超过上限：截断到上限并追加省略号，长度不超上限")
    void longContentTruncatedWithEllipsis() {
        String content = "步".repeat(300);
        String excerpt = CitationText.excerpt(content, 200);
        assertThat(excerpt.length()).isEqualTo(201);
        assertThat(excerpt).endsWith("…");
        assertThat(excerpt.substring(0, 200)).isEqualTo("步".repeat(200));
    }

    @Test
    @DisplayName("代理对边界：截断点落在低位代理时回退，不切断 emoji")
    void surrogatePairBoundaryNotBroken() {
        // 199 个字符 + 1 个 emoji（代理对占 2 个 UTF-16 单元），共 201 UTF-16 单元
        String content = "a".repeat(199) + "🙂";
        String excerpt = CitationText.excerpt(content, 200);
        // 截断到 200 时第 200 个单元是 emoji 的低位代理，需回退到 199
        assertThat(excerpt).isEqualTo("a".repeat(199) + "…");
        assertThat(excerpt).doesNotContain("\uDE42");
    }

    @Test
    @DisplayName("null 输入：返回 null")
    void nullInputReturnsNull() {
        assertThat(CitationText.excerpt(null, 200)).isNull();
    }

    @Test
    @DisplayName("上限非法（<=0）：返回空串")
    void nonPositiveMaxReturnsEmpty() {
        assertThat(CitationText.excerpt("abc", 0)).isEmpty();
    }

    @Test
    @DisplayName("空白去重：首尾空白去除后按原文有限截取")
    void stripsWhitespace() {
        assertThat(CitationText.excerpt("  主轴  ", 200)).isEqualTo("主轴");
    }
}
