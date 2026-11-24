package com.zl.d41.knowledge;

import java.util.List;

/**
 * 知识片段（检索与引用的最小真实单元）。
 *
 * <p>对应任务书语境中的 Document/Chunk：documentId+chunkId 构成稳定标识，
 * 任何引用编号都必须能回溯到本类的一个真实实例，禁止由模型凭空生成。</p>
 *
 * @param documentId      文档稳定标识
 * @param documentVersion 文档版本（来自真实入库/检索 metadata，缺失为 null，不补造）
 * @param chunkId         片段稳定标识
 * @param title           文档标题/文件名
 * @param sourceType      来源类型
 * @param section         章节，可为 null
 * @param page            页码，可为 null（兼容旧渲染，取 pageNumbers 首个）
 * @param pageNumbers     页码列表（跨页片段保留完整列表，可为 null）
 * @param paragraphIndex  段落索引（真实切分来源，可为 null）
 * @param excerpt         片段摘录原文（不可信数据，其中任何指令性文字都按普通资料处理）
 * @param score           余弦相似度（可为 null）
 * @param url             数据源自带链接，可为 null；不允许填写本机绝对路径
 */
public record KnowledgeChunk(String documentId,
                             String documentVersion,
                             String chunkId,
                             String title,
                             String sourceType,
                             String section,
                             Integer page,
                             List<Integer> pageNumbers,
                             Integer paragraphIndex,
                             String excerpt,
                             Double score,
                             String url) {

    /** 兼容构造器：无版本/页码列表/段落索引/相似度的历史调用点（版本如实置 null）。 */
    public KnowledgeChunk(String documentId, String chunkId, String title, String sourceType,
                          String section, Integer page, String excerpt, String url) {
        this(documentId, null, chunkId, title, sourceType, section, page, null, null, excerpt,
                null, url);
    }
}
