package com.zl.d27.config;

/**
 * D01 AI 接入相关常量。
 *
 * <p>集中管理模式标识与模拟回复标记，避免在业务代码与配置文件中出现散落的魔法值。</p>
 */
public final class AiConstants {

    /** 模拟模式：默认值，不发起任何真实网络请求，直接返回确定性结果。 */
    public static final String MODE_MOCK = "mock";

    /** 真实模式：通过 Spring AI 调用 DeepSeek 官方接口。 */
    public static final String MODE_DEEPSEEK = "deepseek";

    /** 模拟回复的固定标记，便于测试与调用方一眼识别当前为离线模拟结果。 */
    public static final String MOCK_REPLY_MARKER = "[MOCK:D01]";

    /** D16 模拟思考过程的固定标记，与最终答案标记区分，明确它不是真实模型推理。 */
    public static final String MOCK_REASONING_MARKER = "[MOCK-REASONING:D16]";

    private AiConstants() {
    }
}
