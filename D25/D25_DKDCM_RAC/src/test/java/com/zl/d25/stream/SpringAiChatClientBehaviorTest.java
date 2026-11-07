package com.zl.d25.stream;

import com.zl.d25.config.AiProperties;
import com.zl.d25.service.DeepSeekAiChatService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 对实际解析到的 Spring AI 1.1.8 ChatClient API 做离线行为验证：
 * call() 产出单值 content，stream() 产出 Flux，且流订阅前不会执行模型调用。
 */
class SpringAiChatClientBehaviorTest {

    @Test
    void callAndStreamShouldHaveDocumentedShapesAndStreamShouldBeLazy() {
        ChatModel chatModel = mock(ChatModel.class);
        AtomicInteger streamSubscriptions = new AtomicInteger();

        when(chatModel.call(any(Prompt.class))).thenReturn(response("同步回答"));
        when(chatModel.stream(any(Prompt.class))).thenAnswer(invocation ->
                Flux.just(response("第一段"), response("第二段"))
                        .doOnSubscribe(ignored -> streamSubscriptions.incrementAndGet()));

        ChatClient chatClient = ChatClient.builder(chatModel).build();
        String sync = chatClient.prompt().user("测试问题").call().content();
        assertThat(sync).isEqualTo("同步回答");

        Flux<String> stream = chatClient.prompt().user("测试问题").stream().content();
        assertThat(streamSubscriptions).hasValue(0);

        List<String> chunks = stream.collectList().block(Duration.ofSeconds(5));
        assertThat(chunks).containsExactly("第一段", "第二段");
        assertThat(streamSubscriptions).hasValue(1);
        verify(chatModel).call(any(Prompt.class));
    }

    @Test
    void deepSeekAdapterShouldUseChatClientStreamWithoutCallingSyncPath() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.stream(any(Prompt.class))).thenReturn(
                Flux.just(response("片段A"), response("片段B")));
        AiProperties properties = new AiProperties();
        properties.setMode("deepseek");
        properties.setApiKey("invalid-test-stream-value");
        properties.setModel("deepseek-flash");
        properties.getThinking().setReasonerModel("deepseek-reasoner");
        DeepSeekAiChatService service = new DeepSeekAiChatService(chatModel, properties);

        List<String> chunks = service.streamChat("适配器流式测试")
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(chunks).containsExactly("片段A", "片段B");
        verify(chatModel, org.mockito.Mockito.never()).call(any(Prompt.class));
        verify(chatModel).stream(any(Prompt.class));
    }

    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
