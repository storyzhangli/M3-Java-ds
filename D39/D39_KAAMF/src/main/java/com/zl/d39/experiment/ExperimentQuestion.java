package com.zl.d39.experiment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d39.experiment.ExperimentReasonCode;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 实验问题（从 D25 questions.jsonl 读取，取 Q01–Q10）。
 *
 * @param id                问题编号
 * @param question          问题原文
 * @param category          问题类别
 * @param expectedChunkIds  Gold 标注逻辑片段 ID
 * @param retrieveEnabled   是否启用检索
 * @param citationRequired  是否要求引用
 */
public record ExperimentQuestion(String id,
                                 String question,
                                 String category,
                                 List<String> expectedChunkIds,
                                 boolean retrieveEnabled,
                                 boolean citationRequired) {

    /**
     * 从 JSONL 输入流加载前 limit 个可检索问题。
     */
    public static List<ExperimentQuestion> load(InputStream input, int limit,
                                                ObjectMapper objectMapper) {
        List<ExperimentQuestion> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && result.size() < limit) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = objectMapper.readTree(line);
                ExperimentQuestion question = new ExperimentQuestion(
                        node.path("id").asText(),
                        node.path("question").asText(),
                        node.path("category").asText(),
                        toStringList(node.get("expected_chunk_ids")),
                        node.path("retrieve_enabled").asBoolean(false),
                        node.path("citation_required").asBoolean(false));
                if (question.retrieveEnabled()) {
                    result.add(question);
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException(
                    ExperimentReasonCode.CONFIG_INVALID.code() + ": 问题集加载失败", ex);
        }
        return List.copyOf(result);
    }

    private static List<String> toStringList(JsonNode array) {
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : array) {
            values.add(item.asText());
        }
        return List.copyOf(values);
    }
}
