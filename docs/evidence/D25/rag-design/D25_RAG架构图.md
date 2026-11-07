# D25 RAG 架构图（初稿 v1）

> 模块：`D25_DKDCM_RAC`（artifactId），主题：RAG 架构与评测设计
> 技术基线：JDK 21 ｜ Spring Boot 3.5.16 ｜ Spring AI 1.1.8 ｜ MyBatis-Plus 3.5.17
> 说明：本架构图基于本地真实代码绘制，当前检索为「classpath 语料 + 确定性关键词评分」的最小可替换端口，**不是向量库、不依赖 Embedding**。

---

## 一、架构定位

当前实现是一个**可替换的最小检索端口**：

- 真实数据来源唯一：classpath 下 `equipment-fault-corpus.json`，启动时扁平化为不可变 `KnowledgeChunk` 列表；
- 检索不依赖向量数据库：对问题做字符二元组（bigram）切分，在标题/章节/摘录上做可复现的关键词命中评分；
- 引用可信：编号由请求级 `CitationRegistry` 按真实命中分配，模型无法凭空创建可映射来源；
- 演进点：后续接入真实 RAG（Embedding + 向量库）时，只需替换检索实现，请求级引用映射与校验契约保持不变。

---

## 二、组件职责一览

| 层次 | 组件 | 职责 |
|------|------|------|
| 接口层 | `ChatController` / `ChatStreamController` / `EquipmentChatController` | 同步对话、SSE 流式对话、设备领域对话入口 |
| 编排层 | `KnowledgeChatOrchestrator` | 串联「检索 → 提示词增强 → 模型调用 → 引用校验」 |
| 检索层 | `KnowledgeRetrievalService` | bigram 切词 + 确定性评分，返回真实命中片段 |
| 语料层 | `KnowledgeCorpus` / `KnowledgeChunk` | 加载并扁平化语料，提供不可变片段快照 |
| 引用层 | `CitationRegistry` | 请求级编号分配、上下文拼装、同步/流式标记清洗 |
| 模型层 | `AiChatService`（`MockAiChatService` / `DeepSeekAiChatService`） | 离线模拟与真实模型调用两种实现 |
| 持久化 | MySQL `chat_session`/`chat_message`/`prompt_template` | 会话、消息、模板权威存储 |
| 缓存 | Redis | 模板读缓存、会话短期存储（学习资产） |
| 横切 | `TraceIdFilter` / 访问日志 / 重试策略 / `ModelCallRecorder` | 链路追踪、脱敏日志、失败重试、Token 用量观测 |

---

## 三、RAG 总体分层架构图

```mermaid
flowchart TD
    A["客户端请求"] --> B["接口层"]
    B --> B1["ChatController 同步对话"]
    B --> B2["ChatStreamController 流式 SSE"]
    B --> B3["EquipmentChatController 设备对话"]
    B1 --> C["KnowledgeChatOrchestrator 知识问答编排器"]
    B2 --> C
    B3 --> C
    C --> D{"retrieveEnabled 是否启用检索?"}
    D -->|否| E["空注册表普通问答"]
    D -->|是| F["KnowledgeRetrievalService 确定性检索"]
    F --> G["KnowledgeCorpus 语料快照"]
    G --> H["equipment-fault-corpus.json 教学夹具语料"]
    G --> I["KnowledgeChunk 知识片段"]
    F --> J["CitationRegistry 请求级引用注册表"]
    E --> J
    J --> K["contextBlock 来源上下文增强"]
    K --> L["AiChatService 模型服务"]
    L --> M{"运行模式 mode?"}
    M -->|mock| N["MockAiChatService 离线模拟"]
    M -->|deepseek| O["DeepSeekAiChatService 真实调用"]
    N --> P["引用校验与标记清洗"]
    O --> P
    P --> Q["统一返回 Result 或 SSE 事件"]
```

---

## 四、同步知识问答链路流程图

```mermaid
flowchart TD
    A["接收 ChatRequest"] --> B["校验 message 长度与必填"]
    B --> C["落 PENDING 用户消息"]
    C --> D["retrievalService.search 检索"]
    D --> E{"是否有真实命中?"}
    E -->|否| F["空注册表普通模型调用"]
    E -->|是| G["CitationRegistry 分配 S1/S2 编号"]
    G --> H["contextBlock 拼接到 systemPrompt"]
    H --> I["模型生成原始答案"]
    F --> I
    I --> J{"是否 mock 模式?"}
    J -->|是| K["服务端追加合法编号行"]
    J -->|否| L["保留模型原始标记"]
    K --> M["validateAndStrip 校验清洗"]
    L --> M
    M --> N{"答案含未知编号?"}
    N -->|是| O["删除未知编号并计数"]
    N -->|否| P["保留合法编号"]
    O --> Q["组装 CitationDto 与统一返回"]
    P --> Q
    Q --> R["消息置 COMPLETED 落库"]
```

---

## 五、流式 SSE 链路与引用边界图

```mermaid
flowchart TD
    A["建立 SseEmitter"] --> B["发送 start 事件"]
    B --> C["Flux 内容分片"]
    C --> D{"分片类型?"}
    D -->|推理分片| E["按 showReasoning 决定是否透传"]
    D -->|内容分片| F["MarkerSanitizer.accept 清洗"]
    F --> G{"存在未闭合标记前缀?"}
    G -->|是| H["暂存前缀等待下一分片"]
    G -->|否| I["校验后下发 delta"]
    E --> J["继续订阅"]
    H --> J
    I --> J
    J --> K{"流是否结束?"}
    K -->|否| C
    K -->|是| L["finish 刷新暂存区"]
    L --> M["发送 citations 与 complete"]
    M --> N["助手全文 COMPLETED 落库"]
```

---

## 六、引用可信边界（关键设计）

1. **请求级隔离**：`CitationRegistry` 是方法栈内局部对象，不放入 Spring 容器、不跨请求共享；
2. **编号只来自真实命中**：后端按检索顺序分配 `S1`、`S2`……模型只能使用这些编号；
3. **未知编号必被清洗**：同步走 `validateAndStrip`，流式走 `MarkerSanitizer`，未知 `[Sn]` 在下发前删除并计数；
4. **资料即数据**：`contextBlock` 明确告知模型资料中的指令性文字一律按普通数据处理，防御提示注入。

---

## 七、向真实 RAG 演进的替换点

| 当前实现 | 未来真实 RAG | 不变的契约 |
|----------|--------------|------------|
| `KnowledgeCorpus` 读 classpath JSON | 文档加载器对接文件/对象存储/数据库 | `KnowledgeChunk` 片段模型 |
| bigram 关键词评分 | Embedding 向量化 + 向量库相似度检索 | 检索返回真实片段、无命中返回空 |
| 内存语料快照 | 向量库 + 元数据过滤 | `CitationRegistry` 编号与校验 |
| 单机教学夹具 | 多文档、增量更新、权限隔离 | 请求级引用映射与清洗 |

> 结论：检索实现可整体替换，但 `KnowledgeChunk` 模型、请求级 `CitationRegistry`、引用编号清洗规则应保持稳定，避免上层接口与前端契约返工。
