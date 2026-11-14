package com.zl.d31.knowledge.vectorindex.support;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 内容指纹工具：文档版本载荷与 Chunk 文本摘要。
 *
 * <p>输出为 64 位小写十六进制，与数据库 CHAR(64) 列对齐；不改写任何输入文本。</p>
 */
@Component
public class ContentHasher {

    public String hash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前环境不支持 SHA-256 摘要算法", e);
        }
    }
}
