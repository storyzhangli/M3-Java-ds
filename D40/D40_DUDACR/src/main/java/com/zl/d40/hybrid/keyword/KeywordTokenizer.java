package com.zl.d40.hybrid.keyword;

import java.text.Normalizer;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 关键词分词器：与 {@code LocalHashNgramEmbeddingModel#extractFeatures} 相同规则的
 * <b>独立复刻</b>（P1.1，登记为受控复制）。
 *
 * <p>复刻规则（逐条对齐既有实现，保证两路 term 集合一致）：</p>
 * <ol>
 *   <li>规范化：NFKC → {@code toLowerCase(Locale.ROOT)} → {@code strip()}；</li>
 *   <li>切段：按码点遍历，{@code Character.isLetterOrDigit} 连续段为一个"词"
 *       ——中文连写整段成词（KP3 实测：主轴过热 → 单一 w: 词）；</li>
 *   <li>特征：{@code w:} 整词、{@code c:} 单码点、{@code b:} bigram、{@code t:} trigram，
 *       计数合并进 TreeMap（字典序，确定性）；</li>
 *   <li>特征全集为空时回退 {@code raw:normalized}（与向量模型一致）；</li>
 *   <li>按冻结 featureSubset（w+b+t）过滤输出，c: 默认停用。</li>
 * </ol>
 *
 * <p>行为一致性由 {@code KeywordTokenizerTest} 以 KP3 六夹具逐条断言锁定
 * （E5 / 20% / 15分钟 / 主轴过热 / 偏差超过20%时判定温度传感器老化 / 数控机床主轴过热报警）。</p>
 *
 * <p>无状态、线程安全。</p>
 */
public final class KeywordTokenizer {

    /** 特征前缀枚举。 */
    public enum Feature {
        /** 整词（isLetterOrDigit 连续段）。 */
        WORD("w:"),
        /** 单码点。 */
        CHAR("c:"),
        /** 双码点 n-gram。 */
        BIGRAM("b:"),
        /** 三码点 n-gram。 */
        TRIGRAM("t:"),
        /** 空特征回退（与向量模型 raw: 语义一致，不受子集过滤）。 */
        RAW("raw:");

        private final String prefix;

        Feature(String prefix) {
            this.prefix = prefix;
        }

        public String prefix() {
            return prefix;
        }
    }

    private final Set<Feature> features;

    public KeywordTokenizer(String featureSubset) {
        Set<Feature> parsed = EnumSet.noneOf(Feature.class);
        for (String token : featureSubset.split("\\+")) {
            String trimmed = token.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            parsed.add(switch (trimmed) {
                case "w" -> Feature.WORD;
                case "c" -> Feature.CHAR;
                case "b" -> Feature.BIGRAM;
                case "t" -> Feature.TRIGRAM;
                default -> throw new IllegalArgumentException(
                        "KeywordTokenizer 非法特征: " + trimmed + "（允许 w/c/b/t）");
            });
        }
        this.features = java.util.Collections.unmodifiableSet(parsed);
    }

    /** 规范化：与向量模型逐字一致。 */
    public static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .strip();
    }

    /**
     * 提取特征计数（已按子集过滤）。输入空白返回空 Map（调用方决定拒绝语义）。
     */
    public Map<String, Integer> tokenCounts(String text) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        String normalized = normalize(text);
        Map<String, Integer> all = new TreeMap<>();
        StringBuilder word = new StringBuilder();
        for (int offset = 0; offset < normalized.length(); ) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isLetterOrDigit(codePoint)) {
                word.appendCodePoint(codePoint);
            } else {
                addWordFeatures(all, word.toString());
                word.setLength(0);
            }
        }
        addWordFeatures(all, word.toString());
        if (all.isEmpty()) {
            // 与 LocalHashNgramEmbeddingModel 相同：无任何字母数字时回退 raw: 全串
            all.put(Feature.RAW.prefix + normalized, 1);
            return features.contains(Feature.RAW) || features.isEmpty()
                    ? all : Map.of(Feature.RAW.prefix + normalized, 1);
        }
        Map<String, Integer> filtered = new TreeMap<>();
        for (Map.Entry<String, Integer> entry : all.entrySet()) {
            String key = entry.getKey();
            String prefix = key.substring(0, 2);
            Feature feature = switch (prefix) {
                case "w:" -> Feature.WORD;
                case "c:" -> Feature.CHAR;
                case "b:" -> Feature.BIGRAM;
                case "t:" -> Feature.TRIGRAM;
                default -> Feature.RAW;
            };
            if (features.contains(feature)) {
                filtered.merge(key, entry.getValue(), Integer::sum);
            }
        }
        return filtered;
    }

    private void addWordFeatures(Map<String, Integer> features, String value) {
        if (value.isBlank()) {
            return;
        }
        int[] codePoints = value.codePoints().toArray();
        if (this.features.contains(Feature.WORD)) {
            features.merge(Feature.WORD.prefix + value, 1, Integer::sum);
        }
        if (this.features.contains(Feature.CHAR)) {
            for (int codePoint : codePoints) {
                features.merge(Feature.CHAR.prefix + new String(Character.toChars(codePoint)),
                        1, Integer::sum);
            }
        }
        if (this.features.contains(Feature.BIGRAM)) {
            for (int index = 0; index + 1 < codePoints.length; index++) {
                features.merge(Feature.BIGRAM.prefix + new String(codePoints, index, 2),
                        1, Integer::sum);
            }
        }
        if (this.features.contains(Feature.TRIGRAM)) {
            for (int index = 0; index + 2 < codePoints.length; index++) {
                features.merge(Feature.TRIGRAM.prefix + new String(codePoints, index, 3),
                        1, Integer::sum);
            }
        }
        // 注意：以上直接按子集生成，与"先生成全集再过滤"结果等价（无跨段合并差异）；
        // raw: 回退分支保持全集语义。
    }
}
