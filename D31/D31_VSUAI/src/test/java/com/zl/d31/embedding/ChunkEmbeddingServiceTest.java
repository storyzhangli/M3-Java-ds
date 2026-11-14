package com.zl.d31.embedding;

import com.zl.d31.common.ResultCode;
import com.zl.d31.model.ModelFailureCategory;
import com.zl.d31.model.NonRetryableModelException;
import com.zl.d31.model.RetryableModelException;
import com.zl.d31.knowledge.chunking.*;
import com.zl.d31.knowledge.embedding.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.retry.support.RetryTemplate;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunkEmbeddingServiceTest {

    private final EmbeddingVectorValidator vectorValidator = new EmbeddingVectorValidator();

    @Test
    void localBatchShouldPreserveChunkMetadataAndProduceValidVectors() {
        EmbeddingProperties properties = new EmbeddingProperties();
        EmbeddingModelDescriptor descriptor = EmbeddingModelDescriptor.from(properties);
        ChunkEmbeddingService service = new ServiceFactory().withLocal(descriptor);
        DocumentChunk first = chunk(1, "冷却液不足时先停机，再补充同型号冷却液。");
        DocumentChunk second = chunk(2, "Check USB connection and restart the printer.");

        EmbeddingBatchResult result = service.embed(chunkingResult(first, second));

        assertThat(result.chunkCount()).isEqualTo(2);
        assertThat(result.model().dimensions()).isEqualTo(384);
        assertThat(result.usage().inputTokens()).isNull();
        assertThat(result.usage().status()).isEqualTo("NOT_APPLICABLE");
        assertThat(result.cost().estimatedApiCostUsd()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.embeddings()).allSatisfy(embedding -> {
            assertThat(embedding.vector()).hasSize(384);
            vectorValidator.validate(embedding.vector(), 384);
            assertThat(embedding.documentId()).isEqualTo("doc-embed");
            assertThat(embedding.title()).isEqualTo("设备手册");
            assertThat(embedding.version()).isEqualTo("v2");
            assertThat(embedding.pageNumbers()).containsExactly(3);
            assertThat(embedding.sources()).containsExactly(new ChunkSource(3, 1));
        });
        assertThat(result.embeddings().get(0).inputHash())
                .isNotEqualTo(result.embeddings().get(1).inputHash());
    }

    @Test
    void repeatedSameChunkShouldHaveSameDimensionsAndVectorValues() {
        EmbeddingProperties properties = new EmbeddingProperties();
        EmbeddingModelDescriptor descriptor = EmbeddingModelDescriptor.from(properties);
        ChunkEmbeddingService service = new ServiceFactory().withLocal(descriptor);

        ChunkEmbedding first = service.embedChunk(chunk(1, "重复文本 stability test 123"));
        ChunkEmbedding second = service.embedChunk(chunk(1, "重复文本 stability test 123"));

        assertThat(first.dimensions()).isEqualTo(second.dimensions());
        assertThat(first.vector()).containsExactly(second.vector());
        assertThat(first.inputHash()).isEqualTo(second.inputHash());
    }

    @Test
    void nullEmptyAndOverlongTextShouldBeRejectedWithoutCallingModel() {
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setMaxInputLength(5);
        EmbeddingModelDescriptor descriptor = EmbeddingModelDescriptor.from(properties);
        ScriptedModel model = new ScriptedModel(descriptor.dimensions(), List.of(validValue(384)));
        ChunkEmbeddingService service = new ChunkEmbeddingService(
                model, descriptor, vectorValidator, noRetryTemplate());

        assertInputInvalid(() -> service.embedChunk(null));
        assertInputInvalid(() -> service.embedChunk(chunk(1, "   ")));
        assertInputInvalid(() -> service.embedChunk(chunk(1, "超过五个码点")));
        assertThat(model.calls).isZero();
    }

    @Test
    void retryableFailureShouldRetryAndRecordAttempts() {
        EmbeddingModelDescriptor descriptor = descriptor(4);
        ScriptedModel model = new ScriptedModel(4, List.of(
                new RetryableModelException(ModelFailureCategory.RATE_LIMITED, 429, null),
                new RetryableModelException(ModelFailureCategory.UPSTREAM_UNAVAILABLE, 503, null),
                new float[]{1, 0, 0, 0}));
        ChunkEmbeddingService service = new ChunkEmbeddingService(
                model, descriptor, vectorValidator, retryTemplate(3));

        ChunkEmbedding embedding = service.embedChunk(chunk(1, "retry succeeds"));

        assertThat(embedding.attempts()).isEqualTo(3);
        assertThat(model.calls).isEqualTo(3);
    }

    @Test
    void permanentFailureShouldNotRetry() {
        EmbeddingModelDescriptor descriptor = descriptor(4);
        ScriptedModel model = new ScriptedModel(4, List.of(
                new NonRetryableModelException(
                        ModelFailureCategory.AUTHENTICATION_FAILED, 401)));
        ChunkEmbeddingService service = new ChunkEmbeddingService(
                model, descriptor, vectorValidator, retryTemplate(3));

        assertThatThrownBy(() -> service.embedChunk(chunk(1, "invalid key simulation")))
                .isInstanceOf(NonRetryableModelException.class);
        assertThat(model.calls).isEqualTo(1);
    }

    @Test
    void retryExhaustionShouldStopAtConfiguredAttempts() {
        EmbeddingModelDescriptor descriptor = descriptor(4);
        ScriptedModel model = new ScriptedModel(4, List.of(
                new RetryableModelException(ModelFailureCategory.READ_TIMEOUT, 408, null),
                new RetryableModelException(ModelFailureCategory.READ_TIMEOUT, 408, null)));
        ChunkEmbeddingService service = new ChunkEmbeddingService(
                model, descriptor, vectorValidator, retryTemplate(2));

        assertThatThrownBy(() -> service.embedChunk(chunk(1, "timeout")))
                .isInstanceOf(RetryableModelException.class);
        assertThat(model.calls).isEqualTo(2);
    }

    @Test
    void invalidModelResponseShouldBeRejectedAfterSingleAttempt() {
        EmbeddingModelDescriptor descriptor = descriptor(3);
        ScriptedModel model = new ScriptedModel(3, List.of(new float[]{1, Float.NaN, 0}));
        ChunkEmbeddingService service = new ChunkEmbeddingService(
                model, descriptor, vectorValidator, retryTemplate(3));

        assertThatThrownBy(() -> service.embedChunk(chunk(1, "invalid vector")))
                .isInstanceOfSatisfying(EmbeddingException.class, ex ->
                        assertThat(ex.getResultCode())
                                .isEqualTo(ResultCode.EMBEDDING_RESPONSE_INVALID));
        assertThat(model.calls).isEqualTo(1);
    }

    private EmbeddingModelDescriptor descriptor(int dimensions) {
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setDimensions(dimensions);
        properties.setMaxInputLength(1000);
        return EmbeddingModelDescriptor.from(properties);
    }

    private DocumentChunk chunk(int index, String content) {
        return new DocumentChunk(
                "doc-embed",
                "设备手册",
                "v2",
                index,
                ChunkStrategy.STRUCTURE_AWARE,
                content,
                content == null ? 0 : content.length(),
                List.of(3),
                List.of(new ChunkSource(3, 1)),
                ChunkBoundaryType.DOCUMENT_END);
    }

    private ChunkingResult chunkingResult(DocumentChunk... chunks) {
        return new ChunkingResult(
                "doc-embed",
                "设备手册",
                "v2",
                ChunkStrategy.STRUCTURE_AWARE,
                "UNICODE_CODE_POINTS",
                480,
                60,
                80,
                720,
                100,
                100,
                new ChunkingMetrics(
                        chunks.length, 50, 1, 100,
                        Map.of(), Map.of()),
                List.of(chunks));
    }

    private RetryTemplate retryTemplate(int maxAttempts) {
        return RetryTemplate.builder()
                .maxAttempts(maxAttempts)
                .fixedBackoff(1)
                .retryOn(RetryableModelException.class)
                .build();
    }

    private RetryTemplate noRetryTemplate() {
        return RetryTemplate.builder().maxAttempts(1).build();
    }

    private float[] validValue(int dimensions) {
        float[] vector = new float[dimensions];
        vector[0] = 1;
        return vector;
    }

    private void assertInputInvalid(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(EmbeddingException.class, ex ->
                        assertThat(ex.getResultCode())
                                .isEqualTo(ResultCode.EMBEDDING_INPUT_INVALID));
    }

    private final class ServiceFactory {
        private ChunkEmbeddingService withLocal(EmbeddingModelDescriptor descriptor) {
            LocalHashNgramEmbeddingModel model =
                    new LocalHashNgramEmbeddingModel(descriptor, vectorValidator);
            return new ChunkEmbeddingService(
                    model, descriptor, vectorValidator, retryTemplate(2));
        }
    }

    private static final class ScriptedModel implements EmbeddingModel {

        private final int dimensions;

        private final Queue<Object> outcomes = new ArrayDeque<>();

        private int calls;

        private ScriptedModel(int dimensions, List<Object> outcomes) {
            this.dimensions = dimensions;
            this.outcomes.addAll(outcomes);
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public float[] embed(Document document) {
            calls++;
            Object outcome = outcomes.remove();
            if (outcome instanceof RuntimeException exception) {
                throw exception;
            }
            return ((float[]) outcome).clone();
        }

        @Override
        public int dimensions() {
            return dimensions;
        }
    }
}
