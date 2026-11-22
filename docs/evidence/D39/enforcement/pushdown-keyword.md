@(#) C08 关键词路同口径证明（精确码 D2P-771 独占命中 + 先过滤后评分）

== T7 原始记录 ==
{"tid":"T7","case":"keyword-exclusive-userA-zero","principal":"userA","endpoint":"/api/v1/hybrid/retrieval/search","code":200,"returnedDocIds":"4e9a5d5b43a2451686c40cc41f4a0426,5dcf079b38d04b179bb1e4daf98101a8,5fd9f2113b6d4f19aea48eaab8466001","forbidden":"4feb2f2cea394ae78a635017233a5f93,c6772a8a59604014bc3d86738d4e59b5","expectedVisible":"","leak":"","missingVisible":"","denied":false,"pass":true}
{"tid":"T7","case":"keyword-exclusive-userB-visible","principal":"userB","endpoint":"/api/v1/hybrid/retrieval/search","code":200,"returnedDocIds":"c6772a8a59604014bc3d86738d4e59b5,4feb2f2cea394ae78a635017233a5f93,4e9a5d5b43a2451686c40cc41f4a0426","forbidden":"","expectedVisible":"c6772a8a59604014bc3d86738d4e59b5","leak":"","missingVisible":"","denied":false,"pass":true}

== 机制说明 ==
KeywordSearchService 评分前先以 PermissionScope.allows 内存谓词过滤候选（与向量路同代数，
AccessPolicyServiceTest 36 组合逐行等价证明两路判定一致）；userA 查询 D2/D2P771 独占词面
零命中而 userB（D2）可见同一 chunk——证明关键词路过滤生效且非数据缺失。
