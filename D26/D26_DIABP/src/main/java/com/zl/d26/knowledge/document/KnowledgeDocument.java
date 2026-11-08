package com.zl.d26.knowledge.document;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识文档登记元数据。
 *
 * <p>本轮 status 表示文件导入/基础解析状态，不是 D25 完整发布生命周期。</p>
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
}
