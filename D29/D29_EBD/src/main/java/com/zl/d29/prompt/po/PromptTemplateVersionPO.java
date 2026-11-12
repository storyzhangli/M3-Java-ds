package com.zl.d29.prompt.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.zl.d29.prompt.model.TemplateVariable;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Prompt 模板不可变版本快照的持久化对象。
 */
@Data
@NoArgsConstructor
@TableName(value = "prompt_template_version", autoResultMap = true)
public class PromptTemplateVersionPO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String templateId;

    private Integer version;

    private String name;

    private String scene;

    private String description;

    private String content;

    @TableField(value = "variables", typeHandler = JacksonTypeHandler.class)
    private List<TemplateVariable> variables = new ArrayList<>();

    private String status;

    private LocalDateTime capturedAt;
}
