package com.zl.d28.observability;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

import java.lang.reflect.Method;

/**
 * 从 Spring AI 1.1.8 ChatResponse metadata 提取 Provider 实际返回的 usage。
 *
 * <p>本类不使用字符数、消息长度或经验比例估算 token。只有 prompt 与 completion
 * 都由 Provider 返回而 total 缺失时，才允许相加并明确标记 totalTokensDerived。</p>
 */
public final class TokenUsageExtractor {

    private TokenUsageExtractor() {
    }

    public static ModelTokenUsage from(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return ModelTokenUsage.notReturned();
        }
        Usage usage = response.getMetadata().getUsage();
        if (usage == null || isEmptyUsage(usage)) {
            return ModelTokenUsage.notReturned();
        }

        Integer prompt = nonNegative(quietly(usage::getPromptTokens));
        Integer completion = nonNegative(quietly(usage::getCompletionTokens));
        Integer total = directTotal(usage);

        boolean anyDirectValue = prompt != null || completion != null || total != null;
        boolean totalDerived = false;
        if (total == null && prompt != null && completion != null) {
            long derived = (long) prompt + completion;
            if (derived <= Integer.MAX_VALUE) {
                total = (int) derived;
                totalDerived = true;
            }
        }

        ModelTokenUsage.UsageStatus status;
        if (prompt != null && completion != null && total != null) {
            status = ModelTokenUsage.UsageStatus.AVAILABLE;
        } else if (anyDirectValue || total != null) {
            status = ModelTokenUsage.UsageStatus.PARTIAL;
        } else {
            status = ModelTokenUsage.UsageStatus.NOT_RETURNED;
        }

        ModelTokenUsage.UsageSource source;
        if (totalDerived) {
            source = ModelTokenUsage.UsageSource.DERIVED;
        } else if (anyDirectValue) {
            source = ModelTokenUsage.UsageSource.PROVIDER_METADATA;
        } else {
            source = ModelTokenUsage.UsageSource.NONE;
        }

        return new ModelTokenUsage(prompt, completion, total, status, source, totalDerived);
    }

    private static Integer quietly(java.util.function.Supplier<Integer> supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static Integer nonNegative(Integer value) {
        return value != null && value >= 0 ? value : null;
    }

    /**
     * DefaultUsage 会在构造时用 prompt+completion 自动计算 total。
     * 因此优先读取 native Provider usage 的 total；只有非 DefaultUsage 实现才信任摘要接口。
     */
    private static Integer directTotal(Usage usage) {
        Object nativeUsage = quietlyObject(usage::getNativeUsage);
        if (nativeUsage != null) {
            Integer nativeTotal = readNativeTotal(nativeUsage);
            if (nativeTotal != null) {
                return nativeTotal;
            }
        }
        if ("org.springframework.ai.chat.metadata.DefaultUsage".equals(usage.getClass().getName())) {
            return null;
        }
        return nonNegative(quietly(usage::getTotalTokens));
    }

    private static Object quietlyObject(java.util.function.Supplier<Object> supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static Integer readNativeTotal(Object nativeUsage) {
        for (String methodName : new String[]{"totalTokens", "getTotalTokens"}) {
            try {
                Method method = nativeUsage.getClass().getMethod(methodName);
                Object value = method.invoke(nativeUsage);
                if (value instanceof Integer tokenCount) {
                    return nonNegative(tokenCount);
                }
            } catch (ReflectiveOperationException ignored) {
                // 尝试另一个常见 Provider native accessor
            }
        }
        return null;
    }

    /**
     * Spring AI 1.1.8 的 ChatResponseMetadata 默认放置 EmptyUsage，其 token 方法返回 0。
     * 这是“Provider 未返回 usage”的框架占位，不是 Provider 明确返回的 0。
     */
    private static boolean isEmptyUsage(Usage usage) {
        return "org.springframework.ai.chat.metadata.EmptyUsage".equals(usage.getClass().getName());
    }
}
