# D25 现状索引与 Git 基线

- 生成时间：2026-11-07 22:38:17 +08:00
- 扫描范围：D25 POM、现有知识语料、关键词检索、引用注册表、持久化迁移和直接相关测试。
- 未扫描无关模块源码，未将完整日志送入分析。
- Git HEAD：824de41d788a4958eec802cd905245c6eb5a9b32
- 当前分支：master
- 模块：D25(pom) -> D25_DKDCM_RAC(jar)

## 工作区状态

```text
?? docs/evidence/
?? "docs/proj_docs/D25-RAG\346\236\266\346\236\204\344\270\216\350\257\204\346\265\213\345\256\236\350\267\265\346\214\207\345\215\227.md"
?? "docs/proj_docs/D25-\351\241\271\347\233\256\350\277\255\344\273\243\345\206\205\345\256\271\344\270\216\346\223\215\344\275\234\350\256\260\345\275\225.md"
```

## 相关文件 SHA-256

| 文件 | SHA-256 |
|---|---|
| `D25/D25_DKDCM_RAC/pom.xml` | `6EF198E8AD8CB5A85F4E826BF642F502F897BB216D3CE3F6EF7F2167D1B1DAF7` |
| `D25/D25_DKDCM_RAC/src/main/java/com/zl/d25/history/ChatHistoryService.java` | `C949FCBD492591FB4ED46C6C1583EA9E9B7349F5CDBDA65A3B7EA36B071F3750` |
| `D25/D25_DKDCM_RAC/src/main/java/com/zl/d25/knowledge/CitationRegistry.java` | `E7AA9F3EDEAC7CC88FF49022948F32BD94B3DDDFFC219DCB5233F3D9FDEEC255` |
| `D25/D25_DKDCM_RAC/src/main/java/com/zl/d25/knowledge/KnowledgeChatOrchestrator.java` | `055648B64AD005CC3DCB34F1BCB61526B12230D2C3A3E61AAAB8B3F169F151DB` |
| `D25/D25_DKDCM_RAC/src/main/java/com/zl/d25/knowledge/KnowledgeChunk.java` | `C16973A99324606EE4881985A641FD74F103CB972D3E0663E16D030A782103C2` |
| `D25/D25_DKDCM_RAC/src/main/java/com/zl/d25/knowledge/KnowledgeCorpus.java` | `CEEBB1744DDF290F3C8E5227034629085AC07DC7926FB5C5BE92A16E2C7C4058` |
| `D25/D25_DKDCM_RAC/src/main/java/com/zl/d25/knowledge/KnowledgeRetrievalService.java` | `E0B50F2A0AF170BBB868C441004B7F22F347DDF050F0219132AE13591A6D5EBD` |
| `D25/D25_DKDCM_RAC/src/main/resources/application.yml` | `6C2CDC63AA664C7CD5A4B6913E3DC1278DA87C8B5A3DBB5E1EDB720DFEF95232` |
| `D25/D25_DKDCM_RAC/src/main/resources/db/schema-chat-history.sql` | `F31A13CB4B0446AC7D31430C3CD7F21CD704264621DFC985DC8C160F9EA11CA5` |
| `D25/D25_DKDCM_RAC/src/main/resources/db/schema-prompt-template.sql` | `4F24E8CCBBCB3D37D61FF015071CE801F946A35646E59504768BC09B02B30B22` |
| `D25/D25_DKDCM_RAC/src/main/resources/knowledge/equipment-fault-corpus.json` | `AAD6AC4DF5585AAD5078D1C94DF4E5093AF15A9730E230149CFDE0940335AB0B` |
| `D25/D25_DKDCM_RAC/src/test/java/com/zl/d25/d16/CitationIntegrationTest.java` | `ABE3F9C4563F61D7EA308A218FEC3DBF6D65F12C5E585587AF2F89E57CE24233` |
| `D25/D25_DKDCM_RAC/src/test/java/com/zl/d25/d16/KnowledgeRetrievalServiceTest.java` | `7499CC6EED27FAB1B011B33F3D9ACED6C150F4E1F27880E3187C594BB26FABFA` |
| `D25/D25_DKDCM_RAC/src/test/java/com/zl/d25/history/HistoryPersistenceTest.java` | `D0C0BD64853D5E057EC3469D358888D90A40878AB51A3A993E5924FDCA7F12AF` |
| `D25/D25_DKDCM_RAC/src/test/java/com/zl/d25/history/SessionIsolationTest.java` | `EAF3DEFE244BAB6E99696D1ED21752F8438428054D521FE5F58EF64F77B64B54` |
| `D25/pom.xml` | `844C8686CC97F24BE5A32FE4616B76E922DD79677E4EE886B00652E28F885785` |

## Surefire 测试类摘要

```text
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 15.66 s -- in com.zl.d25.ApplicationContextLoadTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.056 s -- in com.zl.d25.ChatLengthValidationTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.151 s -- in com.zl.d25.ChatMemoryOrderRegressionTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.166 s -- in com.zl.d25.ChatModelFailureRegressionTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.044 s -- in com.zl.d25.config.AiPropertiesValidationTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.979 s -- in com.zl.d25.config.DeepSeekConfigurationBindingTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.398 s -- in com.zl.d25.conversation.RedisConversationMemoryTest
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.346 s -- in com.zl.d25.conversation.RoleConversationServiceTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.629 s -- in com.zl.d25.d16.CitationIntegrationTest
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.010 s -- in com.zl.d25.d16.CitationRegistryTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 4.066 s -- in com.zl.d25.d16.D16ThinkingStreamIntegrationTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.019 s -- in com.zl.d25.d16.KnowledgeChatOrchestratorTest
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.020 s -- in com.zl.d25.d16.KnowledgeRetrievalServiceTest
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.192 s -- in com.zl.d25.d16.ThinkingModeIntegrationTest
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.009 s -- in com.zl.d25.d16.ThinkingSettingsResolverTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.115 s -- in com.zl.d25.DeepSeekKeyProtectionTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 1, Time elapsed: 0 s -- in com.zl.d25.DeepSeekLiveSmokeTest
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.012 s -- in com.zl.d25.equipment.EquipmentCandidateParserRegressionTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.008 s -- in com.zl.d25.equipment.EquipmentCandidateParserTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.246 s -- in com.zl.d25.equipment.EquipmentChatControllerTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.007 s -- in com.zl.d25.equipment.EquipmentInformationAndClarificationTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.858 s -- in com.zl.d25.history.ChatMemoryReadFailureRegressionTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.947 s -- in com.zl.d25.history.ContextWindowTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.383 s -- in com.zl.d25.history.HistoryConcurrencyTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.147 s -- in com.zl.d25.history.HistoryFailureTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.287 s -- in com.zl.d25.history.HistoryPersistenceTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.167 s -- in com.zl.d25.history.SessionIsolationTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 5.091 s -- in com.zl.d25.history.SpringLockDiagnosticTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.218 s -- in com.zl.d25.logging.AccessLogIntegrationTest
Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.020 s -- in com.zl.d25.logging.AccessLogMaskingTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.085 s -- in com.zl.d25.logging.AccessLogRealContainerTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.474 s -- in com.zl.d25.logging.TraceIdWithoutAccessLogTest
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.129 s -- in com.zl.d25.MockChatControllerTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.050 s -- in com.zl.d25.MockNoExternalApiTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.954 s -- in com.zl.d25.MockWithoutApiKeyTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.015 s -- in com.zl.d25.model.ModelCallPropertiesValidationTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.013 s -- in com.zl.d25.model.ModelFailureClassifierTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.409 s -- in com.zl.d25.model.ModelHttpClientTimeoutTest
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.496 s -- in com.zl.d25.model.ModelHttpRetryIntegrationTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.066 s -- in com.zl.d25.model.ModelRetryPolicyTest
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.444 s -- in com.zl.d25.observability.ModelCallObservabilityTest
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.101 s -- in com.zl.d25.observability.TokenUsageExtractorTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.206 s -- in com.zl.d25.prompt.PersistenceCacheIntegrationTest
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.386 s -- in com.zl.d25.prompt.PromptTemplateServiceTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.049 s -- in com.zl.d25.ProviderIsolationTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.351 s -- in com.zl.d25.service.MockAiChatServiceStreamTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.102 s -- in com.zl.d25.stream.ChatStreamErrorEventTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.536 s -- in com.zl.d25.stream.ChatStreamIntegrationTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.107 s -- in com.zl.d25.stream.ChatStreamTimeoutTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.010 s -- in com.zl.d25.stream.ChatStreamTotalDeadlineTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.008 s -- in com.zl.d25.stream.MvcStackDecisionTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.080 s -- in com.zl.d25.stream.RoleChatStreamIntegrationTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.023 s -- in com.zl.d25.stream.SpringAiChatClientBehaviorTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.202 s -- in com.zl.d25.stream.StreamClientDisconnectIntegrationTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.995 s -- in com.zl.d25.stream.StreamContractCompatibilityTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.022 s -- in com.zl.d25.stream.StreamPreStartFailureTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.371 s -- in com.zl.d25.web.HistoryControllerWebTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.302 s -- in com.zl.d25.web.PromptTemplateControllerWebTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.207 s -- in com.zl.d25.web.RoleChatControllerTest
```
