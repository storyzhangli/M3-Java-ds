package com.zl.d43.access;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d43.access.AccessGuard;
import com.zl.d43.access.AccessPolicyService;
import com.zl.d43.access.AccessProperties;
import com.zl.d43.access.AuthContextFilter;
import com.zl.d43.access.PrincipalRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 权限体系装配（H2：enabled=true 才注册；默认关闭时本配置整体不生效，
 * 主链路 Bean 图与骨架基线零差异——C05 证明面）。
 */
@Configuration
@ConditionalOnProperty(name = "app.access.enabled", havingValue = "true")
@EnableConfigurationProperties(AccessProperties.class)
public class AccessConfiguration {

    @Bean
    public PrincipalRepository principalRepository(JdbcTemplate jdbcTemplate) {
        return new PrincipalRepository(jdbcTemplate);
    }

    @Bean
    public AccessPolicyService accessPolicyService() {
        return new AccessPolicyService();
    }

    @Bean
    public AccessGuard accessGuard(AccessPolicyService policyService) {
        return new AccessGuard(policyService);
    }

    @Bean
    public FilterRegistrationBean<AuthContextFilter> authContextFilterRegistration(
            PrincipalRepository repository, ObjectMapper objectMapper, AccessProperties properties) {
        FilterRegistrationBean<AuthContextFilter> registration =
                new FilterRegistrationBean<>(new AuthContextFilter(repository, objectMapper));
        registration.addUrlPatterns(toServletPatterns(properties.getProtectedPathPatterns()));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
        registration.setName("authContextFilter");
        return registration;
    }

    /** Ant 风格转 Servlet 精确/前缀映射：/api/v1/* → /api/v1/*（容器语义即一级前缀通配）。 */
    private String[] toServletPatterns(String[] antPatterns) {
        return antPatterns == null ? new String[]{"/api/v1/*"} : antPatterns.clone();
    }
}
