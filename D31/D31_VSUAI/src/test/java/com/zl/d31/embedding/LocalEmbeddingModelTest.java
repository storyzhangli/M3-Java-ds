package com.zl.d31.embedding;

import com.zl.d31.knowledge.embedding.EmbeddingModelDescriptor;
import com.zl.d31.knowledge.embedding.EmbeddingProperties;
import com.zl.d31.knowledge.embedding.EmbeddingVectorValidator;
import com.zl.d31.knowledge.embedding.LocalHashNgramEmbeddingModel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class LocalEmbeddingModelTest {

    private final EmbeddingVectorValidator validator = new EmbeddingVectorValidator();

    private final LocalHashNgramEmbeddingModel model = model();

    @Test
    void sameChineseTextShouldReturnStableFiniteNormalizedVector() {
        String text = "数控机床出现主轴过热报警时，应先检查冷却液液位与冷却泵运行声音。";

        float[] first = model.embedText(text);
        float[] second = model.embedText(text);

        assertThat(first).hasSize(384);
        assertThat(second).hasSize(384);
        assertThat(first).containsExactly(second);
        validator.validate(first, 384);
        assertThat(validator.l2Norm(first)).isCloseTo(1.0, within(1.0e-5));
        for (float value : first) {
            assertThat(Float.isFinite(value)).isTrue();
        }
    }

    @Test
    void differentChineseAndMixedTextsShouldProduceDifferentVectors() {
        float[] chinese = model.embedText("冷却液液位不足时必须停机补充同型号冷却液。");
        float[] mixed = model.embedText("Check USB 2.0 connection and restart PIXMA MP500.");

        assertThat(cosine(chinese, mixed)).isLessThan(0.99);
        assertThat(chinese).isNotEqualTo(mixed);
    }

    @Test
    void relatedTechnicalTextShouldBeMoreSimilarThanUnrelatedText() {
        float[] cooling = model.embedText("冷却液液位不足导致主轴过热报警，请检查冷却泵和滤网。");
        float[] relatedCooling = model.embedText(
                "主轴温度过高时检查冷却液、冷却泵运行声音和散热器滤网。");
        float[] workOrder = model.embedText("一级工单要求15分钟内响应并填写关单复测数据。");

        assertThat(cosine(cooling, relatedCooling))
                .isGreaterThan(cosine(cooling, workOrder));
    }

    @Test
    void blankInputShouldBeRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> model.embedText(" \r\n\t "))
                .isInstanceOf(com.zl.d31.knowledge.embedding.EmbeddingException.class)
                .hasMessageContaining("输入不能为空");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> model.embedText(null))
                .isInstanceOf(com.zl.d31.knowledge.embedding.EmbeddingException.class);
    }

    private LocalHashNgramEmbeddingModel model() {
        EmbeddingProperties properties = new EmbeddingProperties();
        EmbeddingModelDescriptor descriptor = EmbeddingModelDescriptor.from(properties);
        return new LocalHashNgramEmbeddingModel(descriptor, validator);
    }

    private double cosine(float[] left, float[] right) {
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int index = 0; index < left.length; index++) {
            dot += (double) left[index] * right[index];
            leftNorm += (double) left[index] * left[index];
            rightNorm += (double) right[index] * right[index];
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}
