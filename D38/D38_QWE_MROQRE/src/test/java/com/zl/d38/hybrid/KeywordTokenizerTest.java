package com.zl.d38.hybrid;

import com.zl.d38.hybrid.keyword.KeywordTokenizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KP3 夹具断言（D38-R02 探测门禁正式形态）：KeywordTokenizer 与
 * LocalHashNgramEmbeddingModel.extractFeatures 同规则的六夹具输出锁定。
 *
 * <p>期望值来源：TokenProbe 对照实现（复刻 NFKC→lower(ROOT)→strip→isLetterOrDigit
 * 连续段→w:/b:/t:）在探测阶段的实测输出（docs/evidence/D38/hybrid/probe/），
 * 逐条转写为断言；任何分词行为漂移都会在此拦截（禁止静默换分词器，KP3 判据）。</p>
 */
class KeywordTokenizerTest {

    private final KeywordTokenizer tokenizer = new KeywordTokenizer("w+b+t");

    private Set<String> keys(String text) {
        return new TreeSet<>(tokenizer.tokenCounts(text).size() >= 0
                ? tokenizer.tokenCounts(text).keySet() : Set.of());
    }

    @Test
    @DisplayName("夹具1：E5 → 整词 w:e5 + bigram b:e5（英文/编码整词增益）")
    void fixtureAlphanumericCode() {
        assertEquals(Set.of("w:e5", "b:e5"), keys("E5"));
    }

    @Test
    @DisplayName("夹具2：20% → w:20（% 非 token 字符被剥为分隔，数字不被臆断拆分）")
    void fixturePercent() {
        assertEquals(Set.of("w:20", "b:20"), keys("20%"));
    }

    @Test
    @DisplayName("夹具3：15分钟 → 中文数字连写整段成单一 w: 词 + b/t n-gram")
    void fixtureDigitCjkGlued() {
        assertEquals(Set.of(
                "w:15分钟",
                "b:15", "b:5分", "b:分钟",
                "t:15分", "t:5分钟"), keys("15分钟"));
    }

    @Test
    @DisplayName("夹具4：主轴过热 → 无标点时整段一词；精确匹配依赖 b:/t:")
    void fixtureCjkRun() {
        assertEquals(Set.of(
                "w:主轴过热",
                "b:主轴", "b:轴过", "b:过热",
                "t:主轴过", "t:轴过热"), keys("主轴过热"));
    }

    @Test
    @DisplayName("夹具5：真实语料句 → % 把连写段截断为两个 w: 词（数字粘连边界如实记录）")
    void fixtureRealCorpusSentence() {
        Set<String> termKeys = keys("偏差超过20%时判定温度传感器老化");
        // w: 层仅两个词：偏差超过20 / 时判定温度传感器老化
        Set<String> words = new TreeSet<>();
        termKeys.forEach(term -> {
            if (term.startsWith("w:")) {
                words.add(term);
            }
        });
        assertEquals(Set.of("w:偏差超过20", "w:时判定温度传感器老化"), words);
        // 边界事实：数字粘连进词 w:偏差超过20；同时 b:20/b:过2 等 n-gram 仍覆盖数字
        assertTrue(termKeys.contains("b:20"), "20 的 bigram 覆盖");
        // 跨段伪 bigram 不存在：% 是分隔符，不产生 过2 之外的跨 % 组合——过2 存在、0时 不存在
        assertTrue(termKeys.contains("b:过2"), "% 前粘连段 bigram");
        assertTrue(!termKeys.contains("b:0时"), "% 为分隔符，不产生跨 % bigram");
    }

    @Test
    @DisplayName("夹具6：数控机床主轴过热报警 → 单词 + 9 bigram + 8 trigram（共 18 term）")
    void fixtureLongCjkRun() {
        Set<String> termKeys = keys("数控机床主轴过热报警");
        assertEquals(18, termKeys.size());
        assertTrue(termKeys.contains("w:数控机床主轴过热报警"));
        assertTrue(termKeys.contains("b:机床"));
        assertTrue(termKeys.contains("t:床主轴"));
        assertTrue(termKeys.contains("b:报警"));
    }

    @Test
    @DisplayName("子集过滤：仅 w 时不产出 b:/t:；c: 默认停用（冻结 w+b+t）")
    void featureSubsetFiltering() {
        KeywordTokenizer wordOnly = new KeywordTokenizer("w");
        assertEquals(Set.of("w:主轴过热"), keys(wordOnly, "主轴过热"));
        KeywordTokenizer full = new KeywordTokenizer("w+c+b+t");
        Set<String> withChars = new TreeSet<>(full.tokenCounts("E5").keySet());
        assertTrue(withChars.contains("c:e") && withChars.contains("c:5"));
    }

    private Set<String> keys(KeywordTokenizer tokenizer, String text) {
        return new TreeSet<>(tokenizer.tokenCounts(text).keySet());
    }

    @Test
    @DisplayName("规范化与空白：NFKC 全角转半角；空白输入返回空 Map（调用方拒绝）")
    void normalizationAndBlank() {
        assertEquals(keys("Ｅ５"), keys("e5"));
        assertEquals(0, tokenizer.tokenCounts("   ").size());
        assertEquals(0, tokenizer.tokenCounts(null).size());
        // 无任何字母数字：raw: 回退（与向量模型一致）
        assertEquals(Set.of("raw:%%%"), keys("%%%"));
    }

    @Test
    @DisplayName("计数合并：重复词的 tf 累加进 Map 值")
    void termFrequencyMerge() {
        Map<String, Integer> counts = tokenizer.tokenCounts("主轴过热 主轴过热");
        assertEquals(2, counts.get("w:主轴过热"));
        assertEquals(2, counts.get("b:主轴"));
    }
}
