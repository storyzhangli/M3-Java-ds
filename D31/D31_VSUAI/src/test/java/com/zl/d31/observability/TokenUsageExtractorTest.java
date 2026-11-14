package com.zl.d31.observability;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TokenUsageExtractorTest {

    @Test
    void completeProviderUsageShouldBeAvailableWithoutDerivation() {
        ModelTokenUsage usage = TokenUsageExtractor.from(response(
                new DefaultUsage(12, 5, 17, new NativeUsage(12, 5, 17))));

        assertThat(usage.promptTokens()).isEqualTo(12);
        assertThat(usage.completionTokens()).isEqualTo(5);
        assertThat(usage.totalTokens()).isEqualTo(17);
        assertThat(usage.status()).isEqualTo(ModelTokenUsage.UsageStatus.AVAILABLE);
        assertThat(usage.source()).isEqualTo(ModelTokenUsage.UsageSource.PROVIDER_METADATA);
        assertThat(usage.totalTokensDerived()).isFalse();
    }

    @Test
    void partialUsageShouldKeepMissingFieldsNull() {
        Usage providerUsage = mock(Usage.class);
        when(providerUsage.getPromptTokens()).thenReturn(7);
        when(providerUsage.getCompletionTokens()).thenReturn(null);
        when(providerUsage.getTotalTokens()).thenReturn(null);

        ModelTokenUsage usage = TokenUsageExtractor.from(response(providerUsage));

        assertThat(usage.promptTokens()).isEqualTo(7);
        assertThat(usage.completionTokens()).isNull();
        assertThat(usage.totalTokens()).isNull();
        assertThat(usage.status()).isEqualTo(ModelTokenUsage.UsageStatus.PARTIAL);
        assertThat(usage.source()).isEqualTo(ModelTokenUsage.UsageSource.PROVIDER_METADATA);
    }

    @Test
    void missingUsageMustNotBecomeZeroOrEstimatedValue() {
        ModelTokenUsage usage = TokenUsageExtractor.from(
                new ChatResponse(List.of(new Generation(new AssistantMessage("answer")))));

        assertThat(usage.promptTokens()).isNull();
        assertThat(usage.completionTokens()).isNull();
        assertThat(usage.totalTokens()).isNull();
        assertThat(usage.status()).isEqualTo(ModelTokenUsage.UsageStatus.NOT_RETURNED);
        assertThat(usage.source()).isEqualTo(ModelTokenUsage.UsageSource.NONE);
    }

    @Test
    void totalMayBeDerivedOnlyWhenBothProviderComponentsAreExplicit() {
        Usage providerUsage = new DefaultUsage(3, 4, null,
                new NativeUsage(3, 4, null));

        ModelTokenUsage usage = TokenUsageExtractor.from(response(providerUsage));

        assertThat(usage.totalTokens()).isEqualTo(7);
        assertThat(usage.status()).isEqualTo(ModelTokenUsage.UsageStatus.AVAILABLE);
        assertThat(usage.source()).isEqualTo(ModelTokenUsage.UsageSource.DERIVED);
        assertThat(usage.totalTokensDerived()).isTrue();
    }

    @Test
    void explicitProviderZeroShouldRemainZero() {
        ModelTokenUsage usage = TokenUsageExtractor.from(response(
                new DefaultUsage(0, 0, 0, new NativeUsage(0, 0, 0))));

        assertThat(usage.promptTokens()).isZero();
        assertThat(usage.completionTokens()).isZero();
        assertThat(usage.totalTokens()).isZero();
        assertThat(usage.status()).isEqualTo(ModelTokenUsage.UsageStatus.AVAILABLE);
    }

    private ChatResponse response(Usage usage) {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage("answer"))),
                ChatResponseMetadata.builder().usage(usage).id("provider-id-1").build());
    }

    private record NativeUsage(Integer promptTokens, Integer completionTokens,
                               Integer totalTokens) {
    }
}
