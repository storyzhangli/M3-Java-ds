package com.zl.d30.knowledge.embedding;

import com.zl.d30.common.ResultCode;
import com.zl.d30.knowledge.embedding.EmbeddingException;
import com.zl.d30.knowledge.embedding.EmbeddingVectorValidator;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.util.StringUtils;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Deterministic local embedding model using hashed word and character n-grams.
 *
 * <p>This is a real, dependency-free lexical embedding implementation. It is
 * not a pretrained neural model, so it provides reproducible vectorization but
 * weaker semantic generalization than a cloud embedding model.</p>
 */
public class LocalHashNgramEmbeddingModel implements EmbeddingModel {

    private final EmbeddingModelDescriptor descriptor;

    private final EmbeddingVectorValidator validator;

    public LocalHashNgramEmbeddingModel(EmbeddingModelDescriptor descriptor,
                                        EmbeddingVectorValidator validator) {
        this.descriptor = descriptor;
        this.validator = validator;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        if (request == null || request.getInstructions() == null
                || request.getInstructions().isEmpty()) {
            throw inputInvalid("Embedding 请求不能为空");
        }
        List<Embedding> results = new ArrayList<>(request.getInstructions().size());
        for (int index = 0; index < request.getInstructions().size(); index++) {
            results.add(new Embedding(embedText(request.getInstructions().get(index)), index));
        }
        EmbeddingResponseMetadata metadata = new EmbeddingResponseMetadata();
        metadata.setModel(descriptor.modelName());
        return new EmbeddingResponse(results, metadata);
    }

    @Override
    public float[] embed(Document document) {
        if (document == null) {
            throw inputInvalid("Embedding Document 不能为空");
        }
        return embedText(document.getText());
    }

    @Override
    public int dimensions() {
        return descriptor.dimensions();
    }

    public EmbeddingModelDescriptor descriptor() {
        return descriptor;
    }

    public float[] embedText(String text) {
        if (text == null || !StringUtils.hasText(text)) {
            throw inputInvalid("Embedding 输入不能为空或纯空白");
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .strip();
        Map<String, Integer> features = extractFeatures(normalized);
        if (features.isEmpty()) {
            features.put("raw:" + normalized, 1);
        }
        double[] raw = new double[descriptor.dimensions()];
        features.forEach((feature, count) -> {
            byte[] digest = sha256(feature);
            int index = Math.floorMod(ByteBuffer.wrap(digest, 0, 4).getInt(),
                    descriptor.dimensions());
            double sign = (digest[4] & 1) == 0 ? 1.0 : -1.0;
            double weight = 1.0 + Math.log(count);
            raw[index] += sign * weight;
        });

        double squaredNorm = 0.0;
        for (double value : raw) {
            squaredNorm += value * value;
        }
        double norm = Math.sqrt(squaredNorm);
        if (!Double.isFinite(norm) || norm <= 1.0e-20) {
            throw new EmbeddingException(
                    ResultCode.EMBEDDING_RESPONSE_INVALID,
                    "本地 Embedding 生成零向量");
        }
        float[] vector = new float[raw.length];
        for (int index = 0; index < raw.length; index++) {
            vector[index] = (float) (raw[index] / norm);
        }
        validator.validate(vector, descriptor.dimensions());
        return vector;
    }

    private Map<String, Integer> extractFeatures(String text) {
        Map<String, Integer> features = new TreeMap<>();
        StringBuilder word = new StringBuilder();
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isLetterOrDigit(codePoint)) {
                word.appendCodePoint(codePoint);
            } else {
                addWordFeatures(features, word.toString());
                word.setLength(0);
            }
        }
        addWordFeatures(features, word.toString());
        return features;
    }

    private void addWordFeatures(Map<String, Integer> features, String value) {
        if (value.isBlank()) {
            return;
        }
        int[] codePoints = value.codePoints().toArray();
        features.merge("w:" + value, 1, Integer::sum);
        for (int codePoint : codePoints) {
            features.merge("c:" + new String(Character.toChars(codePoint)), 1, Integer::sum);
        }
        for (int index = 0; index + 1 < codePoints.length; index++) {
            features.merge("b:" + new String(codePoints, index, 2), 1, Integer::sum);
        }
        for (int index = 0; index + 2 < codePoints.length; index++) {
            features.merge("t:" + new String(codePoints, index, 3), 1, Integer::sum);
        }
    }

    private byte[] sha256(String feature) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(feature.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private EmbeddingException inputInvalid(String message) {
        return new EmbeddingException(ResultCode.EMBEDDING_INPUT_INVALID, message);
    }
}
