package com.zl.d28.prompt;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zl.d28.prompt.dto.PromptTemplateVersionView;
import com.zl.d28.prompt.mapper.PromptTemplateVersionMapper;
import com.zl.d28.prompt.model.PromptTemplateEntity;
import com.zl.d28.prompt.po.PromptTemplateVersionPO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * MySQL 版本历史实现。利用唯一键兜底并发重复归档。
 */
@Repository
public class MybatisPromptTemplateVersionRepository implements PromptTemplateVersionRepository {

    private final PromptTemplateVersionMapper mapper;

    public MybatisPromptTemplateVersionRepository(PromptTemplateVersionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void archive(PromptTemplateEntity entity) {
        Long existing = mapper.selectCount(new LambdaQueryWrapper<PromptTemplateVersionPO>()
                .eq(PromptTemplateVersionPO::getTemplateId, entity.getTemplateId())
                .eq(PromptTemplateVersionPO::getVersion, entity.getVersion()));
        if (existing != null && existing > 0) {
            return;
        }
        try {
            mapper.insert(toPo(entity));
        } catch (DuplicateKeyException ignored) {
            // 多实例同时回填时由 uk_template_version 保证只保留一份不可变快照
        }
    }

    @Override
    public List<PromptTemplateVersionView> findByTemplateId(String templateId) {
        return mapper.selectList(new LambdaQueryWrapper<PromptTemplateVersionPO>()
                        .eq(PromptTemplateVersionPO::getTemplateId, templateId)
                        .orderByDesc(PromptTemplateVersionPO::getVersion))
                .stream()
                .map(this::toView)
                .toList();
    }

    private PromptTemplateVersionPO toPo(PromptTemplateEntity entity) {
        PromptTemplateVersionPO po = new PromptTemplateVersionPO();
        po.setTemplateId(entity.getTemplateId());
        po.setVersion(entity.getVersion());
        po.setName(entity.getName());
        po.setScene(entity.getScene());
        po.setDescription(entity.getDescription());
        po.setContent(entity.getContent());
        po.setVariables(entity.getVariables() == null ? new ArrayList<>() : entity.getVariables());
        po.setStatus(entity.getStatus().name());
        po.setCapturedAt(LocalDateTime.now());
        return po;
    }

    private PromptTemplateVersionView toView(PromptTemplateVersionPO po) {
        return new PromptTemplateVersionView(
                po.getTemplateId(),
                po.getVersion(),
                po.getName(),
                po.getScene(),
                po.getDescription(),
                po.getContent(),
                po.getVariables() == null ? List.of() : List.copyOf(po.getVariables()),
                po.getStatus(),
                po.getCapturedAt()
        );
    }
}
