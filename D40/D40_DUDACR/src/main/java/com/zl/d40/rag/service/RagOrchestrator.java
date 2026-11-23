package com.zl.d40.rag.service;

import com.zl.d40.common.ResultCode;
import com.zl.d40.dto.CitationDto;
import com.zl.d40.equipment.EquipmentFacts;
import com.zl.d40.equipment.EquipmentInformationExtractor;
import com.zl.d40.knowledge.retrieval.RetrievalException;
import com.zl.d40.rag.config.RagProperties;
import com.zl.d40.rag.dto.RagRequest;
import com.zl.d40.rag.model.AssembledContext;
import com.zl.d40.rag.model.EvidenceAssessment;
import com.zl.d40.rag.model.GenerationOutcome;
import com.zl.d40.rag.model.RagDecision;
import com.zl.d40.rag.model.RagDecisionDetail;
import com.zl.d40.rag.model.RagReasonCode;
import com.zl.d40.rag.model.RagResponse;
import com.zl.d40.rag.model.RagStatus;
import com.zl.d40.rag.model.RetrievedChunk;
import com.zl.d40.rag.service.CitationExistenceValidator;
import com.zl.d40.rag.service.ContextAssemblyService;
import com.zl.d40.rag.service.GenerationService;
import com.zl.d40.rag.service.RagRetrievalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * RAG 证据链路编排器：检索 → 证据评估门禁 → 组装 → 生成 → 生成后校验。
 *
 * <p>决策轴（decision：ANSWER/CLARIFY/REFUSE/CONFLICT）与状态轴（status：七态）双轴分离；
 * REFUSE/CLARIFY/CONFLICT 三个分支可核验为"模型零调用"；生成后校验失败转为受控的
 * GENERATION_FAILED（reasonCode=UNSUPPORTED_CLAIM/GENERATION_VALIDATION_FAILED），不伪装成功。</p>
 */
@Slf4j
@Service
public class RagOrchestrator {

    private static final String SCORE_SEMANTIC = "COSINE_SIMILARITY";

    private final RagRetrievalService retrievalService;

    private final EvidenceAssessmentService evidenceAssessmentService;

    private final ContextAssemblyService assemblyService;

    private final GenerationService generationService;

    private final PostGenerationValidator postGenerationValidator;

    private final CitationExistenceValidator citationExistenceValidator;

    private final EquipmentInformationExtractor equipmentExtractor;

    private final RagProperties properties;

    public RagOrchestrator(RagRetrievalService retrievalService,
                           EvidenceAssessmentService evidenceAssessmentService,
                           ContextAssemblyService assemblyService,
                           GenerationService generationService,
                           PostGenerationValidator postGenerationValidator,
                           CitationExistenceValidator citationExistenceValidator,
                           EquipmentInformationExtractor equipmentExtractor,
                           RagProperties properties) {
        this.retrievalService = retrievalService;
        this.evidenceAssessmentService = evidenceAssessmentService;
        this.assemblyService = assemblyService;
        this.generationService = generationService;
        this.postGenerationValidator = postGenerationValidator;
        this.citationExistenceValidator = citationExistenceValidator;
        this.equipmentExtractor = equipmentExtractor;
        this.properties = properties;
        this.properties.validate();
    }

    /**
     * 执行一次 RAG 问答：七态互斥，拒答/澄清/冲突分支可核验为"模型零调用"。
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
                    0, 0L, SCORE_SEMANTIC, 0, 0, 0, 0, null, false, elapsed,
                    null, null, RagDecisionDetail.empty());
        } catch (RuntimeException ex) {
            long elapsed = elapsedMillis(start);
            log.warn("RAG 检索异常，类型={}，message={}", ex.getClass().getSimpleName(), ex.getMessage());
            return new RagResponse(query, RagStatus.RETRIEVAL_FAILED,
                    "检索执行失败：" + ex.getClass().getSimpleName(), null,
                    List.of(), List.of(), List.of(),
                    0, 0L, SCORE_SEMANTIC, 0, 0, 0, 0, null, false, elapsed,
                    null, null, RagDecisionDetail.empty());
        }

        // 2. 空检索：拒答，模型零调用
        if (retrieved.isEmpty()) {
            long elapsed = elapsedMillis(start);
            log.info("RAG 空检索：query=[{}]，decision=REFUSE/NO_EVIDENCE，模型零调用",
                    truncate(query, 40));
            return new RagResponse(query, RagStatus.NO_HIT,
                    "检索成功但无命中，已拒绝调用模型", null,
                    List.of(), List.of(), List.of(),
                    0, retrievalElapsedMillis, SCORE_SEMANTIC, 0, 0, 0, 0, null, false, elapsed,
                    RagDecision.REFUSE, RagReasonCode.NO_EVIDENCE,
                    new RagDecisionDetail(null, List.of(), List.of(),
                            RagReasonCode.NO_EVIDENCE.description(), EvidenceAssessment.UNCOVERED));
        }

        // 3. 证据评估门禁：生命周期过滤 → 相关性 → 澄清 → 冲突 → 充分性（确定性，零模型调用）
        EquipmentFacts facts = equipmentExtractor.extract(List.of(query));
        EvidenceAssessment assessment = evidenceAssessmentService.assess(retrieved, facts);
        long retrievalElapsedTotal = retrievalElapsedMillis;

        switch (assessment.decision()) {
            case CLARIFY -> {
                long elapsed = elapsedMillis(start);
                log.info("RAG 需澄清：query=[{}]，reason={}，模型零调用",
                        truncate(query, 40), assessment.reasonCode());
                return new RagResponse(query, RagStatus.CLARIFY_REQUIRED,
                        "缺少必要条件，需要澄清：" + assessment.reason(), null,
                        List.of(), List.of(), List.of(),
                        retrieved.size(), retrievalElapsedTotal, SCORE_SEMANTIC,
                        0, 0, 0, 0, null, false, elapsed,
                        RagDecision.CLARIFY, assessment.reasonCode(),
                        new RagDecisionDetail(assessment.reason(), assessment.missingInformation(),
                                List.of(), assessment.reason(), assessment.sufficiency()));
            }
            case CONFLICT -> {
                long elapsed = elapsedMillis(start);
                log.info("RAG 证据冲突：query=[{}]，冲突组={}，模型零调用",
                        truncate(query, 40), assessment.conflictGroups().size());
                return new RagResponse(query, RagStatus.EVIDENCE_CONFLICT,
                        assessment.reason(), null,
                        List.of(), List.of(), List.of(),
                        retrieved.size(), retrievalElapsedTotal, SCORE_SEMANTIC,
                        0, 0, 0, 0, null, false, elapsed,
                        RagDecision.CONFLICT, assessment.reasonCode(),
                        new RagDecisionDetail(null, List.of(), assessment.conflictGroups(),
                                assessment.reason(), assessment.sufficiency()));
            }
            case REFUSE -> {
                long elapsed = elapsedMillis(start);
                log.info("RAG 拒绝回答：query=[{}]，reasonCode={}，模型零调用",
                        truncate(query, 40), assessment.reasonCode());
                return new RagResponse(query, RagStatus.NO_USABLE_CONTEXT,
                        assessment.reason() + "，已拒绝调用模型", null,
                        List.of(), List.of(), List.of(),
                        retrieved.size(), retrievalElapsedTotal, SCORE_SEMANTIC,
                        0, 0, 0, 0, null, false, elapsed,
                        RagDecision.REFUSE, assessment.reasonCode(),
                        new RagDecisionDetail(null, List.of(), List.of(),
                                assessment.reason(), assessment.sufficiency()));
            }
            default -> {
                // ANSWER：继续组装与生成
            }
        }

        // 4. 组装：只消费评估通过的证据
        AssembledContext context = assemblyService.assemble(assessment.acceptedChunks());
        if (!context.usable()) {
            long elapsed = elapsedMillis(start);
            log.info("RAG 无可用上下文：query=[{}]，accepted={}，返回 NO_USABLE_CONTEXT，模型零调用",
                    truncate(query, 40), assessment.acceptedEvidenceIds().size());
            return new RagResponse(query, RagStatus.NO_USABLE_CONTEXT,
                    "证据评估通过但上下文组装无可用内容（低于最低分阈值或超出预算），已拒绝调用模型",
                    null, List.of(), List.of(), List.of(),
                    retrieved.size(), retrievalElapsedTotal, SCORE_SEMANTIC,
                    0, 0, 0, 0, null, false, elapsed,
                    RagDecision.REFUSE, RagReasonCode.INSUFFICIENT_EVIDENCE,
                    new RagDecisionDetail(null, List.of(), List.of(),
                            RagReasonCode.INSUFFICIENT_EVIDENCE.description(),
                            EvidenceAssessment.INSUFFICIENT));
        }

        // 5. 生成：异常统一归 GENERATION_FAILED，不伪装成功
        GenerationOutcome outcome = generationService.generate(context, query);
        if (outcome.failed()) {
            long elapsed = elapsedMillis(start);
            return new RagResponse(query, RagStatus.GENERATION_FAILED,
                    "生成执行失败：" + outcome.failureMessage(), null,
                    context.citations(), List.of(), List.of(),
                    retrieved.size(), retrievalElapsedTotal, SCORE_SEMANTIC,
                    context.chunkCount(), context.usedChars(), context.truncatedChars(),
                    outcome.droppedUnknownMarkers(), outcome.model(), outcome.mocked(), elapsed,
                    RagDecision.REFUSE, RagReasonCode.GENERATION_VALIDATION_FAILED,
                    new RagDecisionDetail(null, List.of(), List.of(),
                            RagReasonCode.GENERATION_VALIDATION_FAILED.description(),
                            assessment.sufficiency()));
        }

        // 6. 生成后校验：事实支持关系（引用 ID 合法性已由 CitationRegistry 清洗）
        PostGenerationValidator.ValidationOutcome validation = postGenerationValidator.validate(
                outcome.content(), assessment.acceptedChunks(), facts);
        long elapsed = elapsedMillis(start);
        if (!validation.passed()) {
            log.info("RAG 生成后校验失败：query=[{}]，reasonCode={}，detail={}",
                    truncate(query, 40), validation.reasonCode(), validation.detail());
            return new RagResponse(query, RagStatus.GENERATION_FAILED,
                    "生成结果未通过事实支持校验：" + validation.detail() + "，已拦截", null,
                    context.citations(), List.of(), List.of(),
                    retrieved.size(), retrievalElapsedTotal, SCORE_SEMANTIC,
                    context.chunkCount(), context.usedChars(), context.truncatedChars(),
                    outcome.droppedUnknownMarkers(), outcome.model(), outcome.mocked(), elapsed,
                    RagDecision.REFUSE, validation.reasonCode(),
                    new RagDecisionDetail(null, List.of(), List.of(),
                            validation.detail(), assessment.sufficiency()));
        }
        // 7. 引用存在性核验：检索后、响应前文档/Chunk 被删的删除竞态（批量，避免 N+1）
        CitationExistenceValidator.ExistenceResult existence = citationExistenceValidator.validate(
                outcome.citedSources().stream().map(CitationDto::chunkId).toList());
        if (existence.hasMissing()) {
            log.info("RAG 引用存在性核验失败：query=[{}]，失效 chunkId={}",
                    truncate(query, 40), existence.missingChunkIds());
            return new RagResponse(query, RagStatus.GENERATION_FAILED,
                    "引用的来源在响应返回前已被删除或失效，已拦截", null,
                    context.citations(), List.of(), List.of(),
                    retrieved.size(), retrievalElapsedTotal, SCORE_SEMANTIC,
                    context.chunkCount(), context.usedChars(), context.truncatedChars(),
                    outcome.droppedUnknownMarkers(), outcome.model(), outcome.mocked(), elapsed,
                    RagDecision.REFUSE, RagReasonCode.CITATION_TARGET_DELETED,
                    new RagDecisionDetail(null, List.of(), List.of(),
                            RagReasonCode.CITATION_TARGET_DELETED.description(),
                            assessment.sufficiency()));
        }
        return new RagResponse(query, RagStatus.SUCCESS, RagStatus.SUCCESS.description(),
                outcome.content(), context.citations(), outcome.citedSourceIds(),
                outcome.citedSources(),
                retrieved.size(), retrievalElapsedTotal, SCORE_SEMANTIC,
                context.chunkCount(), context.usedChars(), context.truncatedChars(),
                outcome.droppedUnknownMarkers(), outcome.model(), outcome.mocked(), elapsed,
                RagDecision.ANSWER, null,
                new RagDecisionDetail(null, List.of(), List.of(),
                        RagStatus.SUCCESS.description(), assessment.sufficiency()));
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
