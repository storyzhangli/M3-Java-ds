package com.zl.d26.equipment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d26.equipment.model.CandidateStatus;
import com.zl.d26.equipment.model.TicketCategory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 结构化候选 JSON 的边界测试，覆盖正常映射、非法 JSON、未知枚举、缺字段与超长字段。
 */
class EquipmentCandidateParserTest {

    private final EquipmentCandidateParser parser =
            new EquipmentCandidateParser(new ObjectMapper());

    @Test
    void validCandidateShouldMapAndRemainReadOnly() {
        var candidate = parser.parse("""
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
                """);

        assertThat(candidate.ticketCategory()).isEqualTo(TicketCategory.MECHANICAL);
        assertThat(candidate.candidateStatus()).isEqualTo(CandidateStatus.CANDIDATE);
        assertThat(candidate.candidateOnly()).isTrue();
        assertThat(candidate.missingInformation()).containsExactly("故障码（未提供）");
    }

    @Test
    void invalidJsonShouldFailControlled() {
        assertThatThrownBy(() -> parser.parse("{not-json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("解析失败");
    }

    @Test
    void unknownEnumAndMissingFieldShouldBeRejected() {
        String unknownCategory = validJson().replace("\"MECHANICAL\"", "\"MAGIC_CAUSE\"");
        assertThatThrownBy(() -> parser.parse(unknownCategory))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ticketCategory");

        String missingSummary = validJson().replace(
                "\"symptomSummary\": \"设备运行中异常振动\",", "");
        assertThatThrownBy(() -> parser.parse(missingSummary))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symptomSummary");
    }

    @Test
    void oversizedSummaryShouldBeRejected() {
        String tooLong = "故".repeat(501);
        assertThatThrownBy(() -> parser.parse(validJson().replace("设备运行中异常振动", tooLong)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超长");
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
