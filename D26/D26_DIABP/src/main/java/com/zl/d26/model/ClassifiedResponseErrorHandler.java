package com.zl.d26.model;

import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.ResponseErrorHandler;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * 将同步 HTTP 状态映射为统一内部异常；不把上游响应正文写进异常或日志。
 */
public class ClassifiedResponseErrorHandler implements ResponseErrorHandler {

    private final ModelFailureClassifier classifier;

    public ClassifiedResponseErrorHandler(ModelFailureClassifier classifier) {
        this.classifier = classifier;
    }

    @Override
    public boolean hasError(ClientHttpResponse response) throws IOException {
        return response.getStatusCode().isError();
    }

    @Override
    public void handleError(ClientHttpResponse response) throws IOException {
        handleError(null, null, response);
    }

    @Override
    public void handleError(URI url, org.springframework.http.HttpMethod method,
                            ClientHttpResponse response) throws IOException {
        if (!response.getStatusCode().isError()) {
            return;
        }
        int status = response.getStatusCode().value();
        String retryAfter = response.getHeaders().getFirst("Retry-After");
        String body;
        try (var input = response.getBody()) {
            body = StreamUtils.copyToString(input, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            body = "";
        }
        ModelFailure failure = classifier.httpFailure(status, retryAfter, body);
        if (failure.retryable()) {
            throw new RetryableModelException(failure.category(), status, failure.retryAfter());
        }
        throw new NonRetryableModelException(failure.category(), status);
    }
}
