package com.zl.d27.equipment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D27 结构化输出回归：严格 JSON 边界和跨字段业务校验。
 */
class EquipmentCandidateParserRegressionTest {

    private final EquipmentCandidateParser parser =
            new EquipmentCandidateParser(new ObjectMapper());

    @Test
    void trailingContentAfterValidObjectMustBeRejected() {
        assertThatThrownBy(() -> parser.parse(validJson() + "\n{\"unexpected\":true}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("解析失败");
    }

    @Test
    void needsClarificationMustCarryAtLeastOneMissingInformationItem() {
        String json = validJson()
                .replace("\"candidateStatus\": \"CANDIDATE\"",
                        "\"candidateStatus\": \"NEEDS_CLARIFICATION\"")
                .replace("\"missingInformation\": [\"故障码（未提供）\"]",
                        "\"missingInformation\": []");

        assertThatThrownBy(() -> parser.parse(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missingInformation");
    }

    @Test
    void wrongFieldAndArrayTypesShouldBeRejected() {
        assertThatThrownBy(() -> parser.parse(
                validJson().replace("\"deviceModel\": \"CNC-760\"",
                        "\"deviceModel\": 760")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deviceModel");

        assertThatThrownBy(() -> parser.parse(
                validJson().replace("[\"异常振动\"]", "\"异常振动\"")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("observedSymptoms");
    }

    @Test
    void unknownStatusAndSourceEnumsShouldBeRejected() {
        assertThatThrownBy(() -> parser.parse(
                validJson().replace("\"CANDIDATE\"", "\"UNKNOWN_STATUS\"")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("candidateStatus");

        assertThatThrownBy(() -> parser.parse(
                validJson().replace("\"USER_DESCRIPTION\"", "\"UNKNOWN_SOURCE\"")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceType");
    }

    @Test
    void listSizeAndElementTypesShouldBeBounded() {
        String tooMany = "[" + String.join(",",
                java.util.Collections.nCopies(21, "\"现象\"")) + "]";
        assertThatThrownBy(() -> parser.parse(
                validJson().replace("[\"异常振动\"]", tooMany)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("过多");

        assertThatThrownBy(() -> parser.parse(
                validJson().replace("[\"异常振动\"]", "[123]")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("字符串");
    }

    private String validJson() {
        return """
                {
                  "symptomSummary": "设备运行中异常振动",
                  "ticketCategory": "MECHANICAL",
                  "deviceModel": "CNC-760",
                  "faultCode": null,
                  "deviceIdentifier": null,
                  "observedSymptoms": ["异常振动"],
                  "missingInformation": ["故障码（未提供）"],
                  "uncertaintyNote": "仅基于用户描述，未经现场验证",
                  "candidateStatus": "CANDIDATE",
                  "sourceType": "USER_DESCRIPTION"
                }
                """;
    }
}
