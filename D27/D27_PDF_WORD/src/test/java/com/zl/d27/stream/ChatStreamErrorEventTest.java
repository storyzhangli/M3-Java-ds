package com.zl.d27.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d27.service.AiChatService;
import com.zl.d27.service.ChatInvocation;
import com.zl.d27.service.StreamChunk;
import com.zl.d27.service.ThinkingSettings;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 流开始后发生异常的路径测试：用 Mockito Bean 替换真实服务，令其流式方法直接发布错误，
 * 验证 Controller 在响应头已提交后下发 SSE {@code error} 事件且不产生 {@code complete}，
 * 失败不会被伪装成正常完成。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatStreamErrorEventTest {

    private static final String STREAM_URL = "/api/v1/ai/chat/stream";

    @MockitoBean
    private AiChatService aiChatService;

    @Autowired
    private TestRestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void errorAfterStreamStartedShouldEmitErrorEventWithoutComplete() throws Exception {
        when(aiChatService.streamChat(anyString()))
                .thenReturn(Flux.error(new RuntimeException("simulated upstream failure")));
        // D16 起控制器走类型化分片新方法；Mockito  mock 不继承接口默认方法，需显式补桩
        when(aiChatService.streamChat(any(ChatInvocation.class), any(ThinkingSettings.class)))
                .thenReturn(Flux.error(new RuntimeException("simulated upstream failure")));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(
                objectMapper.writeValueAsString(Map.of("message", "触发流异常")), headers);

        ResponseEntity<byte[]> response =
                restTemplate.exchange(STREAM_URL, HttpMethod.POST, entity, byte[].class);

        // SSE 响应头已经发出，HTTP 层仍为 200，错误通过协议内事件表达
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType().toString()).contains("text/event-stream");

        String body = new String(response.getBody(), StandardCharsets.UTF_8);
        assertThat(body).contains("event:error");
        assertThat(body).contains("\"code\":5000");
        assertThat(body).doesNotContain("event:complete");
        // 原始异常细节不得外泄到客户端
        assertThat(body).doesNotContain("simulated upstream failure");
        verify(aiChatService, times(1))
                .streamChat(any(ChatInvocation.class), any(ThinkingSettings.class));
    }

    @Test
    void transientUpstreamFailureShouldMapToUnavailableCodeWithoutComplete() throws Exception {
        when(aiChatService.streamChat(any(ChatInvocation.class), any(ThinkingSettings.class)))
                .thenReturn(Flux.error(new TransientAiException("simulated rate limit")));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(
                objectMapper.writeValueAsString(Map.of("message", "模拟限流失败")), headers);

        ResponseEntity<byte[]> response =
                restTemplate.exchange(STREAM_URL, HttpMethod.POST, entity, byte[].class);
        String body = new String(response.getBody(), StandardCharsets.UTF_8);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(body).contains("event:error");
        assertThat(body).contains("\"code\":5002");
        assertThat(body).doesNotContain("event:complete");
        assertThat(body).doesNotContain("simulated rate limit");
        verify(aiChatService, times(1))
                .streamChat(any(ChatInvocation.class), any(ThinkingSettings.class));
    }

    @Test
    void failureAfterFirstDeltaShouldNotReplayFromBeginning() throws Exception {
        when(aiChatService.streamChat(any(ChatInvocation.class), any(ThinkingSettings.class)))
                .thenReturn(Flux.concat(
                        Flux.just(new StreamChunk.ContentChunk("首段")),
                        Flux.error(new TransientAiException("stream interrupted"))));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<byte[]> response = restTemplate.exchange(
                STREAM_URL, HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(
                        Map.of("message", "首段后失败测试")), headers),
                byte[].class);
        String body = new String(response.getBody(), StandardCharsets.UTF_8);

        assertThat(body).contains("event:delta");
        assertThat(body).contains("\"content\":\"首段\"");
        assertThat(body).contains("event:error");
        assertThat(body).doesNotContain("event:complete");
        assertThat(body).doesNotContain("stream interrupted");
        // 已产生客户端可见内容后，不允许从头再次调用模型并拼接重复片段。
        verify(aiChatService, times(1))
                .streamChat(any(ChatInvocation.class), any(ThinkingSettings.class));
    }
}
