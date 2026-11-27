package com.zl.d44.knowledge.document.repository;

import com.zl.d44.knowledge.document.ExtractedPage;
import com.zl.d44.knowledge.document.KnowledgeDocument;
import com.zl.d44.knowledge.document.KnowledgeDocumentPage;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 文档元数据与文件内容的存储端口。
 */
public interface KnowledgeDocumentRepository {

    KnowledgeDocument register(KnowledgeDocument document);

    Optional<KnowledgeDocument> findByDocumentId(String documentId);

    Optional<KnowledgeDocument> findByContentHash(String contentHash);

    void claimForProcessing(String documentId, String fileName, String contentType,
                            long fileSize, String contentHash, byte[] content,
                            LocalDateTime updatedAt);

    void complete(String documentId, String contentType, String parsedText,
                  List<ExtractedPage> pages, String parserName, String parserVersion,
                  LocalDateTime updatedAt);

    List<KnowledgeDocumentPage> findExtractedPages(String documentId);

    int countExtractedPages(String documentId);

    void fail(String documentId, String errorCode, String errorMessage,
              LocalDateTime updatedAt);

    /**
     * R01（D3 裁决）：SWITCH 事务内权威化版本指针——knowledge_document.version
     * 更新为当前活动索引版本。行不存在返回 0（no-op，不新增 lifecycle 列，D2）。
     */
    int updateVersionPointer(String documentId, String version, LocalDateTime updatedAt);
    /**
     * P3（D1 裁决）：生命周期标记删除。写 lifecycle_status（列已存在，非新增列，
     * KP1 裁决值 "DELETED"——枚举外字符串，消费点 parseLifecycle 已实测容错）。
     * 行不存在返回 0（no-op 幂等）。
     */
    int markLifecycleStatus(String documentId, String lifecycleStatus, LocalDateTime updatedAt);

    /**
     * P3：单列读取 lifecycle_status（不扩 PO 白名单，selectObjs 字符串列实现）。
     * 行不存在返回 null。
     */
    String findLifecycleStatus(String documentId);

    /**
     * P3：新版本发布内容抢占。语义=claimForProcessing 全套（含 content/pages 清旧），
     * 唯一差异为状态守卫 COMPLETED -> PROCESSING（importFile 守卫 PENDING/FAILED 不可复用）。
     */
    void republishClaim(String documentId, String fileName, String contentType,
                        long fileSize, String contentHash, byte[] content,
                        LocalDateTime updatedAt);
}