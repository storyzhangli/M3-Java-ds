package com.zl.d33.rag.support;

import com.zl.d33.knowledge.retrieval.dto.RetrievalHitView;
import com.zl.d33.knowledge.retrieval.dto.RetrievalResponse;
import com.zl.d33.rag.config.RagProperties;
import com.zl.d33.rag.model.RetrievedChunk;
import com.zl.d33.service.AiChatResult;

import java.util.List;

/**
 * 九类场景共用测试构造器：全部命中数据来自真实结构（RetrievalHitView），不编造字段。
 */
public final class RagTestFixtures {

    private RagTestFixtures() {
    }

    public static RetrievalHitView hit(int rank, String chunkId, String documentId,
                                       String title, String content, double score) {
        return new RetrievalHitView(
                rank, chunkId, documentId, "v1", rank,
                title, List.of(rank), "FIXED_LENGTH", content,
                score, 1.0d - score, "COSINE_SIMILARITY", "COSINE_DISTANCE");
    }

    public static RetrievalResponse response(String query, List<RetrievalHitView> hits) {
        return new RetrievalResponse(query, null, 5, null, hits, hits.size(), 10L,
                "COSINE_SIMILARITY", "COSINE_DISTANCE",
                new RetrievalResponse.QueryEmbeddingSummary(384, "d33-local-384-v1",
                        "local-hash-ngram-v1", 5L));
    }

    public static RetrievedChunk chunk(String chunkId, String title, String content, double score) {
        return new RetrievedChunk(chunkId, "DOC-" + chunkId, "v1", 1, title,
                List.of(1), "FIXED_LENGTH", content, score, 1.0d - score);
    }

    public static AiChatResult answer(String content) {
        return new AiChatResult("deepseek", "deepseek-flash", false, false, content, null);
    }

    public static RagProperties properties(int maxContextChars, double minScore) {
        RagProperties properties = new RagProperties();
        properties.setMaxContextChars(maxContextChars);
        properties.setMinScore(minScore);
        properties.validate();
        return properties;
    }

    public static RagProperties defaultProperties() {
        RagProperties properties = new RagProperties();
        properties.validate();
        return properties;
    }

    /** 由重复字符生成指定长度的内容。 */
    public static String contentOf(String seed, int codePoints) {
        StringBuilder sb = new StringBuilder();
        while (sb.codePointCount(0, sb.length()) < codePoints) {
            sb.append(seed);
        }
        String full = sb.toString();
        int target = offsetByCodePoints(full, codePoints);
        return full.substring(0, target);
    }

    private static int offsetByCodePoints(String text, int codePoints) {
        int count = 0;
        int index = 0;
        while (index < text.length() && count < codePoints) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);
            count++;
        }
        return index;
    }
}
