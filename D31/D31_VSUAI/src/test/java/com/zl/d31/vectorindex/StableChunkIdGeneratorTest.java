package com.zl.d31.knowledge.vectorindex;

import com.zl.d31.knowledge.vectorindex.support.StableChunkIdGenerator;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 稳定 Chunk ID 单元测试（纯内存，不依赖数据库）。
 */
class StableChunkIdGeneratorTest {

    private final StableChunkIdGenerator generator = new StableChunkIdGenerator();

    @Test
    void sameInputAlwaysProducesSameId() {
        String first = generator.generate("doc-1", "v1", "FIXED_LENGTH", 0, "hash-a",
                "split-v1", "space-1");
        String second = generator.generate("doc-1", "v1", "FIXED_LENGTH", 0, "hash-a",
                "split-v1", "space-1");
        assertThat(first).isEqualTo(second);
        assertThat(UUID.fromString(first).version()).isEqualTo(5);
    }

    @Test
    void idIsStandardUuidWithRfcVariant() {
        String id = generator.generate("doc-1", "v1", "FIXED_LENGTH", 0, "hash-a",
                "split-v1", "space-1");
        assertThat(id).hasSize(36);
        assertThat(id.charAt(14)).isEqualTo('5');
        assertThat("89ab").contains(String.valueOf(id.charAt(19)));
    }

    @Test
    void differentIdentityDimensionsProduceDifferentIds() {
        String base = generator.generate("doc-1", "v1", "FIXED_LENGTH", 0, "hash-a",
                "split-v1", "space-1");
        Set<String> ids = new HashSet<>();
        ids.add(base);
        ids.add(generator.generate("doc-2", "v1", "FIXED_LENGTH", 0, "hash-a",
                "split-v1", "space-1"));
        ids.add(generator.generate("doc-1", "v2", "FIXED_LENGTH", 0, "hash-a",
                "split-v1", "space-1"));
        ids.add(generator.generate("doc-1", "v1", "STRUCTURE_AWARE", 0, "hash-a",
                "split-v1", "space-1"));
        ids.add(generator.generate("doc-1", "v1", "FIXED_LENGTH", 1, "hash-a",
                "split-v1", "space-1"));
        ids.add(generator.generate("doc-1", "v1", "FIXED_LENGTH", 0, "hash-b",
                "split-v1", "space-1"));
        ids.add(generator.generate("doc-1", "v1", "FIXED_LENGTH", 0, "hash-a",
                "split-v2", "space-1"));
        ids.add(generator.generate("doc-1", "v1", "FIXED_LENGTH", 0, "hash-a",
                "split-v1", "space-2"));
        assertThat(ids).hasSize(8);
    }

    @Test
    void nullPartsAreTreatedAsEmptyInsteadOfThrowing() {
        String id = generator.generate(null, null, null, 0, null, null, null);
        assertThat(id).hasSize(36);
    }
}
