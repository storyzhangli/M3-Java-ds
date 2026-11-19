# D36 迭代状态文件 R01：检索参数对照实验

| 项 | 内容 |
|---|---|
| 迭代编号 | D36 |
| 切片编号 | R01 |
| 模块 | D36/D36_SPCE（com.zl.d36，新增子包 com.zl.d36.experiment） |
| 执行依据 | D36-项目迭代任务书Prompt-GPT修复版-检索参数对照实验 |
| 状态 | 已完成，待最终汇报 |
| 记录时间 | 2026-11-19 |

---

## 1. 任务目标

在固定问题集（D25 questions.jsonl Q01–Q10）、固定语料（3 文档/8 片段）、固定 Embedding（local-hash-ngram-v1，384 维）、固定距离度量（COSINE）下，执行 1 个真实基线 G0 与 4 个单变量组（G1 chunk size、G2 overlap、G3 Top-K、G4 阈值）对照实验，产出可运行代码、组件级测试、6 项证据与两份 Markdown 文档。

---

## 2. 完成项清单

| 编号 | 交付项 | 状态 |
|---|---|---|
| 1 | 实验主代码 18 个类（com.zl.d36.experiment） | 已完成，离线编译通过 |
| 2 | 组件级测试 6 个类 + application-experiment.yml | 已完成 |
| 3 | 真实五组实验执行 | 已执行，BUILD SUCCESS |
| 4 | 4 个 JSON 证据自动落盘 | 已完成并回读核验 |
| 5 | mvn-clean-test.txt 离线测试输出 | 已完成 |
| 6 | 本状态文件 D36_state_R01.md | 已完成 |
| 7 | 两份 Markdown 文档（D36-01、D36-02） | 已完成 |

---

## 3. 真实验证结果

### 3.1 离线编译与组件测试（已执行并通过）

- 命令：`mvn -B -o clean test`
- 结果：**Tests run: 30, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS**
- 分布：ChunkIdMappingTest 5、ChunkingParametersTest 6、ExperimentIsolationTest 5、MetricsCalculationTest 6、RetrievalParametersTest 8。
- 集成测试 RetrievalExperimentRunnerIT 在默认环境由类级条件守卫整体禁用（未设置 D36_EXP_RUN），不加载上下文。

### 3.2 真实五组实验（已执行并通过）

- 命令：`mvn -B "-Dsurefire.spring.profile=experiment" "-Dtest=RetrievalExperimentRunnerIT" "-DfailIfNoTests=false" test`（环境变量 D36_EXP_RUN=true）
- 结果：**BUILD SUCCESS，1 test，0 failures/errors/skipped**
- 真实连接 PG 192.168.200.153:5432、MySQL 127.0.0.1:3306，实验前均验证连通。

---

## 4. 五组真实指标（来自 experiment-summary.json）

| 组 | 入库块 | HitRate | micro Precision | avg Recall | avg Noise | avg ms | 原因码 |
|---|---|---|---|---|---|---|---|
| G0 | 3 | 1.0 | 0.1389 | 1.0 | 6.2 | 4.761 | K_EXCEEDS_INDEXED_CHUNKS |
| G1 | 5 | 1.0 | 0.1389 | 1.0 | 6.2 | 4.393 | 无 |
| G2 | 3 | 1.0 | 0.1389 | 1.0 | 6.2 | 3.544 | K_EXCEEDS_INDEXED_CHUNKS |
| G3 | 3 | 1.0 | 0.1389 | 1.0 | 6.2 | 3.341 | K_EXCEEDS_INDEXED_CHUNKS |
| G4 | 3 | 0.5 | 0.3846 | 0.5 | 0.8 | 2.512 | ZERO_HITS_AFTER_THRESHOLD、K_EXCEEDS_INDEXED_CHUNKS |

G4 零命中题目：Q03、Q04、Q05、Q08、Q10。五组均无重复结果分歧。

---

## 5. 数据集版本与指纹

- 问题集：docs/evidence/D25/rag-design/questions.jsonl（Q01–Q10）
- 语料：D36/D36_SPCE/src/main/resources/knowledge/equipment-fault-corpus.json
- questionsSha256 = `44426b1771c379343a2239147af0adf4c4d9a2d80e547e0bcf3259c65634750a`
- corpusSha256 = `aad6ac4df5585aadd5078d1c94df4e5093af15a9730e230149cfde0940335ab0b`

---

## 6. 数据库写入与安全

- 写入方式：**只追加**，5 组 × 3 文档 = 15 个带 `d36exp-gX-` 前缀 documentId，未删除任何数据、未执行 prune。
- 密钥：PG 密码经环境变量 `PG_VECTOR_PASSWORD` 注入（无默认、缺失 fail-fast）；MySQL 经 `MYSQL_PASSWORD`（默认 123456）。无硬编码、不进 PATH。
- 未执行 Git commit/push；未执行 Docker prune/删卷/重建。

---

## 7. 关键口径与已知偏差

1. 向量块口径（hitCount，2–5）与逻辑片段映射口径（如 G0 Q01 覆盖 8 个逻辑 ID）并存，Precision/Noise 按逻辑片段口径计算。
2. chunkIndex 1-based 与入库 normalize 0-based 冲突，实验侧 `reindexZeroBased` 适配，生产类零改动。
3. VectorStore.add 入库时二次 Embedding，复用同一本地确定性模型，无外部调用。
4. 本地哈希模型为教学用途，结论不外推到生产语义 Embedding；数据集小，延迟差异不具统计显著性。

---

## 8. 后续事项

- 本切片 R01 范围内任务已全部完成，无未决阻塞。
- 若后续需在大规模语料或真实语义 Embedding 下评估，应另立切片，扩大问题集与重复次数，并重新核验维度与指纹。
