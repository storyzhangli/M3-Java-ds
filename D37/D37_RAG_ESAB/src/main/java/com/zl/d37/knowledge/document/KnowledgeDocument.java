package com.zl.d37.knowledge.document;

import com.zl.d37.knowledge.document.DocumentLifecycleStatus;
import com.zl.d37.knowledge.document.KnowledgeDocumentStatus;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识文档登记元数据。
 *
 * <p>本轮 status 表示文件导入/基础解析状态，不是 D25 完整发布生命周期；
 * 证据生命周期（是否可作为 RAG 证据）由 lifecycleStatus/effectiveAt/expiresAt 表达，
 * 见 {@link DocumentLifecycleStatus#isUsableForEvidence}。</p>
 */
@Data
public class KnowledgeDocument {

    private Long id;

    private String documentId;

    private String sourceSystem;

    private String title;

    private String version;

    private KnowledgeDocumentStatus status;

    private String fileName;

    private String contentType;

    private Long fileSize;

    private String contentHash;

    private String errorCode;

    private String errorMessage;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 证据生命周期状态：DRAFT/ACTIVE/DEPRECATED/EXPIRED（缺省 ACTIVE）。 */
    private String lifecycleStatus;

    /** 生效时间（null 视为立即生效）。 */
    private LocalDateTime effectiveAt;

    /** 过期时间（null 视为不设过期）。 */
    private LocalDateTime expiresAt;
}
