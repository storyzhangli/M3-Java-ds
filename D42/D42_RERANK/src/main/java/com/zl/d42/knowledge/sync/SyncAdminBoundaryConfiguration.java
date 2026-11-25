package com.zl.d42.knowledge.sync;

import com.zl.d42.access.AccessGuard;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 生产权限边界启动检查（R02-06.2）。
 *
 * <p>门禁：{@code app.access.enforce-admin=true}（生产 Profile 设置；默认 false 保持
 * 本地 mock 演练零差异）。开启后若 {@link AccessGuard} 未装配（access.enabled=false
 * 或装配缺失），应用启动直接失败——生产形态不存在"权限组件缺席=默认放行"的
 * 特权写面。权限组件在场时各入口 requireAdmin 既有语义自然生效（ADMIN 校验 +
 * 租户隔离沿用 D39 体系，不重构）。</p>
 */
@Configuration
@ConditionalOnProperty(name = "app.access.enforce-admin", havingValue = "true")
public class SyncAdminBoundaryConfiguration {

    @Bean
    public SmartInitializingSingleton adminGuardPresenceCheck(
            ObjectProvider<AccessGuard> accessGuardProvider) {
        return () -> {
            if (accessGuardProvider.getIfAvailable() == null) {
                throw new IllegalStateException(
                        "R02-06：app.access.enforce-admin=true 但 AccessGuard 未装配；"
                                + "生产环境必须 app.access.enabled=true，禁止特权写面默认放行");
            }
        };
    }
}
