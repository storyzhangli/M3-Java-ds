package com.zl.d39.rag.model;

import com.zl.d39.dto.CitationDto;

import java.util.List;

/**
 * 一次生成调用的结果（成功或失败都由此对象承载，不向上抛运行时异常）。
 *
 * @param content               清洗后的回答文本（失败时为 null）
 * @param failed                是否失败（含模型超时等所有运行时异常）
 * @param failureMessage        失败原因摘要（不含堆栈与敏感信息）
 * @param model                 实际生效的模型标识
 * @param mocked                是否为离线模拟结果
 * @param thinkingEnabled       是否启用思考模式（RAG 最小闭环固定 false）
 * @param droppedUnknownMarkers 答案中被清洗的未知引用编号数
 * @param citedSourceIds        答案中实际出现的合法引用编号（按首次出现顺序）
 * @param citedSources          实际被引用的来源（去重、顺序稳定）
 */
public record GenerationOutcome(String content,
                                boolean failed,
                                String failureMessage,
                                String model,
                                boolean mocked,
                                boolean thinkingEnabled,
                                int droppedUnknownMarkers,
                                List<String> citedSourceIds,
                                List<CitationDto> citedSources) {

    public static GenerationOutcome failure(RuntimeException ex) {
        return new GenerationOutcome(null, true,
                ex.getClass().getSimpleName() + ": " + safeMessage(ex),
                null, false, false, 0, List.of(), List.of());
    }

    private static String safeMessage(RuntimeException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return "无异常信息";
        }
        return message.length() <= 200 ? message : message.substring(0, 200);
    }
}
