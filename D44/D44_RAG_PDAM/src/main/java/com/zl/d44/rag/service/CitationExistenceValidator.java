package com.zl.d44.rag.service;

import com.zl.d44.knowledge.vectorindex.repository.VectorChunkRepository;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 引用存在性核验器（删除竞态防线）。
 *
 * <p>在生成后校验通过、响应返回前，对即将随回答返回的引用做一次权威状态核验：
 * 批量查询 MySQL 关联表（{@code vector_chunk_index}），确认 chunkId 仍存在；
 * 一次 SQL 完成整批核验，避免逐条 N+1。核验发现的失效来源不展示，由编排器映射为
 * {@code CITATION_TARGET_DELETED} 受控失败。</p>
 */
@Service
public class CitationExistenceValidator {

    private final VectorChunkRepository repository;

    public CitationExistenceValidator(VectorChunkRepository repository) {
        this.repository = repository;
    }

    /**
     * 批量核验，返回缺失（已删除/失效）的 chunkId 列表。
     *
     * @param chunkIds 本轮拟返回引用的 chunkId 集合
     */
    public ExistenceResult validate(Collection<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return ExistenceResult.ok();
        }
        List<String> ids = chunkIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return ExistenceResult.ok();
        }
        Set<String> existing = new HashSet<>(repository.findExistingChunkIds(ids));
        List<String> missing = ids.stream().filter(id -> !existing.contains(id)).toList();
        return new ExistenceResult(missing);
    }

    /**
     * 核验结果：缺失的 chunkId 列表（空表示全部存在）。
     */
    public record ExistenceResult(List<String> missingChunkIds) {

        public static ExistenceResult ok() {
            return new ExistenceResult(List.of());
        }

        public boolean hasMissing() {
            return missingChunkIds != null && !missingChunkIds.isEmpty();
        }
    }
}
