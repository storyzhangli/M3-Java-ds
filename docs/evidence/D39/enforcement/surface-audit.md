@(#) C18 暴露面收口审计（D39-R01）
基线分级：37 handler 映射（探测门禁 P0 落盘 endpoint-inventory.csv；"49" 为 @RequestMapping 计数口径含
方法级重复与前缀行，映射级实测 37，两口径差异在 D39-01 文档裁决）。
ADMIN_ONLY=10 DOCUMENT_SCOPE=7 FILTER_REQUIRED=5 OWNER_SCOPED=2 OWNER_SCOPED+PUBLIC_STATIC=3 OUT_OF_SCOPE=10

== R01 增量收口（本轮实测新增缺口与修复）==
1. HistoryController /api/v1/ai/sessions(+/messages)：P3 裁决维持骨架 X-Dev-User 归属；R01 端到端矩阵
   T4 复核发现持任意 token 者可用 X-Dev-User 头读他人 2431 条遗留会话——缺口成立。
   修复：access 开启时 owner 一律取 AuthContext.userId（可信身份，request attribute），
   X-Dev-User 不参与归属；access 关闭保持骨架行为（零差异）。
   复验：T4 四用例 PASS（外域探测集 200 条 leak=0；userA 仅见自身 1 条；no-token 4001）。
2. reconcile USER 拒绝断言补充：T9 user-role-reconcile-denied PASS（4001 该操作仅限管理员）。

== 全类抽样记录（按分级）==
{"Http":"POST","Path":"/api/v1/knowledge/retrieval/search","Controller.Method":"KnowledgeRetrievalController.search","Classification":"FILTER_REQUIRED","理由":"PG 知识检索主入口；Top-K 前权限谓词下推"}
{"Http":"POST","Path":"/api/v1/rag/answer","Controller.Method":"RagController.answer","Classification":"FILTER_REQUIRED","理由":"RAG 复用 SimilarityRetrievalService；只消费已过滤结果"}
{"Http":"POST","Path":"/api/v1/hybrid/retrieval/search","Controller.Method":"HybridSearchController.search","Classification":"FILTER_REQUIRED","理由":"双路融合入口；两路先过滤+RRF 闭包"}
{"Http":"POST","Path":"/api/v1/hybrid/rag/answer","Controller.Method":"HybridRagController.answer","Classification":"FILTER_REQUIRED","理由":"hybrid RAG 继承链；同上"}
{"Http":"POST","Path":"/api/v1/two/knowledge/retrieval/search","Controller.Method":"TwoKnowledgeRetrievalController.search","Classification":"FILTER_REQUIRED","理由":"two_ 检索共享 SimilarityRetrievalService 类；权限谓词经构造注入就位（真实端到端 NOT RUN 预算约束）"}
{"Http":"POST","Path":"/api/v1/knowledge/documents","Controller.Method":"KnowledgeDocumentController.register","Classification":"ADMIN_ONLY","理由":"文档写操作（登记）"}
{"Http":"POST","Path":"/api/v1/knowledge/documents/{documentId}/import","Controller.Method":"KnowledgeDocumentController.importFile","Classification":"ADMIN_ONLY","理由":"文档写操作（导入）"}
{"Http":"POST","Path":"/api/v1/knowledge/retrieval/corpus/index","Controller.Method":"KnowledgeRetrievalController.indexCorpus","Classification":"ADMIN_ONLY","理由":"语料入库运维（写）"}
{"Http":"POST","Path":"/api/v1/two/knowledge/retrieval/corpus/index","Controller.Method":"TwoKnowledgeRetrievalController.indexCorpus","Classification":"ADMIN_ONLY","理由":"two_ 入库运维（写；预算门禁另计）"}
{"Http":"POST","Path":"/api/v1/hybrid/index/rebuild","Controller.Method":"HybridSearchController.rebuild","Classification":"ADMIN_ONLY","理由":"倒排重建运维"}
{"Http":"POST","Path":"/api/v1/knowledge/documents/{documentId}/vector-index","Controller.Method":"VectorIndexController.index","Classification":"ADMIN_ONLY","理由":"向量写入运维"}
{"Http":"DELETE","Path":"/api/v1/knowledge/documents/{documentId}/vector-index","Controller.Method":"VectorIndexController.delete","Classification":"ADMIN_ONLY","理由":"向量删除运维"}
{"Http":"POST","Path":"/api/v1/knowledge/documents/{documentId}/vector-index/recover","Controller.Method":"VectorIndexController.recover","Classification":"ADMIN_ONLY","理由":"恢复运维"}
{"Http":"POST","Path":"/api/v1/knowledge/documents/{documentId}/vector-index/retry","Controller.Method":"VectorIndexController.retry","Classification":"ADMIN_ONLY","理由":"重试运维"}
{"Http":"POST","Path":"/api/v1/knowledge/vector-index/reconcile","Controller.Method":"VectorIndexController.reconcile","Classification":"ADMIN_ONLY","理由":"对账运维（T9 admin 可见性通道）"}
{"Http":"GET","Path":"/api/v1/knowledge/documents/{documentId}","Controller.Method":"KnowledgeDocumentController.detail","Classification":"DOCUMENT_SCOPE","理由":"文档级范围校验后返回元数据"}
{"Http":"GET","Path":"/api/v1/knowledge/documents/{documentId}/pages","Controller.Method":"KnowledgeDocumentController.pages","Classification":"DOCUMENT_SCOPE","理由":"含原文正文"}
{"Http":"GET","Path":"/api/v1/knowledge/documents/{documentId}/chunks","Controller.Method":"KnowledgeDocumentChunkingController.preview","Classification":"DOCUMENT_SCOPE","理由":"含切分正文"}
{"Http":"GET","Path":"/api/v1/knowledge/documents/{documentId}/chunks/compare","Controller.Method":"KnowledgeDocumentChunkingController.compare","Classification":"DOCUMENT_SCOPE","理由":"含切分正文"}
{"Http":"GET","Path":"/api/v1/knowledge/documents/{documentId}/embeddings","Controller.Method":"EmbeddingController.embedDocument","Classification":"DOCUMENT_SCOPE","理由":"含向量与内容"}
{"Http":"GET","Path":"/api/v1/knowledge/documents/{documentId}/vector-index","Controller.Method":"VectorIndexController.status","Classification":"DOCUMENT_SCOPE","理由":"任务状态含 documentId 归属信息"}
{"Http":"GET","Path":"/api/v1/knowledge/documents/{documentId}/vector-index/chunks","Controller.Method":"VectorIndexController.chunks","Classification":"DOCUMENT_SCOPE","理由":"含 chunk 正文"}
{"Http":"POST","Path":"/api/v1/ai/chat","Controller.Method":"ChatController.chat","Classification":"OWNER_SCOPED+PUBLIC_STATIC","理由":"owner 从 AuthContext（enabled 时）；retrieveEnabled 走 classpath 教学语料（静态公开夹具；断言不含 d39- 夹具/权限标签）"}
{"Http":"POST","Path":"/api/v1/ai/chat/stream","Controller.Method":"ChatStreamController.streamChat","Classification":"OWNER_SCOPED+PUBLIC_STATIC","理由":"SSE 分支；授权快照在请求线程内传递"}
{"Http":"POST","Path":"/api/v1/equipment/chat","Controller.Method":"EquipmentChatController.chat","Classification":"OWNER_SCOPED+PUBLIC_STATIC","理由":"同 classpath 语料口径"}
{"Http":"GET","Path":"/api/v1/ai/sessions","Controller.Method":"HistoryController.listSessions","Classification":"OWNER_SCOPED","理由":"owner 隔离（enabled 时 owner=AuthContext.userId）"}
{"Http":"GET","Path":"/api/v1/ai/sessions/{sessionId}/messages","Controller.Method":"HistoryController.listMessages","Classification":"OWNER_SCOPED","理由":"requireOwnedSession 语义复用；T4 测试面"}
{"Http":"GET","Path":"/api/v1/ai/health","Controller.Method":"ChatController.health","Classification":"OUT_OF_SCOPE","理由":"健康信息不含知识内容"}
{"Http":"GET","Path":"/api/v1/embeddings/model","Controller.Method":"EmbeddingController.model","Classification":"OUT_OF_SCOPE","理由":"模型元信息不含知识内容"}
{"Http":"GET","Path":"/api/v1/ai/prompts","Controller.Method":"PromptTemplateController.list","Classification":"OUT_OF_SCOPE","理由":"全局配置资产非知识（D6 裁决）"}
{"Http":"GET","Path":"/api/v1/ai/prompts/{templateId}","Controller.Method":"PromptTemplateController.detail","Classification":"OUT_OF_SCOPE","理由":"同上；内容断言不含 d39- 夹具数据"}
{"Http":"GET","Path":"/api/v1/ai/prompts/{templateId}/versions","Controller.Method":"PromptTemplateController.versions","Classification":"OUT_OF_SCOPE","理由":"同上"}
{"Http":"POST","Path":"/api/v1/ai/prompts","Controller.Method":"PromptTemplateController.create","Classification":"OUT_OF_SCOPE","理由":"配置写操作非知识暴露面（本轮不扩权收口）"}
{"Http":"PUT","Path":"/api/v1/ai/prompts/{templateId}","Controller.Method":"PromptTemplateController.update","Classification":"OUT_OF_SCOPE","理由":"同上"}
{"Http":"PATCH","Path":"/api/v1/ai/prompts/{templateId}/status","Controller.Method":"PromptTemplateController.changeStatus","Classification":"OUT_OF_SCOPE","理由":"同上"}
{"Http":"DELETE","Path":"/api/v1/ai/prompts/{templateId}","Controller.Method":"PromptTemplateController.delete","Classification":"OUT_OF_SCOPE","理由":"同上"}
{"Http":"GET","Path":"/api/v1/ai/roles","Controller.Method":"PromptTemplateController.roles","Classification":"OUT_OF_SCOPE","理由":"角色配置枚举非知识"}
