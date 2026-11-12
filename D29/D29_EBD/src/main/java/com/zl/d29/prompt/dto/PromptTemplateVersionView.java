package com.zl.d29.prompt.dto;

import com.zl.d29.prompt.model.TemplateVariable;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Prompt 模板的不可变版本快照视图。
 *
 * @param templateId 稳定模板标识
 * @param version    内容版本
 * @param name       当时的模板名称
 * @param scene      当时的场景
 * @param description 当时的用途说明
 * @param content    当时的模板正文
 * @param variables  当时的变量声明
 * @param status     快照捕获时的启停状态
 * @param capturedAt 快照落库时间
 */
public record PromptTemplateVersionView(
        String templateId,
        int version,
        String name,
        String scene,
        String description,
        String content,
        List<TemplateVariable> variables,
        String status,
        LocalDateTime capturedAt
) {
}
