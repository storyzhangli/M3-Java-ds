package com.zl.d30;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DeepSeek 真实 API 在线冒烟测试。
 *
 * <p><b>默认禁用</b>：仅当同时满足以下三个环境变量条件时才启用，否则在测试发现阶段即被跳过，
 * 普通 {@code mvn test}/{@code mvn verify} 不会产生任何真实模型调用或费用：</p>
 * <ul>
 *     <li>{@code AI_MODE=deepseek}；</li>
 *     <li>{@code AI_API_KEY} 为非空真实密钥。</li>
 *     <li>{@code AI_LIVE_TEST_AUTHORIZED=true}：用户在独立操作中明确授权真实计费调用。</li>
 * </ul>
 *
 * <p>手动启用示例（PowerShell）：</p>
 * <pre>
 * $env:AI_MODE="deepseek"
 * 密钥由用户自行通过安全环境变量注入，不应出现在脚本、命令历史或文档中。
 * $env:AI_LIVE_TEST_AUTHORIZED="true"
 * mvn -f D30\pom.xml test -Dtest=DeepSeekLiveSmokeTest
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "AI_MODE", matches = "deepseek")
@EnabledIfEnvironmentVariable(named = "AI_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "AI_LIVE_TEST_AUTHORIZED", matches = "true")
@SpringBootTest
class DeepSeekLiveSmokeTest {

    @Autowired
    private ChatModel chatModel;

    @Test
    void realDeepSeekCallShouldReturnNonBlankReply() {
        Prompt prompt = new Prompt(new UserMessage("用一句话说明设备日常点检的作用。"));
        ChatResponse response = chatModel.call(prompt);

        String reply = response.getResult().getOutput().getText();
        assertThat(reply).isNotBlank();
        System.out.println("[LiveSmoke] DeepSeek 真实调用返回非空文本，字符数=" + reply.length());
    }
}
