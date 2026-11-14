package com.zl.d31.knowledge.embedding;

import com.zl.d31.common.ResultCode;
import org.springframework.stereotype.Component;

/**
 * Validates vector shape and finite numeric values.
 */
@Component
public class EmbeddingVectorValidator {

    public void validate(float[] vector, int expectedDimensions) {
        if (vector == null || vector.length == 0) {
            throw invalid("Embedding 返回空向量");
        }
        if (vector.length != expectedDimensions) {
            throw invalid("Embedding 维度不一致，期望 " + expectedDimensions
                    + "，实际 " + vector.length);
        }
        double squaredNorm = 0.0;
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw invalid("Embedding 向量包含 NaN 或 Infinity");
            }
            squaredNorm += (double) value * value;
        }
        if (!Double.isFinite(squaredNorm) || squaredNorm <= 1.0e-20) {
            throw invalid("Embedding 向量为零向量或数值下溢");
        }
    }

    public double l2Norm(float[] vector) {
        double sum = 0.0;
        for (float value : vector) {
            sum += (double) value * value;
        }
        return Math.sqrt(sum);
    }

    private EmbeddingException invalid(String message) {
        return new EmbeddingException(ResultCode.EMBEDDING_RESPONSE_INVALID, message);
    }
}
