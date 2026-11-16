package com.zl.d33.knowledge.retrieval.support;

import com.zl.d33.knowledge.retrieval.dto.RetrievalHitView;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 检索结果适配器：把 Spring AI {@link Document} 映射为对外视图。
 *
 * <p>职责只有两件事：<b>安全解析 metadata</b>（类型不确定、可能缺失）与
 * <b>标注分数语义</b>。任何缺失字段都返回 null，绝不补造值；分数为 null 时同样如实返回。</p>
 */
public final class RetrievalHitMapper {

    /** Spring AI 1.1.8 PgVectorStore 的 {@code Document#getScore()} 为余弦相似度。 */
    public static final String SCORE_SEMANTIC = "COSINE_SIMILARITY";

    /** SQL {@code embedding <=> query} 的原始余弦距离，框架写入 metadata 的 {@code distance}。 */
    public static final String DISTANCE_SEMANTIC = "COSINE_DISTANCE";

    /**
     * 映射单条命中。
     *
     * @param document 框架返回文档（score 已由 RowMapper 计算）
     * @param rank     名次，从 1 开始
     */
    public RetrievalHitView toView(Document document, int rank) {
        Map<String, Object> metadata = document.getMetadata();
        return new RetrievalHitView(
                rank,
                document.getId(),
                asString(metadata.get("documentId")),
                asString(metadata.get("version")),
                asInteger(metadata.get("chunkIndex")),
                asString(metadata.get("title")),
                asIntegerList(metadata.get("pageNumbers")),
                asString(metadata.get("strategy")),
                document.getText(),
                document.getScore(),
                asDouble(metadata.get("distance")),
                SCORE_SEMANTIC,
                DISTANCE_SEMANTIC);
    }

    static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    static Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.valueOf(value.toString().strip());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static Double asDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.valueOf(value.toString().strip());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 页码解析：兼容 jsonb 反序列化出的 List、单个数字、逗号分隔字符串三种真实形态。
     */
    static List<Integer> asIntegerList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            List<Integer> result = new ArrayList<>(list.size());
            for (Object item : list) {
                Integer page = asInteger(item);
                if (page != null) {
                    result.add(page);
                }
            }
            return List.copyOf(result);
        }
        if (value instanceof Number || value instanceof String) {
            Integer single = asInteger(value);
            if (single != null) {
                return List.of(single);
            }
        }
        return List.of();
    }
}
