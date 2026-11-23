package com.zl.d40.access;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Bearer token → SHA-256 小写 hex（与 d40_auth_principal.token_sha256 同口径）。
 * JDK MessageDigest，零新增依赖（D1）。
 */
public final class AuthTokenHasher {

    private AuthTokenHasher() {
    }

    public static String sha256Hex(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            // JDK21 必含 SHA-256；出现即环境异常，fail-closed
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
