package com.zl.d37.evaluator;

import java.util.List;

/**
 * 后端无关的统一检索命中视图。
 *
 * <p>chunkId 语义随后端不同：PGVECTOR 后端为稳定向量块 ID（UUID 类），
 * IN_MEMORY_LOCAL 后端为冻结语料的逻辑 chunkId。判题一律使用
 * {@link #logicalChunkIds()}（已由页码反查得到），不依赖 chunkId 文本。</p>
 *
 * @param rank             名次（1 开始）
 * @param chunkId          后端命中的块标识
 * @param documentId       真实文档标识（不带实验前缀）
 * @param version          文档版本（PGVECTOR 为 corpus-v1，内存为 corpus-v1）
 * @param chunkIndex       块序号
 * @param title            文档标题
 * @param pageNumbers      页码列表
 * @param content          片段原文
 * @param score            余弦相似度
 * @param distance         余弦距离（1-score）
 * @param logicalChunkIds  经页码反查得到的逻辑片段 ID（判题基准）
 */
public record Hit(int rank,
                  String chunkId,
                  String documentId,
                  String version,
                  Integer chunkIndex,
                  String title,
                  List<Integer> pageNumbers,
                  String content,
                  Double score,
                  Double distance,
                  List<String> logicalChunkIds) {
}
