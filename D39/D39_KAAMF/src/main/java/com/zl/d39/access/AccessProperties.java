package com.zl.d39.access;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * app.access 配置（H2：enabled 默认 false，@ConditionalOnProperty 不设 matchIfMissing）。
 */
@Data
@ConfigurationProperties(prefix = "app.access")
public class AccessProperties {

    /** 权限体系总开关。false（默认）=过滤器/Bean 不装配，行为与骨架基线零差异。 */
    private boolean enabled = false;

    /** 受保护路径（Ant 样式）。默认全部业务 API——统一 fail-closed 面。 */
    private String[] protectedPathPatterns = {"/api/v1/*"};
}
