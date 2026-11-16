package com.zl.d33.rag;

import com.zl.d33.rag.config.RagProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 配置校验：非法组合立即失败；默认值符合任务书约定。
 */
class RagPropertiesTest {

    @Test
    void defaultsAreTaskBookConformant() {
        RagProperties properties = new RagProperties();
        assertNull(properties.getTopK(), "top-k 缺省为空，沿用检索默认");
        assertEquals(4000, properties.getMaxContextChars());
        assertEquals(0.0d, properties.getMinScore());
        assertDoesNotThrow(properties::validate);
    }

    @Test
    void illegalCombinationsFailFast() {
        RagProperties topKInvalid = new RagProperties();
        topKInvalid.setTopK(0);
        assertThrows(IllegalArgumentException.class, topKInvalid::validate);

        RagProperties budgetInvalid = new RagProperties();
        budgetInvalid.setMaxContextChars(0);
        assertThrows(IllegalArgumentException.class, budgetInvalid::validate);

        RagProperties badScore = new RagProperties();
        badScore.setMinScore(1.5d);
        assertThrows(IllegalArgumentException.class, badScore::validate);
    }
}
