package com.zl.d44.rag.model;

import com.zl.d44.dto.CitationDto;
import com.zl.d44.knowledge.CitationRegistry;
import com.zl.d44.knowledge.KnowledgeChunk;

import java.util.List;

/**
 * 上下文组装结果：只有已进入上下文的真实 Chunk 才出现在这里。
 *
 * @param registry        请求级引用注册表（编号只在本轮有效）
 * @param contextText     发送给模型的资料块（含防注入约束与预算截断后的摘录）
 * @param citations       进入上下文的全部来源（提供给模型的证据来源，不宣称被模型引用）
 * @param chunks          截断后实际进入上下文的片段（去重、排序、预算内）
 * @param chunkCount      进入上下文的片段数
 * @param usedChars       上下文实际使用字符数（Unicode 码点）
 * @param truncatedChars  因预算被截断的字符总数（含未进入上下文的完整片段长度）
 * @param usable          是否达到可用标准（字符数 >= min-usable-chars）
 */
public record AssembledContext(CitationRegistry registry,
                               String contextText,
                               List<CitationDto> citations,
                               List<KnowledgeChunk> chunks,
                               int chunkCount,
                               int usedChars,
                               int truncatedChars,
                               boolean usable) {

    /** 无可用上下文的空结果（不携带注册表，调用方不得进入生成阶段）。 */
    public static AssembledContext empty() {
        return new AssembledContext(new CitationRegistry(List.of()), "",
                List.of(), List.of(), 0, 0, 0, false);
    }
}
