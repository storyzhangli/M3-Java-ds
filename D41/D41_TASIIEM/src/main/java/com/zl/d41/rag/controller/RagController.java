package com.zl.d41.rag.controller;

import com.zl.d41.common.Result;
import com.zl.d41.rag.dto.RagRequest;
import com.zl.d41.rag.model.RagResponse;
import com.zl.d41.rag.service.RagOrchestrator;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RAG 最小闭环 HTTP 入口。
 *
 * <p>HTTP 层恒返回 200，链路状态由 {@link RagResponse#status()} 五态承载；
 * 入参经 {@link Valid} 触发 Bean Validation 校验；请求级参数错误（空请求/空查询）
 * 抛 {@code RetrievalException}，走全局统一处理。</p>
 */
@RestController
@RequestMapping("/api/v1/rag")
public class RagController {

    private final RagOrchestrator orchestrator;

    public RagController(RagOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /**
     * 一次 RAG 问答：返回回答内容 + 来源列表 + 检索/生成状态。
     */
    @PostMapping("/answer")
    public Result<RagResponse> answer(@Valid @RequestBody RagRequest request) {
        return Result.success(orchestrator.answer(request));
    }
}
