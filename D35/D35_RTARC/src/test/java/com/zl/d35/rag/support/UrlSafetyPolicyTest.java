package com.zl.d35.rag.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * URL 协议安全策略单测（D35-006 / R03 整改）。
 *
 * <p>覆盖任务书表格全部边界：IPv4 loopback/私网/查询参数、IPv6 loopback/未指定/
 * 链路本地/ULA、userinfo 视觉欺骗、危险协议、文件协议、空值，以及合法公网
 * IPv4/IPv6 与端口不误伤。</p>
 */
class UrlSafetyPolicyTest {

    @Test
    @DisplayName("合法 HTTPS 公网：允许")
    void safeHttpsAccepted() {
        assertThat(UrlSafetyPolicy.isSafe("https://example.com/manual.pdf")).isTrue();
    }

    @Test
    @DisplayName("合法 HTTP 公网：允许")
    void safeHttpAccepted() {
        assertThat(UrlSafetyPolicy.isSafe("http://example.com/manual.pdf")).isTrue();
    }

    @Test
    @DisplayName("合法公网 IPv6 字面量：允许（只测拒绝会掩盖误伤）")
    void publicIpv6Accepted() {
        assertThat(UrlSafetyPolicy.isSafe("https://[2001:4860:4860::8888]/dns")).isTrue();
    }

    @Test
    @DisplayName("合法公网 URL 带非默认端口：允许（端口不误伤）")
    void publicHostWithPortAccepted() {
        assertThat(UrlSafetyPolicy.isSafe("https://example.com:8443/manual.pdf")).isTrue();
        assertThat(UrlSafetyPolicy.isSafe("http://172.15.0.1/x")).isTrue();
    }

    @ParameterizedTest(name = "IPv4 回环/私网/查询参数被拒绝：{0}")
    @ValueSource(strings = {
            "http://127.0.0.1:8080/x",
            "http://127.0.0.2/x",
            "https://127.0.0.1?x=1",
            "http://0.0.0.0/x",
            "http://10.0.0.5/x",
            "http://172.16.0.1/x",
            "http://172.31.255.255/x",
            "http://192.168.1.10/x",
            "http://192.168.200.153/x",
            "http://169.254.169.254/latest/meta-data",
            "http://0127.0.0.1/x"
    })
    void localhostAndPrivateIpv4Rejected(String url) {
        assertThat(UrlSafetyPolicy.isSafe(url)).isFalse();
    }

    @ParameterizedTest(name = "IPv6 回环/未指定/链路本地/ULA 被拒绝：{0}")
    @ValueSource(strings = {
            "https://[::1]/admin",
            "https://[::]/admin",
            "https://[fe80::1]/admin",
            "https://[fc00::1]/admin",
            "https://[fd12:3456::1]/admin"
    })
    void unsafeIpv6Rejected(String url) {
        assertThat(UrlSafetyPolicy.isSafe(url)).isFalse();
    }

    @Test
    @DisplayName("UserInfo 视觉欺骗被拒绝：https://trusted.com@127.0.0.1/")
    void userInfoSpoofRejected() {
        assertThat(UrlSafetyPolicy.isSafe("https://trusted.com@127.0.0.1/")).isFalse();
        assertThat(UrlSafetyPolicy.isSafe("https://user:pass@example.com/manual.pdf")).isFalse();
    }

    @ParameterizedTest(name = "危险协议被拒绝：{0}")
    @ValueSource(strings = {
            "javascript:alert(1)",
            "data:text/html;base64,PGI+",
            "file:///C:/Windows/system32/config/SAM",
            "ftp://192.168.1.10/file",
            "about:blank"
    })
    void dangerousProtocolRejected(String url) {
        assertThat(UrlSafetyPolicy.isSafe(url)).isFalse();
    }

    @Test
    @DisplayName("相对路径与协议相对地址被拒绝")
    void relativePathRejected() {
        assertThat(UrlSafetyPolicy.isSafe("/etc/passwd")).isFalse();
        assertThat(UrlSafetyPolicy.isSafe("//example.com/x")).isFalse();
        assertThat(UrlSafetyPolicy.isSafe("manual.pdf")).isFalse();
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
