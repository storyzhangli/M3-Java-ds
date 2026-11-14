package com.zl.d31.prompt;

import com.zl.d31.prompt.dto.PromptTemplateVersionView;
import com.zl.d31.prompt.model.PromptTemplateEntity;

import java.util.List;

/**
 * Prompt 模板版本历史仓储。
 *
 * <p>与当前模板仓储分离：当前表负责“什么版本正在生效”，版本表负责“每个版本当时是什么”。
 * 快照只插入不更新，逻辑删除模板也不删除版本历史。</p>
 */
public interface PromptTemplateVersionRepository {

    /**
     * 幂等归档当前实体快照；同一 templateId/version 已存在时保持不变。
     */
    void archive(PromptTemplateEntity entity);

    /**
     * 按版本倒序返回完整快照。
     */
    List<PromptTemplateVersionView> findByTemplateId(String templateId);
}
