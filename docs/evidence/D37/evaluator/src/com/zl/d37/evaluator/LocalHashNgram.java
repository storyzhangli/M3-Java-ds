package com.zl.d37.evaluator;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 本地确定性哈希 n-gram Embedding（与生产 LocalHashNgramEmbeddingModel 算法等价）。
 *
 * <p>评测器为独立黑盒程序，不依赖 Spring Bean，故在此复刻同一套依赖无关的词法
 * Embedding 算法，用于 IN_MEMORY_LOCAL 降级后端与自测。算法口径：NFKC 归一化 →
 * 小写 → strip → 词/字符/二元/三元特征 → SHA-256 定址 + 符号 + log 权重 → L2 归一化。</p>
 */
public final class LocalHashNgram {

    private final int dimensions;

    public LocalHashNgram(int dimensions) {
        this.dimensions = dimensions;
    }

    public int dimensions() {
        return dimensions;
    }

    /**
     * 文本向量化（L2 归一化后的 384 维向量）。
     */
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Embedding 输入不能为空或纯空白");
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .strip();
        Map<String, Integer> features = extractFeatures(normalized);
        if (features.isEmpty()) {
            features.put("raw:" + normalized, 1);
        }
        double[] raw = new double[dimensions];
        for (Map.Entry<String, Integer> entry : features.entrySet()) {
            byte[] digest = sha256(entry.getKey());
            int index = Math.floorMod(ByteBuffer.wrap(digest, 0, 4).getInt(), dimensions);
            double sign = (digest[4] & 1) == 0 ? 1.0 : -1.0;
            double weight = 1.0 + Math.log(entry.getValue());
            raw[index] += sign * weight;
        }
        double squaredNorm = 0.0;
        for (double value : raw) {
            squaredNorm += value * value;
        }
        double norm = Math.sqrt(squaredNorm);
        if (!Double.isFinite(norm) || norm <= 1.0e-20) {
            throw new IllegalStateException("本地 Embedding 生成零向量");
        }
        float[] vector = new float[raw.length];
        for (int i = 0; i < raw.length; i++) {
            vector[i] = (float) (raw[i] / norm);
        }
        return vector;
    }

    /**
     * 余弦相似度（两向量已 L2 归一化，点积即余弦）。
     */
    public static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("向量维度不一致");
        }
        double dot = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
        }
        return dot;
    }

    private Map<String, Integer> extractFeatures(String text) {
        Map<String, Integer> features = new TreeMap<>();
        StringBuilder word = new StringBuilder();
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isLetterOrDigit(codePoint)) {
                word.appendCodePoint(codePoint);
            } else {
                addWordFeatures(features, word.toString());
                word.setLength(0);
            }
        }
        addWordFeatures(features, word.toString());
        return features;
    }

    private void addWordFeatures(Map<String, Integer> features, String value) {
        if (value.isBlank()) {
            return;
        }
        int[] codePoints = value.codePoints().toArray();
        features.merge("w:" + value, 1, Integer::sum);
        for (int codePoint : codePoints) {
            features.merge("c:" + new String(Character.toChars(codePoint)), 1, Integer::sum);
        }
        for (int index = 0; index + 1 < codePoints.length; index++) {
            features.merge("b:" + new String(codePoints, index, 2), 1, Integer::sum);
        }
        for (int index = 0; index + 2 < codePoints.length; index++) {
            features.merge("t:" + new String(codePoints, index, 3), 1, Integer::sum);
        }
    }

    private byte[] sha256(String feature) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(feature.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
