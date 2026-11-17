package com.zl.d34.rag.model;

import com.zl.d34.dto.CitationDto;
import com.zl.d34.rag.model.RagStatus;

import java.util.List;

/**
 * RAG 最小闭环响应（数据对象，不含业务逻辑）。
 *
 * <p>来源语义声明（诚实约束）：{@code sources} 是<b>进入上下文、提供给模型的证据来源</b>，
 * 不是"模型实际引用来源"；模型实际引用见 {@code citedSourceIds} 与 {@code citedSources}。
 * 本项目无法验证模型逐条引用真实性，因此不声称来源均被模型引用。</p>
 *
 * @param query                  原始问题
 * @param status                 链路状态（五态）
 * @param statusMessage          状态说明（拒答原因/失败摘要）
 * @param answer                 清洗后的回答（仅 SUCCESS 非空）
 * @param sources                提供给模型的证据来源（进入上下文的全部 Chunk）
 * @param citedSourceIds         模型答案中实际出现的合法引用编号
 * @param citedSources           模型实际引用的来源（无引用时为空列表）
 * @param retrievalHitCount      原始检索命中数（未过滤）
 * @param retrievalElapsedMillis 检索耗时
 * @param scoreSemantic          检索分数语义（COSINE_SIMILARITY）
 * @param contextChunkCount      进入上下文的片段数
 * @param contextUsedChars       上下文实际字符数（Unicode 码点，不伪称 Token）
 * @param contextTruncatedChars  因预算截断的字符数
 * @param droppedUnknownMarkers  被清洗的未知引用编号数
 * @param generationModel        生成模型标识（未生成时为 null）
 * @param mocked                 生成是否为离线模拟
 * @param elapsedMillis          全链路耗时
 */
public record RagResponse(String query,
                          RagStatus status,
                          String statusMessage,
                          String answer,
                          List<CitationDto> sources,
                          List<String> citedSourceIds,
                          List<CitationDto> citedSources,
                          int retrievalHitCount,
                          long retrievalElapsedMillis,
                          String scoreSemantic,
                          int contextChunkCount,
                          int contextUsedChars,
                          int contextTruncatedChars,
                          int droppedUnknownMarkers,
                          String generationModel,
                          boolean mocked,
                          long elapsedMillis) {

    /** 固定语义常量：来源列表 = 提供给模型的证据来源。 */
    public static final String SOURCES_SEMANTIC = "PROVIDED_TO_MODEL";
}
