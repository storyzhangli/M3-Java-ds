package com.zl.d30.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d30.service.AiChatService;
import com.zl.d30.service.ChatInvocation;
import com.zl.d30.service.StreamChunk;
import com.zl.d30.service.ThinkingSettings;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 流式空闲间隔足够短，但总时长仍必须有绝对 Deadline。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.ai.stream-timeout-ms=5000",
                "app.ai.model-call.stream-total-timeout=150ms"
        }
)
class ChatStreamTotalDeadlineTest {

    @MockitoBean
    private AiChatService aiChatService;

    @Autowired
    private TestRestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void continuouslySlowChunksShouldStillHitAbsoluteDeadline() throws Exception {
        when(aiChatService.streamChat(any(ChatInvocation.class), any(ThinkingSettings.class)))
                .thenReturn(Flux.interval(Duration.ofMillis(40))
                        .map(index -> (StreamChunk) new StreamChunk.ContentChunk("x")));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<byte[]> response = restTemplate.exchange(
                "/api/v1/ai/chat/stream", HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(
                        Map.of("message", "持续慢速输出总时限测试")), headers),
                byte[].class);

        String body = new String(response.getBody(), StandardCharsets.UTF_8);
        assertThat(body).contains("event:error");
        assertThat(body).contains("\"code\":5000");
        assertThat(body).contains("流式响应超时");
        assertThat(body).doesNotContain("event:complete");
    }
}
