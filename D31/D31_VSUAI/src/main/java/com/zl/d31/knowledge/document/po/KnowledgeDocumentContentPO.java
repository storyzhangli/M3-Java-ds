package com.zl.d31.knowledge.document.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_document_content")
public class KnowledgeDocumentContentPO {

    @TableId(value = "document_id", type = IdType.INPUT)
    private String documentId;

    private byte[] content;

    private String parsedText;

    private LocalDateTime updatedAt;
}
