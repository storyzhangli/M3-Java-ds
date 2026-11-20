package com.zl.d37.evaluator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d37.evaluator.CorpusModel.CorpusChunk;
import com.zl.d37.evaluator.CorpusModel.CorpusDocument;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 冻结数据集加载器：问题集 + 语料 + SHA-256 校验 + 页码到逻辑片段映射。
 *
 * <p>page -&gt; logicalChunkId 是一一映射（每个语料片段有唯一非空物理页码），
 * 该映射是 PGVECTOR 后端命中（稳定向量块携带 pageNumbers）反查逻辑片段 ID 的唯一依据。</p>
 */
public final class DatasetLoader {

    private final ObjectMapper objectMapper;

    public DatasetLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<EvalQuestion> loadQuestions(Path file) throws IOException {
        List<EvalQuestion> questions = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            questions.add(objectMapper.readValue(line, EvalQuestion.class));
        }
        return List.copyOf(questions);
    }

    public List<CorpusDocument> loadCorpus(Path file) throws IOException {
        List<CorpusDocument> documents = objectMapper.readValue(
                file.toFile(), new TypeReference<List<CorpusDocument>>() {
                });
        return List.copyOf(documents);
    }

    /**
     * 构建物理页码到逻辑片段 ID 的一一映射；出现重复或缺失页码即抛出异常（不静默猜测）。
     */
    public Map<Integer, String> buildPageToLogicalChunkId(List<CorpusDocument> documents) {
        Map<Integer, String> mapping = new LinkedHashMap<>();
        for (CorpusDocument document : documents) {
            for (CorpusChunk chunk : document.chunks()) {
                if (chunk.page() == null) {
                    throw new IllegalStateException(
                            "片段 " + chunk.chunkId() + " 缺少物理页码，无法稳定映射");
                }
                String previous = mapping.putIfAbsent(chunk.page(), chunk.chunkId());
                if (previous != null) {
                    throw new IllegalStateException(
                            "重复页码 " + chunk.page() + "：" + previous + " 与 " + chunk.chunkId());
                }
            }
        }
        return Map.copyOf(mapping);
    }

    public String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = Files.readAllBytes(file);
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
