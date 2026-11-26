package com.zl.d43.rag.support;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * URL 协议安全策略（服务端强制，前端只作补充校验，不替代服务端）。
 *
 * <p>整改：改用 {@link URI} 解析，禁止用字符串拆分判断 Host，修复
 * IPv6 字面量（{@code https://[::1]/admin}）、仅带查询参数的地址
 * （{@code https://127.0.0.1?x=1}）、userinfo 视觉欺骗
 * （{@code https://trusted.com@127.0.0.1/}）等边界绕过。</p>
 *
 * <p>规则：仅允许 http/https；拒绝 localhost、IPv4 回环/私网/链路本地/组播、
 * IPv6 回环/未指定/链路本地/ULA/组播、云元数据地址、userinfo、相对路径、
 * 非法 URL 与本地文件路径；合法公网 IPv4/IPv6 保持放行。</p>
 *
 * <p><b>边界说明</b>：本类只做"引用 URL 是否可以安全返回/渲染"的静态判断，
 * 不发起任何服务端 URL 抓取；若未来需要服务端主动访问外部 URL，还须额外处理
 * DNS Rebinding、域名解析到内网地址等 SSRF 风险，不属于本类职责。</p>
 */
public final class UrlSafetyPolicy {

    private UrlSafetyPolicy() {
    }

    /**
     * 判断 URL 是否可安全返回/渲染。null、空白、非法 URL 一律视为不可安全使用
     * （不产生链接，也不渲染 {@code <a>}）。
     */
    public static boolean isSafe(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        URI uri;
        try {
            uri = new URI(url.strip());
        } catch (URISyntaxException ex) {
            return false;
        }
        // 相对路径（无 scheme）与 opaque 形态（javascript:alert(1) 等）直接拒绝
        if (!uri.isAbsolute() || uri.isOpaque()) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return false;
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return false;
        }
        // userinfo 一律拒绝：https://trusted.com@127.0.0.1/ 会被浏览器解析为访问 127.0.0.1
        if (uri.getRawUserInfo() != null) {
            return false;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return false;
        }
        return isPublicAuthority(host);
    }

    private static boolean isPublicAuthority(String rawHost) {
        String host = rawHost.toLowerCase(Locale.ROOT);
        // URI#getHost 对 IPv6 字面量返回带方括号形态（如 "[::1]"）
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.isEmpty()) {
            return false;
        }
        if ("localhost".equals(host) || host.endsWith(".localhost")) {
            return false;
        }
        if (host.indexOf(':') >= 0) {
            return isPublicIpv6Literal(host);
        }
        if (isNumericHost(host)) {
            return isPublicIpv4Literal(host);
        }
        return true;
    }

    /** 纯数字与点组成的主机名按 IPv4 字面量严格校验（含前导零变体的拒绝）。 */
    private static boolean isNumericHost(String host) {
        return host.matches("[0-9.]+");
    }

    private static boolean isPublicIpv4Literal(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3
                    || !part.chars().allMatch(Character::isDigit)) {
                return false;
            }
            // 前导零在部分解析器按八进制处理（0127 -> 127），存在绕过风险，一律拒绝
            if (part.length() > 1 && part.charAt(0) == '0') {
                return false;
            }
            int value = Integer.parseInt(part);
            if (value > 255) {
                return false;
            }
            octets[i] = value;
        }
        return isPublicIpv4Octets(octets[0], octets[1]);
    }

    /**
     * IPv4 网段判定：0/8、10/8、127/8 回环、169.254/16 链路本地（含云元数据
     * 169.254.169.254）、172.16/12、192.168/16 私网、224/4 及以上组播/保留。
     */
    private static boolean isPublicIpv4Octets(int a, int b) {
        if (a == 0 || a == 10 || a == 127) {
            return false;
        }
        if (a == 169 && b == 254) {
            return false;
        }
        if (a == 172 && b >= 16 && b <= 31) {
            return false;
        }
        if (a == 192 && b == 168) {
            return false;
        }
        return a < 224;
    }

    private static boolean isPublicIpv6Literal(String host) {
        // zone id（%eth0 / %25eth0）只出现在链路本地等场景，一律拒绝
        if (host.indexOf('%') >= 0) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            if (address instanceof Inet6Address v6) {
                if (v6.isLoopbackAddress() || v6.isAnyLocalAddress()
                        || v6.isLinkLocalAddress() || v6.isSiteLocalAddress()) {
                    return false;
                }
                byte[] bytes = v6.getAddress();
                // fc00::/7 唯一本地地址（ULA，含 fd00::/8）
                if ((bytes[0] & 0xFE) == 0xFC) {
                    return false;
                }
                // ff00::/8 组播
                if ((bytes[0] & 0xFF) == 0xFF) {
                    return false;
                }
                // IPv4-mapped（::ffff:0:0/96）：提取低 32 位按 IPv4 规则判断
                if (isIpv4Mapped(bytes)) {
                    return isPublicIpv4Octets(bytes[12] & 0xFF, bytes[13] & 0xFF);
                }
                return true;
            }
            // 部分栈会把 ::ffff:127.0.0.1 解析成 IPv4，按 IPv4 规则判断
            return isPublicIpv4Address(address);
        } catch (UnknownHostException ex) {
            return false;
        }
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF;
    }

    private static boolean isPublicIpv4Address(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length != 4) {
            return false;
        }
        return isPublicIpv4Octets(bytes[0] & 0xFF, bytes[1] & 0xFF);
    }
}
