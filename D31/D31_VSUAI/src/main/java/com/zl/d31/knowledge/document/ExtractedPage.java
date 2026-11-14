package com.zl.d31.knowledge.document;

/**
 * 解析器输出的单个物理页面。
 *
 * @param pageNumber PDF 物理页码，从 1 开始
 * @param content 规范化后的页面文本；空白页为空字符串
 * @param blankPage 页面是否未提取到有效文本
 */
public record ExtractedPage(int pageNumber, String content, boolean blankPage) {

    public ExtractedPage {
        if (pageNumber < 1) {
            throw new IllegalArgumentException("pageNumber must start at 1");
        }
        content = content == null ? "" : content;
    }

    public int characterCount() {
        return content.length();
    }
}
