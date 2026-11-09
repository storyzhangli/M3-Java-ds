package com.zl.d27.prompt;

import com.zl.d27.prompt.PromptTemplateRepository;
import com.zl.d27.prompt.PromptTemplateVersionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 为 D17 及更早版本已有的模板行幂等补齐当前版本快照。
 *
 * <p>在种子初始化之后运行。已有历史快照不会被覆盖；该步骤只保证升级前存量模板
 * 至少具备其当前版本的可追溯记录。</p>
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class PromptTemplateVersionInitializer implements ApplicationRunner {

    private final PromptTemplateRepository templateRepository;

    private final PromptTemplateVersionService versionService;

    public PromptTemplateVersionInitializer(PromptTemplateRepository templateRepository,
                                            PromptTemplateVersionService versionService) {
        this.templateRepository = templateRepository;
        this.versionService = versionService;
    }

    @Override
    public void run(ApplicationArguments args) {
        int archived = 0;
        for (var template : templateRepository.findAllForVersionBackfill()) {
            versionService.archive(template);
            archived++;
        }
        log.info("Prompt 模板历史快照回填检查完成，处理模板数={}", archived);
    }
}
