package com.zl.d35.rag.support;

import java.util.Locale;

/**
 * URL 协议安全策略（服务端强制，前端只作补充校验，不替代服务端）。
 *
 * <p>引用来源 url 当前恒 null（无来源），本策略作为"未来防护"与服务端强制兜底：
 * 仅允许 http/https 且非本机/内网的 URL；模型生成或用户输入拼接的 URL 一律拒绝。
 * 黑名单：javascript:、data:、file:、ftp:、localhost、内网地址、本机绝对路径。</p>
 */
public final class UrlSafetyPolicy {

    private UrlSafetyPolicy() {
    }

    /**
     * 判断 URL 是否可安全返回/渲染。null 或空白视为"无来源"，不作为危险（但也不产生链接）。
     */
    public static boolean isSafe(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String value = url.strip().toLowerCase(Locale.ROOT);
        if (value.startsWith("javascript:") || value.startsWith("data:")
                || value.startsWith("file:") || value.startsWith("ftp:")) {
            return false;
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            return false;
        }
        String host = extractHost(value);
        if (host == null || host.isBlank()) {
            return false;
        }
        if (host.equals("localhost") || host.endsWith(".localhost")
                || host.equals("127.0.0.1") || host.equals("0.0.0.0") || host.equals("::1")) {
            return false;
        }
        // 私网/链路本地地址段黑名单（10.x / 172.16-31.x / 192.168.x / 169.254.x）
        if (host.matches("10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")
                || host.matches("172\\.(1[6-9]|2[0-9]|3[01])\\.\\d{1,3}\\.\\d{1,3}")
                || host.matches("192\\.168\\.\\d{1,3}\\.\\d{1,3}")
                || host.matches("169\\.254\\.\\d{1,3}\\.\\d{1,3}")) {
            return false;
        }
        return true;
    }

    private static String extractHost(String url) {
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return null;
        }
        String afterScheme = url.substring(scheme + 3);
        int slash = afterScheme.indexOf('/');
        String authority = slash < 0 ? afterScheme : afterScheme.substring(0, slash);
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        int colon = authority.lastIndexOf(':');
        if (colon >= 0 && authority.indexOf(']') < colon) {
            authority = authority.substring(0, colon);
        }
        return authority;
    }
}
