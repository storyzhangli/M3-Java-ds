package com.zl.d31.vectorindex;

import com.zl.d31.knowledge.vectorindex.support.ContentHasher;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SHA-256 内容指纹单元测试。
 */
class ContentHasherTest {

    private final ContentHasher hasher = new ContentHasher();

    @Test
    void hashIsDeterministicLowercaseHexOf64Chars() {
        String hash = hasher.hash("D31 向量索引幂等测试");
        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(hasher.hash("D31 向量索引幂等测试")).isEqualTo(hash);
    }

    @Test
    void differentTextProducesDifferentHash() {
        assertThat(hasher.hash("a")).isNotEqualTo(hasher.hash("b"));
    }

    @Test
    void nullTextIsRejected() {
        assertThatThrownBy(() -> hasher.hash(null)).isInstanceOf(NullPointerException.class);
    }
}
