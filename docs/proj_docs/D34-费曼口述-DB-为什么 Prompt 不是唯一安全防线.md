# 费曼口述：Spring AI 的核心 API

**技术基线：** JDK 21、Spring Boot 3.5.x、Spring AI 1.1.8、PostgreSQL 18.6、pgvector 0.8.x。

**应用场景：** 制造设备故障知识库与工单助手，从模型调用、向量检索、RAG 编排到 Tool Calling 的完整 Java 工程实现。

共一百六十道关键问题，分为十二个阶段：整体架构、ChatModel、ChatClient、Prompt 与结构化输出、EmbeddingModel、VectorStore、ETL Pipeline、Advisor、ChatMemory、Tool Calling 与 MCP、自动配置与可观测性、综合工程设计。

核心目标一句话：**Spring AI 把不同厂商的模型、向量库、工具统一成 Spring 风格的接口，业务代码面向接口编程，换模型、换库、换工具不改业务逻辑。**

---

## 一、Spring AI 整体架构与核心设计

**说话人1: 问题一，什么是 Spring AI？它主要解决 Java 应用开发大模型应用时的哪些问题？**

**说话人1: 答，** Spring AI 是 Spring 官方的 AI 应用开发框架，解决 Java 生态里调大模型的碎片化问题：不同厂商 API 不统一、Prompt 管理混乱、向量库接入成本高、RAG 要自己拼。它把这些抽象成 Spring 风格的接口，和 Spring Boot 自动配置无缝集成。

**说话人1: 问题二，Spring AI 的整体架构由哪些核心模块组成？**

**说话人1: 答，** 六大模块：ChatModel（对话模型）、EmbeddingModel（向量模型）、VectorStore（向量存储）、RAG/ETL Pipeline、Advisor（横切拦截器）、Tool Calling（工具调用）。底层是各厂商 Starter，上层是统一 API。

**说话人1: 问题三，Spring AI 为什么采用统一 API 抽象不同厂商的大模型？**

**说话人1: 答，** 因为业务代码不应该绑死某一家模型。今天用 OpenAI，明天可能换 DeepSeek 或本地 Ollama。统一接口让切换只改配置，不改业务代码。这和 Spring 当年统一数据访问（JdbcTemplate 屏蔽不同数据库）是同一个思路。

**说话人1: 问题四，Spring AI 的 Model API、Chat Client API、Vector Store API 和 Advisors API 分别处于什么层次？**

**说话人1: 答，** Model API 是最底层，封装单次模型调用；Chat Client API 是上层 fluent 客户端，封装 Prompt 构建和 Advisor 链；Vector Store API 是向量存储抽象；Advisors API 是横切拦截器，插在 Chat Client 调用前后做增强。

```
ChatClient（高层 fluent API）
  ↓ 调用
Advisor Chain（RAG、记忆、日志等横切）
  ↓ 调用
ChatModel / EmbeddingModel（底层模型接口）
  ↓ 调用
各厂商 SDK（OpenAI、Ollama、pgvector...）
```

**说话人1: 问题五，Spring AI 如何通过 Spring Boot 自动配置完成模型客户端和相关组件的初始化？**

**说话人1: 答，** 引入 Starter 后，Spring Boot 根据 application.yml 里的配置自动创建对应的 ChatModel、EmbeddingModel、VectorStore Bean。你只要 `@Autowired` 就能用。

```
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```

**说话人1: 问题六，Spring AI 的 Starter、AutoConfiguration 和核心 API 之间是什么关系？**

**说话人1: 答，** Starter 是依赖打包；AutoConfiguration 是 Spring Boot 的自动配置类，根据条件创建 Bean；核心 API 是这些 Bean 的接口定义。引入 Starter → AutoConfiguration 生效 → Bean 注入 → 业务代码用接口。

**说话人1: 问题七，Spring AI 的 API 抽象与具体模型厂商 SDK 有什么区别？**

**说话人1: 答，** 厂商 SDK 是各厂自己的 HTTP 客户端，参数和返回格式都不一样。Spring AI 抽象层把这些统一成 `ChatModel.call()`、`EmbeddingModel.embed()`，业务代码不直接碰厂商 SDK。

**说话人1: 问题八，Spring AI 如何在统一接口的基础上保留不同模型厂商的特有能力？**

**说话人1: 答，** 通过 `ChatOptions` 扩展。统一接口传通用参数（temperature、maxTokens），专有参数用各厂的 Options 子类（如 `OpenAiChatOptions`）传。既统一又不丢能力。

**说话人1: 问题九，Spring AI 中哪些组件负责模型调用，哪些组件负责业务编排？**

**说话人1: 答，** ChatModel、EmbeddingModel、VectorStore 负责实际调用；ChatClient、Advisor、RAG Service 负责业务编排。业务代码一般直接用 ChatClient，不直接调 ChatModel。

**说话人1: 问题十，Spring AI 的核心 API 如何协同完成一次 RAG 问答？**

**说话人1: 答，** ChatClient 接收用户问题 → Advisor 链里的 QuestionAnswerAdvisor 调 VectorStore 检索 → 把结果拼进 Prompt → ChatModel 生成答案 → 返回。整条链 Spring AI 帮你串好，也可以手动编排。

---

## 二、ChatModel API 与模型调用

**说话人1: 问题十一，什么是 ChatModel？它在 Spring AI 中承担什么职责？**

**说话人1: 答，** ChatModel 是对话模型的统一抽象接口，定义了 `call()` 和 `stream()` 两个核心方法。它是最底层的模型调用入口，屏蔽不同厂商差异。

```
public interface ChatModel extends Model<Prompt, ChatResponse> {
    ChatResponse call(Prompt prompt);
    Flux<ChatResponse> stream(Prompt prompt);
}
```

**说话人1: 问题十二，ChatModel 与具体模型厂商的 API 有什么关系？**

**说话人1: 答，** ChatModel 是接口，OpenAiChatModel、OllamaChatModel 等是实现。业务代码面向 ChatModel 接口编程，运行时注入哪个实现就用哪个模型。

**说话人1: 问题十三，ChatModel 的 call 方法执行什么操作？**

**说话人1: 答，** 接收一个 Prompt 对象，同步调用模型，返回一个 ChatResponse。整个过程阻塞，直到模型返回完整结果。

**说话人1: 问题十四，ChatModel 与 ChatClient 有什么区别？**

**说话人1: 答，** ChatModel 是底层接口，API 原始，要自己拼 Prompt、处理返回；ChatClient 是上层 fluent 客户端，提供 `.user()`、`.system()`、`.advisors()` 等链式方法，开发效率高。

**说话人1: 问题十五，为什么业务代码通常更适合使用 ChatClient，而不是直接调用 ChatModel？**

**说话人1: 答，** 因为 ChatClient 封装了 Prompt 构建、Advisor 链、结构化输出、流式处理等常用能力，业务代码写起来简洁。直接用 ChatModel 要自己拼 Message 列表、自己处理 Response，重复代码多。

**说话人1: 问题十六，ChatModel 的输入 Prompt 包含哪些内容？**

**说话人1: 答，** Prompt 是一个消息列表 + 调用选项。消息列表里有 SystemMessage、UserMessage、AssistantMessage；选项里有 model、temperature、maxTokens 等。

**说话人1: 问题十七，ChatModel 的输出 ChatResponse 包含哪些信息？**

**说话人1: 答，** 包含生成的文本内容、元数据（模型名、完成原因）、Token 使用量（promptTokens、completionTokens、totalTokens）。

**说话人1: 问题十八，Prompt、Message、ChatResponse 和 Generation 之间是什么关系？**

**说话人1: 答，** Prompt 包含一组 Message 和 ChatOptions；ChatResponse 包含一组 Generation；每个 Generation 有一个 Message 和元数据。Message 是消息单元，Generation 是模型一次返回的结果单元。

**说话人1: 问题十九，SystemMessage、UserMessage 和 AssistantMessage 分别承担什么职责？**

**说话人1: 答，** SystemMessage 是系统指令，定角色和规则；UserMessage 是用户提问；AssistantMessage 是模型之前的回答。多轮对话就是这三类消息交替排列。

**说话人1: 问题二十，ChatOptions 有什么作用？如何设置模型名称、Temperature 和 Max Tokens？**

**说话人1: 答，** ChatOptions 封装模型调用参数。通用接口里有 temperature、maxTokens；具体厂商的 Options 子类还有专有参数。

```
OpenAiChatOptions options = OpenAiChatOptions.builder()
    .model("gpt-4o")
    .temperature(0.1)
    .maxTokens(500)
    .build();
```

**说话人1: 问题二十一，ChatOptions 与具体模型厂商的专有参数有什么关系？**

**说话人1: 答，** ChatOptions 是顶层接口，各厂商实现自己的 Options 子类。通用参数在接口里定义，专有参数在子类扩展。传参时用接口类型，实际运行时根据模型实现解析。

**说话人1: 问题二十二，如何使用 ChatModel 实现一次最基础的同步模型调用？**

**说话人1: 答，**

```
@Autowired
private ChatModel chatModel;

String answer = chatModel.call(
    new UserMessage("INV-3000 报 E001 怎么处理")
).getResult().getOutput().getText();
```

**说话人1: 问题二十三，ChatModel 如何实现流式输出？同步调用与流式调用有什么区别？**

**说话人1: 答，**

```
Flux<String> stream = chatModel.stream(
    new UserMessage("E001 怎么处理")
).map(resp -> resp.getResult().getOutput().getText());
```

同步：等全部生成完返回完整字符串。流式：返回 Flux，逐 Token 推送，适合 SSE 场景。

**说话人1: 问题二十四，如何处理模型调用超时、限流、异常和响应失败？**

**说话人1: 答，** 用 Resilience4j 包一层：超时控制、重试、熔断。网络错误重试 2 次，4xx 错误不重试，5xx 熔断降级。不要让异常直接抛给用户。

**说话人1: 问题二十五，如何通过统一 ChatModel API 切换不同模型供应商？**

**说话人1: 答，** 改 application.yml 里的配置，换 Starter 依赖。业务代码一行不改。这就是面向接口编程的好处。

---

## 三、ChatClient API 与流式调用

**说话人1: 问题二十六，什么是 ChatClient？它解决了 ChatModel 直接调用中的哪些开发问题？**

**说话人1: 答，** ChatClient 是 Spring AI 的 fluent 风格高层客户端，解决三个问题：不用手动拼 Message 列表、不用手动处理 Response、内置 Advisor 链和结构化输出。类似 Spring 里 RestClient 封装 RestTemplate。

**说话人1: 问题二十七，ChatClient 与 Spring WebClient、RestClient 的 API 设计风格有什么相似之处？**

**说话人1: 答，** 都是 fluent builder 风格：`chatClient.prompt().user(...).system(...).call().content()`。和 WebClient 的 `.get().uri().retrieve().bodyToMono()` 风格一致，Spring 开发者一看就懂。

**说话人1: 问题二十八，ChatClient.Builder 的作用是什么？**

**说话人1: 答，** 用来构建 ChatClient 实例，可以设置默认系统提示词、默认 Advisor、默认 Options。构建一次，到处复用。

**说话人1: 问题二十九，ChatClient.create(chatModel) 与 ChatClient.builder(chatModel).build() 有什么区别？**

**说话人1: 答，** `create()` 是快速创建一个最简客户端，没有默认配置。`builder()` 可以链式设置默认 system、defaultAdvisors 等，适合复杂场景。生产上一般用 builder 建一个 Bean。

**说话人1: 问题三十，ChatClient 的 prompt()、call() 和 stream() 分别承担什么职责？**

**说话人1: 答，** `prompt()` 开始构建一次请求；`call()` 同步执行并返回完整结果；`stream()` 异步流式返回 Flux。

```
chatClient.prompt().user(q).call().content();      // 同步
chatClient.prompt().user(q).stream().content();    // 流式
```

**说话人1: 问题三十一，ChatClient 如何构建 System Message 和 User Message？**

**说话人1: 答，** 通过 `.system()` 和 `.user()` 两个方法分别设置。

```
chatClient.prompt()
    .system("你是设备维修助手")
    .user("E001 怎么处理")
    .call()
    .content();
```

**说话人1: 问题三十二，ChatClient 如何使用 .user() 和 .system() 设置提示词？**

**说话人1: 答，** 就是上面的写法。`.system()` 传系统指令字符串，`.user()` 传用户问题。支持传入 String 或带参数的 PromptTemplate。

**说话人1: 问题三十三，ChatClient 如何通过 Prompt Template 实现动态参数替换？**

**说话人1: 答，** Prompt 模板用 `{variable}` 占位符，运行时传参替换。

```
chatClient.prompt()
    .user("设备 {deviceModel} 报故障码 {errorCode}，如何排查？")
    .param("deviceModel", "INV-3000")
    .param("errorCode", "E001")
    .call()
    .content();
```

**说话人1: 问题三十四，如何使用 .param() 向 Prompt Template 传递变量？**

**说话人1: 答，** 就是上面的 `.param(key, value)`。模板里写 `{key}`，运行时替换。支持传 Map。

**说话人1: 问题三十五，ChatClient 如何通过 .options() 配置模型调用参数？**

**说话人1: 答，**

```
chatClient.prompt()
    .user(q)
    .options(OpenAiChatOptions.builder().temperature(0.1).maxTokens(500).build())
    .call()
    .content();
```

**说话人1: 问题三十六，ChatClient 如何通过 .entity() 将模型输出转换为 Java POJO？**

**说话人1: 答，**

```
Diagnosis diagnosis = chatClient.prompt()
    .user(q)
    .entity(Diagnosis.class);
```

Spring AI 自动让模型输出 JSON，再用 Jackson 反序列化成 Java 对象。这就是 Structured Output。

**说话人1: 问题三十七，entity() 与 content() 有什么区别？**

**说话人1: 答，** `content()` 返回 String，模型输出什么就是什么；`entity(Class)` 返回 Java 对象，内部做 JSON 解析和类型转换。需要结构化数据时用 entity。

**说话人1: 问题三十八，ChatClient 如何获取 ChatResponse、Metadata 和 Token Usage？**

**说话人1: 答，** 用 `.call().chatResponse()` 拿到完整 Response 对象，再从里面取元数据和 Token 用量。

```
ChatResponse resp = chatClient.prompt().user(q).call().chatResponse();
Usage usage = resp.getMetadata().getUsage();
```

**说话人1: 问题三十九，ChatClient 如何通过 .stream() 实现响应式流式输出？**

**说话人1: 答，**

```
Flux<String> stream = chatClient.prompt().user(q).stream().content();
```

返回 `Flux<String>`，每个元素是一个 Token 片段。适合 SSE 推给前端。

**说话人1: 问题四十，ChatClient 的 defaultSystem、defaultUser 和 defaultAdvisors 有什么作用？**

**说话人1: 答，** 在 Builder 上设置默认值，每次调用自动带上。比如默认系统提示词是"你是维修助手"，所有请求都自动加，不用每次写。

```
ChatClient.builder(chatModel)
    .defaultSystem("你是设备维修助手，严格基于知识库回答")
    .defaultAdvisors(new QuestionAnswerAdvisor(vectorStore))
    .build();
```

**说话人1: 问题四十一，ChatClient 的 mutate() 方法有什么用途？**

**说话人1: 答，** 基于现有 ChatClient 复制一个修改版，不改原 Bean。比如某个特殊请求需要换个 system 提示词，用 `mutate().system(...).build()` 临时改。

**说话人1: 问题四十二，如何设计可复用的 ChatClient Bean，避免每次请求重复构建客户端？**

**说话人1: 答，** 配置一个 @Bean：

```
@Bean
public ChatClient chatClient(ChatModel chatModel) {
    return ChatClient.builder(chatModel)
        .defaultSystem("你是设备维修助手")
        .build();
}
```

业务代码 `@Autowired ChatClient` 直接用。ChatClient 是线程安全的，可以单例。

**说话人1: 问题四十三，ChatClient 的同步调用与流式调用分别适合什么业务场景？**

**说话人1: 答，** 同步：后台任务、短答案、不需要实时展示的场景。流式：前端对话界面，用户要看到字一个一个出来，体验好。

---

## 四、Prompt、Message 与结构化输出 API

**说话人1: 问题四十四，什么是 Prompt？它与普通字符串有什么区别？**

**说话人1: 答，** Prompt 不只是一个字符串，它是结构化的输入：包含多条 Message（系统、用户、历史）+ 调用参数。普通字符串只是用户问题本身。

**说话人1: 问题四十五，Prompt 在 Spring AI 中由哪些核心对象组成？**

**说话人1: 答，** `List<Message>` 消息列表 + `ChatOptions` 调用参数。消息列表里每条消息有类型（System/User/Assistant）和内容。

**说话人1: 问题四十六，Message 接口及其不同实现分别代表什么消息类型？**

**说话人1: 答，** Message 是顶层接口，实现有 SystemMessage、UserMessage、AssistantMessage、ToolResponseMessage。每种对应对话中的一个角色。

**说话人1: 问题四十七，PromptTemplate 的作用是什么？**

**说话人1: 答，** 把带占位符的模板渲染成最终字符串。类似 JSTL 或 Mustache，用 `{var}` 占位，运行时传参替换。

**说话人1: 问题四十八，Spring AI 默认使用什么模板渲染机制？**

**说话人1: 答，** 默认用 Spring 自己的 `StringTemplate`，基于 `{variable}` 语法。不是 Mustache 或 Freemarker，是轻量的字符串替换。

**说话人1: 问题四十九，如何避免 Prompt Template 中变量替换与普通文本占位符产生冲突？**

**说话人1: 答，** 模板里的 `{...}` 会被当变量替换。如果文本里本来就有大括号（比如 JSON 示例），要转义或用其他方式处理。生产上复杂模板建议放外部文件，不要硬编码。

**说话人1: 问题五十，如何通过 Resource 加载外部 Prompt 模板？**

**说话人1: 答，**

```
Resource promptResource = new ClassPathResource("prompts/repair.st");
chatClient.prompt()
    .user(promptResource)
    .param("device", "INV-3000")
    .call()
    .content();
```

模板放 resources/prompts 目录，修改不用改代码。

**说话人1: 问题五十一，Structured Output 是什么？为什么需要将模型输出映射为 Java 对象？**

**说话人1: 答，** Structured Output 是让模型输出符合 JSON Schema 的结构化数据，再反序列化成 Java 对象。业务系统不能处理一段自由文本，需要拿到字段化的数据做后续逻辑。

**说话人1: 问题五十二，BeanOutputConverter 的作用是什么？**

**说话人1: 答，** 它会根据目标 Java 类生成对应的 JSON Schema，塞进 Prompt 里告诉模型"请按这个格式输出 JSON"。模型输出后，再用 Jackson 解析成对象。

**说话人1: 问题五十三，如何将模型返回的 JSON 内容转换为 Java DTO？**

**说话人1: 答，** 用 `.entity(DTO.class)`，Spring AI 内部自动完成 Schema 注入和 JSON 解析。

**说话人1: 问题五十四，如何处理模型输出格式错误、字段缺失和类型不匹配？**

**说话人1: 答，** 捕获转换异常，降级为文本模式重试一次。或者在 DTO 上加 Jackson 注解，允许字段缺失。生产上要做重试和兜底，不能让 JSON 解析失败直接抛 500。

**说话人1: 问题五十五，结构化输出转换成功是否意味着模型生成的数据一定符合业务事实？**

**说话人1: 答，** 不是。JSON 格式对了不代表内容对。模型可能编造零件号、错误步骤。结构化只是格式正确，业务正确性还要靠 RAG 上下文和后处理校验。

**说话人1: 问题五十六，如何结合 Bean Validation 对模型生成的结构化数据进行校验？**

**说话人1: 答，** DTO 字段上加 `@NotBlank`、`@Pattern` 等注解，转换后用 `Validator` 校验。不合法就重试或降级。

---

## 五、EmbeddingModel API 与向量化

**说话人1: 问题五十七，什么是 EmbeddingModel？它在 RAG 中承担什么职责？**

**说话人1: 答，** EmbeddingModel 把文本转成固定维度的向量。RAG 里两个用途：文档入库时把 Chunk 转向量存起来；用户 Query 进来时把问题转向量去搜。

**说话人1: 问题五十八，EmbeddingModel 与 ChatModel 有什么区别？**

**说话人1: 答，** EmbeddingModel 输出是 float[]（向量），没有对话能力；ChatModel 输出是自然语言文本。两个完全不同的模型，不能混用。

**说话人1: 问题五十九，EmbeddingModel 的 embed() 方法执行什么操作？**

**说话人1: 答，** 接收文本，返回向量。

```
float[] vector = embeddingModel.embed("INV-3000 E001 故障排查");
```

**说话人1: 问题六十，EmbeddingRequest 和 EmbeddingResponse 分别承担什么职责？**

**说话人1: 答，** EmbeddingRequest 封装输入文本和 Options；EmbeddingResponse 封装输出向量和元数据。单次调用直接用 `embed()` 简化方法，批量调用用 Request/Response。

**说话人1: 问题六十一，Embedding 与 EmbeddingModel 的关系是什么？**

**说话人1: 答，** EmbeddingModel 是模型接口，Embedding 是单次向量结果对象（含 float[] 和元数据）。Model 输出 Embedding。

**说话人1: 问题六十二，EmbeddingModel 如何将一段文本转换成固定维度的向量？**

**说话人1: 答，** 内部是一个神经网络，把文本编码成 N 维浮点数组。维度由模型决定，bge-small 是 384 维，OpenAI ada 是 1536 维。

**说话人1: 问题六十三，为什么相同模型对相同文本通常能够生成可比较的向量表示？**

**说话人1: 答，** 因为模型是确定性的（temperature=0），相同输入相同模型输出相同向量。这样缓存 Embedding 才有效。

**说话人1: 问题六十四，EmbeddingModel 的维度、模型版本和向量空间之间有什么关系？**

**说话人1: 答，** 模型决定向量空间，空间决定维度。换模型 = 换空间 = 维度可能变。同空间内向量才能比距离。

**说话人1: 问题六十五，如何通过 EmbeddingModel 实现文档 Chunk 的向量化？**

**说话人1: 答，** 批量调用：

```
List<float[]> vectors = embeddingModel.embedForResponse(chunkTexts)
    .getResults().stream().map(Embedding::getOutput).toList();
```

**说话人1: 问题六十六，如何通过 EmbeddingModel 实现用户 Query 的向量化？**

**说话人1: 答，** 单条调用：

```
float[] queryVector = embeddingModel.embed(queryText);
```

**说话人1: 问题六十七，为什么文档入库和在线查询必须使用兼容的 Embedding 模型？**

**说话人1: 答，** 不同模型向量空间不同，距离计算无意义。必须同模型同版本。

**说话人1: 问题六十八，如何通过批量 Embedding 降低模型调用开销？**

**说话人1: 答，** 一次 API 传多个文本，比一个个调省网络往返。Batch Size 32~128 按模型限制调。

**说话人1: 问题六十九，如何处理 Embedding 调用失败、超时和向量维度不匹配？**

**说话人1: 答，** 失败重试 2 次，超时降级 BM25。维度不匹配说明换了模型但没重建向量库，必须全量重建。

**说话人1: 问题七十，EmbeddingModel 的 Token 使用量和模型调用成本如何统计？**

**说话人1: 答，** EmbeddingResponse 的 Metadata 里有 Token 用量。Micrometer 埋点统计每次调用的 Token 数和延迟。

---

## 六、VectorStore API 与向量数据库

**说话人1: 问题七十一，什么是 VectorStore？它在 Spring AI 中承担什么职责？**

**说话人1: 答，** VectorStore 是向量存储的统一抽象，屏蔽不同向量库（pgvector、Milvus、Redis）的底层差异。提供 add()、delete()、similaritySearch() 三个核心方法。

**说话人1: 问题七十二，VectorStore 与 PostgreSQL、pgvector 之间是什么关系？**

**说话人1: 答，** VectorStore 是接口，PgVectorStore 是实现，底层用 PostgreSQL + pgvector 扩展。业务代码面向 VectorStore 接口编程。

**说话人1: 问题七十三，VectorStore 的 add()、delete() 和 similaritySearch() 分别执行什么操作？**

**说话人1: 答，** add() 接收 List<Document>，内部自动调 EmbeddingModel 生成向量，存入数据库；delete() 按 ID 或 Filter 删除；similaritySearch() 接收 SearchRequest，返回相似 Chunk。

**说话人1: 问题七十四，VectorStore 为什么能够屏蔽不同向量数据库之间的底层差异？**

**说话人1: 答，** 因为它定义了统一接口，每个向量库写一个实现。业务代码只调 `vectorStore.similaritySearch()`，底层是 pgvector 还是 Milvus 不关心。

**说话人1: 问题七十五，Spring AI 的 Document 对象包含哪些核心信息？**

**说话人1: 答，** id、content（文本）、metadata（Map，存 documentId、tenantId、page 等）。

**说话人1: 问题七十六，Document 的文本内容与 Metadata 分别承担什么职责？**

**说话人1: 答，** content 是要做 Embedding 的原文；metadata 是结构化属性，用于过滤、引用、权限控制。Metadata 不参与 Embedding，只存着。

**说话人1: 问题七十七，如何通过 VectorStore 将 Document 写入 PostgreSQL/pgvector？**

**说话人1: 答，**

```
Document doc = new Document("E001 故障排查步骤...", metadata);
vectorStore.add(List.of(doc));
```

**说话人1: 问题七十八，VectorStore 如何在内部使用 EmbeddingModel 生成文档向量？**

**说话人1: 答，** add() 方法内部自动注入 EmbeddingModel，对每个 Document 的 content 做 Embedding，再连同 metadata 一起写入数据库。业务代码不用自己调 Embedding。

**说话人1: 问题七十九，similaritySearch() 如何完成相似度检索？**

**说话人1: 答，** 接收 SearchRequest（含 query、topK、threshold、filter），内部对 query 做 Embedding，在向量库找最近邻，按 threshold 过滤，返回 Document 列表。

**说话人1: 问题八十，SearchRequest 是什么？它包含哪些核心配置？**

**说话人1: 答，** SearchRequest 是检索请求的配置对象，包含 query、topK、similarityThreshold、filterExpression。

```
SearchRequest.builder()
    .query("E001 怎么处理")
    .topK(8)
    .similarityThreshold(0.7)
    .filterExpression("tenant_id == 'TENANT-A'")
    .build();
```

**说话人1: 问题八十一，SearchRequest 如何设置 Top-K 和 similarityThreshold？**

**说话人1: 答，** 就是上面的 `.topK(8).similarityThreshold(0.7)`。

**说话人1: 问题八十二，如何通过 Metadata Filter 实现设备型号、租户和文档类型过滤？**

**说话人1: 答，** `.filterExpression("tenant_id == 'TENANT-A' AND device_model == 'INV-3000'")`。表达式语法由 VectorStore 实现决定，pgvector 是 Spring AI 自定义 DSL。

**说话人1: 问题八十三，为什么 Metadata Filter 不能代替完整的业务权限系统？**

**说话人1: 答，** Filter 只是检索条件，不做身份认证、不做 ACL 判断。权限计算是业务服务的事，Filter 只是把计算结果传进去。

**说话人1: 问题八十四，VectorStore 返回的 Document.getScore() 应该如何理解？**

**说话人1: 答，** 是该 Chunk 与 Query 的匹配分数，但具体含义（相似度还是距离、范围多少）取决于 VectorStore 实现。pgvector 返回的是距离，不是相似度。不要假设范围。

**说话人1: 问题八十五，Spring AI VectorStore 与直接编写 pgvector SQL 查询有什么区别？**

**说话人1: 答，** VectorStore 是高层抽象，自动处理 Embedding、序列化、索引；直接写 SQL 更灵活可控。生产复杂场景（混合检索、自定义 Rerank）可能需要直接写 SQL。

**说话人1: 问题八十六，如何通过 VectorStore 实现文档更新、删除和版本管理？**

**说话人1: 答，** VectorStore 只提供 add/delete 基础操作，版本管理要业务层自己做：STAGED → 原子切换 → 旧版本 ARCHIVED。VectorStore 不帮你管版本。

**说话人1: 问题八十七，如何保证向量入库与业务数据库中的文档状态保持一致？**

**说话人1: 答，** 文档主表和 Chunk 表在同一个 PostgreSQL 数据库里，用事务保证。业务状态更新和向量写入在同一个事务提交。

---

## 七、Document 与 ETL Pipeline API

**说话人1: 问题八十八，什么是 Spring AI ETL Pipeline？它解决了什么问题？**

**说话人1: 答，** ETL Pipeline 是文档入库的标准流程抽象：Read（读文件）→ Transform（清洗切块）→ Write（写向量库）。把文档入库的步骤标准化，不用自己拼代码。

**说话人1: 问题八十九，DocumentReader、DocumentTransformer 和 DocumentWriter 分别承担什么职责？**

**说话人1: 答，** Reader 把文件读成 Document；Transformer 对 Document 做清洗、切块、加 Metadata；Writer 把 Document 写入 VectorStore。

**说话人1: 问题九十，DocumentReader 如何从 PDF、Word 和 TXT 等文件中读取内容？**

**说话人1: 答，** `TikaDocumentReader` 自动识别格式，内部用 Apache Tika 解析。

```
TikaDocumentReader reader = new TikaDocumentReader("classpath:manual.pdf");
List<Document> docs = reader.get();
```

**说话人1: 问题九十一，DocumentTransformer 如何完成清洗、切块和 Metadata 处理？**

**说话人1: 答，** Transformer 是函数式接口，输入 List<Document>，输出 List<Document>。可以链式组合多个 Transformer。

**说话人1: 问题九十二，TokenTextSplitter 的作用是什么？**

**说话人1: 答，** 最常用的 Transformer，把长文档切成指定 Token 大小的 Chunk。

```
TokenTextSplitter splitter = TokenTextSplitter.builder()
    .chunkSize(800).minChunkSizeChars(350).build();
List<Document> chunks = splitter.apply(docs);
```

**说话人1: 问题九十三，为什么 Document 的 Metadata 应该在 Chunk 切分过程中得到保留？**

**说话人1: 答，** 切块后每个 Chunk 都要带上原始文档的 documentId、tenantId、fileName 等 Metadata，否则检索回来不知道来源。TokenTextSplitter 默认会继承父文档的 Metadata。

**说话人1: 问题九十四，DocumentWriter 如何将处理后的 Document 写入 VectorStore？**

**说话人1: 答，** `VectorStoreDocumentWriter` 接收 VectorStore，内部调 `vectorStore.add()`。

**说话人1: 问题九十五，如何使用 ETL Pipeline 串联文档读取、转换和写入？**

**说话人1: 答，**

```
List<Document> docs = reader.get();
docs = new TitleContentTransformer().apply(docs);
docs = new TokenTextSplitter().apply(docs);
vectorStore.add(docs);
```

Spring AI 1.1 还提供 `DocumentPipeline` 链式 API。

**说话人1: 问题九十六，ETL Pipeline 与在线 RAG 查询链路有什么区别？**

**说话人1: 答，** ETL 是离线写路径，批量处理，延迟不敏感；在线查询是读路径，秒级响应。两者通过 VectorStore 解耦。

**说话人1: 问题九十七，如何通过自定义 DocumentTransformer 实现企业级文档清洗？**

**说话人1: 答，** 实现 `DocumentTransformer` 接口，写自己的清洗逻辑：去页眉、去水印、表格还原、OCR 纠错。

```
public class CustomCleaner implements DocumentTransformer {
    @Override
    public List<Document> apply(List<Document> documents) {
        // 清洗逻辑
    }
}
```

**说话人1: 问题九十八，如何处理大文件解析、批量导入和部分失败？**

**说话人1: 答，** 大文件流式解析；批量导入走异步队列；部分失败记录失败文档，重试不影响其他文档。

---

## 八、Advisor API 与 RAG 编排

**说话人1: 问题九十九，什么是 Advisor？它在 ChatClient 调用链中处于什么位置？**

**说话人1: 答，** Advisor 是横切拦截器，插在 ChatClient 调用前后。类似 Spring AOP 的 Advice，可以在模型调用前修改 Prompt，调用后处理响应。

```
Request → Advisor1 → Advisor2 → ChatModel → Advisor2后 → Advisor1后 → Response
```

**说话人1: 问题一百，Advisor API 主要解决什么问题？**

**说话人1: 答，** 把 RAG 检索、对话记忆、日志、权限等横切逻辑从业务代码里抽出来，做成可复用的拦截器。业务代码只关心用户问题，横切能力自动生效。

**说话人1: 问题一百零一，CallAdvisor、StreamAdvisor 和 Advisor Chain 分别承担什么职责？**

**说话人1: 答，** CallAdvisor 处理同步调用；StreamAdvisor 处理流式调用；多个 Advisor 组成 Chain，按顺序执行。

**说话人1: 问题一百零二，Advisor 如何在模型调用前修改 Prompt？**

**说话人1: 答，** `before` 方法里拿到当前 Prompt，可以加消息、加上下文、改参数。QuestionAnswerAdvisor 就是在这里把检索结果塞进 User Message。

**说话人1: 问题一百零三，Advisor 如何在模型调用后处理响应？**

**说话人1: 答，** `after` 方法里拿到模型返回，可以后处理、打日志、加引用。

**说话人1: 问题一百零四，多个 Advisor 组成执行链时，执行顺序为什么很重要？**

**说话人1: 答，** 顺序错了会出问题。比如应该先加记忆再加 RAG 上下文，反过来 Prompt 就乱了。默认顺序是：记忆 → RAG → 日志。

**说话人1: 问题一百零五，defaultAdvisors() 与 advisors() 有什么区别？**

**说话人1: 答，** `defaultAdvisors()` 在 Builder 上设置，所有请求自动带；`advisors()` 在单次请求上设置，只对这次生效。

**说话人1: 问题一百零六，什么是 QuestionAnswerAdvisor？它如何实现基础 RAG？**

**说话人1: 答，** 它是 Spring AI 内置的 RAG Advisor，自动把 VectorStore 检索结果拼进 Prompt。一行配置实现 RAG。

```
ChatClient.builder(chatModel)
    .defaultAdvisors(new QuestionAnswerAdvisor(vectorStore))
    .build();
```

**说话人1: 问题一百零七，QuestionAnswerAdvisor 如何将 VectorStore 检索结果注入 Prompt？**

**说话人1: 答，** 在 before 阶段：拿用户 Query → 调 VectorStore.similaritySearch() → 把检索到的 Chunk 拼成上下文 → 塞进 Prompt。

**说话人1: 问题一百零八，使用 QuestionAnswerAdvisor 实现 RAG 有哪些优点和限制？**

**说话人1: 答，** 优点：一行代码搞定 RAG，快速原型。限制：不能自定义检索策略、不能加 Rerank、不能做复杂权限过滤。生产复杂场景不够用。

**说话人1: 问题一百零九，QuestionAnswerAdvisor 与手动编排 Retriever、Context Assembly 和 ChatClient 有什么区别？**

**说话人1: 答，** Advisor 自动黑盒，灵活度低；手动编排每一步可控，能加混合检索、Rerank、权限过滤、上下文去重。生产复杂系统一般手动编排。

**说话人1: 问题一百一十，什么是 RetrievalAugmentationAdvisor？它与 QuestionAnswerAdvisor 有什么区别？**

**说话人1: 答，** Spring AI 1.1 引入的新 RAG Advisor，比 QuestionAnswerAdvisor 更灵活，可以配置 Retriever、QueryTransformer、Rerank 等组件。QuestionAnswerAdvisor 是简化版。

**说话人1: 问题一百一十一，如何通过 Advisor 实现 RAG 检索、上下文增强和模型生成？**

**说话人1: 答，** 用 RetrievalAugmentationAdvisor，配置自定义 Retriever：

```
RetrievalAugmentationAdvisor advisor = RetrievalAugmentationAdvisor.builder()
    .retriever(vectorStore::similaritySearch)
    .build();
```

**说话人1: 问题一百一十二，如何通过自定义 Advisor 实现请求日志、权限检查和上下文处理？**

**说话人1: 答，** 实现 `CallAdvisor` 接口，重写 `adviseCall` 方法。在里面做日志、权限校验、Prompt 修改。

**说话人1: 问题一百一十三，如何避免多个 Advisor 之间发生参数覆盖和上下文冲突？**

**说话人1: 答，** 明确每个 Advisor 的职责，文档化执行顺序。共享数据用 AdvisorContext 传递，不要每个 Advisor 自己存全局状态。

---

## 九、ChatMemory API 与多轮对话

**说话人1: 问题一百一十四，为什么 ChatModel 本身不能自动记住之前的对话？**

**说话人1: 答，** 因为大模型是无状态的，每次调用都是独立的。你不把历史消息塞进 Prompt，它就不知道之前聊过什么。

**说话人1: 问题一百一十五，什么是 ChatMemory？它与普通数据库存储有什么区别？**

**说话人1: 答，** ChatMemory 是对话记忆的抽象，专门存某一会话的历史消息。它不是通用数据库，而是按 conversationId 管理消息窗口。

**说话人1: 问题一百一十六，ChatMemory、ChatMemoryRepository 和 ChatMemoryAdvisor 分别承担什么职责？**

**说话人1: 答，** ChatMemory 是接口，定义消息增删查；ChatMemoryRepository 是存储实现（内存、JDBC、Redis）；ChatMemoryAdvisor 是横切拦截器，自动把历史消息加进 Prompt。

**说话人1: 问题一百一十七，MessageWindowChatMemory 如何管理对话历史？**

**说话人1: 答，** 保留最近 N 条消息，超过窗口自动丢弃最早的。防止 Prompt 无限膨胀。

```
ChatMemory memory = MessageWindowChatMemory.builder()
    .maxMessages(20)
    .chatMemoryRepository(new InMemoryChatMemoryRepository())
    .build();
```

**说话人1: 问题一百一十八，如何通过 ChatMemory.CONVERSATION_ID 区分不同用户和会话？**

**说话人1: 答，** 每次调用传 conversationId：

```
chatClient.prompt()
    .user(q)
    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "user-123"))
    .call()
    .content();
```

**说话人1: 问题一百一十九，MessageChatMemoryAdvisor 如何将历史消息加入模型 Prompt？**

**说话人1: 答，** 在 before 阶段：根据 conversationId 从 ChatMemory 取历史消息，加到 Prompt 消息列表前面。

**说话人1: 问题一百二十，VectorStoreChatMemoryAdvisor 与 MessageChatMemoryAdvisor 有什么区别？**

**说话人1: 答，** MessageChatMemoryAdvisor 存最近 N 条窗口；VectorStoreChatMemoryAdvisor 把历史消息向量化存进 VectorStore，长对话时按相关性检索历史片段，不占满整个窗口。

**说话人1: 问题一百二十一，InMemoryChatMemoryRepository 与 JDBC、Redis 等持久化实现有什么区别？**

**说话人1: 答，** InMemory 重启就丢，适合测试；JDBC 存数据库，重启不丢；Redis 性能好，适合生产。

**说话人1: 问题一百二十二，为什么多轮对话需要限制历史消息数量？**

**说话人1: 答，** 历史太长 Prompt 就长，Token 成本高、延迟大，还会 Lost in the Middle。窗口控制在 10~20 条消息。

**说话人1: 问题一百二十三，如何防止不同用户之间发生会话记忆串用？**

**说话人1: 答，** conversationId 必须由服务端根据登录用户生成，不能由前端传。每个用户一个独立 conversationId，互不干扰。

**说话人1: 问题一百二十四，ChatMemory 与 RAG 知识库检索有什么区别？**

**说话人1: 答，** ChatMemory 存当前用户的对话历史，是短期上下文；RAG 检索企业知识库，是长期事实来源。两个互补，不替代。

**说话人1: 问题一百二十五，如何将 ChatMemory 与 QuestionAnswerAdvisor 组合实现多轮 RAG 问答？**

**说话人1: 答，**

```
ChatClient.builder(chatModel)
    .defaultAdvisors(
        MessageChatMemoryAdvisor.builder(memory).build(),
        new QuestionAnswerAdvisor(vectorStore)
    )
    .build();
```

两个 Advisor 一起用，自动多轮 + 自动 RAG。

---

## 十、Tool Calling、MCP 与扩展 API

**说话人1: 问题一百二十六，什么是 Tool Calling？它解决了大模型无法直接访问外部系统的什么问题？**

**说话人1: 答，** Tool Calling 让大模型可以决定调用外部工具（查数据库、调 API、执行函数），拿到结果后再继续生成答案。解决大模型只能输出文本、不能执行动作的局限。

**说话人1: 问题一百二十七，Spring AI 中如何通过 @Tool 将 Java 方法暴露给模型？**

**说话人1: 答，**

```
@Tool(description = "根据设备型号和故障码查询维修步骤")
public String getRepairSteps(String deviceModel, String errorCode) {
    // 业务逻辑
}
```

Spring AI 自动把方法签名和描述转成 Tool Schema，告诉模型可以调这个工具。

**说话人1: 问题一百二十八，ToolCallback 和 ToolDefinition 分别承担什么职责？**

**说话人1: 答，** ToolDefinition 描述工具的元信息（名字、描述、参数 Schema）；ToolCallback 是实际执行工具的回调。两者配合，模型知道有这个工具，Java 侧负责执行。

**说话人1: 问题一百二十九，模型请求调用工具与 Java 应用实际执行工具有什么区别？**

**说话人1: 答，** 模型只是输出"我要调这个工具、参数是什么"，真正执行是 Java 应用做的。模型不直接碰数据库，Java 侧执行完把结果返回给模型。这就是安全边界。

**说话人1: 问题一百三十，Tool Calling 的完整执行流程是什么？**

**说话人1: 答，**

```
用户提问
  ↓
模型判断需要调工具 → 输出工具名+参数
  ↓
Java 应用执行工具 → 拿到结果
  ↓
把工具结果喂回模型
  ↓
模型基于工具结果生成最终答案
```

**说话人1: 问题一百三十一，Tool Calling 与普通 Java 方法调用有什么区别？**

**说话人1: 答，** 普通方法是代码写死的；Tool Calling 是模型动态决定调不调、调哪个、传什么参数。流程由模型驱动，不是代码写死的。

**说话人1: 问题一百三十二，如何通过 ToolContext 向工具传递业务上下文？**

**说话人1: 答，** ToolContext 是一个 Map，可以存 userId、tenantId 等，工具方法里能拿到。

```
public String getWorkOrder(String orderId, ToolContext ctx) {
    String userId = ctx.getContext().get("userId", String.class);
}
```

**说话人1: 问题一百三十三，如何限制模型可以调用的工具，防止越权操作？**

**说话人1: 答，** 每次请求根据用户权限动态决定暴露哪些工具。模型能看到的工具列表就是用户有权用的。Java 侧执行时还要再校验一次权限。

**说话人1: 问题一百三十四，什么是 MCP？它解决了什么问题？**

**说话人1: 答，** MCP 是 Model Context Protocol，模型上下文协议。标准化模型和外部工具/数据源之间的连接方式，不用每个工具都自己写集成。类似 USB-C 接口，统一标准。

**说话人1: 问题一百三十五，MCP Client、MCP Server 和 Tool 之间是什么关系？**

**说话人1: 答，** MCP Server 暴露一组 Tool；MCP Client 是 AI 应用里的客户端，连接 MCP Server，把 Tool 注册进来。模型通过 Client 调 Server 上的工具。

**说话人1: 问题一百三十六，Spring AI 如何接入 MCP Server 提供的工具？**

**说话人1: 答，** 引入 MCP Client Starter，配置 MCP Server 地址，自动发现工具并注册。

**说话人1: 问题一百三十七，Tool Calling 与 MCP 有什么区别和联系？**

**说话人1: 答，** Tool Calling 是模型能力，决定"能不能调工具"；MCP 是协议标准，决定"怎么连外部工具"。MCP 是 Tool Calling 的一种标准化实现方式。

**说话人1: 问题一百三十八，如何将 RAG 检索能力与 Tool Calling 结合？**

**说话人1: 答，** 把 RAG 检索封装成一个 Tool：模型判断需要查知识库时，调这个工具，传入 Query，工具返回相关 Chunk。和自动 RAG Advisor 的区别是：模型自己决定要不要检索，而不是每次都检索。

**说话人1: 问题一百三十九，在制造设备故障知识库与工单助手中，哪些能力适合设计成 Tool？**

**说话人1: 答，** 查工单状态、创建工单、查询设备实时数据、查询备件库存、调用维修 SOP。这些需要访问业务系统的动作，适合做 Tool。纯知识问答走 RAG 就够了。

---

## 十一、自动配置、配置管理与可观测性

**说话人1: 问题一百四十，Spring AI 的自动配置机制如何创建 ChatModel、EmbeddingModel 和 VectorStore？**

**说话人1: 答，** 引入对应 Starter 后，Spring Boot AutoConfiguration 类根据 application.yml 的配置条件，自动创建 Bean。条件注解 `@ConditionalOnProperty` 控制是否生效。

**说话人1: 问题一百四十一，如何通过 application.yml 配置模型供应商、API Key 和模型名称？**

**说话人1: 答，**

```
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      chat:
        options:
          model: gpt-4o
          temperature: 0.1
```

**说话人1: 问题一百四十二，如何通过环境变量和外部配置中心管理敏感模型配置？**

**说话人1: 答，** API Key 不写配置文件，用环境变量注入：`OPENAI_API_KEY=sk-xxx`。生产用配置中心（Nacos、Apollo）管理，不进 Git。

**说话人1: 问题一百四十三，如何避免在 Java 业务代码中硬编码模型名称和 API Key？**

**说话人1: 答，** 模型名称放配置文件，API Key 走环境变量。业务代码面向接口，不出现具体模型名字符串。

**说话人1: 问题一百四十四，如何通过 @ConditionalOnMissingBean 自定义或替换 Spring AI 默认组件？**

**说话人1: 答，** 自动配置类用 `@ConditionalOnMissingBean`，意思是"用户没自己定义才用默认的"。你自己 @Bean 一个 ChatModel，默认的就不生效。

**说话人1: 问题一百四十五，如何查看 Spring AI 自动配置是否生效？**

**说话人1: 答，** 开启 `--debug` 或看 `/actuator/conditions`，能看到哪些 AutoConfiguration 匹配了、哪些没匹配。

**说话人1: 问题一百四十六，Spring AI 如何通过 Micrometer 提供模型调用指标？**

**说话人1: 答，** Spring AI 自动埋点：模型调用耗时、Token 用量、错误率。接入 Micrometer + Prometheus 就能在 Grafana 看。

**说话人1: 问题一百四十七，如何追踪 ChatClient、ChatModel、EmbeddingModel 和 VectorStore 的调用耗时？**

**说话人1: 答，** Micrometer Timer 自动埋点，或者手动在业务代码里记录每段耗时。

**说话人1: 问题一百四十八，如何记录模型 Token 使用量、调用次数和异常信息？**

**说话人1: 答，** 从 ChatResponse 的 Metadata 拿 Usage，Micrometer Counter 统计调用次数。异常打错误日志。

**说话人1: 问题一百四十九，为什么生产环境不能无差别记录完整 Prompt、用户隐私和敏感上下文？**

**说话人1: 答，** 因为 Prompt 里可能有用户隐私、企业机密。日志要脱敏，敏感字段不打全量。合规要求（等保、GDPR）也不允许随意记录。

**说话人1: 问题一百五十，如何通过 TraceId 定位一次完整 RAG 请求的性能问题？**

**说话人1: 答，** 每次请求一个 TraceId，贯穿 ChatClient → Advisor → ChatModel → EmbeddingModel → VectorStore。Grafana Tempo 或 Jaeger 看链路。

---

## 十二、核心 API 综合应用与工程设计

**说话人1: 问题一百五十一，如何使用 ChatClient、ChatModel 和 EmbeddingModel 构建一个最小 AI 问答服务？**

**说话人1: 答，**

```
@RestController
public class ChatController {
    private final ChatClient chatClient;
    public ChatController(ChatClient.Builder builder) {
        this.chatClient = builder.defaultSystem("你是维修助手").build();
    }
    @GetMapping("/chat")
    public String chat(@RequestParam String q) {
        return chatClient.prompt().user(q).call().content();
    }
}
```

**说话人1: 问题一百五十二，如何使用 DocumentReader、DocumentTransformer 和 VectorStore 构建知识入库流程？**

**说话人1: 答，**

```
List<Document> docs = new TikaDocumentReader(file).get();
docs = new TokenTextSplitter().apply(docs);
vectorStore.add(docs);
```

**说话人1: 问题一百五十三，如何使用 EmbeddingModel、VectorStore 和 SearchRequest 实现完整检索流程？**

**说话人1: 答，**

```
SearchRequest req = SearchRequest.builder()
    .query(query).topK(8).similarityThreshold(0.7)
    .filterExpression("tenant_id == 'TENANT-A'")
    .build();
List<Document> results = vectorStore.similaritySearch(req);
```

**说话人1: 问题一百五十四，如何将 Retriever、Context Assembly、Prompt 和 ChatClient 组合成可控的 RAG 调用链？**

**说话人1: 答，** 手动编排：

```
public Answer ragChat(String query, String userId) {
    String rewritten = queryRewriter.rewrite(query, history);
    List<Document> chunks = retriever.retrieve(rewritten, userId);
    List<Document> filtered = contextAssembler.assemble(chunks, tokenBudget);
    String prompt = promptBuilder.build(filtered, query);
    String answer = chatClient.prompt().user(prompt).call().content();
    return postProcessor.enrich(answer, chunks);
}
```

每一步可控，能加 Rerank、权限、去重。

**说话人1: 问题一百五十五，如何根据业务需求选择 Advisor 自动编排还是手动编排？**

**说话人1: 答，** 简单场景、快速原型用 Advisor；生产复杂场景（混合检索、Rerank、权限过滤、自定义上下文组装）用手动编排。Advisor 是起点，不是终点。

**说话人1: 问题一百五十六，如何设计 Spring AI 核心 API 的依赖注入关系，避免组件职责混乱？**

**说话人1: 答，** 分层：Controller → RagService → Retriever / ContextAssembler / ChatClient。每个类单一职责，不要一个 Service 里调所有东西。

**说话人1: 问题一百五十七，如何为 ChatClient、EmbeddingModel 和 VectorStore 编写单元测试与集成测试？**

**说话人1: 答，** 单元测试用 Mock 模型；集成测试用 Testcontainers 起真实 pgvector，或用 Spring AI 的 Test Model。

**说话人1: 问题一百五十八，如何使用 Mock 模型测试业务逻辑，避免测试过程依赖真实模型服务？**

**说话人1: 答，** Spring AI 提供 `MockChatModel`、`MockEmbeddingModel`，可以预设返回值。测试不花真钱，不依赖外部 API。

**说话人1: 问题一百五十九，Spring AI 核心 API 升级时，如何评估版本兼容性和潜在破坏性变更？**

**说话人1: 答，** 看官方 Migration Guide，跑全量测试，注意 API 废弃提示。Spring AI 1.x 到 1.1 有不少 Advisor 和 RAG API 变动，升级要仔细对照。

**说话人1: 问题一百六十，如果让你基于 Spring AI 1.1.8 从零构建制造设备故障知识库与工单助手，你会如何选择和组合核心 API？**

**说话人1: 答，** 架构选择：

```
入库层：TikaDocumentReader → TokenTextSplitter → PgVectorStore
检索层：自定义 Retriever（向量 + BM25 混合）+ Rerank
编排层：手动编排 RagService（不用 QuestionAnswerAdvisor，因为要加权限和 Rerank）
生成层：ChatClient + MessageChatMemoryAdvisor（多轮对话）
工具层：@Tool 暴露查工单、创建工单等业务能力
治理层：Micrometer 监控 + TraceId 全链路 + 环境变量管 Key
```

核心原则：简单能力用 Spring AI 内置，复杂能力自己编排，面向接口编程，随时可换模型和向量库。

---

## 十三、参考资料

### Spring AI 官方文档

1. **Spring AI API | Spring AI 核心 API 总览**
[https://docs.spring.io/spring-ai/reference/1.1/api/](https://docs.spring.io/spring-ai/reference/1.1/api/)
2. **Chat Client API | 聊天客户端 API**
[https://docs.spring.io/spring-ai/reference/1.1/api/chatclient.html](https://docs.spring.io/spring-ai/reference/1.1/api/chatclient.html)
3. **Chat Models | 聊天模型 API**
[https://docs.spring.io/spring-ai/reference/1.1/api/chatmodel.html](https://docs.spring.io/spring-ai/reference/1.1/api/chatmodel.html)
4. **Embedding Models | 嵌入模型 API**
[https://docs.spring.io/spring-ai/reference/1.1/api/embeddings.html](https://docs.spring.io/spring-ai/reference/1.1/api/embeddings.html)
5. **Vector Databases | 向量数据库 API**
[https://docs.spring.io/spring-ai/reference/1.1/api/vectordbs.html](https://docs.spring.io/spring-ai/reference/1.1/api/vectordbs.html)
6. **ETL Pipeline | ETL 数据处理管道**
[https://docs.spring.io/spring-ai/reference/1.1/api/etl-pipeline.html](https://docs.spring.io/spring-ai/reference/1.1/api/etl-pipeline.html)
7. **Advisors API | 顾问 API**
[https://docs.spring.io/spring-ai/reference/1.1/api/advisors.html](https://docs.spring.io/spring-ai/reference/1.1/api/advisors.html)
8. **Retrieval Augmented Generation (RAG) | 检索增强生成**
[https://docs.spring.io/spring-ai/reference/1.1/api/retrieval-augmented-generation.html](https://docs.spring.io/spring-ai/reference/1.1/api/retrieval-augmented-generation.html)
9. **Chat Memory | 对话记忆 API**
[https://docs.spring.io/spring-ai/reference/1.1/api/chat-memory.html](https://docs.spring.io/spring-ai/reference/1.1/api/chat-memory.html)
10. **Tool Calling | 工具调用 API**
[https://docs.spring.io/spring-ai/reference/1.1/api/tools.html](https://docs.spring.io/spring-ai/reference/1.1/api/tools.html)
11. **MCP Overview | 模型上下文协议总览**
[https://docs.spring.io/spring-ai/reference/1.1/api/mcp/mcp-overview.html](https://docs.spring.io/spring-ai/reference/1.1/api/mcp/mcp-overview.html)
12. **Observability | 可观测性**
[https://docs.spring.io/spring-ai/reference/1.1/observability/index.html](https://docs.spring.io/spring-ai/reference/1.1/observability/index.html)

### Spring AI 1.1.8 API 文档

13. **ChatClient | 聊天客户端接口**
[https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/chat/client/ChatClient.html](https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/chat/client/ChatClient.html)
14. **ChatModel | 聊天模型接口**
[https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/chat/model/ChatModel.html](https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/chat/model/ChatModel.html)
15. **EmbeddingModel | 嵌入模型接口**
[https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/embedding/EmbeddingModel.html](https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/embedding/EmbeddingModel.html)
16. **VectorStore | 向量存储接口**
[https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/vectorstore/VectorStore.html](https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/vectorstore/VectorStore.html)
17. **Document | 文档对象**
[https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/document/Document.html](https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/document/Document.html)
18. **SearchRequest | 检索请求**
[https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/vectorstore/SearchRequest.html](https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/vectorstore/SearchRequest.html)

### PostgreSQL 与 pgvector

19. **pgvector: Open-source vector similarity search for Postgres | PostgreSQL 开源向量相似度搜索扩展**
[https://github.com/pgvector/pgvector](https://github.com/pgvector/pgvector)
20. **PostgreSQL 18 Documentation | PostgreSQL 18 官方文档**
[https://www.postgresql.org/docs/18/](https://www.postgresql.org/docs/18/)

### Java 与 Spring Boot

21. **Spring Boot Reference Documentation | Spring Boot 官方参考文档**
[https://docs.spring.io/spring-boot/reference/](https://docs.spring.io/spring-boot/reference/)
22. **Java 21 Documentation | Java 21 官方文档**
[https://docs.oracle.com/en/java/javase/21/](https://docs.oracle.com/en/java/javase/21/)

---

## 十四、本主题核心知识检查

| 核心知识点              | 必须理解的结论                                  |
| ----------------------- | ----------------------------------------------- |
| 统一抽象                | 面向接口编程，换模型换库不改业务代码            |
| ChatModel vs ChatClient | Model 是底层接口，Client 是高层 fluent 客户端   |
| Prompt 结构化           | 不只是字符串，是 Message 列表 + Options         |
| Structured Output       | entity() 自动 JSON 转 Java 对象                 |
| EmbeddingModel          | 文本转向量，入库和查询必须同模型                |
| VectorStore             | 屏蔽不同向量库差异，add/delete/similaritySearch |
| Metadata Filter         | 检索过滤手段，不代替权限系统                    |
| Advisor                 | 横切拦截器，RAG、记忆、日志都插在这里           |
| ChatMemory              | 按 conversationId 管理对话窗口，防止串用        |
| Tool Calling            | 模型决定调工具，Java 侧执行，安全边界在服务端   |

**面试中要讲清楚的三个问题：**

1. Spring AI 的 ChatModel 和 ChatClient 有什么区别？为什么生产推荐用 ChatClient？
2. QuestionAnswerAdvisor 一行代码实现 RAG，为什么复杂生产场景还要手动编排？
3. Tool Calling 的完整流程是什么？为什么说安全边界在 Java 应用侧，不在模型侧？