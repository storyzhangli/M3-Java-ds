package com.zl.d29;

import com.zl.d29.controller.ChatController;
import com.zl.d29.controller.ChatStreamController;
import com.zl.d29.dto.ChatReplyResponse;
import com.zl.d29.dto.ChatRequest;
import com.zl.d29.dto.RoleChatReplyResponse;
import com.zl.d29.knowledge.KnowledgeChatOrchestrator;
import com.zl.d29.service.AiChatResult;
import com.zl.d29.service.AiChatService;
import com.zl.d29.service.ChatInvocation;
import com.zl.d29.service.StreamChunk;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 应用公开契约和业务层的供应商类型隔离检查。
 */
class ProviderIsolationTest {

    private static final String PROVIDER_PACKAGE = "org.springframework.ai.deepseek";

    @Test
    void publicApplicationContractsShouldNotExposeProviderTypes() {
        List<Class<?>> contracts = List.of(
                AiChatService.class,
                ChatInvocation.class,
                AiChatResult.class,
                StreamChunk.class,
                ChatRequest.class,
                ChatReplyResponse.class,
                RoleChatReplyResponse.class,
                ChatController.class,
                ChatStreamController.class,
                KnowledgeChatOrchestrator.class
        );

        for (Class<?> contract : contracts) {
            for (Method method : contract.getDeclaredMethods()) {
                assertNoProviderType(method.getReturnType());
                for (Type parameterType : method.getGenericParameterTypes()) {
                    assertNoProviderType(parameterType);
                }
                assertNoProviderType(method.getGenericReturnType());
            }
        }
    }

    @Test
    void providerImportsShouldBeLimitedToAdapterAndConfigurationBoundary() throws Exception {
        Path sourceRoot = Path.of("src", "main", "java");
        try (var paths = Files.walk(sourceRoot)) {
            Set<String> files = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(this::containsProviderImport)
                    .map(path -> sourceRoot.relativize(path).toString().replace('\\', '/'))
                    .collect(Collectors.toSet());

            assertThat(files).containsExactlyInAnyOrder(
                    "com/zl/d29/config/DeepSeekChatModelConfiguration.java",
                    "com/zl/d29/service/DeepSeekAiChatService.java"
            );
        }
    }

    @Test
    void controllersAndServicePortShouldNotReferenceVendorConfiguration() throws Exception {
        for (String file : List.of(
                "src/main/java/com/zl/d29/service/AiChatService.java",
                "src/main/java/com/zl/d29/controller/ChatController.java",
                "src/main/java/com/zl/d29/controller/ChatStreamController.java")) {
            String source = Files.readString(Path.of(file));
            assertThat(source)
                    .doesNotContain(PROVIDER_PACKAGE)
                    .doesNotContain("spring.ai.deepseek")
                    .doesNotContain("DeepSeekChat");
        }
    }

    private boolean containsProviderImport(Path path) {
        try {
            return Files.readString(path).contains("import " + PROVIDER_PACKAGE);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to scan " + path, ex);
        }
    }

    private void assertNoProviderType(Type type) {
        assertThat(type.getTypeName()).doesNotContain(PROVIDER_PACKAGE);
        if (type instanceof ParameterizedType parameterizedType) {
            for (Type argument : parameterizedType.getActualTypeArguments()) {
                assertNoProviderType(argument);
            }
        }
    }
}
