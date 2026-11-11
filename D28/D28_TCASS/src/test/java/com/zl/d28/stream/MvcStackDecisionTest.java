package com.zl.d28.stream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ClassUtils;
import org.springframework.web.reactive.DispatcherHandler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D28 技术组合证据：当前实际应用类型仍是 Servlet/Tomcat + Spring MVC。
 *
 * <p>Spring AI DeepSeek starter 会传递引入 spring-webflux 与 reactor-core，
 * 但这不等于 Spring Boot 选择 WebFlux 容器。自动配置类型必须由运行时上下文确认。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MvcStackDecisionTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void applicationContextShouldUseServletTomcatAndNotReactiveDispatcherHandler() {
        assertThat(applicationContext).isInstanceOf(ServletWebServerApplicationContext.class);
        ServletWebServerApplicationContext servletContext =
                (ServletWebServerApplicationContext) applicationContext;
        assertThat(servletContext.getWebServer()).isNotNull();
        assertThat(servletContext.getWebServer().getClass().getName()).contains("Tomcat");
        assertThat(applicationContext.getBeanNamesForType(DispatcherHandler.class)).isEmpty();

        // Spring AI 的流式 API 依赖在 classpath 中，但应用容器没有被切换为 WebFlux。
        assertThat(ClassUtils.isPresent("reactor.core.publisher.Flux", getClass().getClassLoader()))
                .isTrue();
        assertThat(ClassUtils.isPresent(
                "org.springframework.web.reactive.DispatcherHandler",
                getClass().getClassLoader())).isTrue();
    }
}
