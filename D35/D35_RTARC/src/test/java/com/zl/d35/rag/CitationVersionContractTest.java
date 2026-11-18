package com.zl.d35.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d35.dto.CitationDto;
import com.zl.d35.dto.SsePayloads;
import com.zl.d35.knowledge.CitationRegistry;
import com.zl.d35.rag.model.RetrievedChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文档版本溯源契约单测（D35 R03 / 整改任务二）。
 *
 * <p>验证 {@code documentVersion} 来自检索命中的真实 Chunk metadata：
 * 同一 sourceId 的 V1/V2 片段各自保持真实版本，不被当前最新版本覆盖；版本缺失如实为 null。
 * SSE 契约通过 {@link SsePayloads.Citations} 与同步侧共用同一 {@link CitationDto} 类型验证
 * （契约级证据，不等于 SSE 端点端到端测试）。</p>
 */
class CitationVersionContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("正常版本 V1：documentVersion 透传为 V1.0")
    void versionV1Transferred() {
        CitationDto dto = new CitationRegistry(List.of(
                CitationTestFixtures.chunkR2Second().toKnowledgeChunk())).allCitations().get(0);
        assertThat(dto.documentVersion()).isEqualTo("V1.0");
    }

    @Test
    @DisplayName("正常版本 V2：documentVersion 透传为 V2.0")
    void versionV2Transferred() {
        CitationDto dto = new CitationRegistry(List.of(
                CitationTestFixtures.chunkR2Active().toKnowledgeChunk())).allCitations().get(0);
        assertThat(dto.documentVersion()).isEqualTo("V2.0");
    }

    @Test
    @DisplayName("同一文档不同版本：两条引用各自保持对应 Chunk 的真实版本，不被最新版本覆盖")
    void sameDocumentDifferentVersionsKeptPerChunk() {
        RetrievedChunk v1 = new RetrievedChunk("c-same-v1", "doc-same", "V1.0", 0, "同一文档",
                List.of(3), 1, "FIXED_LENGTH", "旧版内容", 0.60, 0.40, "ACTIVE", null, null);
        RetrievedChunk v2 = new RetrievedChunk("c-same-v2", "doc-same", "V2.0", 0, "同一文档",
                List.of(4), 2, "FIXED_LENGTH", "新版内容", 0.58, 0.42, "ACTIVE", null, null);

        List<CitationDto> citations = new CitationRegistry(List.of(
                v1.toKnowledgeChunk(), v2.toKnowledgeChunk())).allCitations();

        assertThat(citations).hasSize(2);
        assertThat(citations.get(0).sourceId()).isEqualTo("doc-same");
        assertThat(citations.get(1).sourceId()).isEqualTo("doc-same");
        assertThat(citations.get(0).documentVersion()).isEqualTo("V1.0");
        assertThat(citations.get(1).documentVersion()).isEqualTo("V2.0");
    }

    @Test
    @DisplayName("版本缺失：documentVersion 返回 null，不补造")
    void missingVersionNotInvented() throws Exception {
        RetrievedChunk noVersion = new RetrievedChunk("c1", "doc-1", null, 0, "标题",
                List.of(5), 1, "FIXED_LENGTH", "内容", 0.5, 0.5, null, null, null);

        CitationDto dto = new CitationRegistry(List.of(
                noVersion.toKnowledgeChunk())).allCitations().get(0);

        assertThat(dto.documentVersion()).isNull();
        assertThat(objectMapper.writeValueAsString(dto)).doesNotContain("documentVersion");
    }

    @Test
    @DisplayName("SSE 契约：Citations 载荷与同步侧共用同一 CitationDto，documentVersion 字段一致")
    void sseCitationsCarrySameVersionField() throws Exception {
        CitationDto dto = new CitationRegistry(List.of(
                CitationTestFixtures.chunkR2Active().toKnowledgeChunk())).allCitations().get(0);

        String syncJson = objectMapper.writeValueAsString(dto);
        String sseJson = objectMapper.writeValueAsString(new SsePayloads.Citations(List.of(dto)));

        assertThat(syncJson).contains("\"documentVersion\":\"V2.0\"");
        assertThat(sseJson).contains("\"documentVersion\":\"V2.0\"");
        assertThat(sseJson).contains("\"sourceId\":\"doc-r2\"");
    }
}
