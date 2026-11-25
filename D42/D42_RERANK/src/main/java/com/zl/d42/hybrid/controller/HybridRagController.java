package com.zl.d42.hybrid.controller;

import com.zl.d42.common.Result;
import com.zl.d42.equipment.EquipmentInformationExtractor;
import com.zl.d42.hybrid.HybridRagRetrievalService;
import com.zl.d42.hybrid.HybridSearchService;
import com.zl.d42.hybrid.controller.HybridSearchController;
import com.zl.d42.knowledge.retrieval.service.SimilarityRetrievalService;
import com.zl.d42.rag.config.RagProperties;
import com.zl.d42.rag.dto.RagRequest;
import com.zl.d42.rag.model.RagResponse;
import com.zl.d42.rag.service.CitationExistenceValidator;
import com.zl.d42.rag.service.ContextAssemblyService;
import com.zl.d42.rag.service.EvidenceAssessmentService;
import com.zl.d42.rag.service.GenerationService;
import com.zl.d42.rag.service.PostGenerationValidator;
import com.zl.d42.rag.service.RagOrchestrator;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 混合 RAG 问答入口（P2.4）：{@code POST /api/v1/hybrid/rag/answer}。
 *
 * <p>注册方式（R02 实例 B 真实启动 L2 门禁修正）：类级 {@code @Controller} +
 * {@code @ConditionalOnProperty} 走组件扫描条件化注册——与
 * {@link HybridSearchController} 完全同模式。此前"裸 {@code @RequestMapping} 类注解 +
 * {@code HybridConfiguration @Bean} 工厂"方案实测无效：
 * {@code RequestMappingHandlerMapping#isHandler} 仅识别类上的
 * {@code @Controller}/{@code @RestController}（元注解），独立 @RequestMapping 不成立，
 * 端点落入 NoResourceFoundException(5000)。</p>
 *
 * <p>隔离要点不变：第二 {@link RagOrchestrator} 与其检索通道 {@link HybridRagRetrievalService}
 * 在构造器内手动 new、局部持有，不注册 Bean——容器内按类型注入点保持单候选，
 * 主链路零变更（H1/H2）。enabled=false 时本类不实例化，端点不存在（C04）。</p>
 *
 * <p>响应契约对齐 {@link RagResponse} 七态/十原因码；HTTP 恒 200、Result.code 判成败。
 * 注意：响应 {@code scoreSemantic} 沿用共享编排器的固定值 COSINE_SIMILARITY——该值
 * 描述的是决策链消费的分数语义（向量路原始余弦或 null），与检索端点的 RRF 语义区分，
 * 已在 文档如实标注。</p>
 */
@Controller
@ResponseBody
@ConditionalOnProperty(name = "app.hybrid.enabled", havingValue = "true")
@RequestMapping("/api/v1/hybrid/rag")
public class HybridRagController {

    private static final Logger log = LoggerFactory.getLogger(HybridRagController.class);

    private final RagOrchestrator hybridOrchestrator;

    public HybridRagController(SimilarityRetrievalService similarityRetrievalService,
                               RagProperties ragProperties,
                               HybridSearchService hybridSearchService,
                               EvidenceAssessmentService evidenceAssessmentService,
                               ContextAssemblyService contextAssemblyService,
                               GenerationService generationService,
                               PostGenerationValidator postGenerationValidator,
                               CitationExistenceValidator citationExistenceValidator,
                               EquipmentInformationExtractor equipmentExtractor) {
        HybridRagRetrievalService hybridRetrieval = new HybridRagRetrievalService(
                similarityRetrievalService, ragProperties, hybridSearchService);
        this.hybridOrchestrator = new RagOrchestrator(hybridRetrieval,
                evidenceAssessmentService, contextAssemblyService, generationService,
                postGenerationValidator, citationExistenceValidator, equipmentExtractor,
                ragProperties);
        log.info("混合 RAG 装配完成：第二 RagOrchestrator 为手动实例（非 Bean），"
                + "决策/生成/校验组件与主链路共享单例");
    }

    /**
     * 混合检索版 RAG 问答：检索来源为向量+BM25+RRF，决策/生成门禁与主链路完全同实现。
     */
    @PostMapping("/answer")
    public Result<RagResponse> answer(@Valid @RequestBody RagRequest request) {
        return Result.success(hybridOrchestrator.answer(request));
    }
}
