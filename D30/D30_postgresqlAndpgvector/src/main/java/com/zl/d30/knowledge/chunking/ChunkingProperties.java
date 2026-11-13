package com.zl.d30.knowledge.chunking;

import com.zl.d30.knowledge.chunking.ChunkStrategy;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Externalized text cleaning and chunking configuration.
 *
 * <p>All sizes are Unicode code points, not Java UTF-16 code units and not
 * model tokens.</p>
 */
@Data
@Validated
@ConfigurationProperties(prefix = "app.knowledge.chunking")
public class ChunkingProperties {

    @NotNull
    private ChunkStrategy defaultStrategy = ChunkStrategy.FIXED_LENGTH;

    @Min(1)
    private int chunkSize = 480;

    @Min(0)
    private int overlap = 60;

    @Min(1)
    private int minChunkSize = 80;

    @Min(1)
    private int maxChunkSize = 720;

    @AssertTrue(message = "chunk-size 必须小于等于 max-chunk-size")
    public boolean isChunkSizeWithinMaximum() {
        return chunkSize <= maxChunkSize;
    }

    @AssertTrue(message = "min-chunk-size 必须小于等于 chunk-size，且 overlap 必须小于 min-chunk-size")
    public boolean isSizeRelationsValid() {
        return minChunkSize <= chunkSize && overlap < minChunkSize;
    }

    /**
     * Runtime guard for direct instantiation outside Spring binding.
     */
    public void requireValid() {
        if (defaultStrategy == null) {
            throw new IllegalArgumentException("default-strategy 不能为空");
        }
        if (chunkSize < 1 || minChunkSize < 1 || maxChunkSize < 1) {
            throw new IllegalArgumentException("切分长度配置必须大于 0");
        }
        if (overlap < 0) {
            throw new IllegalArgumentException("overlap 不能为负数");
        }
        if (chunkSize > maxChunkSize) {
            throw new IllegalArgumentException("chunk-size 不能大于 max-chunk-size");
        }
        if (minChunkSize > chunkSize) {
            throw new IllegalArgumentException("min-chunk-size 不能大于 chunk-size");
        }
        if (overlap >= minChunkSize) {
            throw new IllegalArgumentException("overlap 必须小于 min-chunk-size");
        }
    }
}
