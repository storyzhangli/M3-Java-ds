package com.zl.d31.knowledge.document.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_document_page")
public class KnowledgeDocumentPagePO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String documentId;

    private Integer pageNumber;

    private String content;

    private Integer characterCount;

    private Boolean blankPage;

    private String parserName;

    private String parserVersion;

    private LocalDateTime updatedAt;
}
