package com.zl.d41.hybrid.keyword;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 倒排索引的文档行（从 PG {@code vector_store} 只读加载的镜像投影，P1.2）。
 *
 * <p>chunkId 使用 PG 主键 {@code id}（uuid 字符串）——与既有向量路
 * {@code RetrievalHitView.chunkId = document.getId()} 同一稳定键，保证 RRF 融合可按
 * 同一连接键对齐两路命中（KP2 实证 metadata 内另有 chunkId 键，但既有链路一律以 id 为键，
 * 倒排不引入第二 ID 体系）。</p>
 *
 * <p>metadata 缺失字段如实为 null / 空列表，不补造（对齐 RetrievalHitMapper 纪律）。</p>
 */
public record ChunkRow(String chunkId,
                       String documentId,
                       String version,
                       Integer chunkIndex,
                       String title,
                       List<Integer> pageNumbers,
                       Integer paragraphIndex,
                       String strategy,
                       String content,
                       String lifecycleStatus,
                       LocalDateTime effectiveAt,
                       LocalDateTime expiresAt,
                       // 追加：权限三键（metadata 缺失=true，fail-closed 由谓词判定，不补造）
                       String tenantId,
                       String deptId,
                       String deviceId) {

    public ChunkRow {
        pageNumbers = pageNumbers == null ? List.of() : List.copyOf(pageNumbers);
    }
}
