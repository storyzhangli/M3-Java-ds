package com.zl.d28.knowledge.document.repository;

import com.zl.d28.knowledge.document.KnowledgeDocument;
import com.zl.d28.knowledge.document.KnowledgeDocumentPage;
import com.zl.d28.knowledge.document.ExtractedPage;

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
}
