# D39_state_R01 —— 知识权限与元数据过滤（two-lp）

- 迭代/切片：D39 / R01；骨架基线 commit：`776de40c5682349bb49b8e9785087e53c1140b28`（D7 裁决：P0 骨架先提交，已执行）；迭代提交：见本文末 Git 节
- 技术基线：Spring Boot 3.5.16 / JDK21 / Spring AI 1.1.8 / MyBatis-Plus 3.5.17 / PG 18.7 + pgvector 0.8.7 / MySQL 8（ai_db）
- 冻结参数（`probe\access-params.json`）：predicateScheme=USER 三键 AND（dept/device 各 OR 哨兵，Group 括号形态冻结）/ ADMIN=仅 tenantId（H7）；wildcardSentinel=ALL；principalsTable=d39_auth_principal；baselineFingerprint=`a6a6bd4fe0aed45c14d1c059339d2a156cbc75c86fa4af4d31e14aec69e31b07`；baselineRowCount=30（DOC-8+d36exp-17+d34-real-3+hex-2）；ledgerSha=`9A620A583FA0950993785784381F94B8A950FE3211C41E025421377DEC95E10E`（17 行，本轮零写入）
- 偏差裁决：D1 最小服务端凭据=已实现（Bearer+SHA-256 表，不引 Spring Security）/ D2 三键+哨兵 ALL=白名单零改动 / D3 无标签=权限空间外（遗留 30 行与 MySQL 3 行含 ADMIN 一律不可见，禁补标签）/ D4 端点分级=49（注解行口径）→**37（handler 映射实口径）**，`endpoint-inventory.csv` 全分级 / D5 无检索指纹门禁→改「矩阵+基线 diff+台账 SHA」三重纪律 / D6 缓存裁决=无新增检索缓存（grep 留痕），prompt 缓存 owner 语义不变，历史会话归属改可信身份（R01 增量收口）/ D7 骨架先提交=已执行

## 门禁与用例落表（四态合计：PASS 18 + NOT RUN 2 = C01–C20 共 20；KP 4 项全 PASS）

| 编号 | 状态 | 证据路径 | 备注 |
|---|---|---|---|
| KP1 环境 | PASS | `probe\` | 端口 9090/PG/MySQL/凭据可达 |
| KP2 数据态 | PASS | `probe\` | 30 行/指纹/two_ 8 行/台账 SHA 逐项复核一致；发现外部 IDE 预建 d39_auth_principal 5 行+三权限列（漂移取证+处置留痕，见 access-params.driftNote） |
| KP3 参数冻结 | PASS | `probe\access-params.json` | 白名单/哨兵/能力面（javap）/NULL 语义（JDBC 实测） |
| KP4 下推证明方法 | PASS | 同上 + `enforcement\pushdown-vector.md` | 决定性夹具+topK50/th0 零命中法 |
| C01 编译+单测 | PASS | `compile-test-r01-final.txt` | 45/45（26 access + 3 RRF 闭包新增） |
| C02 默认关闭零差异 | PASS | `regression\` | enabled=false 三组对照全 0 diff |
| C03 身份 fail-closed | PASS | `auth\`、`security\t8-failclosed.jsonl` | 5 类拒绝码全谱单测+端到端 |
| C04 DDL 幂等 | PASS | `ddl\c04*` | 双跑核验；发现并修复 page 缺列/created_at 1364 两层 schema 漂移 |
| C05 既有端点回归 | PASS | `regression\d37-frozen-baseline-diff.txt` | **D37 冻结 60 行 × 12 质量列 diff=0**（任务书原口径）；骨架 worktree 三轮对照 0 diff 为增强证据；6 FAIL=D37 基线自身存量（14/6 同态） |
| C06 三键元数据写入 | PASS | `metadata\`、`security\` | 有值才写/哨兵显式写；PG 核验 |
| C07 向量轨过滤 | PASS | `enforcement\pushdown-vector.md` | Group 修复+整串冻结单测+端到端零命中 |
| C08 关键词轨过滤 | PASS | `enforcement\pushdown-keyword.md`、`security\t7` | 先过滤后评分 |
| C09 融合闭包 | PASS | `enforcement\fusion-closure.md`、t7 | RRF 不凭空引入第三文档 |
| C10 RAG 生成端点端到端 | NOT RUN | `measurability-boundary.md` | 零远程纪律（生成需模型调用）；代码路径由 require + 同源谓词单测覆盖，rag/chat 引用链继承 FILTER_REQUIRED 设计 |
| C11 跨部门 | PASS | `security\t1.jsonl` | userA ∩ D2 = ∅ |
| C12 伪造身份 | PASS | `security\t2.jsonl` | userId/role/tenant 声明忽略；X-Dev-User 不获权限 |
| C13 admin 范围+跨租户 | PASS | `security\t3-t5.jsonl` | adminT1 见 T1 全量、零跨租户 |
| C14 设备范围 | PASS | `security\t6.jsonl` | DEV-B 专属零命中；哨兵通配合规可见 |
| C15 缓存/会话 | PASS | `security\t4-cache.jsonl` | 跨 owner 4034 拒绝；发现并收口 HistoryController X-Dev-User 缺口（R01 修复#3） |
| C16 故障注入端到端 | NOT RUN | `measurability-boundary.md` | 禁破坏性停 PG/Docker；fail-closed 存储不可达分支单测 PASS 替代（AUTH_STORE_UNAVAILABLE） |
| C17 无标签+注入 | PASS | `security\t9-t10.jsonl`、`legacy-docids.txt` | 30 行权限端点零命中；注入载荷零越权；reconcile 定性=一致性对账（admin 200+userA 4001） |
| C18 暴露面收口 | PASS | `enforcement\surface-audit.md` | 37 映射全分级+2 项 R01 增量收口（corpus/index 双端点 admin、history 可信身份） |
| C19 清理+回归复验 | PASS | `cleanup\c19-post-cleanup-verification.txt` | FIXTURE_REMAINING=0（MySQL 5 表×0、PG 12 行删、倒排 6→0）；PG 30 行/with_keys=0/22 docs 残留原样；指纹恢复逐字符一致；清理后回归再 0 diff |
| C20 卫生+预算+文档+Git | PASS | 卫生扫描 0 命中；`cost\zero-remote-proof.md`；本文+双文档；迭代提交 | token 明文/sk-/Bearer 全 0；台账 SHA 前后不变 |

## 分级结论

L1=PASS（45/45）；L2=PASS（骨架提交/KP 门禁/DDL 幂等/fail-closed/零差异）；L3=PASS（三轨过滤/双路一致/闭包/矩阵 33/33/D37 基线 diff 0）；L4=PASS（卫生/预算/清理）；L5=NOT RUN（单机环境，跨机复现未执行）

## 安全测试结论（引用返回 documentId 集合断言）

- T1–T10 矩阵 **TOTAL=33 FAIL=0**（`report\permission-matrix.csv`）；全部越权用例 forbidden 命中数恒 0，正向对照恒命中（co-occurrence 词面设计）⇒「过滤而非拒服务」；
- 下推证明：d2p771 与合法文档同词面、无过滤必 Top1，权限端点 topK=50/threshold=0 零命中 ⇒ 过滤先于 Top-K；admin 同查询可见 ⇒ 数据在库非入库失败；
- 权限矩阵 principal × document 期望/实际一致率 = 33/33 = **100%**；
- 决定性修复：PG 下推 OR 子树 Group 括号契约（15 FAIL→33/33 全程留痕 `enforcement\pushdown-vector.md`）。

## 成本实耗

DeepSeek calls=0、tokens=0（mock，不伪称 Token 口径=max-context-chars 4000 码点）；百炼 calls=0（two_ 环境关闭；台账 17 条 SHA `9A620A58…E10E` 前后不变、mtime 11-21 未触碰，余 1 预算未动）；结构性零远程日志核验 `cost\zero-remote-proof.md`（真实调用行 0，仅装配 INFO 一行与骨架同态）。

## 诚实声明

- **已执行并通过**：C01–C09、C11–C15、C17–C20 全部（含 D37 冻结 60 行 diff=0、矩阵 33/33、45/45、清理终验、卫生扫描、指纹恢复）；
- **已执行但失败**：无带病推进项——首跑矩阵 15 FAIL（下推括号泄漏）与 Rounds2 前 BLOCKED（8080 硬编码）均已定位根因修复后全绿，失败现场全部留痕；
- **尚未执行**：C10/C16 端到端（NOT RUN，理由与替代证据见 measurability-boundary.md）；L5 跨机复现；
- **N/A**：two_ 真实权限端到端（环境关闭）、SSE 流式全路径（骨架无该链路权限分支）。

## Git（D7：骨架/迭代分离）

- 骨架提交：`776de40`（P0，已完成）
- 迭代提交：`D39 项目迭代R01 知识权限与元数据过滤`（信息冻结），范围=新源码（access 主/测、hybrid 测）+28 白名单修改+temp_docs 源码（`git add -f` 逐文件，**fixtures\principal-tokens.json 绝不提交**）+evidence\D39+proj_docs 双文档+本状态文件；排除 target/、surefire-reports、logs 敏感产物；提交前 `git diff --cached --stat` 核验；**不 push**。

## 遗留与 D40 候选

two_ 真实权限端到端（待预算与环境授权）/ C10 RAG 生成端权限链实弹测试（可 mock 模型下扩展）/ SSE 流式全路径 / JWT 或 Spring Security 正式化替代最小凭据 / 权限管理后台与三键编辑 API / 遗留 30 行处置决策（需授权，禁擅自补标签）/ 跨机 L5 / 倒排+chunker 生产修复的独立回归批次。
