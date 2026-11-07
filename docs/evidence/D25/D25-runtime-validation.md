# D25 运行与文件校验

## 数据集校验

```text
21 cases
JSON Schema: PASS
Unique caseId: PASS
Unique question: PASS
Required category counts: PASS
Real Document/Chunk references: PASS
Permission evidence non-disclosure: PASS
```

摘要：`D25-validation-summary.md`

日志：`logs/D25/D25-dataset-validation-final.log`

## 项目测试

```text
Tests run: 194, Failures: 0, Errors: 0, Skipped: 1
```

完整日志：`logs/D25/D25-baseline-full-test.log`

## 打包

- 成功
- JAR：`D25/D25_DKDCM_RAC/target/D25_DKDCM_RAC-1.0-SNAPSHOT.jar`
- 字节数：59,515,457
- SHA-256：`1F8900AAFDB25B14C6976B5A1171E5D44480D93C7F125808F90B63CE684E2846`
- 日志：`logs/D25/D25-package-01.log`

## 未执行

- RAG 端到端运行。
- Embedding/向量召回。
- 知识 ACL 实际过滤。
- Recall/MRR 等运行指标。
