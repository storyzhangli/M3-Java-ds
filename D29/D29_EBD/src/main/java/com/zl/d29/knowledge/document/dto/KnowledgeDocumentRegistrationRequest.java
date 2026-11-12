package com.zl.d29.knowledge.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 只登记元数据，不包含文件和用户可写状态。
 */
@Data
public class KnowledgeDocumentRegistrationRequest {

    @NotBlank(message = "source 不能为空")
    @Size(max = 128, message = "source 长度不能超过128")
    private String source;

    @NotBlank(message = "title 不能为空")
    @Size(max = 300, message = "title 长度不能超过300")
    private String title;

    @NotBlank(message = "version 不能为空")
    @Size(max = 64, message = "version 长度不能超过64")
    private String version;
}
