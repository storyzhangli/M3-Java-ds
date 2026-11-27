package com.zl.d44.rerank;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** H2 装配矩阵：默认零 Bean / enabled 装本地 / remote.enabled 追装远程（C02）。 */
class RerankConfigurationTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("默认（enabled=false）：三个 rerank Bean 均不装配")
    void disabledByDefault() {
        new ApplicationContextRunner()
                .withUserConfiguration(RerankConfiguration.class)
                .run(ctx -> {
                    assertEquals(0, ctx.getBeanNamesForType(LocalLexicalReranker.class).length);
                    assertEquals(0, ctx.getBeanNamesForType(RerankCallLedger.class).length);
                    assertEquals(0, ctx.getBeanNamesForType(BailianTextReranker.class).length);
                });
    }

    @Test
    @DisplayName("enabled=true：装 local+ledger，不装 remote；Controller 同步装配")
    void localOnly() {
        new ApplicationContextRunner()
                .withUserConfiguration(RerankConfiguration.class)
                .withPropertyValues("app.rerank.enabled=true",
                        "app.rerank.remote.ledger-path=" + tempDir.resolve("l.jsonl"))
                .run(ctx -> {
                    assertEquals(1, ctx.getBeanNamesForType(LocalLexicalReranker.class).length);
                    assertEquals(1, ctx.getBeanNamesForType(RerankCallLedger.class).length);
                    assertEquals(0, ctx.getBeanNamesForType(BailianTextReranker.class).length);
                });
    }

    @Test
    @DisplayName("enabled=true + remote.enabled=true：remote 适配器装配（Key 运行期读取，装配不校验）")
    void remoteAssembly() {
        new ApplicationContextRunner()
                .withUserConfiguration(RerankConfiguration.class)
                .withPropertyValues("app.rerank.enabled=true",
                        "app.rerank.remote.enabled=true",
                        "app.rerank.remote.ledger-path=" + tempDir.resolve("l2.jsonl"))
                .run(ctx -> assertEquals(1,
                        ctx.getBeanNamesForType(BailianTextReranker.class).length));
    }
}