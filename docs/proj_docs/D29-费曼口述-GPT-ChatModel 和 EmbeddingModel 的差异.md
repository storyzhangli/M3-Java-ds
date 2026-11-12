# 费曼口述：ChatModel 与 EmbeddingModel 的差异

说话人1: 费曼口述，ChatModel 与 EmbeddingModel 的差异，共七十道关键问题，由浅入深分六个阶段，从基础认知与核心区别、底层原理与模型执行机制、Spring AI 1.1.8 API 与接口设计，到两种模型在 RAG 中的协作关系、企业级工程实践及综合面试分析。技术基线为 Java 21、Spring Boot 3.5.x、Spring AI 1.1.8，结合企业级 Java AI 后端、制造设备故障知识库和 RAG 知识库问答系统。

## 一、基础认知与核心区别

说话人1: 问题一，什么是 ChatModel？它主要解决什么问题？

说话人1: 答，ChatModel 就是聊天模型接口，主要负责与大语言模型进行对话和内容生成。我们把用户的问题、系统提示词和历史对话组成 Prompt，发送给 ChatModel，它调用底层模型生成自然语言回答。比如用户问设备 E101 故障怎么处理，ChatModel 就负责理解问题，并组织语言生成维修建议。

说话人1: 问题二，什么是 EmbeddingModel？它主要解决什么问题？

说话人1: 答，EmbeddingModel 就是嵌入模型接口，主要负责把文本转换成数字向量。它不负责像 ChatModel 一样生成完整的自然语言回答，而是把文本转换成计算机可以进行数学比较的向量。比如把设备说明书、故障案例和用户问题都转换成向量，就可以通过相似度计算找到语义相关的知识。

说话人1: 问题三，ChatModel 和 EmbeddingModel 的核心区别是什么？

说话人1: 答，核心区别就是职责不同。ChatModel 负责理解上下文和生成内容，输入主要是 Prompt，输出主要是自然语言或者结构化内容。EmbeddingModel 负责把文本转换成向量，输入是文本，输出是浮点数数组。简单来说，ChatModel 负责回答问题，EmbeddingModel 负责把语义转换成可以检索和比较的数据。

说话人1: 问题四，为什么 ChatModel 被称为生成式模型，而 EmbeddingModel 被称为嵌入模型？

说话人1: 答，因为两种模型的主要任务不同。ChatModel 通常使用生成式语言模型，根据输入上下文预测后续 Token，逐步生成回答。EmbeddingModel 则通过神经网络把输入文本映射到固定维度的向量空间，让语义相似的内容在这个空间中具有较高的相似度。所以一个侧重生成，一个侧重向量表示。

说话人1: 问题五，两种模型的输入数据分别是什么？输出数据分别是什么？

说话人1: 答，ChatModel 的输入通常是 Prompt，里面可以包含 SystemMessage、UserMessage、AssistantMessage 和模型参数，输出通常是 ChatResponse，里面包含模型生成的回答和相关元数据。EmbeddingModel 的输入通常是文本或者 Document，输出是 float 数组，也就是一个向量。批量输入时，可以一次得到多个向量。

说话人1: 问题六，ChatModel 输出自然语言，EmbeddingModel 输出向量，这两种输出分别有什么用途？

说话人1: 答，ChatModel 输出自然语言，主要用于智能问答、内容生成、故障分析、工单总结和业务决策辅助。EmbeddingModel 输出向量，主要用于语义搜索、向量数据库检索、文本聚类和相似内容推荐。在 RAG 系统中，EmbeddingModel 负责找到知识，ChatModel 负责结合找到的知识组织答案。

说话人1: 问题七，什么是 Embedding，也就是嵌入？为什么需要将文本转换成向量？

说话人1: 答，Embedding 就是把文本等数据映射成一组浮点数，让计算机能够通过数学运算表达和比较它们的语义关系。因为计算机不能直接通过普通字符串比较准确理解两段话的含义。比如设备无法启动和机器不能开机，文字不同，但是语义接近。把它们转换成向量之后，就可以通过相似度计算寻找相关内容。

说话人1: 问题八，什么是文本向量？向量中的每个维度是否都具有明确的人类可解释含义？

说话人1: 答，文本向量就是由多个浮点数组成的数组，比如 `[0.12, -0.35, 0.68, ...]`。每个数字代表向量空间中的一个维度。通常这些维度是模型在训练过程中学习得到的，并不意味着第一个维度代表设备类型、第二个维度代表故障代码。我们一般不能直接解释单个维度的业务含义，而是通过整个向量表达文本的语义特征。

说话人1: 问题九，EmbeddingModel 生成的向量如何表达文本之间的语义关系？

说话人1: 答，因为模型在训练过程中学习了文本的语义表示方式。经过训练后，语义相近的文本通常会映射到向量空间中相对接近的位置。比如设备无法启动和电机不能正常开机，可能得到相似度比较高的向量。然后我们就可以使用余弦相似度、点积等方法进行比较。

说话人1: 问题十，为什么 EmbeddingModel 能够用于语义相似度搜索，而 ChatModel 主要用于理解和生成内容？

说话人1: 答，因为 EmbeddingModel 的输出是固定维度的向量，可以提前计算并保存到向量数据库中。用户查询时，再把问题转换成向量，通过近似最近邻检索找到相似的文档。ChatModel 的主要输出是生成内容，不是专门设计的、可直接用于向量索引的语义表示。所以两者承担不同的任务。

说话人1: 问题十一，ChatModel 和 EmbeddingModel 是否都属于大语言模型？它们在模型架构和训练目标上有什么区别？

说话人1: 答，不一定都属于严格意义上的大语言模型。ChatModel 通常使用生成式大语言模型，比如基于 Transformer 的自回归模型，主要训练目标是根据上下文预测后续 Token。EmbeddingModel 通常使用经过专门训练的编码模型或其他神经网络，主要目标是学习高质量的向量表示。两者可能都使用 Transformer，但模型架构和训练目标不一定相同。

说话人1: 问题十二，两种模型是否可以使用同一家 AI 服务商提供的模型？为什么同一家服务商可能提供不同的 Chat 和 Embedding 模型？

说话人1: 答，可以。很多 AI 服务商同时提供 Chat 和 Embedding 两类模型。因为生成任务和向量表示任务需要不同的模型能力，所以服务商通常会提供不同的模型名称、模型接口和计费方式。比如 OpenAI 就提供聊天模型和专门的文本 Embedding 模型。企业可以选择同一家服务商，也可以分别选择不同服务商，但是必须保证向量检索使用的模型之间具有兼容性。

## 二、底层原理与模型执行机制

说话人1: 问题十三，ChatModel 接收 Prompt 后，通常经历哪些处理和生成步骤？

说话人1: 答，主要经历五个步骤。

第一，应用程序构造 Prompt，包含系统指令、用户问题和对话历史。

第二，Spring AI 将 Prompt 转换成底层模型服务商要求的请求格式。

第三，模型对输入进行 Tokenization，也就是分词和 Token 编码。

第四，模型通过神经网络计算，根据上下文生成回答 Token。

第五，模型服务返回结果，Spring AI 将其转换成 ChatResponse，交给业务代码处理。

所以 ChatModel 不只是发送一个字符串，还负责统一模型请求和响应的接口。

说话人1: 问题十四，ChatModel 的 Token 预测机制是什么？它如何逐步生成自然语言？

说话人1: 答，Token 是大语言模型处理文本的基本单位，不一定等于一个汉字或者一个英文单词。模型会根据当前上下文计算下一个 Token 的概率分布，然后按照解码策略选择一个 Token，再把这个 Token 加入上下文，继续预测下一个 Token。这样不断重复，直到达到停止条件或者 Token 数量限制，最终形成完整回答。

说话人1: 问题十五，什么是自回归生成？它与 ChatModel 的文本生成有什么关系？

说话人1: 答，自回归生成就是模型在生成下一个 Token 时，会使用前面已经生成的 Token 作为上下文。比如模型先生成请，再生成检查，然后生成电源。后面的生成过程依赖前面的结果。ChatModel 通常采用这种方式逐步生成自然语言，因此生成内容具有上下文连续性。

说话人1: 问题十六，ChatModel 的 Temperature、TopP、MaxTokens 分别影响什么？

说话人1: 答，主要有三个参数。

第一，Temperature，温度参数，用来调整 Token 选择的随机程度。通常温度越高，生成结果的多样性越强；温度越低，生成结果通常越稳定。

第二，TopP，核采样参数，控制从累计概率达到指定阈值的候选 Token 中进行选择。它影响模型生成内容的多样性。

第三，MaxTokens，限制本次生成允许输出的 Token 数量，具体语义以服务商 API 定义为准。

在企业级故障问答系统中，通常需要控制生成随机性，避免回答过度发散，同时限制最大输出长度，控制调用成本。

说话人1: 问题十七，EmbeddingModel 如何将输入文本转换成固定维度的向量？

说话人1: 答，通常经历四个步骤。

第一，把输入文本转换成 Token ID。

第二，把 Token 输入模型的神经网络，提取上下文语义特征。

第三，通过模型特定的池化或者其他向量提取机制，把多个 Token 的表示聚合成一个整体向量。

第四，根据模型要求进行归一化等处理，输出固定维度的浮点数组。

比如一个模型规定输出 1024 维向量，那么符合该模型配置的每段输入文本就会得到一个长度为 1024 的向量。

说话人1: 问题十八，EmbeddingModel 的向量是如何通过模型计算得到的？

说话人1: 答，底层主要是神经网络的前向计算。首先把 Token 转换成向量表示，然后通过多层神经网络提取上下文特征，再通过池化或者专门设计的输出层形成句子级表示。最后得到一个浮点数组。这个过程不是人工给每个词分配数字，而是模型根据训练得到的参数计算出来的。

说话人1: 问题十九，Embedding 模型通常采用什么训练目标来学习文本之间的语义关系？

说话人1: 答，常见方法包括对比学习、相似度回归、分类任务和其他自监督或监督学习目标。比如对比学习会让语义相似的文本向量更接近，让语义不相关的文本向量相对远离。不同模型的训练方法不完全一样，但最终目标通常是让向量空间具有更好的语义表达能力和检索能力。

说话人1: 问题二十，什么是对比学习（Contrastive Learning）？它与文本 Embedding 训练有什么关系？

说话人1: 答，对比学习就是通过比较正样本和负样本，让模型学会区分相似内容和不相似内容。比如一段设备故障描述，与其正确的维修案例属于正样本，与无关的设备说明属于负样本。模型训练时，会优化这些样本在向量空间中的相对位置，让正确匹配的文本更加接近，不相关的文本更加远离。它是很多现代文本 Embedding 模型的重要训练方法。

说话人1: 问题二十一，什么是向量维度（Dimensions）？为什么不同 Embedding 模型生成的向量维度可能不同？

说话人1: 答，向量维度就是一个向量中包含多少个数字。比如 384 维、768 维和 1024 维。不同模型的网络结构、输出层和训练目标不同，因此产生的维度也可能不同。维度越高，并不代表模型一定越好，还需要结合语义检索效果、计算成本、存储成本和延迟进行评估。

说话人1: 问题二十二，为什么相同文本经过不同 Embedding 模型处理，可能产生完全不同的向量？

说话人1: 答，因为不同 Embedding 模型具有不同的模型参数、词表、网络结构、训练数据和向量空间。即使输入的是同一句话，两个模型也可能产生完全不同的数字数组。就像两个模型使用不同的坐标系描述同一个位置，数字不一样并不代表文本内容发生了变化。因此，不能简单地把不同模型的向量混合起来进行相似度比较。

说话人1: 问题二十三，什么是 Cosine Similarity、Dot Product 和 Euclidean Distance？它们如何衡量向量之间的相似程度？

说话人1: 答，主要有三种方法。

第一，Cosine Similarity，也就是余弦相似度，比较两个向量方向的相似程度。方向越接近，通常语义相似度越高。

第二，Dot Product，也就是点积，把两个向量对应维度相乘后再求和。它同时受到向量方向和向量长度的影响。

第三，Euclidean Distance，也就是欧氏距离，计算两个向量在空间中的直线距离。距离越小，表示两个向量越接近。

实际项目中需要根据 Embedding 模型的输出特性和向量数据库支持情况选择合适的距离度量。不能简单认为所有模型都适合使用同一种计算方式。

说话人1: 问题二十四，为什么 Embedding 相似度高不代表两段文本的事实内容完全一致？

说话人1: 答，因为 Embedding 主要表达语义相关性，而不是证明两个文本的事实完全一致。比如设备 A 温度过高和设备 A 温度正常，可能因为共享大量相同词语而得到一定的语义相似度，但是两者表达的事实恰好相反。所以向量相似度不能直接当作事实一致性判断，也不能替代业务规则和事实核验。

说话人1: 问题二十五，Embedding 向量能否直接还原成原始自然语言？为什么？

说话人1: 答，通常不能。Embedding 是对文本语义特征的压缩表示，不是对原始文本进行无损编码。模型在转换过程中会保留一部分有用的语义信息，但不会保证保留原文全部词语、顺序和细节。因此，我们一般需要同时保存原始 Document，而不是只保存向量。

说话人1: 问题二十六，ChatModel 能否直接替代 EmbeddingModel 完成语义向量检索？为什么？

说话人1: 答，通常不能直接替代。ChatModel 的主要任务是生成内容，没有统一保证可以输出适合向量检索的语义向量。即使某些聊天模型能够通过隐藏层或者其他接口获得内部表示，也不代表这些表示具有适合语义检索的训练目标和兼容性。要实现标准的向量检索，应该使用专门的 EmbeddingModel。

## 三、Spring AI 1.1.8 API 与接口设计

说话人1: 问题二十七，Spring AI 为什么需要分别设计 ChatModel 和 EmbeddingModel 两套接口？

说话人1: 答，因为它们解决的是两种不同的 AI 任务。ChatModel 需要封装对话请求、消息角色、生成参数、响应内容和流式输出。EmbeddingModel 需要封装文本输入、向量生成、批量处理和向量维度。Spring AI 将它们设计为独立接口，可以统一不同服务商的实现，也可以让业务代码独立使用两种模型，降低对具体服务商的依赖。

说话人1: 问题二十八，ChatModel 和 EmbeddingModel 是否都继承 Spring AI 的通用 Model 接口？它们的泛型参数分别是什么？

说话人1: 答，都继承 Spring AI 的通用 Model 接口，但是泛型参数不同。

第一，ChatModel 继承的是 `Model<Prompt, ChatResponse>`，表示接收 Prompt，返回 ChatResponse。同时它还继承 StreamingChatModel。

第二，EmbeddingModel 继承的是 `Model<EmbeddingRequest, EmbeddingResponse>`，表示接收 EmbeddingRequest，返回 EmbeddingResponse。

这体现了 Spring AI 通过统一的 Model 抽象规范不同模型，同时保留各自任务特有的请求和响应类型。

[image](https://www.google.com/s2/favicons?domain=https://docs.spring.io\&sz=32)

Home+1



说话人1: 问题二十九，Spring AI 1.1.8 中 ChatModel 的核心方法有哪些？`call()` 和 `stream()` 分别解决什么问题？

说话人1: 答，主要关注 call 和 stream 两组方法。

第一，`call(Prompt prompt)`，接收完整的 Prompt，返回 ChatResponse，适合一次性获取完整回答。

第二，`call(String message)`，是简化调用方法，直接传入字符串，返回 String。

第三，`stream(Prompt prompt)`，返回 `Flux<ChatResponse>`，适合流式处理模型响应。

第四，`stream(String message)`，是简化的流式调用方式，通常返回 `Flux<String>`。

需要注意，虽然 Spring AI 提供了流式接口，但具体模型实现是否支持流式输出，还要看对应服务商和模型能力。

[image](https://www.google.com/s2/favicons?domain=https://docs.spring.io\&sz=32)

Home+1



说话人1: 问题三十，ChatModel 的 Prompt、Message、ChatOptions 分别承担什么职责？

说话人1: 答，它们分别解决三个不同的问题。

第一，Prompt 是完整请求对象，负责把消息列表和模型调用参数组合起来。

第二，Message 表示对话中的一条消息，包含消息内容、消息类型和元数据。

第三，ChatOptions 负责设置模型调用参数，比如模型名称、Temperature、TopP、MaxTokens 等。

简单来说，Prompt 是请求容器，Message 是消息内容，ChatOptions 是模型生成配置。

说话人1: 问题三十一，ChatResponse、Generation 和 AssistantMessage 分别代表什么？

说话人1: 答，它们是 ChatModel 响应结构中的三个重要对象。

第一，ChatResponse 是模型响应对象，包含生成结果列表和响应元数据。

第二，Generation 表示一次具体的生成结果，包含 AssistantMessage 和生成元数据。

第三，AssistantMessage 表示模型生成的助手消息，里面包含实际回答内容，还可以携带工具调用等相关信息。

可以理解为 ChatResponse 是最外层响应，Generation 是具体生成结果，AssistantMessage 是最终的助手消息内容。

说话人1: 问题三十二，ChatModel 为什么支持多轮对话？如何通过 SystemMessage、UserMessage 和 AssistantMessage 表达对话上下文？

说话人1: 答，因为 ChatModel 可以接收由多条 Message 组成的 Prompt。SystemMessage 用来定义模型的行为规则，UserMessage 表示用户输入，AssistantMessage 表示历史助手回答。业务系统把这些消息按照对话顺序组织起来，再发送给 ChatModel，模型就可以基于历史上下文继续回答。需要注意，多轮对话的历史管理通常由应用程序或者 ChatMemory 负责，不能简单认为 ChatModel 会自动记住所有历史对话。

说话人1: 问题三十三，ChatModel 如何支持流式输出？为什么 Spring AI 使用 Reactor 的 Flux 表达流式响应？

说话人1: 答，流式输出就是模型在生成过程中，不需要等完整回答生成结束，就可以把已经生成的内容逐步返回。Spring AI 使用 Reactor 的 Flux 表示可能连续产生的多个响应数据。Flux 属于 Reactive Streams 编程模型，支持异步数据处理和背压机制。这样可以让 Java 后端逐步接收模型输出，再通过 SSE 等方式发送给前端，降低用户等待完整回答的感知延迟。

说话人1: 问题三十四，EmbeddingModel 的核心方法有哪些？`embed(String)`、`embed(Document)` 和 `embed(List<String>)` 分别有什么用途？

说话人1: 答，主要有三种快捷方法。

第一，`embed(String text)`，传入一段普通文本，返回一个 float 数组，适合把用户问题转换成向量。

第二，`embed(Document document)`，传入 Spring AI 的 Document，提取其中的文本内容并转换成向量，适合处理知识库文档。

第三，`embed(List<String> texts)`，批量传入多段文本，返回多个向量，适合批量生成知识库 Embedding。

它们最终都围绕 `call(EmbeddingRequest request)` 这个核心模型调用方法实现。

说话人1: 问题三十五，EmbeddingRequest 和 EmbeddingResponse 分别封装什么内容？

说话人1: 答，EmbeddingRequest 是 EmbeddingModel 的请求对象，主要封装待转换的文本列表和 EmbeddingOptions。EmbeddingResponse 是模型响应对象，主要封装生成的多个 Embedding 结果以及响应元数据。通过这两个对象，Spring AI 将不同服务商的向量生成请求和响应统一起来。

说话人1: 问题三十六，Embedding、EmbeddingOptions 和 EmbeddingResponseMetadata 分别承担什么职责？

说话人1: 答，它们分别代表向量结果、请求配置和响应元数据。

第一，Embedding 表示单条输入文本对应的向量结果，包含 float 数组、索引和结果元数据。

第二，EmbeddingOptions 表示 Embedding 模型的调用选项，用于传递模型特定的配置参数。

第三，EmbeddingResponseMetadata 表示一次向量生成请求的响应元数据。

它们共同组成 EmbeddingModel 的标准请求和响应结构。

说话人1: 问题三十七，Spring AI 的 EmbeddingModel 为什么提供批量 Embedding 能力？

说话人1: 答，因为知识库通常包含大量文档。如果每条文档都单独调用一次模型，会增加网络请求次数、调用延迟和服务开销。批量 Embedding 可以一次处理多段文本，减少重复请求，提高处理效率。但是批量大小仍然需要考虑服务商的单次输入数量限制、Token 限制和速率限制。Spring AI 还提供 BatchingStrategy，用于按照 Token 数量等规则对 Document 进行分批处理。

说话人1: 问题三十八，EmbeddingModel 的 `dimensions()` 方法有什么作用？为什么向量数据库需要关注向量维度？

说话人1: 答，`dimensions()` 用来获取 EmbeddingModel 输出向量的维度数量。比如一个模型输出 1024 维向量，那么向量数据库的向量字段也必须支持 1024 维。因为向量数据库需要按照固定维度存储和比较向量。如果写入的向量维度和索引定义不一致，就可能出现写入失败或者检索异常。实际工程中应该在初始化索引时明确确认模型维度，避免每次启动都产生不必要的远程维度探测调用。

说话人1: 问题三十九，ChatModel 与 EmbeddingModel 在 Spring AI 中是否都支持流式接口？两者的接口设计有什么区别？

说话人1: 答，不是。Spring AI 1.1.8 中 ChatModel 继承了 StreamingChatModel，具有流式调用接口，可以返回 Flux。EmbeddingModel 没有继承流式模型接口，主要通过同步请求返回完整向量结果。EmbeddingModel 可以通过批量处理提高效率，但批量处理不等于流式输出。需要特别注意，ChatModel 虽然有流式接口，但是具体模型实现不一定支持流式能力。

说话人1: 问题四十，在 Spring Boot 项目中，如何分别注入 ChatModel 和 EmbeddingModel？如何避免混淆两种模型的配置和职责？

说话人1: 答，可以使用 Spring 的依赖注入机制，分别注入 ChatModel 和 EmbeddingModel。

比如业务 Service 中：

```
@Service
public class RagModelService {

    private final ChatModel chatModel;

    private final EmbeddingModel embeddingModel;

    public RagModelService(
            ChatModel chatModel,
            EmbeddingModel embeddingModel) {
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
    }
}
```

ChatModel 用于生成回答，EmbeddingModel 用于生成向量。配置层面分别管理模型类型、模型名称、调用地址、API Key 和模型参数。如果项目中存在多个 ChatModel 或 EmbeddingModel Bean，就使用 `@Qualifier` 明确指定注入对象。这样可以避免把聊天模型错误地注入向量检索服务，或者把 EmbeddingModel 当作聊天模型使用。

## 四、两种模型在 RAG 中的协作关系

说话人1: 问题四十一，什么是 RAG？为什么一个完整的 RAG 系统通常同时需要 EmbeddingModel 和 ChatModel？

说话人1: 答，RAG 就是 Retrieval-Augmented Generation，检索增强生成。它的核心思想是先从外部知识库检索相关资料，再把资料交给大语言模型生成回答。通常需要两个模型协作：EmbeddingModel 把文档和用户问题转换成向量，负责语义检索；ChatModel 根据检索到的文档和用户问题生成最终答案。简单来说，EmbeddingModel 负责找知识，ChatModel 负责用知识回答问题。

说话人1: 问题四十二，在 RAG 文档入库阶段，EmbeddingModel 承担什么职责？

说话人1: 答，文档入库阶段，EmbeddingModel 主要负责把清洗和切分后的文本 Chunk 转换成向量。比如一份设备维修手册，经过 PDF 解析、文本清洗和切分，得到多个 Document。然后 EmbeddingModel 把每个 Document 转换成对应的 float 数组，最后由 VectorStore 保存文档内容、向量和元数据，供后续检索使用。

说话人1: 问题四十三，在 RAG 用户查询阶段，EmbeddingModel 承担什么职责？

说话人1: 答，用户查询阶段，EmbeddingModel 负责把用户的问题转换成查询向量。比如用户输入设备 E101 故障，EmbeddingModel 将这段文本转换成向量，然后 VectorStore 使用这个向量与知识库中的文档向量进行相似度计算，找出最相关的文档。这里 EmbeddingModel 负责生成查询向量，真正执行向量搜索的是 VectorStore。

说话人1: 问题四十四，为什么知识库文档和用户问题需要转换成向量？

说话人1: 答，因为用户提问的表达方式可能与知识库中的原文完全不同。比如手册中写的是电机过载保护触发，用户问的是电机为什么突然停机。关键词可能不一致，但是语义可能相关。把两者转换成向量后，就可以通过语义相似度找到相关内容，而不只是依赖字符串完全匹配。

说话人1: 问题四十五，文档向量和问题向量如何通过相似度计算进行匹配？

说话人1: 答，首先文档入库时生成文档向量，并保存到向量数据库。用户提问时，使用同一个兼容的 EmbeddingModel 生成问题向量。然后向量数据库计算问题向量与文档向量之间的相似度，根据相似度或距离进行排序，返回 TopK 个相关文档。整个过程属于向量相似度检索，也可以结合元数据过滤提高准确性。

说话人1: 问题四十六，向量数据库在 EmbeddingModel 和 ChatModel 之间承担什么职责？

说话人1: 答，向量数据库主要承担向量存储和相似度检索职责。EmbeddingModel 负责生成向量，向量数据库负责保存向量、原始文档和元数据，并根据用户查询返回相关文档。最后，ChatModel 接收检索结果作为上下文，生成自然语言回答。它们形成一个完整的 RAG 数据处理链路。

说话人1: 问题四十七，为什么向量数据库负责存储和检索向量，却不等于 EmbeddingModel？

说话人1: 答，因为它们的职责完全不同。EmbeddingModel 是负责计算向量的 AI 模型，向量数据库是负责存储和检索向量的数据基础设施。向量数据库本身不一定具备生成语义向量的能力。以 Spring AI 为例，EmbeddingModel 可以将文本转换成 float 数组，VectorStore 则负责添加 Document、保存向量和执行相似度搜索。两者可以通过 Spring AI 的接口进行集成。

[image](https://www.google.com/s2/favicons?domain=https://docs.spring.io\&sz=32)

Home+1



说话人1: 问题四十八，向量检索得到的 Document 如何转换成 ChatModel 可以使用的上下文？

说话人1: 答，向量检索返回的是一个或多个 Document，每个 Document 通常包含文本内容和元数据。应用程序可以提取 Document 的正文，按照一定的格式组合成知识上下文，再放入 ChatModel 的 Prompt 中。比如把设备名称、故障代码、维修步骤和来源页码一起组织起来，让模型根据这些资料回答问题。需要注意，来源信息和元数据也应该保留，方便后续进行引用和溯源。

说话人1: 问题四十九，ChatModel 如何利用检索到的知识库内容生成最终答案？

说话人1: 答，ChatModel 会接收用户问题、系统提示词和检索到的知识内容。系统提示词可以要求模型优先依据提供的知识库资料回答，不允许随意编造维修参数，并且要求给出文档来源。如果检索到的资料能够回答问题，模型就根据资料组织答案。如果没有找到足够证据，就应该明确说明信息不足，要求补充信息或者提示人工工程师确认。这样可以降低大模型产生幻觉的风险。

说话人1: 问题五十，如果只有 ChatModel，没有 EmbeddingModel，能否实现普通对话和完整的向量 RAG？为什么？

说话人1: 答，可以实现普通对话，但是不能按照标准的 Embedding 向量检索流程实现完整的向量 RAG。因为 ChatModel 可以直接接收用户问题并生成回答，但是它不负责提供标准的语义向量。如果需要知识库检索，还必须有其他检索能力，比如传统关键词检索、全文检索或者外部搜索服务。所以没有 EmbeddingModel，不代表完全不能做 RAG，而是不能直接完成基于 Embedding 的向量检索流程。

说话人1: 问题五十一，如果只有 EmbeddingModel，没有 ChatModel，能否实现语义检索？为什么不能直接完成完整的生成式问答？

说话人1: 答，可以实现语义检索。因为 EmbeddingModel 能够把用户问题和知识库文档转换成向量，再通过向量数据库找到相关文档。但是它不会像 ChatModel 一样把多份资料综合起来生成自然语言回答。系统可以返回检索结果、文档原文和相似度排名，也可以使用预设模板展示内容，但无法完成通常意义上的生成式问答。

说话人1: 问题五十二，为什么 RAG 的答案生成质量不仅取决于 ChatModel，还取决于 EmbeddingModel 的检索质量？

说话人1: 答，因为 ChatModel 的回答很大程度上依赖提供给它的上下文。如果 EmbeddingModel 检索到错误的知识，即使 ChatModel 的理解和生成能力很强，也可能根据错误资料生成错误答案。比如用户询问 E101 故障，系统却检索到了 E102 的维修手册，ChatModel 就可能给出错误的处理建议。因此，RAG 的质量不仅取决于生成模型，还取决于 Embedding 模型、文本切分、检索策略、元数据过滤和排序机制。

说话人1: 问题五十三，EmbeddingModel 能否判断检索到的文档是否真正回答了用户问题？为什么还可能需要 Rerank？

说话人1: 答，EmbeddingModel 主要判断文本在向量空间中的语义相关程度，并不能保证检索到的文档真正回答了用户问题。比如用户问某种设备的维修步骤，检索结果可能只是包含相同设备名称，但没有实际维修方法。Rerank 就是重排序，可以使用更精细的模型对用户问题和候选文档进行相关性评估，再调整文档排名。常见方案是使用 Cross-Encoder 类模型进行联合编码和评分，提高最终交给 ChatModel 的上下文质量。

说话人1: 问题五十四，如果 ChatModel 已经具备较强的知识理解能力，为什么企业知识库仍然需要 EmbeddingModel？

说话人1: 答，因为 ChatModel 的预训练知识不等于企业内部的实时业务知识。企业设备维修手册、内部故障案例、客户工单和设备运行规范，可能没有进入大模型的训练数据。即使模型理解能力很强，也不一定知道企业内部最新的维修规定。EmbeddingModel 可以帮助系统从企业私有知识库中找到相关资料，再交给 ChatModel 生成回答。这样既能利用大模型的语言理解能力，也能利用企业自己的知识资产。

## 五、工程实践与技术选型

说话人1: 问题五十五，ChatModel 和 EmbeddingModel 在 Token 限制、上下文窗口、向量维度及资源消耗方面有什么区别？

说话人1: 答，主要有四个方面的区别。

第一，Token 限制。ChatModel 既要考虑输入 Token，也要考虑输出 Token。EmbeddingModel 主要考虑输入文本的 Token 数量，以及单次批量请求限制。

第二，上下文窗口。ChatModel 的上下文窗口决定一次请求能够接收多少历史消息和知识资料。EmbeddingModel 的输入长度限制决定一段文本能否完整转换成向量。

第三，向量维度。ChatModel 的主要输出是文本内容，EmbeddingModel 的主要输出是固定维度的向量，比如 768 维或者 1024 维。

第四，资源消耗。ChatModel 的生成过程需要持续进行 Token 预测，输出长度会影响调用耗时和成本。EmbeddingModel 主要执行向量计算，批量处理可以提高吞吐量，但也需要考虑模型推理资源、输入 Token 和向量存储成本。

具体成本不能简单认为 EmbeddingModel 永远更便宜，需要根据模型定价、输入规模和部署方式计算。

说话人1: 问题五十六，如何设计 EmbeddingModel 的批量处理、缓存和限流机制？

说话人1: 答，我会从三个方面设计。

第一，批量处理。把待处理的 Document 按 Token 数量和服务商单次请求限制分批，避免一次发送过多文本。Spring AI 提供 TokenCountBatchingStrategy，可以根据 Token 数量进行分批。

第二，缓存。对已经生成的向量进行缓存，缓存键应该包含文本内容的哈希值、Embedding 模型标识和模型版本，避免相同文本反复计算。还可以对重复文档进行去重。

第三，限流。根据服务商的 QPS、并发和 Token 配额设置请求限制，使用有界队列控制任务积压，并结合重试、退避和熔断机制处理限流和临时故障。

在企业级知识库中，还应该记录处理状态，保证失败任务能够重新执行，不会造成文档丢失。

说话人1: 问题五十七，为什么更换 EmbeddingModel 时，通常需要重新计算历史文档向量？

说话人1: 答，因为不同 EmbeddingModel 的参数、训练数据和向量空间可能不同。同一段文本经过新旧模型计算后，会得到不同的向量表示。原来存储在向量数据库中的历史向量，如果与新模型生成的查询向量不兼容，就会导致检索结果失真。因此，更换 EmbeddingModel 时，通常需要对历史文档重新生成向量，并重新建立或迁移向量索引。

说话人1: 问题五十八，如果新旧 EmbeddingModel 的向量维度相同，是否就意味着生成的向量可以混合存储和比较？为什么？

说话人1: 答，不能。向量维度相同，只能说明两个数组的长度相同，不代表它们属于同一个向量空间。比如两个模型都生成 1024 维向量，但是它们的每个维度对应的语义表示和坐标分布可能完全不同。如果直接混合存储，可能产生没有意义的相似度结果。所以向量兼容性不能只看维度，还要看模型及其版本、训练空间和具体使用规范。

说话人1: 问题五十九，如何保证文档入库和用户查询使用兼容的 Embedding 模型及向量空间？

说话人1: 答，首先要统一模型配置。文档入库和用户查询必须使用相同或者明确兼容的 Embedding 模型。其次，要记录模型名称、版本、向量维度、文本预处理规则和必要的输入任务类型。第三，向量数据库中的集合或者索引要明确绑定对应的模型版本。第四，更换模型时，需要进行数据迁移和重新向量化。不能让新模型生成的查询向量直接检索不兼容的历史文档向量。

说话人1: 问题六十，如何分别监控 ChatModel 和 EmbeddingModel 的调用次数、Token 消耗、请求延迟、失败率和服务可用性？

说话人1: 答，可以建立统一的模型调用监控体系，但是需要区分两种模型的指标。

第一，ChatModel 监控请求次数、输入 Token、输出 Token、首 Token 延迟、完整响应时间、失败率和限流次数。

第二，EmbeddingModel 监控请求次数、输入 Token、生成向量数量、平均批量大小、请求延迟、失败率和服务可用性。

第三，统一记录模型名称、模型版本、业务场景、TraceId 和调用状态，方便排查问题。

第四，对 API Key、用户隐私和业务敏感内容进行脱敏，不应该直接把完整密钥和敏感 Prompt 写入日志。

这样就能区分生成模型性能问题和向量模型性能问题。

说话人1: 问题六十一，ChatModel 调用失败和 EmbeddingModel 调用失败分别应该如何设计异常处理与降级机制？

说话人1: 答，两种模型的降级策略应该分开设计。

第一，ChatModel 调用失败时，可以采用有限次数重试、超时控制、熔断和备用模型。如果备用模型不可用，就返回明确的服务降级信息，或者提供已经检索到的原始知识资料，但不能假装已经完成智能分析。

第二，EmbeddingModel 调用失败时，应该暂停或记录失败的文档向量化任务，避免把没有向量的数据当作已经入库成功。查询阶段可以降级到关键词检索、全文检索，或者使用已经缓存的兼容查询向量。

第三，两种模型都应该有独立的监控、告警和恢复机制。

需要注意，不能因为 EmbeddingModel 故障，就直接用 ChatModel 输出一些数字冒充查询向量。

说话人1: 问题六十二，如何分别建立 ChatModel 的生成质量评估和 EmbeddingModel 的检索质量评估体系？

说话人1: 答，需要分别建立两套评估体系。

第一，ChatModel 重点评估答案准确性、事实一致性、回答相关性、内容完整性、引用正确性和响应延迟。比如设备故障问答中，要检查模型有没有正确引用维修手册，有没有编造故障原因。

第二，EmbeddingModel 重点评估检索质量。可以使用 Recall\@K、Precision\@K、MRR 和 nDCG 等指标，衡量正确文档是否被检索、排名是否合理。

第三，构建真实业务测试集，比如设备型号、故障代码、故障现象和标准维修案例。

第四，进行端到端 RAG 评估，分别观察检索质量和生成质量，避免只看最终回答就无法定位问题。

简单来说，EmbeddingModel 重点看能不能找到正确知识，ChatModel 重点看能不能根据正确知识生成正确答案。

## 六、企业级项目与面试综合分析

说话人1: 问题六十三，在制造设备故障知识库中，如何利用 EmbeddingModel 将设备说明书、维修手册和故障案例转换成向量？

说话人1: 答，我会设计一条完整的文档向量化流程。

第一，文档采集。从 PDF、Word、Excel 和历史工单中获取设备知识。

第二，文档解析。提取正文、表格、故障代码、维修步骤和来源信息。

第三，文本清洗和切分。按照设备型号、故障类型、章节和语义完整度，将文档拆成多个 Chunk。

第四，生成向量。调用 EmbeddingModel，把每个 Chunk 转换成 float 数组。

第五，持久化。将 Document 的文本、Embedding 向量和元数据保存到 VectorStore。

第六，记录来源。保存文档 ID、设备型号、故障代码、页码、章节和文档版本，方便后续追溯。

这样就形成了一个可以进行语义检索的设备知识库。

说话人1: 问题六十四，用户输入设备故障代码和故障现象后，EmbeddingModel 与 ChatModel 分别执行哪些任务？

说话人1: 答，两个模型负责不同的阶段。

第一，用户输入故障代码和故障现象，比如 E101，设备运行过程中突然停机。

第二，应用程序先解析用户输入，提取设备型号、故障代码和故障现象。

第三，EmbeddingModel 将用户问题转换成向量。

第四，VectorStore 根据查询向量执行相似度检索，找到相关设备手册和历史故障案例。与此同时，可以通过关键词检索精确匹配 E101。

第五，将检索到的知识整理成上下文，交给 ChatModel。

第六，ChatModel 根据设备型号、故障代码、故障现象和知识库资料生成故障分析及维修建议。

总结，EmbeddingModel 负责语义向量化，VectorStore 负责知识检索，ChatModel 负责理解资料和生成回答。

说话人1: 问题六十五，如果用户输入的故障代码是 `E101`，但知识库中存在多个相似故障描述，为什么不能只依赖 EmbeddingModel？

说话人1: 答，因为 EmbeddingModel 主要衡量语义相关性，不保证精确匹配业务标识。比如不同设备可能都存在 E101 故障代码，但是代表的故障原因完全不同。甚至同一设备的不同固件版本，也可能使用不同的故障定义。因此，不能只根据向量相似度选择维修方案。应该结合精确故障代码、设备型号、固件版本、关键词检索和元数据过滤，再通过 Rerank 进行相关性排序，最后交给 ChatModel 生成答案。

说话人1: 问题六十六，如何组合关键词检索、向量检索、Rerank 和 ChatModel，提高设备故障问答的准确性？

说话人1: 答，我会采用混合检索架构。

第一，关键词检索。使用 BM25 或数据库全文检索，优先匹配设备编号、故障代码、零件编号等精确字段。

第二，向量检索。使用 EmbeddingModel 将用户问题转换成向量，检索语义相关的故障案例和维修手册。

第三，结果融合。合并关键词检索和向量检索的结果，进行去重，并保留来源信息。

第四，Rerank 重排序。根据用户问题和候选文档的相关性，重新调整结果排名。

第五，ChatModel 生成答案。把高质量检索结果放入 Prompt，要求模型严格依据知识库资料回答。

最终形成关键词检索、向量检索、结果融合、Rerank 和生成回答的完整流程。这样可以兼顾精确匹配和语义理解。

说话人1: 问题六十七，如果 EmbeddingModel 发生故障，能否通过 ChatModel 临时替代向量检索？有哪些可行的降级方案？

说话人1: 答，不能直接替代，因为 ChatModel 不具备标准 EmbeddingModel 的向量生成接口和向量空间保证。但是可以采用几种降级方案。

第一，降级到关键词检索和 BM25 全文检索。

第二，使用已经缓存的查询向量，前提是缓存向量与当前知识库的 Embedding 模型兼容。

第三，查询已有的精确故障代码索引和设备型号索引。

第四，如果 ChatModel 正常，可以让它从用户问题中提取故障代码、设备型号和关键词，辅助传统检索。

第五，如果检索结果不足，就明确告诉用户当前处于降级模式，不要让 ChatModel 编造知识库内容。

所以 EmbeddingModel 故障后，可以降级检索能力，但不能简单拿 ChatModel 代替向量计算。

说话人1: 问题六十八，如果 ChatModel 发生故障，但 EmbeddingModel 正常，系统还能提供哪些服务？

说话人1: 答，可以继续提供语义检索服务。因为 EmbeddingModel 和 VectorStore 仍然可以正常工作。系统能够将用户问题转换成向量，查找相关设备手册、故障案例和维修知识，并返回原始文档和来源信息。

除此之外，还可以提供相似故障推荐、相似工单查询和知识分类等功能。但是无法正常完成基于大模型的自然语言综合分析和生成式回答。可以使用预设模板展示检索结果，等 ChatModel 恢复后再提供完整的智能问答服务。

说话人1: 问题六十九，如果需要降低 RAG 系统的模型调用成本，应该如何分别优化 ChatModel 和 EmbeddingModel 的调用策略？

说话人1: 答，我会分别优化两个模型。

第一，ChatModel 优化。减少不必要的历史对话，优化 Prompt，控制检索 TopK，减少重复上下文，限制最大输出 Token。对于简单问题，可以选择成本更低的模型。对于重复问题，可以设计答案缓存。

第二，EmbeddingModel 优化。使用批量 Embedding，减少单条请求数量。对重复文本进行去重，对已经生成的向量进行缓存。合理设计 Chunk 大小，避免不必要的重复向量化。文档没有变化时，不要重新计算向量。

第三，数据治理。建立文档版本和 Embedding 版本管理，只对发生变化的文档重新生成向量。

第四，模型选型。在满足检索效果的前提下，可以比较不同维度、不同成本的 Embedding 模型，通过实际测试选择合适的方案。

第五，监控成本。分别统计 ChatModel 的输入输出 Token 消耗和 EmbeddingModel 的输入 Token、向量生成数量及费用。

总结，ChatModel 主要优化生成 Token 和上下文长度，EmbeddingModel 主要优化重复计算、批量处理和向量化频率。

说话人1: 问题七十，面试官问：ChatModel 和 EmbeddingModel 有什么区别？为什么一个完整的 RAG 系统通常需要两者协作？请从模型职责、底层原理、输入输出、Spring AI 接口及实际工程应用五个方面完整回答。

说话人1: 答，我从五个方面回答。

说话人1：第一，模型职责。

ChatModel 主要负责理解上下文、生成自然语言回答。EmbeddingModel 主要负责把文本转换成数字向量，用于语义表示和相似度检索。

说话人1：第二，底层原理。

ChatModel 通常基于自回归生成机制，根据上下文预测下一个 Token，不断生成完整回答。EmbeddingModel 通常通过神经网络提取文本语义特征，将文本映射到固定维度的向量空间，让语义相近的文本具有较高的相似度。

说话人1：第三，输入输出。

ChatModel 的输入是 Prompt，包含系统提示词、用户消息和历史对话，输出通常是 ChatResponse，里面包含 AssistantMessage。

EmbeddingModel 的输入是文本或者 Document，输出是 float 数组，也就是 Embedding 向量。它还支持批量处理多个文本。

说话人1：第四，Spring AI 接口。

在 Spring AI 1.1.8 中，ChatModel 继承 `Model<Prompt, ChatResponse>` 和 StreamingChatModel，提供 call 和 stream 等方法。

EmbeddingModel 继承 `Model<EmbeddingRequest, EmbeddingResponse>`，提供 call、embed 和 dimensions 等方法。

Spring AI 通过两套独立接口，统一了不同服务商的模型调用方式，方便 Java 后端进行依赖注入和模型切换。

说话人1：第五，实际工程应用。

以制造设备故障知识库为例。

第一，文档入库阶段，EmbeddingModel 把设备说明书、维修手册和故障案例转换成向量，保存到 VectorStore。

第二，用户查询阶段，EmbeddingModel 把用户输入的故障问题转换成查询向量。

第三，VectorStore 执行相似度搜索，找到相关知识。

第四，必要时结合关键词检索和 Rerank，提高检索准确性。

第五，ChatModel 获取检索结果和用户问题，生成最终的故障分析和维修建议。

说话人1：最后总结，ChatModel 负责生成，EmbeddingModel 负责向量化，VectorStore 负责存储和检索。三者相互协作，构成企业级 RAG 知识库问答系统的核心链路。没有 ChatModel，可以做语义检索，但无法完成完整的生成式问答；没有 EmbeddingModel，就无法按照标准的向量检索流程构建 RAG。它们不是相互替代的关系，而是分工协作的关系。

说话人1: 全部回答完毕。

## 七、参考资料

以下按照 Spring AI 1.1.8 官方版本资料及模型原理资料整理，链接不附加跟踪参数。

Spring AI 官方 API 文档

1\. Chat Model API :: Spring AI Reference 1.1.8 | Spring AI 聊天模型 API 参考文档

[https://docs.spring.io/spring-ai/reference/1.1/api/chatmodel.html](https://docs.spring.io/spring-ai/reference/1.1/api/chatmodel.html?utm_source=chatgpt.com)

2\. Embeddings Model API :: Spring AI Reference 1.1.8 | Spring AI 嵌入模型 API 参考文档

[https://docs.spring.io/spring-ai/reference/1.1/api/embeddings.html](https://docs.spring.io/spring-ai/reference/1.1/api/embeddings.html?utm_source=chatgpt.com)

3\. ChatModel (Spring AI Parent 1.1.8 API) | Spring AI 1.1.8 聊天模型接口 API 文档

[https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/chat/model/ChatModel.html](https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/chat/model/ChatModel.html?utm_source=chatgpt.com)

4\. EmbeddingModel (Spring AI Parent 1.1.8 API) | Spring AI 1.1.8 嵌入模型接口 API 文档

[https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/embedding/EmbeddingModel.html](https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/embedding/EmbeddingModel.html?utm_source=chatgpt.com)

5\. Vector Databases :: Spring AI Reference 1.1.8 | Spring AI 向量数据库参考文档

[https://docs.spring.io/spring-ai/reference/1.1/api/vectordbs.html](https://docs.spring.io/spring-ai/reference/1.1/api/vectordbs.html?utm_source=chatgpt.com)

6\. Spring AI 1.1.8 Release Notes | Spring AI 1.1.8 版本发布说明

[https://github.com/spring-projects/spring-ai/releases/tag/v1.1.8](https://github.com/spring-projects/spring-ai/releases/tag/v1.1.8?utm_source=chatgpt.com)

Embedding 模型原理与实践资料

7\. Vector embeddings | OpenAI 向量嵌入官方技术指南

[https://developers.openai.com/api/docs/guides/embeddings](https://developers.openai.com/api/docs/guides/embeddings?utm_source=chatgpt.com)

8\. Sentence-BERT: Sentence Embeddings using Siamese BERT-Networks | Sentence-BERT：基于孪生 BERT 网络的句子嵌入

[https://arxiv.org/abs/1908.10084](https://arxiv.org/abs/1908.10084?utm_source=chatgpt.com)

9\. Semantic Textual Similarity — Sentence Transformers documentation | 语义文本相似度——Sentence Transformers 技术文档

[https://www.sbert.net/examples/sentence_transformer/training/sts/README.html](https://www.sbert.net/examples/sentence_transformer/training/sts/README.html?utm_source=chatgpt.com)

技术基线说明： 本套题以 Spring AI 1.1.8 为准。官方 API 确认 ChatModel 与 EmbeddingModel 分别使用不同的泛型请求和响应类型；向量数据库负责存储与检索，EmbeddingModel 负责生成向量。Spring AI 1.1.8 的发布说明也确认其依赖升级至 Spring Boot 3.5.15，属于本题要求的 3.5.x 技术线。

[image](https://www.google.com/s2/favicons?domain=https://docs.spring.io\&sz=32)

Home+3