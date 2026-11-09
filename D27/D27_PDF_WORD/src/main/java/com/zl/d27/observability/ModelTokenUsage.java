package com.zl.d27.observability;

/**
 * 一次逻辑模型调用可从 Provider metadata 获得的 token usage。
 *
 * <p>缺失 token 字段必须保持 null；0 只有在 Provider 明确返回 0 时才有效。</p>
 */
public record ModelTokenUsage(
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        UsageStatus status,
        UsageSource source,
        boolean totalTokensDerived) {

    public enum UsageStatus {
        /** prompt、completion、total 均有可用值。 */
        AVAILABLE,
        /** 至少一个 token 字段可用，但并非三项齐全。 */
        PARTIAL,
        /** Provider 没有返回 usage。 */
        NOT_RETURNED,
        /** 当前链路（如离线 Mock）不产生 Provider usage。 */
        NOT_SUPPORTED
    }

    public enum UsageSource {
        PROVIDER_METADATA,
        DERIVED,
        NONE
    }

    public static ModelTokenUsage notReturned() {
        return new ModelTokenUsage(null, null, null,
                UsageStatus.NOT_RETURNED, UsageSource.NONE, false);
    }

    public static ModelTokenUsage notSupported() {
        return new ModelTokenUsage(null, null, null,
                UsageStatus.NOT_SUPPORTED, UsageSource.NONE, false);
    }
}
