package com.zl.d44.rag.service;

import com.zl.d44.knowledge.CitationRegistry;
import com.zl.d44.knowledge.KnowledgeChunk;
import com.zl.d44.rag.config.RagProperties;
import com.zl.d44.rag.model.AssembledContext;
import com.zl.d44.rag.model.RetrievedChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 上下文组装：只处理已检索 Chunk（去重、排序、预算截断、来源组织），不触发新检索。
 *
 * <p>预算口径：Unicode 码点字符（与 {@code EmbeddingLengthUnit} 一致），
 * 不伪称 Token；截断只作用于摘录文本，来源映射（引用编号、文档、页码）永不丢失，
 * 被截断的字符数记入 {@code truncatedChars}。</p>
 */
@Slf4j
@Service
public class ContextAssemblyService {

    private final RagProperties properties;

    public ContextAssemblyService(RagProperties properties) {
        this.properties = properties;
    }

    /**
     * 组装上下文：去重 → 排序 → 阈值过滤 → 预算截断 → 来源注册。
     */
    public AssembledContext assemble(List<RetrievedChunk> retrieved) {
        if (retrieved == null || retrieved.isEmpty()) {
            return AssembledContext.empty();
        }

        // 1. 按 chunkId 去重，保留分数更高的一条（分数相同保留先出现者）
        Map<String, RetrievedChunk> unique = new LinkedHashMap<>();
        for (RetrievedChunk chunk : retrieved) {
            if (chunk.chunkId() == null || chunk.chunkId().isBlank()) {
                continue; // 无稳定主键的片段不能进入引用注册
            }
            RetrievedChunk existing = unique.get(chunk.chunkId());
            if (existing == null || chunk.normalizedScore() > existing.normalizedScore()) {
                unique.put(chunk.chunkId(), chunk);
            }
        }

        // 2. 按余弦相似度降序（null 分数视为 0）
        List<RetrievedChunk> ordered = new ArrayList<>(unique.values());
        ordered.sort(Comparator.comparingDouble(RetrievedChunk::normalizedScore).reversed());

        // 3. 分数下限过滤：全部低于阈值时视为无可用上下文
        List<RetrievedChunk> eligible = ordered.stream()
                .filter(chunk -> chunk.normalizedScore() >= properties.getMinScore())
                .toList();
        if (eligible.isEmpty()) {
            log.info("上下文组装：{} 条命中全部低于 min-score={}，判定无可用上下文",
                    ordered.size(), properties.getMinScore());
            return AssembledContext.empty();
        }

        // 4. 预算截断并构建资料块（引用编号由后端按进入顺序分配）
        // UNTRUSTED_EVIDENCE 结构化包裹：声明每一块证据均为不可信数据，与 System Prompt
        // 中的信任边界条款互为呼应（多层防护之一）。
        List<KnowledgeChunk> chunks = new ArrayList<>();
        StringBuilder contextText = new StringBuilder();
        int truncatedChars = 0;
        int seq = 1;
        for (RetrievedChunk chunk : eligible) {
            String header = evidenceHeader("S" + seq, chunk);
            String footer = "</UNTRUSTED_EVIDENCE>\n";
            int headerChars = header.codePointCount(0, header.length());
            int tailChars = footer.codePointCount(0, footer.length());
            int usedBefore = contextText.codePointCount(0, contextText.length());
            if (usedBefore + headerChars + tailChars >= properties.getMaxContextChars()) {
                // 连头部都放不下：整块不进上下文，但来源信息仍需如实记录截断
                truncatedChars += chunk.contentLength();
                log.info("预算截断：chunkId={} 整块未进入上下文（预算已满）", chunk.chunkId());
                continue;
            }
            int contentChars = chunk.contentLength();
            int remaining = properties.getMaxContextChars() - usedBefore - headerChars - tailChars;
            String excerpt;
            if (contentChars <= remaining) {
                excerpt = chunk.content() == null ? "" : chunk.content();
            } else if (remaining <= 1) {
                // 连一个字符加省略号都放不下：摘录为空，整块截断计入
                excerpt = "";
                truncatedChars += contentChars;
            } else {
                int cutIndex = offsetByCodePoints(chunk.content(), remaining - 1);
                excerpt = chunk.content().substring(0, cutIndex) + "…";
                truncatedChars += contentChars - (remaining - 1);
            }
            KnowledgeChunk knowledgeChunk = chunk.toKnowledgeChunk(
                    properties.getCitation().getExcerptMaxChars());
            chunks.add(knowledgeChunk);
            contextText.append(header).append(excerpt).append(footer);
            seq++;
        }

        int usedChars = contextText.codePointCount(0, contextText.length());
        boolean usable = !chunks.isEmpty();
        if (!usable) {
            log.info("上下文组装：预算 {} 连编号头都无法容纳，判定无可用上下文",
                    properties.getMaxContextChars());
            return AssembledContext.empty();
        }

        CitationRegistry registry = new CitationRegistry(chunks);
        return new AssembledContext(registry, contextText.toString(),
                registry.allCitations(), List.copyOf(chunks), chunks.size(),
                usedChars, truncatedChars, true);
    }

    /**
     * 不可信证据块头部：引用编号 + 来源三要素（标题/版本/生效状态）。
     * 版本与生效状态缺失时如实写"未知"，不补造。
     */
    private String evidenceHeader(String markerId, RetrievedChunk chunk) {
        String version = (chunk.version() == null || chunk.version().isBlank())
                ? "未知" : chunk.version();
        String lifecycle = (chunk.lifecycleStatus() == null || chunk.lifecycleStatus().isBlank())
                ? "未知" : chunk.lifecycleStatus();
        return "<UNTRUSTED_EVIDENCE id=\"" + markerId + "\">\n"
                + "来源：" + safeTitle(chunk.title())
                + " | 版本：" + version
                + " | 生效状态：" + lifecycle + "\n"
                + "正文：";
    }

    private String safeTitle(String title) {
        return title == null ? "未命名文档" : title;
    }

    /** 取字符串前 {@code codePoints} 个码点对应的 UTF-16 下标。 */
    private int offsetByCodePoints(String text, int codePoints) {
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
