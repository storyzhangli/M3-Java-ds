package com.zl.d34.rag.service;

import com.zl.d34.rag.RagTestFixtures;
import com.zl.d34.rag.config.RagProperties;
import com.zl.d34.rag.model.AssembledContext;
import com.zl.d34.rag.model.RetrievedChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下文组装 UNTRUSTED_EVIDENCE 标记与预算口径测试。
 */
class ContextAssemblyUntrustedMarkingTest {

    private RagProperties properties;

    private ContextAssemblyService assemblyService;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        properties.validate();
        assemblyService = new ContextAssemblyService(properties);
    }

    @Test
    @DisplayName("上下文以 UNTRUSTED_EVIDENCE 包裹，并携带来源三要素")
    void contextIsWrappedWithSourceTriad() {
        AssembledContext context = assemblyService.assemble(List.of(RagTestFixtures.chunkR2Active()));
        assertThat(context.usable()).isTrue();
        assertThat(context.contextText()).startsWith("<UNTRUSTED_EVIDENCE id=\"S1\">");
        assertThat(context.contextText()).contains("来源：R2 型水泵维护手册");
        assertThat(context.contextText()).contains("版本：V2.0");
        assertThat(context.contextText()).contains("生效状态：ACTIVE");
        assertThat(context.contextText()).endsWith("</UNTRUSTED_EVIDENCE>\n");
    }

    @Test
    @DisplayName("版本/生效状态缺失时如实写“未知”，不补造")
    void missingMetadataWrittenAsUnknown() {
        RetrievedChunk legacy = new RetrievedChunk("c-legacy-01", "doc-legacy", null, 0,
                "通用维护说明", List.of(), "PARAGRAPH", "设备运行注意事项。",
                0.60, 0.40);
        AssembledContext context = assemblyService.assemble(List.of(legacy));
        assertThat(context.contextText()).contains("版本：未知");
        assertThat(context.contextText()).contains("生效状态：未知");
    }

    @Test
    @DisplayName("多证据块编号按进入顺序 S1/S2，均被包裹")
    void multipleChunksNumberedInOrder() {
        AssembledContext context = assemblyService.assemble(List.of(
                RagTestFixtures.chunkR2Active(), RagTestFixtures.chunkR2Conflict()));
        assertThat(context.contextText()).contains("id=\"S1\"");
        assertThat(context.contextText()).contains("id=\"S2\"");
        assertThat(context.contextText().split("<UNTRUSTED_EVIDENCE").length).isEqualTo(3);
    }

    @Test
    @DisplayName("预算截断仍按码点口径计数，摘录以省略号结尾")
    void budgetTruncationCountsCodePoints() {
        RagProperties tiny = new RagProperties();
        tiny.setMaxContextChars(120);
        tiny.validate();
        ContextAssemblyService tinyService = new ContextAssemblyService(tiny);
        AssembledContext context = tinyService.assemble(List.of(RagTestFixtures.chunkR2Active()));
        assertThat(context.usable()).isTrue();
        assertThat(context.truncatedChars()).isGreaterThan(0);
        assertThat(context.usedChars()).isLessThanOrEqualTo(120);
    }

    @Test
    @DisplayName("预算连包裹头都放不下 → 无可用上下文")
    void budgetTooSmallMeansUnusable() {
        RagProperties tiny = new RagProperties();
        tiny.setMaxContextChars(20);
        tiny.validate();
        ContextAssemblyService tinyService = new ContextAssemblyService(tiny);
        AssembledContext context = tinyService.assemble(List.of(RagTestFixtures.chunkR2Active()));
        assertThat(context.usable()).isFalse();
    }
}
