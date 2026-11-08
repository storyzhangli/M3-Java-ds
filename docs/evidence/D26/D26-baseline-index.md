# D26 现状索引与 Git 基线

- 生成时间：2026-11-08 18:28:39 +08:00
- 扫描范围：D26 POM、Multipart/文件解析依赖、现有异常/Repository/Prompt 模式、知识语料与迁移。
- 未扫描无关模块源码，未将完整日志送入分析。
- Git HEAD：d144ad4b3369f0b83a3ae1a5d918d22f9348366e
- 当前分支：master
- 模块：D26(pom) -> D26_DIABP(jar)

## 工作区状态

```text
 M D26/D26_DIABP/src/main/java/com/zl/d26/common/GlobalExceptionHandler.java
 M D26/D26_DIABP/src/main/java/com/zl/d26/common/ResultCode.java
 M D26/D26_DIABP/src/main/resources/application.yml
?? D26/D26_DIABP/src/main/java/com/zl/d26/knowledge/document/
?? D26/D26_DIABP/src/main/resources/db/schema-knowledge-document.sql
?? D26/D26_DIABP/src/test/java/com/zl/d26/knowledge/
?? docs/evidence/D26/
?? "docs/proj_docs/D26-\346\226\207\346\241\243\345\257\274\345\205\245\344\270\216\345\237\272\347\241\200\350\247\243\346\236\220\346\212\200\346\234\257\345\216\237\347\220\206\345\217\212\346\223\215\344\275\234\345\256\236\350\267\265\346\214\207\345\215\227.md"
?? "docs/proj_docs/D26-\346\226\207\346\241\243\347\231\273\350\256\260\344\270\216\345\257\274\345\205\245\345\212\237\350\203\275\345\256\236\347\216\260\345\205\250\351\207\217\350\257\264\346\230\216.md"
```

## 相关文件 SHA-256

| 文件 | SHA-256 |
|---|---|
| `D26/D26_DIABP/pom.xml` | `40E28BF84E5ABFC26C60419350E56907A5E5E4B231F61F021708DC1888ECC454` |
| `D26/D26_DIABP/src/main/java/com/zl/d26/common/GlobalExceptionHandler.java` | `8E10350FF79B744C05ACACE849DF4352677A7A0ACB350A4E1F56D0F4B0187AF6` |
| `D26/D26_DIABP/src/main/java/com/zl/d26/common/ResultCode.java` | `145710D62CAE88BC862CD595FC1A1A4DA1988162729F35260D7576CAFDA55FB5` |
| `D26/D26_DIABP/src/main/java/com/zl/d26/knowledge/document/dto/KnowledgeDocumentRegistrationRequest.java` | `CB4EF66DCB98E03B56099F9567B775E47A87D64C3DCE2B2CEB4310384B9CACD1` |
| `D26/D26_DIABP/src/main/java/com/zl/d26/knowledge/document/dto/KnowledgeDocumentView.java` | `5A238F844FF1146F16530807C4B944B68588B774C9FB07BF3FA949A6E645E939` |
| `D26/D26_DIABP/src/main/java/com/zl/d26/knowledge/document/KnowledgeDocument.java` | `3F62408BAACD71CF3B1DA3A66047CD0B2324DE6CDC9298F9DAA019DE626F6515` |
| `D26/D26_DIABP/src/main/java/com/zl/d26/knowledge/document/KnowledgeDocumentController.java` | `982DC63081591FF3FEFEE25E91646E15E1DFC4BD1C5812ACD1A42BA33CC23DBF` |
| `D26/D26_DIABP/src/main/java/com/zl/d26/knowledge/document/KnowledgeDocumentException.java` | `B460CDD009EC5AADC6788860A0D29C8E3605FAAF6C8D24ECBA208543D28C1B5B` |
| `D26/D26_DIABP/src/main/java/com/zl/d26/knowledge/document/KnowledgeDocumentParser.java` | `74AA9B60232835B5BCADCFD5F7D8D742218EC4A8D9940D28EDED07B6254779F5` |
| `D26/D26_DIABP/src/main/java/com/zl/d26/knowledge/document/KnowledgeDocumentService.java` | `1963CBEECB82965797B936BB90F00584233B7407B260525DB13BFE2CEA06DEEA` |
| `D26/D26_DIABP/src/main/java/com/zl/d26/knowledge/document/KnowledgeDocumentStatus.java` | `9610126B5399E662BA526E122F17339B602389EE62CC382DA11E0846201DAA21` |
| `D26/D26_DIABP/src/main/java/com/zl/d26/knowledge/document/KnowledgeImportProperties.java` | `C32CD9B2B535956F98EFE568E0FCB108DA9A2C17F5EE7301E23650F7CF02239D` |
| `D26/D26_DIABP/src/main/java/com/zl/d26/knowledge/document/mapper/KnowledgeDocumentContentMapper.java` | `277B303926FA47266863E8AC8EC298E90A43405E2EC5AD73E4DDBEE41BCC5C92` |
| `D26/D26_DIABP/src/main/java/com/zl/d26/knowledge/document/mapper/KnowledgeDocumentMapper.java` | `1A7942B4AEE703938F3050EBA338E90866AF4FCA5E22D6D4A661B45305664075` |
| `D26/D26_DIABP/src/main/java/com/zl/d26/knowledge/document/po/KnowledgeDocumentContentPO.java` | `199398567D3385A124C3A3656C9B82ADB83AF70C6CD981BAB2F014CE67D7D9E1` |
| `D26/D26_DIABP/src/main/java/com/zl/d26/knowledge/document/po/KnowledgeDocumentPO.java` | `5ADDE803C8495424FFA2EE8173E579B31D370200404BAFFF9A402DC0790333CB` |
| `D26/D26_DIABP/src/main/java/com/zl/d26/knowledge/document/repository/KnowledgeDocumentRepository.java` | `A8F68E08F76DA5B72DFEB8439999B9C33D870093B6A11BD487CD5F31675146B3` |
| `D26/D26_DIABP/src/main/java/com/zl/d26/knowledge/document/repository/MybatisKnowledgeDocumentRepository.java` | `B02E4CFCCD163CBA46CC4C467F12888F41730EC436EC69F26F56AD1F607A0A4D` |
| `D26/D26_DIABP/src/main/java/com/zl/d26/knowledge/KnowledgeChunk.java` | `2049D6C84051E53AA366DBF52408E73EED15CC048A21D664D9C4AD855163E1F0` |
| `D26/D26_DIABP/src/main/java/com/zl/d26/knowledge/KnowledgeCorpus.java` | `BE2B6C5788828EBECE9C1B8C4768033637208CF4AE3ED0E02C58F1494DD1967F` |
| `D26/D26_DIABP/src/main/java/com/zl/d26/prompt/PromptTemplateController.java` | `8E9CDE1D07FFA9F619C4A61D8D007C1BDB5D0D97D3C2FF521A074BA4F9EA6FD1` |
| `D26/D26_DIABP/src/main/java/com/zl/d26/prompt/PromptTemplateRepository.java` | `9718041F9FAA0842C709E9642725AE4D261507C34BD9762171CD5ED0E30A35EE` |
| `D26/D26_DIABP/src/main/java/com/zl/d26/prompt/PromptTemplateService.java` | `2F615C7D401FDD20AB683F851AAABA123E89641132A6196F7B61C86EE15CF6CB` |
| `D26/D26_DIABP/src/main/resources/application.yml` | `EA99FB48B64668F0C680775C68DF1B071909205DE3ADD2862906B290B693F9B2` |
| `D26/D26_DIABP/src/main/resources/db/schema-chat-history.sql` | `F31A13CB4B0446AC7D31430C3CD7F21CD704264621DFC985DC8C160F9EA11CA5` |
| `D26/D26_DIABP/src/main/resources/db/schema-prompt-template.sql` | `4F24E8CCBBCB3D37D61FF015071CE801F946A35646E59504768BC09B02B30B22` |
| `D26/D26_DIABP/src/test/java/com/zl/d26/history/HistoryPersistenceTest.java` | `20BE5891D43223C51CB691E59811321F49FDB2E7CCE3F254A15084B1F5A00C86` |
| `D26/D26_DIABP/src/test/java/com/zl/d26/knowledge/document/KnowledgeDocumentBusinessSizeLimitTest.java` | `B5D03A6809EEBCFE2FF89F9D3258F7B45B8ADF39ED0D7BC7892FECD94DDDD307` |
| `D26/D26_DIABP/src/test/java/com/zl/d26/knowledge/document/KnowledgeDocumentContainerSizeLimitTest.java` | `F0DB905F7472F4AB124863D631972F640C5E2D1123C3C36252451132A786D3FE` |
| `D26/D26_DIABP/src/test/java/com/zl/d26/knowledge/document/KnowledgeDocumentFileReadFailureTest.java` | `C398237E9447255E44976BE5AD4CCC04589029BDC9A482FD83D95FB8C560FC45` |
| `D26/D26_DIABP/src/test/java/com/zl/d26/knowledge/document/KnowledgeDocumentImportIntegrationTest.java` | `44264457912AD43FE904BEB59B9CD751FACD7EA04077544BBC2AA26362A93E31` |
| `D26/D26_DIABP/src/test/java/com/zl/d26/knowledge/document/KnowledgeDocumentParserTest.java` | `9B66B1939D5FEFEDD62F3C4406736C45A6D6ED284CC6DF440D42DDD9D0D42C77` |
| `D26/D26_DIABP/src/test/java/com/zl/d26/support/AbstractPersistenceCleanupTest.java` | `5BEDF26E1B5983595F683603779691849AB1E84D59B9E96B41435FB73E46E987` |
| `D26/pom.xml` | `66FF601151C37EA18B634B1F4E73576D76A91C85F13928C296E1B2CF31057250` |

## Surefire 测试类摘要

```text
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 15.19 s -- in com.zl.d26.ApplicationContextLoadTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.829 s -- in com.zl.d26.ChatLengthValidationTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.842 s -- in com.zl.d26.ChatMemoryOrderRegressionTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.141 s -- in com.zl.d26.ChatModelFailureRegressionTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.038 s -- in com.zl.d26.config.AiPropertiesValidationTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.025 s -- in com.zl.d26.config.DeepSeekConfigurationBindingTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.182 s -- in com.zl.d26.conversation.RedisConversationMemoryTest
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.333 s -- in com.zl.d26.conversation.RoleConversationServiceTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.586 s -- in com.zl.d26.d16.CitationIntegrationTest
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.008 s -- in com.zl.d26.d16.CitationRegistryTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.892 s -- in com.zl.d26.d16.D16ThinkingStreamIntegrationTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.008 s -- in com.zl.d26.d16.KnowledgeChatOrchestratorTest
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.014 s -- in com.zl.d26.d16.KnowledgeRetrievalServiceTest
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.179 s -- in com.zl.d26.d16.ThinkingModeIntegrationTest
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.008 s -- in com.zl.d26.d16.ThinkingSettingsResolverTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.111 s -- in com.zl.d26.DeepSeekKeyProtectionTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 1, Time elapsed: 0.001 s -- in com.zl.d26.DeepSeekLiveSmokeTest
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.017 s -- in com.zl.d26.equipment.EquipmentCandidateParserRegressionTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.008 s -- in com.zl.d26.equipment.EquipmentCandidateParserTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.379 s -- in com.zl.d26.equipment.EquipmentChatControllerTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.008 s -- in com.zl.d26.equipment.EquipmentInformationAndClarificationTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.758 s -- in com.zl.d26.history.ChatMemoryReadFailureRegressionTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.680 s -- in com.zl.d26.history.ContextWindowTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.321 s -- in com.zl.d26.history.HistoryConcurrencyTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.153 s -- in com.zl.d26.history.HistoryFailureTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.248 s -- in com.zl.d26.history.HistoryPersistenceTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.152 s -- in com.zl.d26.history.SessionIsolationTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 5.074 s -- in com.zl.d26.history.SpringLockDiagnosticTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.338 s -- in com.zl.d26.knowledge.document.KnowledgeDocumentBusinessSizeLimitTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.132 s -- in com.zl.d26.knowledge.document.KnowledgeDocumentContainerSizeLimitTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.100 s -- in com.zl.d26.knowledge.document.KnowledgeDocumentFileReadFailureTest
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.243 s -- in com.zl.d26.knowledge.document.KnowledgeDocumentImportIntegrationTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.406 s -- in com.zl.d26.knowledge.document.KnowledgeDocumentParserTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.170 s -- in com.zl.d26.logging.AccessLogIntegrationTest
Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.009 s -- in com.zl.d26.logging.AccessLogMaskingTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.055 s -- in com.zl.d26.logging.AccessLogRealContainerTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.985 s -- in com.zl.d26.logging.TraceIdWithoutAccessLogTest
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.143 s -- in com.zl.d26.MockChatControllerTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.051 s -- in com.zl.d26.MockNoExternalApiTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.752 s -- in com.zl.d26.MockWithoutApiKeyTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.010 s -- in com.zl.d26.model.ModelCallPropertiesValidationTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.006 s -- in com.zl.d26.model.ModelFailureClassifierTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.122 s -- in com.zl.d26.model.ModelHttpClientTimeoutTest
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.435 s -- in com.zl.d26.model.ModelHttpRetryIntegrationTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.056 s -- in com.zl.d26.model.ModelRetryPolicyTest
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.402 s -- in com.zl.d26.observability.ModelCallObservabilityTest
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.078 s -- in com.zl.d26.observability.TokenUsageExtractorTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.143 s -- in com.zl.d26.prompt.PersistenceCacheIntegrationTest
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.295 s -- in com.zl.d26.prompt.PromptTemplateServiceTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.052 s -- in com.zl.d26.ProviderIsolationTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.382 s -- in com.zl.d26.service.MockAiChatServiceStreamTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.883 s -- in com.zl.d26.stream.ChatStreamErrorEventTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.476 s -- in com.zl.d26.stream.ChatStreamIntegrationTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.961 s -- in com.zl.d26.stream.ChatStreamTimeoutTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.919 s -- in com.zl.d26.stream.ChatStreamTotalDeadlineTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.008 s -- in com.zl.d26.stream.MvcStackDecisionTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.771 s -- in com.zl.d26.stream.RoleChatStreamIntegrationTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.013 s -- in com.zl.d26.stream.SpringAiChatClientBehaviorTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.201 s -- in com.zl.d26.stream.StreamClientDisconnectIntegrationTest
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.989 s -- in com.zl.d26.stream.StreamContractCompatibilityTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.729 s -- in com.zl.d26.stream.StreamPreStartFailureTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.314 s -- in com.zl.d26.web.HistoryControllerWebTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.182 s -- in com.zl.d26.web.PromptTemplateControllerWebTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.169 s -- in com.zl.d26.web.RoleChatControllerTest
```
