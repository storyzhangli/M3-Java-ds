package com.zl.d38.two;

import com.zl.d38.knowledge.embedding.EmbeddingModelDescriptor;
import com.zl.d38.knowledge.retrieval.service.SimilarityRetrievalService;
import com.zl.d38.knowledge.vectorindex.service.DocumentVectorIndexService;
import com.zl.d38.knowledge.vectorstore.PgVectorAccess;

/**
 * two_ 隔离链路组件持有器：唯一注册为 Spring Bean 的 two_ 业务入口。
 *
 * <p>内部组件（BailianEmbeddingModel / two_PgVectorStore / two_ 入库与检索服务）
 * 全部由 {@link TwoPgVectorConfiguration} 手动构造，<b>不注册为 Bean</b>，
 * 从而不产生第二个 EmbeddingModel / VectorStore / PgVectorStore /
 * PgVectorStoreProperties / EmbeddingProperties / VectorChunkRepository Bean，
 * 本地链路全部按类型注入点零歧义、零 @Primary 改动。</p>
 *
 * @param descriptor         two_ 向量空间描述符（BAILIAN / qwen3.7-text-embedding / 1024）
 * @param indexService       two_ 幂等入库编排（复用既有类，two_ 依赖实例）
 * @param retrievalService   two_ 相似度检索编排（复用既有类，two_ 依赖实例）
 * @param corpusIndexService two_ 语料入库编排
 * @param pgVectorAccess     two_vector_store 只读访问（计数/对账）
 */
public record TwoSpaceComponents(
        EmbeddingModelDescriptor descriptor,
        DocumentVectorIndexService indexService,
        SimilarityRetrievalService retrievalService,
        TwoCorpusIndexService corpusIndexService,
        PgVectorAccess pgVectorAccess) {
}