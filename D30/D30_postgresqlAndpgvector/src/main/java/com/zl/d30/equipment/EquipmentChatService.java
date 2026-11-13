package com.zl.d30.equipment;

import com.zl.d30.conversation.RoleConversationService;
import com.zl.d30.dto.ChatRequest;
import com.zl.d30.dto.CitationDto;
import com.zl.d30.equipment.dto.EquipmentChatResponse;
import com.zl.d30.equipment.model.EquipmentTicketCandidate;
import com.zl.d30.history.ChatHistoryService;
import com.zl.d30.history.model.ChatSession;
import com.zl.d30.history.model.HistoryMessage;
import com.zl.d30.knowledge.KnowledgeChatOrchestrator;
import com.zl.d30.prompt.PromptTemplateService;
import com.zl.d30.prompt.model.PromptScenes;
import com.zl.d30.prompt.model.PromptTemplateEntity;
import com.zl.d30.prompt.model.TemplateVariable;
import com.zl.d30.service.ThinkingSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 设备客服/技术工程师完整链路：模板选择、会话历史、澄清判断、模型回答和只读候选投影。
 *
 * <p>会话主存储与上下文窗口全部复用 {@link ChatHistoryService}，本类不拥有独立会话状态。
 * 同会话请求通过固定数量分段锁串行执行，避免两轮同时读取同一事实快照后交错落库；
 * 当前适用于单实例部署，多实例时仍由数据库序号唯一约束保留最终一致性边界。</p>
 */
@Slf4j
@Service
public class EquipmentChatService {

    private static final int LOCK_STRIPES = 64;

    private final ReentrantLock[] sessionLocks = createLocks();

    private final PromptTemplateService promptTemplateService;

    private final RoleConversationService roleConversationService;

    private final ChatHistoryService historyService;

    private final KnowledgeChatOrchestrator orchestrator;

    private final EquipmentInformationExtractor informationExtractor;

    private final EquipmentClarificationPolicy clarificationPolicy;

    private final EquipmentCandidateExtractor candidateExtractor;

    public EquipmentChatService(PromptTemplateService promptTemplateService,
                                RoleConversationService roleConversationService,
                                ChatHistoryService historyService,
                                KnowledgeChatOrchestrator orchestrator,
                                EquipmentInformationExtractor informationExtractor,
                                EquipmentClarificationPolicy clarificationPolicy,
                                EquipmentCandidateExtractor candidateExtractor) {
        this.promptTemplateService = promptTemplateService;
        this.roleConversationService = roleConversationService;
        this.historyService = historyService;
        this.orchestrator = orchestrator;
        this.informationExtractor = informationExtractor;
        this.clarificationPolicy = clarificationPolicy;
        this.candidateExtractor = candidateExtractor;
    }

    /**
     * 执行一轮设备对话。默认关闭知识检索；显式开启时引用仍由既有检索器真实命中产生。
     */
    public EquipmentChatResponse chat(ChatRequest request, String owner) {
        requireEquipmentScene(request.getScene());
        ReentrantLock lock = lockFor(request.getSessionId());
        lock.lock();
        try {
            return chatLocked(request, owner);
        } finally {
            lock.unlock();
        }
    }

    private EquipmentChatResponse chatLocked(ChatRequest request, String owner) {
        List<String> userMessages = new ArrayList<>(loadPreviousUserMessages(request, owner));
        userMessages.add(request.getMessage());
        EquipmentFacts facts = informationExtractor.extract(userMessages);
        EquipmentClarificationPolicy.Assessment assessment = clarificationPolicy.assess(facts);
        EquipmentTicketCandidate candidate =
                candidateExtractor.extract(facts, assessment, userMessages.size());

        PromptTemplateEntity template =
                promptTemplateService.resolveEnabledRoleTemplate(request.getScene());
        ChatRequest enriched = enrichedRequest(request, template, facts, assessment);
        RoleConversationService.PreparedConversation prepared =
                roleConversationService.prepare(enriched, owner);

        try {
            if (assessment.required()) {
                String answer = buildClarificationAnswer(facts, assessment);
                historyService.completeTurn(prepared.turn(), answer, "deterministic-equipment-rules");
                log.info("设备对话进入澄清轮，sessionId={}，scene={}，缺失项数={}",
                        prepared.session().getSessionId(), request.getScene(),
                        assessment.missingInformation().size());
                return response(prepared, template, "rules", "deterministic-equipment-rules",
                        true, answer, facts, assessment, candidate, List.of());
            }

            boolean retrieveEnabled = Boolean.TRUE.equals(request.getRetrieveEnabled());
            KnowledgeChatOrchestrator.SyncOutcome outcome = orchestrator.chat(
                    prepared.invocation(), ThinkingSettings.legacyDefault(), retrieveEnabled);
            historyService.completeTurn(prepared.turn(), outcome.result().content(),
                    outcome.result().model());
            log.info("设备对话完成，sessionId={}，scene={}，mode={}，mocked={}，候选状态={}",
                    prepared.session().getSessionId(), request.getScene(),
                    outcome.result().mode(), outcome.result().mocked(),
                    candidate.candidateStatus());
            return response(prepared, template, outcome.result().mode(), outcome.result().model(),
                    outcome.result().mocked(), outcome.result().content(), facts,
                    assessment, candidate, outcome.citations());
        } catch (RuntimeException ex) {
            historyService.failTurn(prepared.turn(), ex.getClass().getSimpleName(),
                    "设备对话处理失败");
            throw ex;
        }
    }

    private List<String> loadPreviousUserMessages(ChatRequest request, String owner) {
        if (!StringUtils.hasText(request.getSessionId())) {
            return List.of();
        }
        ChatSession existing = historyService.requireOwnedSession(owner, request.getSessionId());
        if (!request.getScene().equals(existing.getScene())) {
            throw new IllegalArgumentException("会话场景与当前请求不匹配，不同设备角色请新建会话");
        }
        return historyService.getContextWindow(existing.getSessionId()).stream()
                .filter(message -> ChatHistoryService.ROLE_USER.equals(message.getRole()))
                .map(HistoryMessage::getContent)
                .toList();
    }

    private ChatRequest enrichedRequest(ChatRequest source, PromptTemplateEntity template,
                                        EquipmentFacts facts,
                                        EquipmentClarificationPolicy.Assessment assessment) {
        ChatRequest target = new ChatRequest();
        target.setMessage(source.getMessage());
        target.setScene(source.getScene());
        target.setSessionId(source.getSessionId());
        target.setThinkingEnabled(source.getThinkingEnabled());
        target.setReasoningEffort(source.getReasoningEffort());
        target.setShowReasoning(source.getShowReasoning());
        target.setRetrieveEnabled(source.getRetrieveEnabled());

        Map<String, Object> variables = source.getVariables() == null
                ? new HashMap<>() : new HashMap<>(source.getVariables());
        Set<String> declared = template.getVariables().stream()
                .map(TemplateVariable::name)
                .collect(java.util.stream.Collectors.toSet());
        putIfDeclared(declared, variables, "known_information", knownInformation(facts));
        putIfDeclared(declared, variables, "missing_information",
                assessment.missingInformation().isEmpty()
                        ? "暂无阻断性缺失信息" : String.join("；", assessment.missingInformation()));
        putIfDeclared(declared, variables, "uncertainty_note", assessment.uncertaintyNote());
        target.setVariables(variables);
        return target;
    }

    private EquipmentChatResponse response(RoleConversationService.PreparedConversation prepared,
                                           PromptTemplateEntity template,
                                           String mode,
                                           String model,
                                           Boolean mocked,
                                           String answer,
                                           EquipmentFacts facts,
                                           EquipmentClarificationPolicy.Assessment assessment,
                                           EquipmentTicketCandidate candidate,
                                           List<CitationDto> citations) {
        return new EquipmentChatResponse(
                prepared.session().getSessionId(),
                prepared.session().getScene(),
                prepared.session().getTemplateId(),
                prepared.session().getTemplateVersion(),
                template.getUpdatedAt() == null ? null : template.getUpdatedAt().toString(),
                mode,
                model,
                mocked,
                answer,
                assessment.required(),
                knownFacts(facts),
                assessment.missingInformation(),
                candidateExtractor.possibleDirections(facts),
                assessment.uncertaintyNote(),
                candidate,
                List.copyOf(citations)
        );
    }

    private List<String> knownFacts(EquipmentFacts facts) {
        List<String> values = new ArrayList<>();
        addFact(values, "设备型号", facts.deviceModel());
        addFact(values, "设备编号", facts.deviceIdentifier());
        addFact(values, "设备类型", facts.deviceType());
        addFact(values, "故障码", facts.faultCode());
        if (facts.hasSymptoms()) {
            values.add("观察到的现象：" + String.join("；", facts.observedSymptoms()));
        }
        addFact(values, "发生时间", facts.onset());
        addFact(values, "发生频率", facts.frequency());
        addFact(values, "当前状态", facts.currentState());
        return List.copyOf(values);
    }

    private void addFact(List<String> values, String label, String value) {
        if (StringUtils.hasText(value)) {
            values.add(label + "：" + value);
        }
    }

    private String knownInformation(EquipmentFacts facts) {
        List<String> known = knownFacts(facts);
        return known.isEmpty() ? "当前尚无已归纳的设备信息" : String.join("；", known);
    }

    private String buildClarificationAnswer(EquipmentFacts facts,
                                            EquipmentClarificationPolicy.Assessment assessment) {
        StringBuilder answer = new StringBuilder("目前还不能确定具体故障原因。");
        if (facts.hasDeviceIdentity() || facts.hasSymptoms()) {
            answer.append("已知信息：")
                    .append(knownInformation(facts))
                    .append("。");
        }
        answer.append(assessment.question());
        if (facts.faultCode() == null && !assessment.question().contains("故障码")) {
            answer.append(" 如果设备有报警码，请原样提供；没有报警码也可以直接说明。");
        }
        if (facts.highRiskMentioned()) {
            answer.append(" 请先遵循现场安全规程，由具备资质的人员确认设备状态；不要进行带电、带压或接触高温/旋转部件的操作。");
        }
        answer.append(" 当前未检索到可核验的知识来源。");
        return answer.toString();
    }

    private void putIfDeclared(Set<String> declared, Map<String, Object> variables,
                               String name, String value) {
        if (declared.contains(name)) {
            variables.put(name, value);
        }
    }

    private void requireEquipmentScene(String scene) {
        if (!PromptScenes.EQUIPMENT_CUSTOMER_SERVICE.equals(scene)
                && !PromptScenes.EQUIPMENT_TECHNICAL_ENGINEER.equals(scene)) {
            throw new IllegalArgumentException("scene 必须是 equipment_customer_service 或 "
                    + "equipment_technical_engineer");
        }
    }

    private ReentrantLock lockFor(String sessionId) {
        int hash = sessionId == null ? 0 : sessionId.hashCode();
        return sessionLocks[Math.floorMod(hash, sessionLocks.length)];
    }

    private static ReentrantLock[] createLocks() {
        ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock();
        }
        return locks;
    }
}
