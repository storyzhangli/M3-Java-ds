package com.zl.d37.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 引用来源结构化数据（Citation，引用项）。
 *
 * <p>所有字段只能由后端根据本轮真实检索命中的知识片段组装，模型自身输出的文件路径、URL、页码、
 * 文档版本等一律不得直接出现在本结构中；缺失的可选字段通过
 * {@link JsonInclude.Include#NON_NULL} 隐藏，不生成占位假数据，也不暴露本机绝对路径。</p>
 *
 * @param id              请求级引用编号（citationId 语义），如 S1、S2，仅在本次请求/回答范围内有效
 * @param sourceId        稳定来源标识（跨请求不变，当前映射真实 documentId，不擅自新增独立来源 ID）
 * @param documentId      真实文档稳定标识
 * @param documentVersion 文档版本（来自真实检索命中的 Chunk metadata，缺失为 null，
 *                        不从当前最新版本反向覆盖历史检索结果）
 * @param chunkId         真实文档片段稳定标识
 * @param title           文档标题或文件名
 * @param sourceType      来源类型（如 MANUAL/GUIDE/SPEC）
 * @param section         章节名，可能为 null
 * @param page            页码，可能为 null（向后兼容，取 pageNumbers 首个）
 * @param pageNumbers     页码列表（跨页片段保留完整列表，可能为 null）
 * @param paragraphIndex  段落索引（真实切分来源，可能为 null）
 * @param score           余弦相似度（可能为 null，仅作内部溯源，前端不展示）
 * @param excerpt         来自真实片段的有限摘录（≤上限，来自原文不改写）
 * @param url             数据源确实提供的有效链接，经协议黑名单校验，没有则为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CitationDto(String id,
                          String sourceId,
                          String documentId,
                          String documentVersion,
                          String chunkId,
                          String title,
                          String sourceType,
                          String section,
                          Integer page,
                          List<Integer> pageNumbers,
                          Integer paragraphIndex,
                          Double score,
                          String excerpt,
                          String url) {
}
