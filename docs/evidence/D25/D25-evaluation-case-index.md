# D25 评测用例索引

数据集：`D25-rag-evaluation-dataset.json`

| Case ID | 类别 | 期望决策 | 期望证据/缺失说明 |
|---|---|---|---|
| D25-EVAL-001 | answerable | answer | `DOC-CNC-COOLING-001-C01`，第12页 |
| D25-EVAL-002 | answerable | answer | `DOC-CNC-COOLING-001-C02`，第15页 |
| D25-EVAL-003 | answerable | answer | `DOC-CNC-COOLING-001-C03`，第18页 |
| D25-EVAL-004 | answerable | answer | `DOC-SERVO-TROUBLE-002-C01`，第6页 |
| D25-EVAL-005 | answerable | answer | `DOC-SERVO-TROUBLE-002-C02`，第9页 |
| D25-EVAL-006 | answerable | answer | `DOC-WORKORDER-SPEC-003-C01`，第3页 |
| D25-EVAL-007 | answerable | answer | `DOC-WORKORDER-SPEC-003-C02`，第7页 |
| D25-EVAL-008 | fault-code | abstain | 缺少 CNC-760/E102 映射 |
| D25-EVAL-009 | fault-code | abstain | 缺少版本化根因证据 |
| D25-EVAL-010 | fault-code | clarify | 缺少 FM-22/E211 字典 |
| D25-EVAL-011 | fault-code | clarify | 缺少 CNC-760/CNC-880 对照资料 |
| D25-EVAL-012 | unanswerable | abstain | 缺少价格/交期 |
| D25-EVAL-013 | unanswerable | abstain | 缺少噪声限值 |
| D25-EVAL-014 | unanswerable | abstain | 缺少更换周期 |
| D25-EVAL-015 | unanswerable | abstain | 缺少电气参数 |
| D25-EVAL-016 | permission | deny | 受限事故资料/ACL 夹具待补 |
| D25-EVAL-017 | permission | deny | 跨工厂范围/ACL 待补 |
| D25-EVAL-018 | permission | deny | 承包商权限策略待补 |
| D25-EVAL-019 | permission | deny | 匿名用户权限策略待补 |
| D25-EVAL-020 | boundary | clarify | 缺少设备型号/版本 |
| D25-EVAL-021 | boundary | clarify | 跨型号适用范围未确认 |

## 统计

- 总用例：21
- answerable：7
- unanswerable：4
- permission：4
- fault-code：4
- boundary：2
- 当前真实语料能直接支持的证据引用：7
