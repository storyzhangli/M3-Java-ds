# D41 R03 — PARSE_FAILED 审计语义修复（R02 未解决项②）

## 1. 瑕疵与根因

R02 遗留：`TableController.extract` 的审计判定仅识别 `REEXTRACT_TEXT_TRACK_ONLY`——
degradedCodes 含 `PARSE_FAILED`（R02-P4 新增的解析失败三态）时 `ok=true`，审计记 **ALLOWED**，
与"执行失败应记 FAILED"语义相悖（已入 R02 文档，本轮修复）。

## 2. 修复（最小改动，1 文件）

文件：`D41/D41_TASIIEM/src/main/java/com/zl/d41/knowledge/table/TableController.java`

```java
boolean parseFailed = summary.degradedCodes().contains(TableDegrade.PARSE_FAILED);
boolean textTrackOnly = summary.degradedCodes().contains(TableDegrade.REEXTRACT_TEXT_TRACK_ONLY);
auditIfPresent(actor, ACTION_TABLE_EXTRACT, documentId, document.getVersion(),
        !textTrackOnly && !parseFailed,
        parseFailed ? TableDegrade.PARSE_FAILED : TableDegrade.REEXTRACT_TEXT_TRACK_ONLY);
```

`auditIfPresent` 增加 `failedReason` 参数（私有方法，唯一调用点同步；对外 HTTP 契约/Result 结构零变更）。
优先级：PARSE_FAILED > REEXTRACT_TEXT_TRACK_ONLY（双码并存记 PARSE_FAILED）。

## 3. 测试锁定（新增 4 例，Mockito 纯单测，无 Spring 上下文）

文件：`src/test/java/com/zl/d41/knowledge/table/TableControllerAuditTest.java`

| 用例 | 期望 | 结果 |
|---|---|---|
| parseFailedAuditsAsFailedWithHonestReason | recordFailed(reason=PARSE_FAILED) 且无 recordAllowed | PASS |
| textTrackOnlyKeepsR01AuditSemantics | recordFailed(reason=REEXTRACT_TEXT_TRACK_ONLY)（回归锁） | PASS |
| normalExtractionAuditsAllowed | recordAllowed，无 recordFailed | PASS |
| parseFailedTakesPrecedenceWhenBothPresent | 双码并存记 PARSE_FAILED | PASS |

- 单跑：`mvn -o -B test -Dtest=TableControllerAuditTest` → Tests run: 4, Failures: 0（exit 0）
- 全量：`mvn -o -B clean test` → **95/95, Skipped=0, BUILD SUCCESS**（surefire 13 类聚合见 `../test/surefire-summary-r03.txt`）

## 4. HTTP 层交叉事实

演练 S5（本批 d41t-r01-20261124-220506）正常文档双次 extract 审计行 result=ALLOWED ×2——
与修复语义一致（无退化码=成功）；PARSE_FAILED→FAILED 路径在演练中无自然触发点（9 夹具全部解析成功），
由上述 4 例单测承担锁定，如实区分"单测实证"与"HTTP 面未触发场景"。

## 5. 未改动声明

TableRecordService/AuditService/DDL/接口签名零变更；`d41_admin_audit` 历史数据未动。
