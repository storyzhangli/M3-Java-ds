package com.zl.d28.prompt;

import com.zl.d28.prompt.PromptTemplateVersionRepository;
import com.zl.d28.prompt.dto.PromptTemplateVersionView;
import com.zl.d28.prompt.model.PromptTemplateEntity;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Prompt 模板版本归档与查询服务。
 */
@Service
public class PromptTemplateVersionService {

    private final PromptTemplateVersionRepository repository;

    public PromptTemplateVersionService(PromptTemplateVersionRepository repository) {
        this.repository = repository;
    }

    /**
     * 归档指定实体当前版本。仓储实现保证重复调用不覆盖历史。
     */
    public void archive(PromptTemplateEntity entity) {
        repository.archive(entity);
    }

    /**
     * 返回模板的完整历史版本，最新版本在前。
     */
    public List<PromptTemplateVersionView> listVersions(String templateId) {
        return repository.findByTemplateId(templateId);
    }
}
