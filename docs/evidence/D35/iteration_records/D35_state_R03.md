# D35 整改状态（R03）

## 整改依据

- 任务书：《D35 项目整改任务书：引用溯源与返回契约》（用户上传，本地副本路径 `docs/temp_docs/D35/D35-项目整改任务书-引用溯源与返回契约.md`）。
- 整改基线 Commit：`80097b0413780ddc10c89421f1ce32ea22aa1b99`（D35 迭代实现）。
- 验收评分：8.4 / 10；整改目标为修复 URL 安全边界、补齐 documentVersion、补充语义边界说明，不重构 RAG 主链路。

## 任务一（P0）：URL 安全校验修复

- 问题（静态阅读旧实现确认）：`extractHost` 字符串拆分导致
  1. `https://[::1]/admin` 的 host 为 `[::1]`，与 `::1` 比较不命中 → 放行；
  2. `https://127.0.0.1?x=1` 无 `/`，authority 含查询串 → 不匹配 loopback → 放行；
  3. userinfo 仅靠 `@` 位置假设，规则化不足。
- 修复：`UrlSafetyPolicy` 改为 `java.net.URI` 解析；scheme 白名单；userinfo 一律拒绝；
  Host 由 `URI#getHost` 获取；IPv4 严格 4 段数值判定（0/8、10/8、127/8、169.254/16、
  172.16/12、192.168/16、224/4 拒绝，前导零变体拒绝）；IPv6 剥离方括号后由
  `InetAddress` 判定回环/未指定/链路本地/site-local/ULA(fc00::/7)/组播，
  IPv4-mapped 提取低 32 位按 IPv4 规则；相对路径、opaque、盘符路径拒绝。
- 测试：`UrlSafetyPolicyTest` 扩展为 **29 个测试**（任务书 13 类 + 公网 IPv6 允许 +
  端口/前导零/协议相对等边界），全部通过。
- 证据：`docs/evidence/D35/url-safety-regression.txt`。

## 任务二（P1）：documentVersion 补齐

- 现有链路已具备：`VectorMetadataBuilder` 写 metadata `version` →
  `RetrievalHitMapper` 读 `version` → `RetrievalHitView.version` → `RetrievedChunk.version`。
- 本轮补齐最后一跳：`RetrievedChunk.toKnowledgeChunk` 把 `version` 写入
  `KnowledgeChunk.documentVersion`；`CitationDto` 新增 `documentVersion`；
  `CitationRegistry.toDto` 映射。字段只从检索命中 metadata 来，不查"当前最新版本"，
  历史 V1 不会被 V2 覆盖；缺失为 null 不补造。
- 测试：新增 `CitationVersionContractTest`（5 个）；`RagCitationApiIntegrationTest`
  增加同步契约 `documentVersion` 断言。全部通过。
- 证据：`docs/evidence/D35/citation-version-regression.txt`。

## 任务三（P2）：引用语义支持边界说明

- 已在 `D35-02` 技术文档新增独立章节，明确区分
  **Citation Existence Validation（引用存在性/合法性校验）** 与
  **Claim-level Grounding（声明级语义支撑）**，列出 `PostGenerationValidator`
  的实际检查范围（故障码、数值参数、URL/本机路径）与局限，给出 2 个
  "引用真实但结论不受证据支持"的反例，并将完整 Claim-level Grounding
  标记为后续迭代。

## 验证结果（真实执行）

- 命令：`& "D:\SoftwareInstall\Apache\Maven\apache-maven-3.9.16\bin\mvn.cmd" -B -f "E:\AI\project\AI-Learning-two-lp\M3-Java-ds\D35\pom.xml" clean test`
- 时间：2026-11-18 21:53:03 → 21:53:53（UTC+8）
- 结果：**BUILD SUCCESS，EXIT=0**
- **Tests run: 65, Failures: 0, Errors: 0, Skipped: 0**
  - 原有 47 个测试全部回归通过，无退化；
  - 新增：`UrlSafetyPolicyTest` 29（原 16 → 29，参数化展开）、`CitationVersionContractTest` 5。
- 完整日志：`docs/evidence/D35/mvn-clean-test-R03.txt`

## 中途失败与修复（如实记录）

1. 首次编译失败：`CitationVersionContractTest` 中 `ObjectMapper.writeValueAsString`
   `JsonProcessingException` 未声明，`testCompile` 报错、BUILD FAILURE（EXIT=1）。
   修复：测试方法签名加 `throws Exception` 后重跑通过。

## 修改文件

主代码（5）：
- `rag/support/UrlSafetyPolicy.java`（重写）
- `knowledge/KnowledgeChunk.java`（新增 documentVersion，保留 8 参兼容构造器）
- `dto/CitationDto.java`（新增 documentVersion）
- `rag/model/RetrievedChunk.java`（toKnowledgeChunk 透传 version）
- `knowledge/CitationRegistry.java`（toDto 映射 documentVersion）

测试（3）：
- `rag/support/UrlSafetyPolicyTest.java`（扩展）
- `rag/CitationVersionContractTest.java`（新增）
- `rag/RagCitationApiIntegrationTest.java`（加 documentVersion 断言）

文档（2）：`D35-01`、`D35-02`（更新字段契约、API 示例、能力边界）

## 改造边界遵守

- 保留 `CitationRegistry`、`CitationExistenceValidator`、`PostGenerationValidator`；
- 同步与 SSE 共用 `CitationDto` 契约不变；
- 未改动 D32 检索 / D33 组装 / D34 证据评估；
- 未新增依赖、未升级版本、未删除任何原有测试断言；
- 未执行破坏性操作（无删卷、无重新入库、无真实数据删除）。

## 未执行（如实）

- Level 5 真实 pgvector 端到端：需隔离可控数据与授权，本轮未执行；
- 真实 `/api/v1/chat/stream` SSE 端点端到端测试：未新增，SSE 证据为同一 DTO 契约级；
- 语料 JSON 无版本字段，语料链路 `documentVersion` 恒为 null（如实缺失）。

## Git 提交

- 本轮整改提交编号：（提交后回填，见最终 commit 记录）
