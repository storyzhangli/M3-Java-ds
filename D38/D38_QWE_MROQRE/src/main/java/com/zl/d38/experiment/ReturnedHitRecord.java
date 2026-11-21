package com.zl.d38.experiment;

import java.util.List;

/**
 * 单条返回命中的记录（证据用）。
 *
 * @param rank             名次（1 开始）
 * @param vectorChunkId    向量主键（稳定 UUID）
 * @param documentId       实验文档 ID（带前缀）
 * @param chunkIndex       入库块序号（0-based）
 * @param logicalChunkIds  映射回的逻辑片段 ID（一个新块可能覆盖多个逻辑片段）
 * @param score            余弦相似度（可能为 null）
 * @param distance         余弦距离（可能为 null）
 */
public record ReturnedHitRecord(int rank,
                                String vectorChunkId,
                                String documentId,
                                Integer chunkIndex,
                                List<String> logicalChunkIds,
                                Double score,
                                Double distance) {
}
