package com.zl.d35.rag;

import com.zl.d35.dto.CitationDto;
import com.zl.d35.knowledge.CitationRegistry;
import com.zl.d35.knowledge.KnowledgeChunk;
import com.zl.d35.rag.model.RetrievedChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 引用字段语义单测（D35-001 伪造引用 / D35-002 页码缺失 / D35-003 多来源 / D35-004 页码列表透传）。
 */
class CitationRegistryFieldTest {

    @Test
    @DisplayName("D35-001 伪造引用：未知编号被删除并计数，合法编号保留")
    void fabricatedCitationStripped() {
        KnowledgeChunk chunk = CitationTestFixtures.chunkR2Active().toKnowledgeChunk();
        CitationRegistry registry = new CitationRegistry(List.of(chunk));

        String sanitized = registry.validateAndStrip("压力阈值 2.5MPa。[S1][S99]");
        assertThat(sanitized).isEqualTo("压力阈值 2.5MPa。[S1]");
        assertThat(registry.getDroppedUnknownMarkers()).isEqualTo(1);
        assertThat(registry.citedCitations()).hasSize(1);
    }

    @Test
    @DisplayName("D35-002 页码缺失：page 与 pageNumbers 均为 null，不补造")
    void missingPageNotInvented() {
        RetrievedChunk noPage = new RetrievedChunk(
                "c1", "doc-1", "V1", 0, "标题", List.of(), null,
                "PARAGRAPH", "内容", 0.5, 0.5, "ACTIVE", null, null);
        CitationRegistry registry = new CitationRegistry(List.of(noPage.toKnowledgeChunk()));

        CitationDto dto = registry.allCitations().get(0);
        assertThat(dto.page()).isNull();
        assertThat(dto.pageNumbers()).isNull();
        assertThat(dto.paragraphIndex()).isNull();
    }

    @Test
    @DisplayName("D35-003 多来源：稳定分配 S1/S2，顺序稳定")
    void multipleSourcesAssignedInOrder() {
        CitationRegistry registry = new CitationRegistry(List.of(
                CitationTestFixtures.chunkR2Active().toKnowledgeChunk(),
                CitationTestFixtures.chunkR2Second().toKnowledgeChunk()));

        List<CitationDto> all = registry.allCitations();
        assertThat(all).hasSize(2);
        assertThat(all.get(0).id()).isEqualTo("S1");
        assertThat(all.get(1).id()).isEqualTo("S2");
        assertThat(all.get(0).sourceId()).isEqualTo("doc-r2");
        assertThat(all.get(1).sourceId()).isEqualTo("doc-r2-tune");
    }

    @Test
    @DisplayName("D35-004 页码列表/段落索引/相似度透传 + excerpt 有限截取")
    void pageNumbersParagraphIndexAndScoreTransferred() {
        RetrievedChunk source = CitationTestFixtures.chunkR2Active();
        KnowledgeChunk chunk = source.toKnowledgeChunk();

        assertThat(chunk.page()).isEqualTo(15);
        assertThat(chunk.pageNumbers()).containsExactly(15, 16, 17);
        assertThat(chunk.paragraphIndex()).isEqualTo(3);
        assertThat(chunk.score()).isEqualTo(0.62);

        CitationDto dto = new CitationRegistry(List.of(chunk)).allCitations().get(0);
        assertThat(dto.sourceId()).isEqualTo("doc-r2");
        assertThat(dto.pageNumbers()).containsExactly(15, 16, 17);
        assertThat(dto.paragraphIndex()).isEqualTo(3);
        assertThat(dto.score()).isEqualTo(0.62);
        assertThat(dto.excerpt()).isNotNull();
    }

    @Test
    @DisplayName("D35-005 长文本 excerpt 有限截取到默认上限（UTF-16 ≤200+省略号）")
    void excerptTruncatedToDefaultMax() {
        RetrievedChunk longChunk = CitationTestFixtures.chunkLongContent();
        KnowledgeChunk chunk = longChunk.toKnowledgeChunk();
        assertThat(chunk.excerpt().length()).isLessThanOrEqualTo(201);
        assertThat(chunk.excerpt()).endsWith("…");
    }
}
