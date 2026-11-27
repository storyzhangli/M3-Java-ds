# D44 语义 diff 报告 v2（§6.2：主键对齐；计时键抖动豁免；d43->d44 命名等值映射显式留痕）
生成时间: 2026-11-27T18:16:51
抖动白名单: elapsedMs, elapsedMillis, latencyMillis, durationMs, timestamp, p50Ms, p95Ms, maxMs, meanMs, avgMs, requestId, runId, writtenAt, frozenAt, generatedAt, sampleCount, date, round, profile, aiMode, engine, frozenFingerprintsRef, saturationNote, evidencePath
命名映射规则: d43e2e->d44e2e; d43_admin_audit->d44_admin_audit; d43_document_sync->d44_document_sync; d43_auth_principal->d44_auth_principal; d43_vector_store->d44_vector_store; com.zl.d43->com.zl.d44
分类判定: STRUCT>0=STRUCT_DIFF | SEM>0=SEMANTIC_DIFF | 仅NAMING=NAMING_ONLY(等值通过) | 无差=TIMING_ONLY

== e2e-scenario-records.json : NAMING_ONLY sem=0 struct=0 namingEquiv=5 ==
NAMING_EQUIV $.records[scenarioId=S01].assertions[1].name: base=[documentId 前缀 d43e2e-] rerun=[documentId 前缀 d44e2e-]
NAMING_EQUIV $.records[scenarioId=S01].assertions[1].detail: base=[d43e2e-lc-001] rerun=[d44e2e-lc-001]
NAMING_EQUIV $.records[scenarioId=S04].assertions[0].detail: base=[documentId=d43e2e-lc-001] rerun=[documentId=d44e2e-lc-001]
NAMING_EQUIV $.records[scenarioId=S05].assertions[1].detail: base=[docs=[d43e2e-lc-001#1, d43e2e-lc-002#2]] rerun=[docs=[d44e2e-lc-001#1, d44e2e-lc-002#2]]
NAMING_EQUIV $.records[scenarioId=S08].assertions[3].detail: base=[documentId=d43e2e-lc-001] rerun=[documentId=d44e2e-lc-001]
== permission-matrix-results.json : NAMING_ONLY sem=0 struct=0 namingEquiv=1 ==
NAMING_EQUIV $.records[caseId=P09].expected: base=[放行 + d43_admin_audit 落行 DOC_DELETE] rerun=[放行 + d44_admin_audit 落行 DOC_DELETE]
== fixed-eval-results.json : TIMING_ONLY sem=0 struct=0 namingEquiv=0 ==
== fault-drill-observation.json : NAMING_ONLY sem=0 struct=0 namingEquiv=1 ==
NAMING_EQUIV $.fixtureDocumentId: base=[d43e2e-drill-001] rerun=[d44e2e-drill-001]

## 汇总结论
- e2e-scenario-records.json: NAMING_ONLY (sem=0 struct=0 naming=5)
- permission-matrix-results.json: NAMING_ONLY (sem=0 struct=0 naming=1)
- fixed-eval-results.json: TIMING_ONLY (sem=0 struct=0 naming=0)
- fault-drill-observation.json: NAMING_ONLY (sem=0 struct=0 naming=1)
