package com.zl.d34.rag.service;

import com.zl.d34.common.ResultCode;
import com.zl.d34.knowledge.retrieval.RetrievalException;
import com.zl.d34.rag.config.RagProperties;
import com.zl.d34.rag.dto.RagRequest;
import com.zl.d34.rag.model.AssembledContext;
import com.zl.d34.rag.model.GenerationOutcome;
import com.zl.d34.rag.model.RagResponse;
import com.zl.d34.rag.model.RagStatus;
import com.zl.d34.rag.model.RetrievedChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * RAG 最小闭环编排器：只编排检索 → 组装 → 生成三步，不直接写 VectorStore 调用，
 * 也不直接拼接 Prompt；空检索与无可用上下文时直接拒答，绝不进入生成阶段。
 */
@Slf4j
@Service
public class RagOrchestrator {

    private static final String SCORE_SEMANTIC = "COSINE_SIMILARITY";

    private final RagRetrievalService retrievalService;

    private final ContextAssemblyService assemblyService;

    private final GenerationService generationService;

    private final RagProperties properties;

    public RagOrchestrator(RagRetrievalService retrievalService,
                           ContextAssemblyService assemblyService,
                           GenerationService generationService,
                           RagProperties properties) {
        this.retrievalService = retrievalService;
        this.assemblyService = assemblyService;
        this.generationService = generationService;
        this.properties = properties;
        this.properties.validate();
    }

    /**
     * 执行一次 RAG 问答：五态互斥，空检索/无可用上下文分支可核验为"模型零调用"。
     *
     * @throws RetrievalException 请求为空/查询空白等参数错误，交给统一异常处理
     */
    public RagResponse answer(RagRequest request) {
        if (request == null) {
            throw new RetrievalException(ResultCode.PARAM_INVALID, "RAG 请求不能为空", "REQUEST_NULL");
        }
        String query = request.query();
        if (query == null || query.isBlank()) {
            throw new RetrievalException(ResultCode.PARAM_INVALID, "查询内容不能为空", "QUERY_BLANK");
        }
        long start = System.nanoTime();

        // 1. 检索：异常与空结果严格区分，不伪装成空检索
        List<RetrievedChunk> retrieved;
        long retrievalElapsedMillis;
        try {
            long retrievalStart = System.nanoTime();
            retrieved = retrievalService.retrieve(request);
            retrievalElapsedMillis = (System.nanoTime() - retrievalStart) / 1_000_000L;
        } catch (RetrievalException ex) {
            long elapsed = elapsedMillis(start);
            log.warn("RAG 检索失败：errorCode={}，message={}", ex.getErrorCode(), ex.getMessage());
            return new RagResponse(query, RagStatus.RETRIEVAL_FAILED,
                    "检索执行失败：" + ex.getMessage(), null, List.of(), List.of(), List.of(),
                    0, 0L, SCORE_SEMANTIC, 0, 0, 0, 0, null, false, elapsed);
        } catch (RuntimeException ex) {
            long elapsed = elapsedMillis(start);
            log.warn("RAG 检索异常，类型={}，message={}", ex.getClass().getSimpleName(), ex.getMessage());
            return new RagResponse(query, RagStatus.RETRIEVAL_FAILED,
                    "检索执行失败：" + ex.getClass().getSimpleName(), null,
                    List.of(), List.of(), List.of(),
                    0, 0L, SCORE_SEMANTIC, 0, 0, 0, 0, null, false, elapsed);
        }

        // 2. 空检索：拒绝回答，模型零调用
        if (retrieved.isEmpty()) {
            long elapsed = elapsedMillis(start);
            log.info("RAG 空检索：query=[{}]，返回 NO_HIT，模型零调用", truncate(query, 40));
            return new RagResponse(query, RagStatus.NO_HIT,
                    "检索成功但无命中，已拒绝调用模型", null,
                    List.of(), List.of(), List.of(),
                    0, retrievalElapsedMillis, SCORE_SEMANTIC, 0, 0, 0, 0, null, false, elapsed);
        }

        // 3. 组装：无可用上下文同样拒绝回答
        AssembledContext context = assemblyService.assemble(retrieved);
        if (!context.usable()) {
            long elapsed = elapsedMillis(start);
            log.info("RAG 无可用上下文：query=[{}]，命中={}，返回 NO_USABLE_CONTEXT，模型零调用",
                    truncate(query, 40), retrieved.size());
            return new RagResponse(query, RagStatus.NO_USABLE_CONTEXT,
                    "检索有结果但无可用上下文（低于最低分阈值或超出预算），已拒绝调用模型", null,
                    List.of(), List.of(), List.of(),
                    retrieved.size(), retrievalElapsedMillis, SCORE_SEMANTIC,
                    0, 0, 0, 0, null, false, elapsed);
        }

        // 4. 生成：异常统一归 GENERATION_FAILED，不伪装成功
        GenerationOutcome outcome = generationService.generate(context, query);
        long elapsed = elapsedMillis(start);
        if (outcome.failed()) {
            return new RagResponse(query, RagStatus.GENERATION_FAILED,
                    "生成执行失败：" + outcome.failureMessage(), null,
                    context.citations(), List.of(), List.of(),
                    retrieved.size(), retrievalElapsedMillis, SCORE_SEMANTIC,
                    context.chunkCount(), context.usedChars(), context.truncatedChars(),
                    outcome.droppedUnknownMarkers(), outcome.model(), outcome.mocked(), elapsed);
        }
        return new RagResponse(query, RagStatus.SUCCESS, RagStatus.SUCCESS.description(),
                outcome.content(), context.citations(), outcome.citedSourceIds(),
                outcome.citedSources(),
                retrieved.size(), retrievalElapsedMillis, SCORE_SEMANTIC,
                context.chunkCount(), context.usedChars(), context.truncatedChars(),
                outcome.droppedUnknownMarkers(), outcome.model(), outcome.mocked(), elapsed);
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }
}
