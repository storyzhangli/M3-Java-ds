@(#) C07 向量路 Top-K 前下推决定性证明（D39-R01）
生成: 2026-11-22 19:17:40

== 决定性用例（T1/T5/T6/T10 vector 路, topK=50 threshold=0）==
{"tid":"T1","case":"vector/D2P-771-topK50","principal":"userA","endpoint":"/api/v1/knowledge/retrieval/search","code":200,"returnedDocIds":"5fd9f2113b6d4f19aea48eaab8466001","forbidden":"4feb2f2cea394ae78a635017233a5f93,c6772a8a59604014bc3d86738d4e59b5","expectedVisible":"5fd9f2113b6d4f19aea48eaab8466001","leak":"","missingVisible":"","denied":false,"pass":true}
{"tid":"T1","case":"vector/Relief-D2P771-topK50","principal":"userA","endpoint":"/api/v1/knowledge/retrieval/search","code":200,"returnedDocIds":"","forbidden":"4feb2f2cea394ae78a635017233a5f93,c6772a8a59604014bc3d86738d4e59b5","expectedVisible":"","leak":"","missingVisible":"","denied":false,"pass":true}
{"tid":"T1","case":"admin-same-query-visible(KP4辅证)","principal":"adminT1","endpoint":"/api/v1/knowledge/retrieval/search","code":200,"returnedDocIds":"c6772a8a59604014bc3d86738d4e59b5","forbidden":"","expectedVisible":"c6772a8a59604014bc3d86738d4e59b5","leak":"","missingVisible":"","denied":false,"pass":true}
{"tid":"T5","case":"userC-cannot-see-T1","principal":"userC","endpoint":"/api/v1/knowledge/retrieval/search","code":200,"returnedDocIds":"ac89ecf5e5864b1fb99ff2c5c4480d2d","forbidden":"5fd9f2113b6d4f19aea48eaab8466001,5dcf079b38d04b179bb1e4daf98101a8,4feb2f2cea394ae78a635017233a5f93,4e9a5d5b43a2451686c40cc41f4a0426,c6772a8a59604014bc3d86738d4e59b5","expectedVisible":"ac89ecf5e5864b1fb99ff2c5c4480d2d","leak":"","missingVisible":"","denied":false,"pass":true}
{"tid":"T5","case":"adminT1-cannot-see-T2","principal":"adminT1","endpoint":"/api/v1/knowledge/retrieval/search","code":200,"returnedDocIds":"4feb2f2cea394ae78a635017233a5f93,5dcf079b38d04b179bb1e4daf98101a8,5fd9f2113b6d4f19aea48eaab8466001,4e9a5d5b43a2451686c40cc41f4a0426,5fd9f2113b6d4f19aea48eaab8466001,4feb2f2cea394ae78a635017233a5f93,5dcf079b38d04b179bb1e4daf98101a8,4e9a5d5b43a2451686c40cc41f4a0426,c6772a8a59604014bc3d86738d4e59b5,c6772a8a59604014bc3d86738d4e59b5","forbidden":"ac89ecf5e5864b1fb99ff2c5c4480d2d","expectedVisible":"","leak":"","missingVisible":"","denied":false,"pass":true}
{"tid":"T6","case":"devA-cannot-see-devB-doc","principal":"userA","endpoint":"/api/v1/knowledge/retrieval/search","code":200,"returnedDocIds":"5fd9f2113b6d4f19aea48eaab8466001,4e9a5d5b43a2451686c40cc41f4a0426,5fd9f2113b6d4f19aea48eaab8466001,5dcf079b38d04b179bb1e4daf98101a8,4e9a5d5b43a2451686c40cc41f4a0426,5dcf079b38d04b179bb1e4daf98101a8","forbidden":"4feb2f2cea394ae78a635017233a5f93","expectedVisible":"","leak":"","missingVisible":"","denied":false,"pass":true}
{"tid":"T6","case":"dev-sentinel-visible-to-userA","principal":"userA","endpoint":"/api/v1/knowledge/retrieval/search","code":200,"returnedDocIds":"5dcf079b38d04b179bb1e4daf98101a8","forbidden":"","expectedVisible":"5dcf079b38d04b179bb1e4daf98101a8","leak":"","missingVisible":"","denied":false,"pass":true}
{"tid":"T10","case":"injection-vector","principal":"userA","endpoint":"/api/v1/knowledge/retrieval/search","code":200,"returnedDocIds":"5dcf079b38d04b179bb1e4daf98101a8,4e9a5d5b43a2451686c40cc41f4a0426,5fd9f2113b6d4f19aea48eaab8466001,5dcf079b38d04b179bb1e4daf98101a8,4e9a5d5b43a2451686c40cc41f4a0426,5fd9f2113b6d4f19aea48eaab8466001","forbidden":"4feb2f2cea394ae78a635017233a5f93,c6772a8a59604014bc3d86738d4e59b5","expectedVisible":"","leak":"","missingVisible":"","denied":false,"pass":true}

== 修复链证据（泄漏→根因→修复→复验）==
1. 首轮矩阵 FAIL=15 全为 vector 路过滤未生效（旧文件已覆盖，本轮复跑 33/33 PASS）
2. 探针 docs\temp_docs\D39\D39PgFilterProbe.java 实证 Spring AI 1.1.8 PgVectorFilterExpressionConverter
   仅对 Group 节点渲染括号；裸 b.or(...) 展平进 && 链后 jsonpath 优先级丢失：
   修复前 USERA_SQL = '$."tenantId" == "T1" && $."deptId" == "D1" || $."deptId" == "ALL" && ($."deviceId" == "DEV-A") || $."deviceId" == "ALL"'
   （设备哨兵段独立析取恒真 → 跨部门/跨租户泄漏）
3. 修复：AccessPolicyService.vectorFilterOp 以 b.group(...) 包裹 deptPart/devicePart OR 子树
4. 修复后 USERA_SQL = '$."tenantId" == "T1" && ($."deptId" == "D1" || $."deptId" == "ALL") && (($."deviceId" == "DEV-A") || $."deviceId" == "ALL")'
5. 单测锁：AccessPolicyServiceTest.pgPushdownRenderKeepsOrGroupsParenthesized 整串冻结（形态漂移即失败）
6. 运行时样本：logs\D39\D39-r01-run3.log filter= 行（矩阵 traceId 见 jsonl）
7. admin 同查询可见（数据在库证明）：T1 case admin-same-query-visible PASS
