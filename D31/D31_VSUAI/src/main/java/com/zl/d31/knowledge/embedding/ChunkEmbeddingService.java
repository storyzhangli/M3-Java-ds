package com.zl.d31.knowledge.embedding;

import com.zl.d31.common.AiConfigurationException;
import com.zl.d31.common.ResultCode;
import com.zl.d31.knowledge.chunking.ChunkingResult;
import com.zl.d31.knowledge.chunking.DocumentChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Applies the configured EmbeddingModel to D31 chunks with validation and
 * bounded retry. It never persists vectors or logs chunk text.
 */
@Slf4j
@Service
public class ChunkEmbeddingService {

    private final EmbeddingModel embeddingModel;

    private final EmbeddingModelDescriptor descriptor;

    private final EmbeddingVectorValidator vectorValidator;

    private final RetryTemplate retryTemplate;

    public ChunkEmbeddingService(EmbeddingModel embeddingModel,
                                 EmbeddingModelDescriptor descriptor,
                                 EmbeddingVectorValidator vectorValidator,
                                 RetryTemplate retryTemplate) {
        this.embeddingModel = embeddingModel;
        this.descriptor = descriptor;
        this.vectorValidator = vectorValidator;
        this.retryTemplate = retryTemplate;
    }

    public EmbeddingModelDescriptor descriptor() {
        return descriptor;
    }

    public EmbeddingBatchResult embed(ChunkingResult chunkingResult) {
        if (chunkingResult == null || chunkingResult.chunks().isEmpty()) {
            throw inputInvalid("没有可向量化的 Chunk");
        }
        long startedNanos = System.nanoTime();
        List<ChunkEmbedding> embeddings = new ArrayList<>(chunkingResult.chunks().size());
        int totalCharacters = 0;
        int totalAttempts = 0;
        for (DocumentChunk chunk : chunkingResult.chunks()) {
            ChunkEmbedding embedded = embedChunk(chunk);
            embeddings.add(embedded);
            totalCharacters += embedded.inputCharacters();
            totalAttempts += embedded.attempts();
        }
        long elapsedMs = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        EmbeddingUsage usage = new EmbeddingUsage(
                "NOT_APPLICABLE",
                null,
                "LOCAL_MODEL_DOES_NOT_REPORT_TOKENS",
                totalAttempts);
        EmbeddingCostEstimate cost = new EmbeddingCostEstimate(
                descriptor.costPerMillionInputTokensUsd(),
                null,
                descriptor.costPerMillionInputTokensUsd().signum() == 0
                        ? java.math.BigDecimal.ZERO
                        : null,
                descriptor.provider() == EmbeddingProvider.LOCAL
                        ? "LOCAL_COMPUTE_NO_API_FEE"
                        : "TOKEN_USAGE_NOT_RETURNED");
        log.info("Embedding 批次完成，documentId={}，chunks={}，model={}，dimensions={}",
                chunkingResult.documentId(),
                embeddings.size(),
                descriptor.modelName(),
                descriptor.dimensions());
        return new EmbeddingBatchResult(
                chunkingResult.documentId(),
                chunkingResult.title(),
                chunkingResult.version(),
                descriptor,
                descriptor.indexMetadata(),
                embeddings.size(),
                totalCharacters,
                elapsedMs,
                usage,
                cost,
                embeddings);
    }

    public ChunkEmbedding embedChunk(DocumentChunk chunk) {
        if (chunk == null || !StringUtils.hasText(chunk.content())) {
            throw inputInvalid("Chunk 文本不能为空");
        }
        validateLength(chunk.content());
        AtomicInteger attempts = new AtomicInteger();
        float[] vector = retryTemplate.execute(context -> {
            attempts.incrementAndGet();
            return embeddingModel.embed(new Document(chunk.content()));
        });
        vectorValidator.validate(vector, descriptor.dimensions());
        return new ChunkEmbedding(
                chunk.documentId(),
                chunk.title(),
                chunk.version(),
                chunk.chunkIndex(),
                chunk.pageNumbers(),
                chunk.sources(),
                sha256(chunk.content()),
                codePointLength(chunk.content()),
                vector.length,
                vector,
                attempts.get());
    }

    private void validateLength(String text) {
        int actual = switch (descriptor.lengthUnit()) {
            case UNICODE_CODE_POINTS -> codePointLength(text);
            case TOKENS -> throw new AiConfigurationException(
                    "当前未配置 Tokenizer，不能安全验证 Token 长度");
        };
        if (actual > descriptor.maxInputLength()) {
            throw inputInvalid("Embedding 输入超过上限，上限="
                    + descriptor.maxInputLength() + "，实际=" + actual
                    + "，单位=" + descriptor.lengthUnit());
        }
    }

    private int codePointLength(String text) {
        return text.codePointCount(0, text.length());
    }

    private String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private EmbeddingException inputInvalid(String message) {
        return new EmbeddingException(ResultCode.EMBEDDING_INPUT_INVALID, message);
    }
}
