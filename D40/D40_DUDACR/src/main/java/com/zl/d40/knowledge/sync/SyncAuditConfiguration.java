package com.zl.d40.knowledge.sync;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 审计装配（与 AccessConfiguration 同门禁：app.access.enabled=true 才注册；
 * 默认关闭时整体不生效=零差异，无需改动 access 包既有文件，H1 白名单最小化）。
 */
@Configuration
@ConditionalOnProperty(name = "app.access.enabled", havingValue = "true")
public class SyncAuditConfiguration {

    @Bean
    public AdminAuditRepository adminAuditRepository(JdbcTemplate jdbcTemplate) {
        return new AdminAuditRepository(jdbcTemplate);
    }

    @Bean
    public AuditService auditService(AdminAuditRepository auditRepository) {
        return new AuditService(auditRepository);
    }
}
