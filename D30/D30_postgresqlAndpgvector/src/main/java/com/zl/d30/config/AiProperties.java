package com.zl.d30.config;

import com.zl.d30.config.AiConstants;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

import java.net.URI;

/**
 * 应用级 AI 配置（前缀 {@code app.ai}）。
 *
 * <ul>
 *     <li>{@code mode}：运行模式，取值 {@code mock}（默认）或 {@code deepseek}；</li>
 *     <li>{@code model}、{@code baseUrl}、{@code apiKey}：真实模型配置；</li>
 *     <li>{@code maxMessageLength}：聊天入参消息长度上限，可通过 {@code AI_CHAT_MAX_LENGTH}
 *         调整，避免无上限请求。</li>
 * </ul>
 *
 * <p>API Key 只用于供应商适配边界，不进入 toString，不参与日志。</p>
 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "app.ai")
public class AiProperties {

    /** 运行模式：mock（离线模拟）或 deepseek（真实调用），默认 mock。 */
    @NotBlank
    @Pattern(regexp = "mock|deepseek", message = "mode 仅支持 mock 或 deepseek")
    private String mode = AiConstants.MODE_MOCK;

    /** 真实模式默认模型名；Mock 输出仍使用 offline-mock。 */
    @NotBlank
    private String model = "deepseek-flash";

    /** 受信任的模型 Base URL，仅允许 http/https，不接受请求参数覆盖。 */
    @NotBlank
    private String baseUrl = "https://api.deepseek.com";

    /** Provider API Key；默认空字符串，Mock 模式允许为空。 */
    @ToString.Exclude
    private String apiKey = "";

    /** 默认采样温度。 */
    @DecimalMin(value = "0.0", message = "temperature 必须 >= 0")
    @DecimalMax(value = "2.0", message = "temperature 必须 <= 2")
    private Double temperature = 0.7;

    /** 思考模式使用的模型名。 */
    @Valid
    private Thinking thinking = new Thinking();

    /** 消息最大长度（字符数），默认 2000，可通过配置覆盖。 */
    private int maxMessageLength = 2000;

    /**
     * SSE 流式连接的服务端超时时间（毫秒），默认 60 秒。
     *
     * <p>超时后容器会触发 {@code SseEmitter.onTimeout}，服务端据此取消上游订阅、释放连接，
     * 避免慢客户端或僵死连接长期占用线程与模型调用资源。可通过 {@code AI_STREAM_TIMEOUT_MS} 调整。</p>
     */
    private long streamTimeoutMs = 60000L;

    @AssertTrue(message = "base-url 必须是带 host 的 http/https URI，且不得包含 user-info")
    public boolean isBaseUrlValid() {
        if (!StringUtils.hasText(baseUrl)) {
            return false;
        }
        try {
            URI uri = URI.create(baseUrl);
            return ("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))
                    && StringUtils.hasText(uri.getHost())
                    && uri.getUserInfo() == null;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    @Data
    public static class Thinking {

        /** 思考模式模型名；通过 app.ai.thinking.reasoner-model 或 AI_REASONER_MODEL 配置。 */
        @NotBlank
        private String reasonerModel = "deepseek-reasoner";
    }
}
