# D30 证据 04：编译与单元测试（L1）

- 命令：`mvn.cmd -f D30\pom.xml compile`（Maven 3.9.16 / JDK 21）→ 退出码 0
- 命令：`mvn.cmd -f D30\pom.xml test` → 退出码 0，BUILD SUCCESS
- 日志：`docs/temp_docs/D30/mvn-test-run1.log`

## 新增单元测试（16 个，全通过）

| 测试类 | 用例 | 结果 |
|---|---|---|
| PgVectorStorePropertiesTest | 5 | 0 失败 0 错误 |
| PgVectorStoreConfigurationGuardTest | 2 | 0 失败 0 错误 |
| PgVectorEnvironmentVerifierTest | 5 | 0 失败 0 错误 |
| KnowledgeVectorStoreServiceTest | 4 | 0 失败 0 错误 |
| PgVectorStoreRealIntegrationTest | 4 | 门控未开，4 Skipped（符合设计） |

## 历史回归防护

修复前：5 个历史 @SpringBootTest 类（ApplicationContextLoadTest 等，共 10 用例）因 PG fail-fast
全部 Errors；修复（test profile 关闭向量库装配）后全部恢复通过。
全仓 surefire 报告：0 Failures / 0 Errors（唯一 Skipped 为 DeepSeekLiveSmokeTest，历史即有）。
