package com.zl.d28.model;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 D28 的 RestClient/WebClient 超时 Customizer 会实际进入客户端请求工厂。
 */
@SpringBootTest(properties = {
        "app.ai.model-call.connect-timeout=200ms",
        "app.ai.model-call.response-timeout=150ms",
        "app.ai.model-call.total-deadline=2s",
        "app.ai.model-call.initial-backoff=100ms",
        "app.ai.model-call.max-backoff=100ms"
})
class ModelHttpClientTimeoutTest {

    @Autowired
    @Qualifier("deepSeekRestClientTimeoutCustomizer")
    private RestClientCustomizer restClientCustomizer;

    @Autowired
    @Qualifier("deepSeekWebClientTimeoutCustomizer")
    private WebClientCustomizer webClientCustomizer;

    private HttpServer server;

    private ExecutorService executor;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void restClientShouldUseConfiguredReadTimeout() throws Exception {
        String baseUrl = startSlowServer();
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
        restClientCustomizer.customize(builder);

        assertThatThrownBy(() -> builder.build().post().uri("/model")
                        .body("{}")
                        .retrieve().body(String.class))
                .isInstanceOf(ResourceAccessException.class);
    }

    @Test
    void webClientShouldUseConfiguredResponseTimeout() throws Exception {
        String baseUrl = startSlowServer();
        WebClient.Builder builder = WebClient.builder().baseUrl(baseUrl);
        webClientCustomizer.customize(builder);

        assertThatThrownBy(() -> builder.build().post().uri("/model")
                        .bodyValue("{}")
                        .retrieve().bodyToMono(String.class)
                        .block(Duration.ofSeconds(2)))
                .isInstanceOf(org.springframework.web.reactive.function.client.WebClientRequestException.class);
    }

    private String startSlowServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/model", exchange -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "late".getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (IOException ignored) {
            } finally {
                exchange.close();
            }
        });
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
