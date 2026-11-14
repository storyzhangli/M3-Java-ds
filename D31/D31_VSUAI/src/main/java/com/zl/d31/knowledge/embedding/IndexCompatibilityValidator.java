package com.zl.d31.knowledge.embedding;

import com.zl.d31.common.ResultCode;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Prevents mixing vectors from different model spaces or incompatible indexes.
 */
@Component
public class IndexCompatibilityValidator {

    public IndexCompatibility check(VectorIndexMetadata index,
                                    EmbeddingModelDescriptor candidate) {
        if (index == null || candidate == null) {
            return new IndexCompatibility(
                    false, "索引或候选模型元数据为空", index, candidate);
        }
        if (index.provider() != candidate.provider()) {
            return incompatible("provider 不一致", index, candidate);
        }
        if (!Objects.equals(index.modelName(), candidate.modelName())) {
            return incompatible("模型名称不一致", index, candidate);
        }
        if (!Objects.equals(index.modelVersion(), candidate.modelVersion())) {
            return incompatible("模型版本不一致", index, candidate);
        }
        if (index.dimensions() != candidate.dimensions()) {
            return incompatible("向量维度不一致", index, candidate);
        }
        if (!Objects.equals(index.vectorSpaceId(), candidate.vectorSpaceId())) {
            return incompatible("向量空间标识不一致", index, candidate);
        }
        if (!Objects.equals(index.indexVersion(), candidate.indexVersion())) {
            return incompatible("索引版本不一致", index, candidate);
        }
        if (index.distanceMetric() != candidate.distanceMetric()) {
            return incompatible("距离度量不一致", index, candidate);
        }
        return new IndexCompatibility(true, "兼容", index, candidate);
    }

    public void requireCompatible(VectorIndexMetadata index,
                                  EmbeddingModelDescriptor candidate) {
        IndexCompatibility compatibility = check(index, candidate);
        if (!compatibility.compatible()) {
            throw new EmbeddingException(
                    ResultCode.VECTOR_INDEX_INCOMPATIBLE,
                    "禁止混用不兼容向量: " + compatibility.reason());
        }
    }

    private IndexCompatibility incompatible(String reason,
                                            VectorIndexMetadata index,
                                            EmbeddingModelDescriptor candidate) {
        return new IndexCompatibility(false, reason, index, candidate);
    }
}
