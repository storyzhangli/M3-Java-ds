package com.zl.d44.knowledge.document.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_document")
public class KnowledgeDocumentPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String documentId;

    private String sourceSystem;

    private String title;

    private String version;

    private String status;

    private String fileName;

    private String contentType;

    private Long fileSize;

    private String contentHash;

    private String errorCode;

    private String errorMessage;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    // ---- 追加：权限范围列（MyBatis-Plus 驼峰↔下划线自动映射；遗留行为 NULL）----

    private String tenantId;

    private String deptId;

    private String deviceId;
}
