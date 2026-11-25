package com.zl.d42.rag.support;

/**
 * 引用片段有限截取工具（引用溯源契约的一部分）。
 *
 * <p>引用片段（excerpt）只能来自真实 chunk 原文的有限截取，禁止模型生成或改写；
 * 截取口径为 UTF-16 字符数（与"引用片段"展示语义一致），且在代理对边界截断，
 * 不破坏 Unicode 码点（emoji 等增补平面字符不会被切成两半）。</p>
 */
public final class CitationText {

    private CitationText() {
    }

    /** 默认引用片段上限（UTF-16 字符）。 */
    public static final int DEFAULT_EXCERPT_MAX_CHARS = 200;

    /**
     * 从原文生成有限引用片段：不足上限原样返回；超过上限在代理对边界截断并追加省略号。
     *
     * @param content  真实 chunk 原文（不可信数据，只做截取，不改写语义）
     * @param maxChars 上限（UTF-16 字符）
     */
    public static String excerpt(String content, int maxChars) {
        if (content == null) {
            return null;
        }
        String compact = content.strip();
        if (maxChars <= 0) {
            return "";
        }
        if (compact.length() <= maxChars) {
            return compact;
        }
        int cut = maxChars;
        // 代理对边界：若截断点落在低位代理（low surrogate），回退一个 UTF-16 单元
        if (Character.isLowSurrogate(compact.charAt(cut))) {
            cut--;
        }
        return compact.substring(0, cut) + "…";
    }
}
