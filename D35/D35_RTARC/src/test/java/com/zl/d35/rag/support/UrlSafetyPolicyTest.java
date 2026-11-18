package com.zl.d35.rag.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * URL 协议安全策略单测（D35-006）：服务端强制黑名单。
 */
class UrlSafetyPolicyTest {

    @Test
    @DisplayName("合法 https 外链：安全")
    void safeHttpsAccepted() {
        assertThat(UrlSafetyPolicy.isSafe("https://example.com/manual.pdf")).isTrue();
    }

    @Test
    @DisplayName("合法 http 外链：安全")
    void safeHttpAccepted() {
        assertThat(UrlSafetyPolicy.isSafe("http://example.com/manual.pdf")).isTrue();
    }

    @ParameterizedTest(name = "危险协议被拒绝：{0}")
    @ValueSource(strings = {
            "javascript:alert(1)",
            "data:text/html;base64,PGI+",
            "file:///C:/Windows/system32/config/SAM",
            "ftp://192.168.1.10/file"
    })
    void dangerousProtocolRejected(String url) {
        assertThat(UrlSafetyPolicy.isSafe(url)).isFalse();
    }

    @ParameterizedTest(name = "本机/内网被拒绝：{0}")
    @ValueSource(strings = {
            "http://localhost:8080/api",
            "http://127.0.0.1:8080/x",
            "http://0.0.0.0/x",
            "http://10.0.0.5/x",
            "http://172.16.0.1/x",
            "http://172.31.255.255/x",
            "http://192.168.200.153/x",
            "http://169.254.169.254/latest/meta-data"
    })
    void localhostAndPrivateNetworkRejected(String url) {
        assertThat(UrlSafetyPolicy.isSafe(url)).isFalse();
    }

    @Test
    @DisplayName("非 http(s) 字符串（含盘符绝对路径）被拒绝")
    void nonHttpRejected() {
        assertThat(UrlSafetyPolicy.isSafe("C:\\Users\\zl\\secret.txt")).isFalse();
        assertThat(UrlSafetyPolicy.isSafe("D:/data/secret.pdf")).isFalse();
        assertThat(UrlSafetyPolicy.isSafe("about:blank")).isFalse();
    }

    @Test
    @DisplayName("null/空白：不产生链接（非危险，但也不渲染）")
    void nullAndBlankNotSafe() {
        assertThat(UrlSafetyPolicy.isSafe(null)).isFalse();
        assertThat(UrlSafetyPolicy.isSafe("")).isFalse();
        assertThat(UrlSafetyPolicy.isSafe("   ")).isFalse();
    }
}
