# D25 RAG API 清单（初稿 v1）

> 模块：`D25_DKDCM_RAC` ｜ 端口：`8080` ｜ 上下文路径：无（根路径）
> 统一返回体：`Result<T>`，结构为 `code`（业务码）、`msg`（提示）、`data`（数据）
> 开发身份头：`X-Dev-User`（缺省 `dev-user`），仅为开发环境归属标识，**非生产鉴权**

---

## 一、通用约定

1. 所有接口响应均由 `Result` 包装，异常由 `GlobalExceptionHandler` 统一拦截；
2. 当前模块无登录/权限体系，管理类写接口不做身份鉴权（该风险已在历史文档标注，接入鉴权后统一加拦截）；
3. `message` 受 `@NotBlank` 与 `@Size(max=2000)` 校验，业务可配置上限 `app.ai.max-message-length`；
4. 仅在 `ChatRequest.retrieveEnabled=true` 时执行知识检索与引用标注。

### 端点总览（共 14 个）

| # | 方法 | 路径 | 用途 |
|---|------|------|------|
| 1 | POST | `/api/v1/ai/chat` | 同步对话（普通/角色） |
| 2 | POST | `/api/v1/ai/chat/stream` | SSE 流式对话 |
| 3 | GET | `/api/v1/ai/health` | 健康检查 |
| 4 | GET | `/api/v1/ai/sessions` | 会话分页查询 |
| 5 | GET | `/api/v1/ai/sessions/{sessionId}/messages` | 会话消息分页 |
| 6 | GET | `/api/v1/ai/prompts` | 模板列表 |
| 7 | GET | `/api/v1/ai/prompts/{templateId}` | 模板详情 |
| 8 | GET | `/api/v1/ai/prompts/{templateId}/versions` | 模板版本历史 |
| 9 | POST | `/api/v1/ai/prompts` | 创建模板 |
| 10 | PUT | `/api/v1/ai/prompts/{templateId}` | 更新模板（版本自增） |
| 11 | PATCH | `/api/v1/ai/prompts/{templateId}/status` | 启用/停用模板 |
| 12 | DELETE | `/api/v1/ai/prompts/{templateId}` | 逻辑删除模板 |
| 13 | GET | `/api/v1/ai/roles` | 可对话角色清单 |
| 14 | POST | `/api/v1/equipment/chat` | 设备客服/工程师对话 |

---

## 二、AI 对话接口

### 1. POST `/api/v1/ai/chat` — 同步对话

- 请求头：`Content-Type: application/json`，可选 `X-Dev-User`
- 请求体 `ChatRequest`：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `message` | string | 是 | 提问内容，非空白，最长 2000 |
| `scene` | string | 否 | 角色场景，填写后走角色链路 |
| `sessionId` | string | 否 | 会话标识，为空则新建并回传 |
| `variables` | object | 否 | 模板变量，键须为模板声明变量 |
| `thinkingEnabled` | boolean | 否 | 思考模式开关 |
| `reasoningEffort` | string | 否 | 白名单 LOW/HIGH/MAX，当前服务端显式拒绝 |
| `showReasoning` | boolean | 否 | 是否返回模型原始推理内容 |
| `retrieveEnabled` | boolean | 否 | 是否启用知识检索与引用 |

- 响应：`Result<Object>`，普通链路为 `ChatReplyResponse`，角色链路为 `RoleChatReplyResponse`；
- 备注：轮次开始落 PENDING，成功置 COMPLETED，失败置 FAILED。

### 2. POST `/api/v1/ai/chat/stream` — SSE 流式对话

- 产生类型：`text/event-stream;charset=UTF-8`
- 请求体：同 `ChatRequest`
- 响应：`SseEmitter` 命名事件流（见第四节）
- 备注：助手内容内存累积，complete 时全文落库；客户端断开且有部分内容置 INTERRUPTED。

### 3. GET `/api/v1/ai/health` — 健康检查

- 响应：`Result<Map>`，含 `application`、`mode`、`contextWindowMessages`、`devIdentity`。

---

## 三、历史与模板接口

### 4. GET `/api/v1/ai/sessions` — 会话分页

- 参数：`page`（默认 1）、`size`（默认 20）
- 响应：`Result<PageView<SessionView>>`，按最近消息时间倒序。

### 5. GET `/api/v1/ai/sessions/{sessionId}/messages` — 消息分页

- 路径参数：`sessionId`；参数：`page`、`size`
- 响应：`Result<PageView<MessageView>>`，先校验归属。

### 6. GET `/api/v1/ai/prompts` — 模板列表

- 参数：`scene`、`status`、`keyword`（均可选）
- 响应：`Result<List<PromptTemplateSummary>>`。

### 7. GET `/api/v1/ai/prompts/{templateId}` — 模板详情

- 响应：`Result<PromptTemplateView>`，含正文与变量声明。

### 8. GET `/api/v1/ai/prompts/{templateId}/versions` — 版本历史

- 响应：`Result<List<PromptTemplateVersionView>>`，最新版本在前。

### 9. POST `/api/v1/ai/prompts` — 创建模板

- 请求体：`PromptTemplateUpsertRequest`（`@Valid` 校验）
- 响应：`Result<PromptTemplateView>`。

### 10. PUT `/api/v1/ai/prompts/{templateId}` — 更新模板

- 请求体：`PromptTemplateUpsertRequest`
- 响应：`Result<PromptTemplateView>`，版本号自增并写入版本快照。

### 11. PATCH `/api/v1/ai/prompts/{templateId}/status` — 启停

- 请求体：`TemplateStatusUpdateRequest`（目标 status）
- 响应：`Result<PromptTemplateView>`。

### 12. DELETE `/api/v1/ai/prompts/{templateId}` — 逻辑删除

- 响应：`Result<Map>`，返回 `templateId` 与 `deleted=true`，数据不物理删除。

### 13. GET `/api/v1/ai/roles` — 角色清单

- 响应：`Result<List<PromptTemplateSummary>>`，返回启用中的可对话角色。

---

## 四、设备领域接口

### 14. POST `/api/v1/equipment/chat` — 设备对话

- 请求体：`ChatRequest`
- 响应：`Result<EquipmentChatResponse>`，含 `answer`、`clarificationRequired`、`knownFacts`、`missingInformation`、`possibleDirections`、`candidateExtraction`（只读候选，不落正式工单）、`citations`；
- 备注：该路径在通用访问日志中被排除（`/api/v1/equipment/chat`），TraceId 仍生效。

---

## 五、SSE 命名事件清单

| 事件名 | 载荷类型 | 时机 | 说明 |
|--------|----------|------|------|
| `start` | `SsePayloads.Start` | 流开始 | 携带 requestId、mode、model、sessionId、角色溯源、思考标志 |
| `thinking` | `SsePayloads.Thinking` | 推理增量 | 仅 `showReasoning=true` 时发送，独立于答案 |
| `delta` | `SsePayloads.Delta` | 答案增量 | `seq` 从 0 递增，前端按序追加 |
| `citations` | `SsePayloads.Citations` | complete 前 | 只含注册表中的真实来源 |
| `complete` | `SsePayloads.Complete` | 正常结束 | 汇总 seq、totalChars、mode、model |
| `error` | `SsePayloads.Error` | 异常 | 安全错误码与固定文案，不含堆栈/密钥 |

---

## 六、待确认与后续扩展

1. 生产化需补统一鉴权（当前写接口裸奔），建议在拦截器层统一收敛；
2. 真实 RAG 接入后可能新增文档/语料管理接口（上传、切分、重建索引），本清单未包含；
3. 服务间调用（OpenFeign/`@LoadBalanced`）尚未暴露，属后续微服务迭代范围。
