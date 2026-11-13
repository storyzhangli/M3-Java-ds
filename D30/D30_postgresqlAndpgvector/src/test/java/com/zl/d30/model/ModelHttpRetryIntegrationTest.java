package com.zl.d30.model;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zl.d30.config.ModelCallConfiguration;
import com.zl.d30.config.ModelCallProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.MediaType;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 本地 Mock HTTP Server 验证状态码分类、实际请求次数和读取超时，不访问 DeepSeek。
 */
class ModelHttpRetryIntegrationTest {

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
    void recoverable503ShouldRetryOnceAndThenSucceed() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        startServer(exchange -> {
            int request = requests.incrementAndGet();
            respond(exchange, request < 2 ? 503 : 200,
                    request < 2 ? "unavailable" : "ok", null);
        });
        AtomicInteger attempts = new AtomicInteger();

        String result = template(5000).execute(context -> {
            attempts.incrementAndGet();
            return client(Duration.ofSeconds(1)).post().uri("/model")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"prompt\":\"local\"}")
                    .retrieve().body(String.class);
        });

        assertThat(result).isEqualTo("ok");
        assertThat(requests).hasValue(2);
        assertThat(attempts).hasValue(2);
    }

    @Test
    void recoverableRateLimitShouldHonorRetryAfter() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        startServer(exchange -> {
            int request = requests.incrementAndGet();
            respond(exchange, request == 1 ? 429 : 200,
                    request == 1 ? "rate limited" : "ok",
                    request == 1 ? "1" : null);
        });
        long started = System.nanoTime();

        String result = template(5000).execute(context ->
                client(Duration.ofSeconds(1)).post().uri("/model")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{}")
                        .retrieve().body(String.class));

        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertThat(result).isEqualTo("ok");
        assertThat(requests).hasValue(2);
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(900);
    }

    @Test
    void permanentQuotaShouldNotRetry() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        startServer(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 429,
                    "{\"error\":{\"code\":\"insufficient_quota\"}}", "1");
        });

        assertThatThrownBy(() -> template(5000).execute(context ->
                client(Duration.ofSeconds(1)).post().uri("/model")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{}")
                        .retrieve().body(String.class)))
                .isInstanceOf(NonRetryableModelException.class);
        assertThat(requests).hasValue(1);
    }

    @Test
    void readTimeoutAfterRequestWasSentShouldNotRetry() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        startServer(exchange -> {
            requests.incrementAndGet();
            sleep(500);
            respond(exchange, 200, "late", null);
        });
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> template(5000).execute(context -> {
            attempts.incrementAndGet();
            return client(Duration.ofMillis(120)).post().uri("/model")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{}")
                    .retrieve().body(String.class);
        })).isInstanceOf(ResourceAccessException.class);

        assertThat(attempts).hasValue(1);
        assertThat(requests).hasValue(1);
    }

    @Test
    void connectFailureShouldReceiveOnlyOneBoundedRetry() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        ModelCallProperties properties = shortProperties();
        ModelFailureClassifier classifier = new ModelFailureClassifier(properties);
        RetryTemplate retryTemplate = new ModelCallConfiguration()
                .retryTemplate(properties, classifier);
        RestClient client = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + closedPort)
                .requestFactory(ClientHttpRequestFactoryBuilder.jdk().build(
                        ClientHttpRequestFactorySettings.defaults()
                                .withTimeouts(Duration.ofMillis(100), Duration.ofMillis(100))))
                .defaultStatusHandler(new ClassifiedResponseErrorHandler(classifier))
                .build();
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> retryTemplate.execute(context -> {
            attempts.incrementAndGet();
            return client.post().uri("/model").body("{}").retrieve().body(String.class);
        })).isInstanceOf(RestClientException.class);

        assertThat(attempts).hasValue(2);
    }

    private RetryTemplate template(long totalDeadlineMillis) {
        ModelCallProperties properties = shortProperties();
        properties.setTotalDeadline(Duration.ofMillis(totalDeadlineMillis));
        ModelFailureClassifier classifier = new ModelFailureClassifier(properties);
        return new ModelCallConfiguration().retryTemplate(properties, classifier);
    }

    private ModelCallProperties shortProperties() {
        ModelCallProperties properties = new ModelCallProperties();
        properties.setInitialBackoff(Duration.ofMillis(10));
        properties.setMaxBackoff(Duration.ofSeconds(2));
        properties.setJitterFactor(0);
        return properties;
    }

    private RestClient client(Duration responseTimeout) {
        ModelFailureClassifier classifier =
                new ModelFailureClassifier(shortProperties());
        var factory = ClientHttpRequestFactoryBuilder.jdk().build(
                ClientHttpRequestFactorySettings.defaults()
                        .withTimeouts(Duration.ofMillis(200), responseTimeout));
        return RestClient.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .requestFactory(factory)
                .defaultStatusHandler(new ClassifiedResponseErrorHandler(classifier))
                .build();
    }

    private void startServer(ThrowingHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/model", exchange -> {
            try {
                handler.handle(exchange);
            } catch (Exception ex) {
                try {
                    respond(exchange, 500, "local handler failure", null);
                } catch (IOException ignored) {
                }
            }
        });
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();
    }

    private void respond(HttpExchange exchange, int status, String body, String retryAfter)
            throws IOException {
        if (retryAfter != null) {
            exchange.getResponseHeaders().set("Retry-After", retryAfter);
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface ThrowingHandler {
        void handle(HttpExchange exchange) throws Exception;
    }
}
