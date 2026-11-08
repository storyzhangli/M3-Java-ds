package com.zl.d26.equipment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zl.d26.support.AbstractPersistenceCleanupTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 设备角色端到端测试：默认 Mock、真实 MySQL 历史、模板溯源、澄清、候选和归属隔离。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EquipmentChatControllerTest extends AbstractPersistenceCleanupTest {

    private static final String URL = "/api/v1/equipment/chat";
    private static final String OWNER = "ut_equipment_owner";

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void missingModelShouldClarifyThenCarryHistoryIntoNextTurn() throws Exception {
        MvcResult firstResult = mockMvc.perform(post(URL)
                        .header("X-Dev-User", OWNER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of(
                                "message", "设备运行中一直异常振动",
                                "scene", "equipment_customer_service"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clarificationRequired").value(true))
                .andExpect(jsonPath("$.data.candidateExtraction.candidateStatus")
                        .value("NEEDS_CLARIFICATION"))
                .andExpect(jsonPath("$.data.answer").value(
                        org.hamcrest.Matchers.containsString("设备型号")))
                .andExpect(jsonPath("$.data.citations.length()").value(0))
                .andReturn();
        JsonNode first = objectMapper.readTree(firstResult.getResponse().getContentAsString()).path("data");
        String sessionId = first.path("sessionId").asText();
        assertThat(sessionId).hasSize(32);
        assertThat(first.path("templateId").asText()).isEqualTo("equipment_customer_service");
        assertThat(first.path("templateVersion").asInt()).isPositive();
        assertThat(first.path("templateUpdatedAt").asText()).isNotBlank();

        MvcResult secondResult = mockMvc.perform(post(URL)
                        .header("X-Dev-User", OWNER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of(
                                "message", "型号是 CNC-760，仍然有异常振动",
                                "scene", "equipment_customer_service",
                                "sessionId", sessionId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clarificationRequired").value(false))
                .andExpect(jsonPath("$.data.candidateExtraction.candidateOnly").value(true))
                .andExpect(jsonPath("$.data.candidateExtraction.ticketCategory")
                        .value("MECHANICAL"))
                .andExpect(jsonPath("$.data.candidateExtraction.faultCode").doesNotExist())
                .andExpect(jsonPath("$.data.candidateExtraction.uncertaintyNote")
                        .value(org.hamcrest.Matchers.containsString("现场验证")))
                .andReturn();
        JsonNode second = objectMapper.readTree(secondResult.getResponse().getContentAsString()).path("data");
        assertThat(second.path("sessionId").asText()).isEqualTo(sessionId);
        assertThat(second.path("answer").asText()).doesNotContain("请先补充设备型号");
        assertThat(second.path("answer").asText()).contains("历史消息条数=2");
        assertThat(second.path("knownFacts").toString()).contains("CNC-760", "异常振动");
    }

    @Test
    void engineerSceneShouldUseItsOwnTemplateAndNeverClaimUnverifiedSources() throws Exception {
        mockMvc.perform(post(URL)
                        .header("X-Dev-User", OWNER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of(
                                "message", "设备型号 FM-22，出现过热报警，未提供故障码",
                                "scene", "equipment_technical_engineer"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scene").value("equipment_technical_engineer"))
                .andExpect(jsonPath("$.data.templateId").value("equipment_technical_engineer"))
                .andExpect(jsonPath("$.data.citations.length()").value(0))
                .andExpect(jsonPath("$.data.answer").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("根据知识库资料"))));
    }

    @Test
    void differentOwnerMustNotReadExistingSession() throws Exception {
        MvcResult first = mockMvc.perform(post(URL)
                        .header("X-Dev-User", OWNER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of(
                                "message", "设备异常振动",
                                "scene", "equipment_customer_service"))))
                .andExpect(status().isOk())
                .andReturn();
        String sessionId = objectMapper.readTree(first.getResponse().getContentAsString())
                .path("data").path("sessionId").asText();

        mockMvc.perform(post(URL)
                        .header("X-Dev-User", "ut_other_owner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of(
                                "message", "尝试读取其他会话",
                                "scene", "equipment_customer_service",
                                "sessionId", sessionId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4001))
                .andExpect(jsonPath("$.msg").value(
                        org.hamcrest.Matchers.containsString("无权访问")));
    }

    @Test
    void unsafeAmbiguousDescriptionShouldNotProceedToModelDiagnosis() throws Exception {
        mockMvc.perform(post(URL)
                        .header("X-Dev-User", OWNER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of(
                                "message", "型号是 CNC-760，设备冒烟并有异响",
                                "scene", "equipment_customer_service"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clarificationRequired").value(true))
                .andExpect(jsonPath("$.data.mode").value("rules"))
                .andExpect(jsonPath("$.data.candidateExtraction.ticketCategory")
                        .value("SAFETY_RISK"))
                .andExpect(jsonPath("$.data.answer").value(
                        org.hamcrest.Matchers.containsString("安全规程")));
    }

    private String body(Map<String, Object> payload) throws Exception {
        return objectMapper.writeValueAsString(payload);
    }
}
