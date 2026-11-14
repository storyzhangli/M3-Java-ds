package com.zl.d31.knowledge.vectorindex.support;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * 稳定 Chunk ID 生成器：RFC 4122 名称型 UUID v5（SHA-1）。
 *
 * <p>名称由稳定身份要素拼接：
 * {@code documentId|version|strategy|chunkIndex|chunkContentHash|splitVersion|vectorSpaceId}。
 * 该设计保证：</p>
 * <ul>
 *   <li>相同输入（含失败重试）恒得相同 UUID，不产生新的无关 ID；</li>
 *   <li>不同文档即使内容相同也得到不同 ID，归属不混淆；</li>
 *   <li>文档版本、切分算法版本或向量空间（模型/维度）变化后旧 ID 不复用；</li>
 *   <li>输出为标准 36 字符 UUID，可直接作为 PgVectorStore uuid 主键。</li>
 * </ul>
 */
@Component
public class StableChunkIdGenerator {

    /** D31 向量索引专用命名空间 UUID（固定常量，不随运行变化）。 */
    public static final UUID NAMESPACE = UUID.fromString("0f9f7d52-6b3e-4a1c-9f2d-7c8e5b3a6d41");

    /**
     * 计算稳定 Chunk ID（36 字符 UUID 字符串）。
     *
     * @param documentId       文档业务标识
     * @param version          文档版本
     * @param strategy         切分策略
     * @param chunkIndex       版本内 Chunk 顺序
     * @param chunkContentHash Chunk 文本指纹（SHA-256）
     * @param splitVersion     切分算法版本
     * @param vectorSpaceId    向量空间标识（模型 + 版本 + 维度）
     */
    public String generate(String documentId, String version, String strategy, int chunkIndex,
                           String chunkContentHash, String splitVersion, String vectorSpaceId) {
        String name = String.join("|",
                nullToEmpty(documentId),
                nullToEmpty(version),
                nullToEmpty(strategy),
                String.valueOf(chunkIndex),
                nullToEmpty(chunkContentHash),
                nullToEmpty(splitVersion),
                nullToEmpty(vectorSpaceId));
        return nameUuidV5(NAMESPACE, name).toString();
    }

    /**
     * RFC 4122 UUID v5：SHA-1 名称型。
     */
    public static UUID nameUuidV5(UUID namespace, String name) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(toBytes(namespace));
            digest.update(name.getBytes(StandardCharsets.UTF_8));
            byte[] hash = digest.digest();
            byte[] result = new byte[16];
            System.arraycopy(hash, 0, result, 0, 16);
            // 版本号 5（高 4 位）与 IETF 变体（10xx）
            result[6] &= 0x0F;
            result[6] |= 0x50;
            result[8] &= 0x3F;
            result[8] |= (byte) 0x80;
            return fromBytes(result);
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 为 JDK 内置标准算法，正常环境必然存在
            throw new IllegalStateException("当前环境不支持 SHA-1 摘要算法", e);
        }
    }

    private static byte[] toBytes(UUID uuid) {
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        byte[] bytes = new byte[16];
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (msb >>> 8 * (7 - i));
            bytes[8 + i] = (byte) (lsb >>> 8 * (7 - i));
        }
        return bytes;
    }

    private static UUID fromBytes(byte[] bytes) {
        long msb = 0;
        long lsb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (bytes[i] & 0xFF);
        }
        for (int i = 8; i < 16; i++) {
            lsb = (lsb << 8) | (bytes[i] & 0xFF);
        }
        return new UUID(msb, lsb);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
