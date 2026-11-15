package com.zl.d32.knowledge.retrieval;

import com.zl.d32.knowledge.retrieval.dto.RetrievalHitView;
import com.zl.d32.knowledge.retrieval.support.RetrievalHitMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结果映射：metadata 类型不确定、字段缺失、分数缺失时都必须如实返回，不得补造。
 */
class RetrievalHitMapperTest {

    private final RetrievalHitMapper mapper = new RetrievalHitMapper();

    @Test
    @DisplayName("完整 metadata：来源字段、分数与距离全部映射")
    void mapsFullMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("documentId", "DOC-CNC-COOLING-001");
        metadata.put("version", "corpus-v1");
        metadata.put("chunkIndex", 0);
        metadata.put("title", "数控机床冷却系统维护手册（教学夹具）");
        metadata.put("strategy", "FIXED_LENGTH");
        metadata.put("pageNumbers", List.of(12));
        metadata.put("distance", 0.18f);

        Document document = Document.builder()
                .id("6f1c0f0e-0000-5000-8000-000000000001")
                .text("数控机床出现主轴过热报警时，应先检查冷却液液位与冷却泵运行声音。")
                .metadata(metadata)
                .score(0.82d)
                .build();

        RetrievalHitView view = mapper.toView(document, 1);

        assertThat(view.rank()).isEqualTo(1);
        assertThat(view.chunkId()).isEqualTo("6f1c0f0e-0000-5000-8000-000000000001");
        assertThat(view.documentId()).isEqualTo("DOC-CNC-COOLING-001");
        assertThat(view.version()).isEqualTo("corpus-v1");
        assertThat(view.chunkIndex()).isZero();
        assertThat(view.pageNumbers()).containsExactly(12);
        assertThat(view.strategy()).isEqualTo("FIXED_LENGTH");
        assertThat(view.content()).contains("主轴过热报警");
        assertThat(view.score()).isEqualTo(0.82d);
        // metadata 中的 distance 由框架以 Float 写入，转 double 后存在 float 精度误差
        assertThat(view.distance()).isCloseTo(0.18d, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(view.scoreSemantic()).isEqualTo("COSINE_SIMILARITY");
        assertThat(view.distanceSemantic()).isEqualTo("COSINE_DISTANCE");
    }

    @Test
    @DisplayName("metadata 缺失：来源字段为 null，页码为空列表，不补造")
    void mapsMissingMetadata() {
        Document document = Document.builder()
                .id("doc-2")
                .text("无 metadata 的内容")
                .score(0.5d)
                .build();

        RetrievalHitView view = mapper.toView(document, 2);

        assertThat(view.rank()).isEqualTo(2);
        assertThat(view.documentId()).isNull();
        assertThat(view.version()).isNull();
        assertThat(view.chunkIndex()).isNull();
        assertThat(view.title()).isNull();
        assertThat(view.strategy()).isNull();
        assertThat(view.pageNumbers()).isEmpty();
        assertThat(view.distance()).isNull();
        assertThat(view.score()).isEqualTo(0.5d);
    }

    @Test
    @DisplayName("score 未设置时返回 null，不伪装成 0")
    void keepsNullScore() {
        Document document = Document.builder().id("doc-3").text("无分数").build();
        RetrievalHitView view = mapper.toView(document, 3);
        assertThat(view.score()).isNull();
    }

    @Test
    @DisplayName("页码兼容单个数字、列表与字符串")
    void parsesPageNumbersFlexibly() {
        Document single = Document.builder().id("doc-4").text("x")
                .metadata(Map.<String, Object>of("pageNumbers", 7))
                .build();
        assertThat(mapper.toView(single, 1).pageNumbers()).containsExactly(7);

        Document multi = Document.builder().id("doc-5").text("y")
                .metadata(Map.<String, Object>of("pageNumbers", List.of(3, 5)))
                .build();
        assertThat(mapper.toView(multi, 1).pageNumbers()).containsExactly(3, 5);

        Document stringValue = Document.builder().id("doc-6").text("z")
                .metadata(Map.<String, Object>of("pageNumbers", "9"))
                .build();
        assertThat(mapper.toView(stringValue, 1).pageNumbers()).containsExactly(9);
    }
}
