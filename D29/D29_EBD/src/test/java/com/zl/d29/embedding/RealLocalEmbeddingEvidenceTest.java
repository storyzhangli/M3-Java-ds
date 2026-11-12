package com.zl.d29.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zl.d29.knowledge.KnowledgeChunk;
import com.zl.d29.knowledge.KnowledgeCorpus;
import com.zl.d29.knowledge.KnowledgeProperties;
import com.zl.d29.knowledge.chunking.ChunkBoundaryType;
import com.zl.d29.knowledge.chunking.ChunkSource;
import com.zl.d29.knowledge.chunking.ChunkStrategy;
import com.zl.d29.knowledge.chunking.ChunkingMetrics;
import com.zl.d29.knowledge.chunking.ChunkingResult;
import com.zl.d29.knowledge.chunking.DocumentChunk;
import com.zl.d29.knowledge.embedding.ChunkEmbedding;
import com.zl.d29.knowledge.embedding.ChunkEmbeddingService;
import com.zl.d29.knowledge.embedding.EmbeddingBatchResult;
import com.zl.d29.knowledge.embedding.EmbeddingModelCatalog;
import com.zl.d29.knowledge.embedding.EmbeddingModelDescriptor;
import com.zl.d29.knowledge.embedding.EmbeddingProperties;
import com.zl.d29.knowledge.embedding.EmbeddingVectorValidator;
import com.zl.d29.knowledge.embedding.LocalHashNgramEmbeddingModel;
import org.junit.jupiter.api.Test;
import org.springframework.retry.support.RetryTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RealLocalEmbeddingEvidenceTest {

    @Test
    void realLocalModelShouldEmbedD29ChunksWithTraceableMetadata() throws Exception {
        KnowledgeCorpus corpus = new KnowledgeCorpus(
                new ObjectMapper(), new KnowledgeProperties());
        corpus.load();
        List<KnowledgeChunk> sourceChunks = corpus.allChunks();
        assertThat(sourceChunks).hasSizeGreaterThanOrEqualTo(8);

        List<DocumentChunk> chunks = new ArrayList<>();
        chunks.add(fromKnowledgeChunk(sourceChunks.get(0), 1));
        chunks.add(fromKnowledgeChunk(sourceChunks.get(1), 2));
        chunks.add(new DocumentChunk(
                "D29-MIXED-SAMPLE",
                "中英文混合设备说明",
                "2026.11",
                3,
                ChunkStrategy.STRUCTURE_AWARE,
                "设备型号 PIXMA MP500；Check USB 2.0 connection and restart the printer.",
                68,
                List.of(2),
                List.of(new ChunkSource(2, 1)),
                ChunkBoundaryType.DOCUMENT_END));
        chunks.add(new DocumentChunk(
                "D29-LONG-SAMPLE",
                "设备故障排查长文本",
                "2026.11",
                4,
                ChunkStrategy.STRUCTURE_AWARE,
                String.join(" ", sourceChunks.stream().map(KnowledgeChunk::excerpt).toList()),
                String.join(" ", sourceChunks.stream().map(KnowledgeChunk::excerpt).toList())
                        .codePointCount(0, String.join(" ", sourceChunks.stream()
                                .map(KnowledgeChunk::excerpt).toList()).length()),
                List.of(6, 9, 10),
                List.of(new ChunkSource(6, 1),
                        new ChunkSource(9, 1),
                        new ChunkSource(10, 1)),
                ChunkBoundaryType.DOCUMENT_END));

        ChunkingResult chunkingResult = new ChunkingResult(
                "D29-REAL-CORPUS",
                "D29 设备故障教学语料",
                "2026.11",
                ChunkStrategy.STRUCTURE_AWARE,
                "UNICODE_CODE_POINTS",
                480,
                60,
                80,
                720,
                chunks.stream().mapToInt(DocumentChunk::characterCount).sum(),
                chunks.stream().mapToInt(DocumentChunk::characterCount).sum(),
                new ChunkingMetrics(
                        chunks.size(), 100, 1, 500,
                        java.util.Map.of(), java.util.Map.of()),
                chunks);

        EmbeddingProperties properties = new EmbeddingProperties();
        EmbeddingModelDescriptor descriptor = EmbeddingModelDescriptor.from(properties);
        EmbeddingVectorValidator validator = new EmbeddingVectorValidator();
        LocalHashNgramEmbeddingModel model =
                new LocalHashNgramEmbeddingModel(descriptor, validator);
        RetryTemplate retryTemplate = RetryTemplate.builder()
                .maxAttempts(2)
                .fixedBackoff(1)
                .build();
        ChunkEmbeddingService service =
                new ChunkEmbeddingService(model, descriptor, validator, retryTemplate);

        EmbeddingBatchResult result = service.embed(chunkingResult);

        assertThat(result.embeddings()).hasSize(4);
        assertThat(result.model().dimensions()).isEqualTo(384);
        assertThat(result.embeddings()).allSatisfy(embedding -> {
            assertThat(embedding.vector()).hasSize(384);
            validator.validate(embedding.vector(), 384);
            assertThat(embedding.inputHash()).hasSize(64);
            assertThat(embedding.pageNumbers()).isNotEmpty();
            assertThat(embedding.sources()).isNotEmpty();
        });
        ChunkEmbedding repeated = service.embedChunk(chunks.get(0));
        assertThat(repeated.vector()).containsExactly(result.embeddings().get(0).vector());
        assertThat(result.usage().inputTokens()).isNull();
        assertThat(result.cost().estimatedApiCostUsd()).isEqualByComparingTo("0");

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        root.put("callType", "REAL_LOCAL_MODEL");
        root.put("sourceCatalog",
                "src/main/resources/knowledge/equipment-fault-corpus.json");
        root.put("provider", result.model().provider().name());
        root.put("modelName", result.model().modelName());
        root.put("modelVersion", result.model().modelVersion());
        root.put("dimensions", result.model().dimensions());
        root.put("vectorSpaceId", result.model().vectorSpaceId());
        root.put("indexVersion", result.model().indexVersion());
        root.put("distanceMetric", result.model().distanceMetric().name());
        root.put("inputLengthUnit", result.model().lengthUnit().name());
        root.put("apiKeyRequired", result.model().keyRequired());
        root.put("pricePerMillionInputTokensUsd",
                result.model().costPerMillionInputTokensUsd());
        root.put("usageStatus", result.usage().status());
        root.put("inputTokens", result.usage().inputTokens());
        root.put("estimatedApiCostUsd", result.cost().estimatedApiCostUsd());
        root.put("costBasis", result.cost().basis());
        root.put("elapsedMs", result.elapsedMs());
        root.put("chunkCount", result.chunkCount());
        root.set("officialCandidates",
                mapper.valueToTree(List.of(
                        EmbeddingModelCatalog.OPENAI_TEXT_EMBEDDING_3_SMALL,
                        EmbeddingModelCatalog.OPENAI_TEXT_EMBEDDING_3_LARGE)));
        ArrayNode samples = root.putArray("samples");
        for (int index = 0; index < result.embeddings().size(); index++) {
            ChunkEmbedding embedding = result.embeddings().get(index);
            DocumentChunk source = chunks.get(index);
            ObjectNode sample = samples.addObject();
            sample.put("documentId", embedding.documentId());
            sample.put("title", embedding.title());
            sample.put("version", embedding.version());
            sample.put("chunkIndex", embedding.chunkIndex());
            sample.set("pageNumbers", mapper.valueToTree(embedding.pageNumbers()));
            sample.set("sources", mapper.valueToTree(embedding.sources()));
            sample.put("inputCharacters", embedding.inputCharacters());
            sample.put("inputHash", embedding.inputHash());
            sample.put("dimensions", embedding.dimensions());
            sample.put("l2Norm", validator.l2Norm(embedding.vector()));
            sample.put("attempts", embedding.attempts());
            sample.put("vectorPrefix",
                    mapper.valueToTree(java.util.Arrays.copyOf(
                            embedding.vector(), 8)));
            sample.put("textPreview",
                    source.content().substring(0,
                            Math.min(120, source.content().length())));
        }

        Path moduleRoot = Path.of("").toAbsolutePath();
        Path evidence = moduleRoot.getParent().getParent()
                .resolve("docs/evidence/D29/D29-local-embedding-evidence-20261112.json");
        Files.createDirectories(evidence.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(evidence.toFile(), root);
    }

    private DocumentChunk fromKnowledgeChunk(KnowledgeChunk source, int chunkIndex) {
        return new DocumentChunk(
                source.documentId(),
                source.title(),
                "teaching-fixture-2026.11",
                chunkIndex,
                ChunkStrategy.STRUCTURE_AWARE,
                source.excerpt(),
                source.excerpt().codePointCount(0, source.excerpt().length()),
                source.page() == null ? List.of() : List.of(source.page()),
                List.of(new ChunkSource(source.page(), 1)),
                ChunkBoundaryType.DOCUMENT_END);
    }
}
