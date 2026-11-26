package com.zl.d43.knowledge.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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

    // ---- 追加（可选；未传=无标签=权限空间外 D3；值域对齐既有白名单字符集 D2）----

    @Size(max = 64, message = "tenantId 长度不能超过64")
    @Pattern(regexp = "^$|[A-Za-z0-9._:-]+", message = "tenantId 仅允许字母、数字、点、下划线、冒号、连字符")
    private String tenantId;

    @Size(max = 64, message = "deptId 长度不能超过64")
    @Pattern(regexp = "^$|[A-Za-z0-9._:-]+", message = "deptId 仅允许字母、数字、点、下划线、冒号、连字符")
    private String deptId;

    @Size(max = 64, message = "deviceId 长度不能超过64")
    @Pattern(regexp = "^$|[A-Za-z0-9._:-]+", message = "deviceId 仅允许字母、数字、点、下划线、冒号、连字符")
    private String deviceId;
}
