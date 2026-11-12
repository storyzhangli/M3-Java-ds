# M3 知识笔记：Embedding 原理与模型选择

**技术基线：** JDK 21 / Spring Boot 3.5.x / Spring AI 1.1.8

**学习方向：** Java 后端工程与 Spring AI 应用开发

**实践对象：** 中文设备知识库、设备技术手册、设备故障描述

## 一、Embedding 基础概念与工作原理

### 1.1 什么是 Embedding

**概念：**

Embedding（嵌入）是将文本等输入转换为数值向量的技术。

在 RAG 中，主要使用文本 Embedding，将文档 Chunk 和用户查询分别转换为向量，使系统能够通过向量相似度寻找语义相关的内容。

例如：

```
文本 A：设备无法启动
文本 B：设备启动失败
文本 C：如何更换设备滤芯
```

经过同一个 Embedding 模型编码后，会得到三个向量：

```
文本 A -> [0.12, -0.34, 0.56, ...]
文本 B -> [0.10, -0.30, 0.53, ...]
文本 C -> [-0.42, 0.21, 0.08, ...]
```

以上数值仅用于说明数据结构，不是真实模型输出。

**原理：**

Embedding 模型通过训练学习文本与语义表示之间的关系。

输入文本经过模型内部的 Tokenization、Embedding 层及神经网络计算，最终输出固定维度的浮点数数组。

可以简化为：

```
输入文本
   |
   v
Tokenizer
   |
   v
Token ID
   |
   v
Embedding 模型
   |
   v
上下文特征提取
   |
   v
向量表示
   |
   v
float[]
```

这里的 Embedding 层和最终输出向量不是完全相同的概念。

模型通常会通过多层网络、池化或其他输出处理方式，将输入文本转换为最终向量。

具体计算过程取决于模型架构。

**作用：**

将原本难以直接计算语义关系的文本，转换为可以进行数学比较的向量。

例如：

- 文档：控制器上电后无法正常启动。
- 查询：设备无法开机。

即使两个文本没有完全相同的关键词，模型也可能将它们映射到相近的向量区域。

**工程实践：**

Java 中最终得到的通常是：

```
float[] vector = embeddingModel.embed(
        "设备无法启动"
);
```

**局限：**

1. Embedding 不会直接生成答案。
2. Embedding 不保证所有语义相近的文本都得到高相似度。
3. 专业领域术语可能存在理解偏差。
4. 型号、故障代码和数字参数不一定能够被正确区分。
5. 不同模型的向量空间可能完全不同。

### 1.2 Embedding、Tokenization 和 Chat Model 的区别

| 技术         | 主要职责           | 输入         | 输出             |
| ------------ | ------------------ | ------------ | ---------------- |
| Tokenization | 将文本转换为 Token | 文本         | Token ID         |
| Embedding    | 将文本转换为向量   | 文本         | 浮点数向量       |
| Chat Model   | 理解指令并生成内容 | 消息、上下文 | 文本或结构化输出 |

三者之间存在关系，但不能混为一谈。

例如：

```
用户输入：
设备无法启动
       |
       +----------------------+
       |                      |
       v                      v
Embedding Model          Chat Model
       |                      |
       v                      v
[0.12, -0.34, ...]       生成自然语言回答
```

Embedding 的输出是向量，不是自然语言答案。

Chat Model 的输出主要用于生成内容，不是直接用于向量相似度计算的标准语义表示。

### 1.3 向量数值是不是词语权重

不是。

例如：

```
[0.12, -0.34, 0.56, 0.08]
```

不能直接解释为：

```
0.12 = 设备权重
-0.34 = 故障权重
0.56 = 启动权重
```

模型输出的各维度通常是学习得到的分布式表示。

单个维度一般没有稳定、可直接解释的业务语义。

应该通过整体向量之间的关系进行比较，而不是逐个维度赋予词语含义。

## 二、文本向量化的数据流程

### 2.1 RAG 中的向量化流程

```
flowchart TD
    A["设备手册 / 故障知识文档"] --> B["文档解析与清洗"]
    B --> C["Chunk 切分"]
    C --> D["文档 Chunk"]
    D --> E["Embedding Model"]
    E --> F["文档向量"]
    F --> G["保存向量及来源 Metadata"]

    H["用户问题"] --> I["查询预处理"]
    I --> J["同一 Embedding Model"]
    J --> K["查询向量"]

    G --> L["后续向量相似度比较"]
    K --> L
```

本次学习重点是从文本到向量的过程，以及模型兼容性。

不深入实现 Vector Store 和完整检索流程。

### 2.2 文档向量化

假设设备手册切分得到三个 Chunk：

```
Chunk 1：
CPU 1214C 的基本技术规格。

Chunk 2：
设备数字量输入为 14 点，输入电压为 24 V DC。

Chunk 3：
设备运行过程中出现过热时，应检查散热条件。
```

分别调用 Embedding Model：

```
List<String> chunks = List.of(
        "CPU 1214C 的基本技术规格。",
        "设备数字量输入为 14 点，输入电压为 24 V DC。",
        "设备运行过程中出现过热时，应检查散热条件。"
);

List<float[]> vectors = embeddingModel.embed(chunks);
```

结果：

```
Chunk 1 -> Vector 1
Chunk 2 -> Vector 2
Chunk 3 -> Vector 3
```

每个 Chunk 对应一个向量。

实际生产环境还需要保存：

- Document ID
- Chunk ID
- 原始文件名
- 页码
- 章节标题
- Embedding 模型名称
- Embedding 模型版本
- 向量维度
- 向量生成时间

### 2.3 查询向量化

用户提问：

```
设备出现过热怎么办？
```

使用与文档向量兼容的 Embedding Model：

```
float[] queryVector = embeddingModel.embed(
        "设备出现过热怎么办？"
);
```

得到查询向量后，才能与文档向量进行相似度比较。

**关键原则：**

文档向量与查询向量必须处于兼容的向量空间。

不能使用模型 A 编码文档，再直接使用模型 B 编码查询，然后因为维度相同就认为可以正常比较。

## 三、向量维度与相似度计算

### 3.1 什么是向量维度

**概念：**

向量维度（Dimensions）表示向量中包含多少个数值。

例如：

```
向量 A = [0.1, 0.2, 0.3]
```

它的维度为 3。

```
向量 B = [0.1, 0.2, 0.3, 0.4]
```

它的维度为 4。

**原理：**

Embedding 模型在设计或配置时决定输出向量的维度。

不同模型可能输出不同维度。

例如：

| 模型                          | 向量维度          |
| ----------------------------- | ----------------- |
| BAAI/bge-m3                   | 1024              |
| OpenAI text-embedding-3-small | 默认 1536         |
| OpenAI text-embedding-3-large | 默认 3072         |
| 阿里云 text-embedding-v4      | 默认 1024，可配置 |

这里的维度是官方模型参数，不代表模型质量排名。

**作用：**

维度会影响：

1. 单个向量的存储空间。
2. 向量计算的资源消耗。
3. 向量索引的内存占用。
4. 数据传输量。
5. 后续检索基础设施的配置。

例如，假设一个向量使用 32 位浮点数：

```
1024 维：
1024 × 4 Bytes = 4096 Bytes

1536 维：
1536 × 4 Bytes = 6144 Bytes

3072 维：
3072 × 4 Bytes = 12288 Bytes
```

这里只计算原始 float 数据，不包括对象、索引、Metadata 和数据库存储开销。

**工程实践：**

Java 中可以直接检查：

```
float[] vector = embeddingModel.embed("设备无法启动");

int dimensions = vector.length;

System.out.println("向量维度：" + dimensions);
```

**局限：**

向量维度越高，不代表模型质量一定越好。

维度只是向量表示空间的一个属性，实际效果还取决于训练数据、模型架构、训练目标、领域适配和任务类型。

### 3.2 维度相同为什么不能保证向量兼容

假设：

```
模型 A -> 1024 维
模型 B -> 1024 维
```

两个模型虽然都输出 1024 个浮点数，但它们可能具有完全不同的向量空间。

例如：

```
模型 A：
设备无法启动 -> [0.12, 0.34, ...]

模型 B：
设备无法启动 -> [-0.28, 0.11, ...]
```

它们的维度相同，但不能直接把两个模型生成的向量混在一起计算相似度。

原因：

1. 训练数据可能不同。
2. 模型架构可能不同。
3. 训练目标可能不同。
4. 向量空间的坐标系不同。
5. 向量分布和归一化方式可能不同。

可以把它理解为两个不同坐标系中的坐标。

即使两个坐标系都有 1024 个维度，也不意味着坐标值可以直接比较。

### 3.3 Cosine Similarity：余弦相似度

**概念：**

余弦相似度通过两个向量之间夹角的余弦值，衡量向量方向的接近程度。

公式：

\frac{A\cdot B}
{|A||B|}
]

其中：

- (A\cdot B)：向量点积。
- (|A|)：向量 A 的模长。
- (|B|)：向量 B 的模长。

在非零向量条件下，余弦相似度通常位于 -1 到 1 之间。

一般而言：

- 越接近 1：方向越接近。
- 接近 0：方向接近正交。
- 越接近 -1：方向越相反。

但实际 Embedding 空间中的分数分布，需要根据具体模型和数据集进行观察。

**Java 实现：**

```
public static double cosineSimilarity(
        float[] a,
        float[] b
) {
    if (a == null || b == null || a.length != b.length) {
        throw new IllegalArgumentException(
                "Vectors must have the same dimension"
        );
    }

    double dotProduct = 0.0;
    double normA = 0.0;
    double normB = 0.0;

    for (int i = 0; i < a.length; i++) {
        dotProduct += (double) a[i] * b[i];

        normA += (double) a[i] * a[i];
        normB += (double) b[i] * b[i];
    }

    if (normA == 0.0 || normB == 0.0) {
        throw new IllegalArgumentException(
                "Zero vector cannot calculate cosine similarity"
        );
    }

    return dotProduct /
            (Math.sqrt(normA) * Math.sqrt(normB));
}
```

### 3.4 Dot Product：点积

**概念：**

点积是两个对应维度数值相乘后求和。

公式：

[
A\cdot B = \sum_{i=1}^{n}a_i b_i
]

例如：

```
A = [1, 2, 3]
B = [4, 5, 6]

点积：
1×4 + 2×5 + 3×6 = 32
```

**作用：**

点积可以衡量两个向量的方向和模长共同作用下的数值关系。

如果两个向量都已经归一化：

[
|A|=|B|=1
]

则：

[
A\cdot B = \operatorname{cosine}(A,B)
]

因此，归一化后的向量可以使用点积计算余弦相似度。

**局限：**

如果向量没有归一化，点积会受到向量模长影响。

不能把任意模型输出的点积分数直接当作余弦相似度。

### 3.5 Euclidean Distance：欧几里得距离

**概念：**

欧几里得距离衡量两个向量在空间中的直线距离。

公式：

[
d(A,B)=
\sqrt{
\sum_{i=1}^{n}(a_i-b_i)^2
}
]

例如：

```
A = [1, 2]
B = [4, 6]

距离：
sqrt((1-4)^2 + (2-6)^2)
= 5
```

通常情况下，距离越小，表示两个向量越接近。

**作用：**

可以用于向量空间中的距离比较。

**局限：**

欧几里得距离受到向量模长影响。

对于已经归一化的向量：

[
|A-B|^2 = 2 - 2\operatorname{cosine}(A,B)
]

因此，对于单位向量，欧几里得距离和余弦相似度具有一致的排序关系。

### 3.6 三种相似度算法对比

| 算法               | 计算对象 | 数值特点       | 主要注意事项 |
| ------------------ | -------- | -------------- | ------------ |
| Cosine Similarity  | 向量方向 | 通常 -1 到 1   | 忽略模长影响 |
| Dot Product        | 向量乘积 | 范围取决于向量 | 受模长影响   |
| Euclidean Distance | 向量距离 | 非负数         | 越小越接近   |

不能在没有确认模型输出特性的情况下，随意替换距离算法。

尤其需要注意：

- 模型是否输出归一化向量。
- 服务商是否提供向量归一化选项。
- 使用的向量索引采用什么距离度量。
- 相似度计算和模型训练目标是否匹配。

### 3.7 向量归一化

**概念：**

向量归一化是将向量缩放，使其模长变成 1。

公式：

[
A'=\frac{A}{|A|}
]

例如：

```
A = [3, 4]

模长 = 5

归一化：
A' = [0.6, 0.8]
```

**作用：**

1. 去除向量模长对比较结果的影响。
2. 使点积可以等价于余弦相似度。
3. 方便部分向量检索系统统一使用点积计算。

**Java 实现：**

```
public static float[] normalize(float[] vector) {

    double norm = 0.0;

    for (float value : vector) {
        norm += (double) value * value;
    }

    norm = Math.sqrt(norm);

    if (norm == 0.0) {
        throw new IllegalArgumentException(
                "Zero vector cannot be normalized"
        );
    }

    float[] result = new float[vector.length];

    for (int i = 0; i < vector.length; i++) {
        result[i] = (float) (vector[i] / norm);
    }

    return result;
}
```

注意：

不要在没有确认模型输出规范的情况下重复归一化。

对于已经归一化的向量，再归一化通常不会改变方向，但会产生额外计算，也可能因浮点精度带来微小变化。

### 3.8 相似度分数不是答案正确率

例如：

```
查询：设备无法启动

Chunk A：控制器上电后无法正常启动
Cosine Similarity：0.91

Chunk B：如何更换设备滤芯
Cosine Similarity：0.32
```

以上仅为示意，不是真实实验结果。

不能据此得出：

```
0.91 = 答案正确率 91%
```

相似度表示向量之间的数值关系，不代表答案正确概率。

一个相似度很高的 Chunk 也可能：

- 只讨论相似设备。
- 缺少用户所需的关键参数。
- 使用了不同型号。
- 描述的是不同故障原因。

必须结合真实查询和正确文档进行评估。

## 四、Embedding 模型选型

### 4.1 模型类型

| 模型类型               | 主要特点               | 典型用途           |
| ---------------------- | ---------------------- | ------------------ |
| 通用 Embedding         | 面向多种文本任务       | 通用语义检索       |
| 中文及多语言 Embedding | 对多语言内容进行优化   | 中文知识库         |
| 检索优化 Embedding     | 训练目标更贴近检索任务 | 文档与查询匹配     |
| 本地开源 Embedding     | 可以自行部署           | 私有化和数据隔离   |
| 云端 API Embedding     | 通过网络调用           | 快速接入和弹性扩容 |

模型是否适合设备知识库，不能只看模型名称中是否包含 Chinese、Multilingual 或 Retrieval。

需要用实际设备问题验证。

### 4.2 模型选型的核心指标

**第一：语言能力**

重点验证：

- 中文设备手册。
- 中文故障描述。
- 中英文混合内容。
- 英文技术术语。
- 型号和专业缩写。

**第二：向量维度**

维度决定向量存储和计算开销，但不能单独代表模型质量。

**第三：最大输入长度**

需要区分：

- 单条文本最大 Token 数。
- 一次请求最多文本条数。
- 一次请求总 Token 数。
- 服务商的并发与速率限制。

**第四：查询和文档编码方式**

部分模型要求：

- Query 使用查询任务类型。
- Document 使用文档任务类型。
- 使用指定指令前缀。

如果模型有明确要求，不能随意省略。

**第五：归一化**

需要核实模型输出是否已经归一化。

**第六：调用成本**

需要计算：

- 文档首次向量化费用。
- 文档更新费用。
- 用户每次查询的向量化费用。
- 批量任务费用。
- 本地部署资源成本。

**第七：部署方式**

需要比较：

- 网络依赖。
- 数据传输。
- 服务可用性。
- API Key 管理。
- GPU/CPU 资源。
- 运维和升级成本。

### 4.3 候选模型对比

以下候选模型采用官方资料中可核实的参数。价格和限制均有区域、账户和计费方案差异，不能将其视为所有部署区域的统一价格。

| 对比维度   | 阿里云 text-embedding-v4                          | BAAI/bge-m3                           | OpenAI text-embedding-3-small |
| ---------- | ------------------------------------------------- | ------------------------------------- | ----------------------------- |
| 提供方     | 阿里云 Model Studio                               | BAAI                                  | OpenAI                        |
| 类型       | 云端 API                                          | 本地开源模型                          | 云端 API                      |
| 语言       | 100+ 多语言                                       | 多语言                                | 多语言                        |
| 默认维度   | 1024                                              | 1024                                  | 1536                          |
| 可配置维度 | 64～2048 中的指定选项                             | 模型输出为 1024                       | 可指定 dimensions             |
| 最大输入   | 8192 Tokens/条                                    | 8192 Tokens                           | 8192 Tokens                   |
| 批量限制   | 每批最多 10 条                                    | 由部署和推理框架决定                  | 受 API 请求限制               |
| 价格       | 新加坡区域官方价格页面约 0.514 CNY/百万输入 Token | 无官方 API Token 单价，需计算部署成本 | 0.02 USD/百万输入 Token       |
| 部署       | 云端                                              | 本地或自建服务                        | 云端                          |
| 数据控制   | 取决于云端区域和服务条款                          | 可自行控制                            | 取决于云端区域和服务条款      |
| 领域适用性 | 需要实测                                          | 需要实测                              | 需要实测                      |

说明：

1. `text-embedding-v4` 的维度、批量和 Token 限制以阿里云对应区域的最新官方文档为准。
2. BGE-M3 的 1024 维和 8192 Token 是模型官方资料参数。
3. OpenAI 的价格来自官方模型页面，具体账户价格、折扣及批处理价格以实际账单规则为准。
4. 不同区域可能有不同的模型开放情况。
5. 以上不是模型质量排名。

### 4.4 结合中文设备知识库的选型建议

**候选 A：阿里云 text-embedding-v4**

适合优先验证的云端方案。

原因：

- 支持中文及多语言。
- 支持 1024 默认维度。
- 支持自定义维度选项。
- 提供 OpenAI 兼容接口。
- 适合通过 Spring AI 的 OpenAI 兼容实现进行集成。

需要进一步验证：

- 中文设备故障问题的召回效果。
- 型号和告警代码的区分能力。
- 设备参数的语义匹配能力。
- 实际接口延迟和并发限制。

**候选 B：BAAI/bge-m3**

适合优先验证的本地模型方案。

原因：

- 多语言能力。
- 1024 维输出。
- 支持较长文本输入。
- 可以本地部署。
- 模型资料提供 Dense、Sparse 和 ColBERT 等不同检索能力。

本次只验证 Dense Embedding，不提前实现混合检索。

需要进一步验证：

- 本地推理速度。
- CPU/GPU 资源消耗。
- 中文设备术语匹配。
- 服务化部署和并发能力。

**候选 C：OpenAI text-embedding-3-small**

适合作为第三方云端参考方案。

原因：

- API 接口成熟。
- 默认 1536 维。
- 官方支持指定输出维度。
- 官方提供 Token 计费信息。

需要验证：

- 当前项目网络环境是否可访问。
- 账户及区域服务是否可用。
- 中文设备领域的实际匹配质量。
- 数据传输是否符合业务要求。

### 4.5 推荐选型思路

不建议现在直接确定唯一模型。

推荐先完成：

```
text-embedding-v4
        |
        +-------------------+
        |                   |
        v                   v
  中文设备问题测试      成本与延迟测试

bge-m3
        |
        +-------------------+
        |                   |
        v                   v
  中文设备问题测试      本地资源测试

        |
        v
统一测试集与评估指标
        |
        v
综合选型
```

如果当前目标是快速完成 Java AI 工程验证，可以优先接入一个云端 Embedding API，再使用 BGE-M3 作为本地对照方案。

## 五、Chat Model 与 Embedding Model 的关系

### 5.1 两种模型的职责

| 对比维度 | Chat Model           | Embedding Model           |
| -------- | -------------------- | ------------------------- |
| 核心职责 | 理解消息、生成内容   | 将文本转换为向量          |
| 输入     | 对话消息、提示词     | 文本                      |
| 输出     | 自然语言或结构化结果 | 浮点数数组                |
| 主要任务 | 问答、总结、分析     | 向量表示                  |
| 典型 API | Chat Completion      | Embeddings                |
| 输出示例 | 设备可能存在供电异常 | `[0.12, -0.34, ...]`      |
| 主要成本 | 输入和输出 Token     | 输入 Token                |
| 主要限制 | 上下文和输出长度     | 单条输入 Token 和批量限制 |

### 5.2 是否必须使用同一家厂商

不需要。

例如：

```
Chat Model：
DeepSeek

Embedding Model：
阿里云 text-embedding-v4

向量模型：
BAAI/bge-m3（另一套可选方案）
```

这是合理的架构设计。

原因：

Chat Model 和 Embedding Model 负责不同任务，不需要使用同一个模型供应商。

只要两种模型分别满足各自的 API 协议、数据安全、业务需求和工程配置即可。

### 5.3 DeepSeek Chat + 独立 Embedding 服务

架构示意：

```
flowchart TD
    A["Java Spring Boot 服务"] --> B["ChatModel"]
    A --> C["EmbeddingModel"]

    B --> D["DeepSeek Chat API"]
    C --> E["独立 Embedding API"]

    E --> F["文档向量"]
    E --> G["查询向量"]

    F --> H["后续向量检索"]
    G --> H

    H --> I["检索结果"]
    I --> B
    B --> J["生成最终答案"]
```

截至本笔记编写时，DeepSeek 官方 API 文档中可以核实 Chat API 的调用方式；不能仅凭 Chat API 兼容 OpenAI，就推断其一定提供 Embeddings API。

因此，本次架构不把 DeepSeek 作为 Embedding 服务。

是否存在其他独立产品或后续接口，应以 DeepSeek 官方 Embedding API 文档实际公布情况为准。

### 5.4 不同厂商的 API 配置

不同模型服务商可能提供：

- OpenAI 兼容 API。
- 自有 REST API。
- Java SDK。
- 本地推理接口。

Spring AI 的 `EmbeddingModel` 抽象可以降低业务层对具体供应商的依赖。

但不能认为所有供应商只要声明 OpenAI 兼容，就一定完全兼容。

仍需要验证：

1. 请求路径。
2. 请求参数。
3. API Key 认证。
4. 模型名称。
5. dimensions 参数。
6. 批量输入格式。
7. 响应字段。
8. 错误码。
9. Token 用量信息。

## 六、Spring AI 1.1.8 Embedding API

### 6.1 EmbeddingModel

**概念：**

`EmbeddingModel` 是 Spring AI 定义的 Embedding 模型抽象接口。

包名：

```
org.springframework.ai.embedding.EmbeddingModel
```

主要作用：

- 提供统一的 Embedding 调用接口。
- 屏蔽部分供应商调用细节。
- 让业务代码不必直接依赖某一家厂商的底层 SDK。

它继承：

```
Model<EmbeddingRequest, EmbeddingResponse>
```

因此，除了便捷方法外，还可以使用统一的请求和响应对象。

### 6.2 EmbeddingModel 核心 API

Spring AI 1.1.8 官方 API 中可以确认以下方法：

| 方法                                     | 作用                           |
| ---------------------------------------- | ------------------------------ |
| `call(EmbeddingRequest request)`         | 执行 Embedding 请求            |
| `embed(String text)`                     | 对单条文本编码                 |
| `embed(List<String> texts)`              | 对多条文本编码                 |
| `embed(Document document)`               | 对 Document 内容编码           |
| `embedForResponse(List<String> texts)`   | 批量编码并返回完整响应         |
| `dimensions()`                           | 获取向量维度                   |
| `getEmbeddingContent(Document document)` | 提取 Document 中用于编码的文本 |

注意：

`embed(Document)` 是对 Document 内容进行编码，不意味着所有 Metadata 都自动加入模型输入。

默认情况下，`getEmbeddingContent(Document)` 返回 `Document.getText()`。

支持 `MetadataMode` 的实现可以根据配置将 Metadata 格式化后加入实际 Embedding 输入。

因此，不能认为 Document 的全部 Metadata 都会自动参与向量化。

### 6.3 EmbeddingRequest

**作用：**

封装 Embedding 请求。

核心组成：

```
EmbeddingRequest
├── inputs
└── options
```

其中：

- `inputs`：待编码的文本列表。
- `options`：模型调用参数。

1.1.8 API 中提供的构造器：

```
new EmbeddingRequest(
        List<String> inputs,
        EmbeddingOptions options
);
```

示例：

```
EmbeddingRequest request = new EmbeddingRequest(
        List.of(
                "设备无法启动",
                "电机温度过高"
        ),
        EmbeddingOptions.EMPTY
);
```

### 6.4 EmbeddingResponse

**作用：**

封装模型返回的结果。

主要包含：

```
EmbeddingResponse
├── embeddings
└── metadata
```

可以通过：

```
EmbeddingResponse response =
        embeddingModel.embedForResponse(
                List.of("设备无法启动")
        );
```

获取结果：

```
response.getResults();
response.getMetadata();
```

每个 `Embedding` 表示一条文本对应的向量结果。

需要注意，`EmbeddingResponse` 不只是一个简单的 `float[]`，它还保留模型响应信息。

### 6.5 Embedding

Embedding 表示单条文本对应的向量结果。

核心内容包括：

- 向量输出。
- 输入索引。
- 结果 Metadata。

在 Spring AI 的结果对象中，可以通过对应的输出访问方法取得 `float[]`。

示例：

```
EmbeddingResponse response =
        embeddingModel.embedForResponse(
                List.of(
                        "设备无法启动",
                        "设备启动失败"
                )
        );

float[] vector = response
        .getResults()
        .get(0)
        .getOutput();
```

这里的 `getOutput()` 是结果向量访问方式；实际编译时仍应以项目依赖的 1.1.8 API 为准。

### 6.6 EmbeddingOptions

`EmbeddingOptions` 用于封装 Embedding 模型的可选参数。

1.1.8 API 中可以确认：

```
EmbeddingOptions.builder()
```

以及：

```
getModel()
getDimensions()
```

具体可用选项由供应商对应的 Options 实现决定。

例如：

- OpenAiEmbeddingOptions。
- OllamaEmbeddingOptions。
- MistralAiEmbeddingOptions。
- ZhiPuAiEmbeddingOptions。

不能认为所有供应商都支持相同的模型参数。

### 6.7 dimensions()

调用：

```
int dimensions = embeddingModel.dimensions();
```

可以获取 Embedding 模型的输出维度。

注意：

Spring AI 1.1.8 的 `EmbeddingModel` 默认实现可能通过一次远程 Embedding 调用获得维度。

因此：

- 不要在每个业务请求中反复调用。
- 可以在启动或模型初始化阶段校验。
- 如果维度已知，可以结合具体实现和配置管理。
- 必须确保配置维度与真实模型输出一致。

### 6.8 单条与批量编码

单条编码：

```
float[] vector = embeddingModel.embed(
        "设备无法启动"
);
```

批量编码：

```
List<float[]> vectors = embeddingModel.embed(
        List.of(
                "设备无法启动",
                "电机温度过高",
                "设备出现异常振动"
        )
);
```

区别：

| 对比     | 单条编码         | 批量编码              |
| -------- | ---------------- | --------------------- |
| 输入     | 一个文本         | 多个文本              |
| 调用方式 | `embed(String)`  | `embed(List<String>)` |
| 网络请求 | 通常单次         | 具体由实现决定        |
| 吞吐量   | 适合少量实时请求 | 适合文档批处理        |
| 失败处理 | 单条结果处理     | 需要考虑批次失败      |

批量编码并不意味着一定减少 Token 费用。

通常计费仍然取决于实际处理的输入 Token 数量。

批处理主要影响网络请求次数、吞吐量和调用效率。

### 6.9 Spring AI 抽象与供应商实现

Spring AI 主要分为：

```
业务层
   |
   v
EmbeddingModel
   |
   v
供应商实现
   |
   +---- OpenAIEmbeddingModel
   |
   +---- OllamaEmbeddingModel
   |
   +---- TransformersEmbeddingModel
   |
   +---- 其他供应商实现
   |
   v
实际 Embedding 服务
```

业务层依赖接口，具体供应商实现负责：

- 请求组装。
- 参数转换。
- 网络调用。
- 响应解析。
- 错误处理。

但是，抽象接口只能统一部分调用形式，不能消除供应商在模型参数、Token 计算、输入限制和错误行为方面的差异。

## 七、Embedding 成本、批处理与性能

### 7.1 Token 与 API 计费

大多数云端 Embedding API 按输入 Token 数量计费。

例如：

```
设备手册：
总输入 Token = 1,000,000

模型单价：
0.5 CNY / 1,000,000 Tokens

理论费用：
0.5 CNY
```

这是计算示例，不代表某个模型的真实账单。

实际费用还可能受到：

- 服务区域。
- 批处理优惠。
- 账户等级。
- 免费额度。
- 促销活动。
- 模型版本。

等因素影响。

### 7.2 文档首次向量化成本

假设：

```
文档总量：100 万 Tokens
```

首次向量化需要对全部 Chunk 进行编码。

如果文档发生变化，只需要对发生变化的内容重新编码，前提是系统能够准确识别内容变化。

### 7.3 用户查询的持续编码成本

用户每次提出新问题，通常也需要进行一次查询向量化。

例如：

```
用户问题 1 -> Query Vector 1
用户问题 2 -> Query Vector 2
用户问题 3 -> Query Vector 3
```

因此，长期运行的知识库存在两类主要 Embedding 成本：

1. 文档首次向量化和更新成本。
2. 用户查询持续向量化成本。

### 7.4 批处理

批量编码的优势：

- 减少部分网络请求开销。
- 提高文档导入吞吐量。
- 更容易控制批量任务。
- 便于统一记录 Token 使用量。

但是，批量大小不能随意设置。

必须满足：

- 单条文本长度限制。
- 单次请求文本数量限制。
- 单次请求总 Token 限制。
- 服务商 RPM 和 TPM 限制。
- 客户端内存和网络超时要求。

例如，阿里云 text-embedding-v4 的 OpenAI 兼容接口文档规定每批最多 10 条，每条输入最多 8192 Tokens。

这些是对应模型和服务区域的官方限制，不是 Spring AI 的通用限制。

### 7.5 超长输入处理

如果 Chunk 超过模型允许的输入长度，需要提前处理。

推荐：

```
输入 Chunk
    |
    v
Token 长度检查
    |
    +---- 未超限 ----> 正常编码
    |
    +---- 超限 ------> 重新切分
                            |
                            v
                       再次检查
                            |
                            v
                         编码
```

不能仅使用：

```
text.length()
```

判断 Token 是否超限。

需要使用对应模型或服务商提供的 Tokenizer。

### 7.6 请求超时、限流与重试

生产环境至少考虑：

| 问题               | 处理方式               |
| ------------------ | ---------------------- |
| 网络超时           | 设置合理超时时间       |
| HTTP 429           | 限流与退避重试         |
| HTTP 5xx           | 根据错误类型重试       |
| API Key 错误       | 不进行无意义重试       |
| 超长输入           | 重新切分               |
| 部分批次失败       | 记录失败批次并支持补偿 |
| 返回向量数量不一致 | 校验响应并拒绝错误数据 |

注意：

对于请求已经成功、但客户端没有收到响应的情况，直接重试可能产生重复费用。

可以通过本地任务状态、缓存和内容指纹降低重复调用。

### 7.7 Embedding 缓存

对于相同内容、相同模型和相同配置，可以缓存向量结果。

建议缓存 Key 包含：

```
SHA-256(
    文本内容
    + 模型标识
    + 模型版本
    + 维度配置
    + 预处理版本
    + Query/Document 任务配置
)
```

这样可以避免不同模型或不同任务类型之间错误复用缓存。

不能只使用文本内容作为缓存 Key。

### 7.8 本地模型成本

本地部署没有直接的 API Token 计费，但并不意味着没有成本。

需要考虑：

- CPU。
- GPU。
- 显存。
- 内存。
- 模型加载时间。
- 推理吞吐量。
- 并发能力。
- 模型更新。
- 部署维护。
- 服务监控。

对于 BGE-M3，需要结合实际部署环境测量 CPU/GPU 性能。

不能仅凭模型文件大小判断生产环境的运行成本。

## 八、向量兼容性与模型版本管理

### 8.1 为什么文档向量和查询向量必须兼容

假设：

```
文档向量：
使用 text-embedding-v4 生成。

查询向量：
使用 BGE-M3 生成。
```

即使两者都输出 1024 维，也不能直接进行相似度计算。

因为两个模型可能拥有不同的向量空间。

因此：

```
Document Embedding Model
        =
Query Embedding Model
```

这里的等号表示必须满足向量空间和编码方式兼容，不只是模型名字相同。

### 8.2 哪些因素会影响向量兼容性

| 因素                | 影响                         |
| ------------------- | ---------------------------- |
| 模型厂商            | 可能使用不同向量空间         |
| 模型名称            | 不同模型可能不兼容           |
| 模型版本            | 升级可能改变向量分布         |
| 向量维度            | 维度不一致时通常无法直接计算 |
| 归一化方式          | 影响距离计算                 |
| 文本预处理          | 相同原文可能变成不同输入     |
| Query/Document 指令 | 可能影响编码分布             |
| Tokenizer           | 影响模型输入表示             |

### 8.3 模型升级风险

例如：

```
旧版本：
text-embedding-v3

新版本：
text-embedding-v4
```

即使两个模型都可以生成向量，也不能假设新旧向量可以直接混合。

模型升级可能导致：

- 输出维度变化。
- 向量空间变化。
- 语义分布变化。
- 相似度分数分布变化。
- 检索排序变化。

因此，模型升级需要评估是否重新生成文档向量。

### 8.4 向量数据 Metadata

建议保存：

```
{
  "documentId": "doc-s7-1200",
  "chunkId": "doc-s7-1200-0001",
  "embeddingProvider": "aliyun",
  "embeddingModel": "text-embedding-v4",
  "embeddingModelVersion": "configured-model-version",
  "embeddingDimensions": 1024,
  "normalization": "provider-default",
  "inputType": "document",
  "preprocessingVersion": "clean-v1",
  "embeddingConfigVersion": "embedding-v1"
}
```

注意：

`embeddingModelVersion` 必须记录真实可获得的版本信息。

如果服务商使用可变模型别名，没有提供不可变版本号，就应记录：

- 调用时的模型名称。
- 配置版本。
- 调用时间。
- 服务商返回的模型标识（如果提供）。

不要自行编造模型版本号。

### 8.5 模型替换的三种情况

| 变化                                | 是否一定重新向量化     | 说明                       |
| ----------------------------------- | ---------------------- | -------------------------- |
| 更换 API 地址，但模型和编码行为一致 | 不一定                 | 需要验证服务兼容性         |
| 模型版本升级                        | 通常需要评估，可能需要 | 取决于版本兼容性           |
| 更换到不同向量空间的模型            | 通常需要               | 文档和查询必须重新统一编码 |

### 8.6 新旧模型迁移

推荐采用双版本迁移：

```
旧模型向量集合
        |
        v
继续提供旧检索服务

新模型
        |
        v
重新编码文档
        |
        v
生成新向量集合
        |
        v
质量验证
        |
        v
切换新检索服务
        |
        v
清理旧向量集合
```

不要在同一个向量集合中直接混合不同模型产生的向量。

## 九、Java 实践与模型对比

### 9.1 实践目标

本次实践使用：

- JDK 21。
- Spring Boot 3.5.x。
- Spring AI 1.1.8。
- 阿里云 text-embedding-v4 作为云端候选。
- BGE-M3 作为本地对照候选。

DeepSeek 继续承担 Chat Model 的角色，不假设它同时提供 Embedding 服务。

本次不实现完整 RAG。

### 9.2 Maven 依赖

使用 Spring AI 1.1.8 的 OpenAI Starter，接入兼容 OpenAI Embeddings API 的服务。

```
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```

项目应通过 Spring AI BOM 统一管理依赖版本：

```
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>1.1.8</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

注意：

不要再为 Starter 单独指定不同的 Spring AI 版本。

### 9.3 云端模型配置

阿里云 Model Studio 提供 OpenAI 兼容 Embeddings API。

新加坡区域可以使用工作空间专属 Base URL：

```
spring:
  ai:
    openai:
      embedding:
        base-url: ${EMBEDDING_BASE_URL}
        api-key: ${EMBEDDING_API_KEY}
        options:
          model: text-embedding-v4
```

环境变量示意：

```
$env:EMBEDDING_BASE_URL="https://<WorkspaceId>.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1"

$env:EMBEDDING_API_KEY="你的 API Key"
```

这里的 WorkspaceId 和 API Key 必须使用自己账户中对应区域的真实值。

不要将 API Key 提交到 Git。

如果当前 1.1.8 项目使用的自动配置属性与上面的配置不匹配，应以对应 Starter 的 1.1.8 配置 Metadata 和实际启动日志为准，不要照搬其他 Spring AI 版本的属性名称。

### 9.4 Chat Model 和 Embedding Model 分离配置

架构上可以使用不同厂商：

```
DeepSeek Chat API
       |
       v
ChatModel

阿里云 Embedding API
       |
       v
EmbeddingModel
```

需要注意：

Spring AI 的不同供应商 Starter、配置属性和自动配置方式可能不同。

如果 Chat 和 Embedding 使用不同供应商，必须分别配置各自的 Base URL、API Key 和模型名称。

不要让 Chat Model 与 Embedding Model 无意中共享同一个供应商地址和密钥。

### 9.5 单条文本 Embedding

```
package com.example.rag.embedding;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

@Service
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;

    public EmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(
                    "Text must not be blank"
            );
        }

        return embeddingModel.embed(text);
    }

    public int dimensions() {
        return embeddingModel.dimensions();
    }
}
```

这里展示的是单一 EmbeddingModel Bean 的使用方式。

如果应用中同时配置多个 EmbeddingModel，需要使用明确的 Bean 名称或 `@Qualifier`，避免注入歧义。

### 9.6 批量 Embedding

```
package com.example.rag.embedding;

import java.util.List;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

@Service
public class BatchEmbeddingService {

    private final EmbeddingModel embeddingModel;

    public BatchEmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public List<float[]> embedBatch(List<String> texts) {

        if (texts == null || texts.isEmpty()) {
            throw new IllegalArgumentException(
                    "Texts must not be empty"
            );
        }

        if (texts.stream().anyMatch(
                text -> text == null || text.isBlank()
        )) {
            throw new IllegalArgumentException(
                    "Texts must not contain blank values"
            );
        }

        return embeddingModel.embed(texts);
    }
}
```

实际项目中还需要根据供应商批量限制进行分批。

不要把数千条文本一次性传给模型。

### 9.7 获取完整响应

```
import java.util.List;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;

public class EmbeddingResponseExample {

    private final EmbeddingModel embeddingModel;

    public EmbeddingResponseExample(
            EmbeddingModel embeddingModel
    ) {
        this.embeddingModel = embeddingModel;
    }

    public void execute() {

        EmbeddingResponse response =
                embeddingModel.embedForResponse(
                        List.of(
                                "设备无法启动",
                                "设备启动失败"
                        )
                );

        System.out.println(
                "结果数量：" + response.getResults().size()
        );

        System.out.println(
                "响应 Metadata：" + response.getMetadata()
        );

        for (var result : response.getResults()) {

            float[] vector = result.getOutput();

            System.out.println(
                    "向量维度：" + vector.length
            );
        }
    }
}
```

该示例展示完整响应和结果向量的获取方式。

实际调用需要配置真实的 Embedding 服务。

### 9.8 Document Embedding

Spring AI 1.1.8 也支持直接对 Document 进行编码。

示例：

```
import java.util.Map;

import org.springframework.ai.document.Document;

Document document = new Document(
        "设备无法启动，需要检查供电和控制器状态。",
        Map.of(
                "documentId", "doc-001",
                "section", "故障排查",
                "page", 120
        )
);

float[] vector = embeddingModel.embed(document);
```

需要注意：

默认情况下，EmbeddingModel 使用 `Document.getText()` 获取文本。

如果具体实现支持 MetadataMode，也可以根据实现配置决定是否把 Metadata 格式化后加入模型输入。

Metadata 并不会因为放入 Document 就自动全部参与 Embedding。

### 9.9 相似度测试数据集

准备至少 5 组文本：

```
List<String> texts = List.of(
        "设备无法启动",
        "设备启动失败",
        "电机运行过程中温度过高",
        "设备出现异常振动",
        "如何更换设备滤芯"
);
```

测试目标：

1. 设备无法启动与设备启动失败是否更接近。
2. 设备过热与设备振动是否存在一定语义关联。
3. 更换滤芯与设备无法启动是否相对不相关。
4. 相同模型对不同文本的编码是否稳定。
5. 相似度是否符合实际业务判断。

### 9.10 相似度计算测试

```
import java.util.List;

import org.springframework.ai.embedding.EmbeddingModel;

public class EmbeddingSimilarityTest {

    public static void main(String[] args) {

        // 此处通过 Spring 注入获得真实 EmbeddingModel。
        // 不使用人工构造向量代替真实模型输出。

    }

    public static double cosineSimilarity(
            float[] a,
            float[] b
    ) {

        if (a.length != b.length) {
            throw new IllegalArgumentException(
                    "Dimension mismatch"
            );
        }

        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < a.length; i++) {

            dot += (double) a[i] * b[i];

            normA += (double) a[i] * a[i];

            normB += (double) b[i] * b[i];
        }

        if (normA == 0 || normB == 0) {
            throw new IllegalArgumentException(
                    "Zero vector"
            );
        }

        return dot / (
                Math.sqrt(normA) * Math.sqrt(normB)
        );
    }
}
```

正式测试需要通过真实模型获取向量，然后调用相似度方法。

### 9.11 模型对比实验

建议使用同一组文本，分别调用两个模型：

```
模型 A：text-embedding-v4
模型 B：BGE-M3
```

对比：

- 单条编码结果。
- 向量维度。
- 模型输出是否归一化。
- 相似度排序。
- 单条响应延迟。
- 批量吞吐量。
- 错误率。
- 成本。
- 本地资源占用。

不要直接比较两个模型生成的向量数值。

应该分别在各自模型内部计算相似度，并对比它们在同一测试集上的排序和检索表现。

## 十、真实向量验证记录

### 10.1 实验环境

| 项目           | 实际值 |
| -------------- | ------ |
| JDK            | 待记录 |
| Spring Boot    | 待记录 |
| Spring AI      | 1.1.8  |
| Embedding 服务 | 待填写 |
| 模型名称       | 待填写 |
| 模型版本       | 待填写 |
| API 区域       | 待填写 |
| 向量维度       | 待实测 |
| 测试时间       | 待填写 |

### 10.2 单条编码记录

| 测试文本     | 请求结果 | 向量维度 | 实际耗时 |
| ------------ | -------- | -------- | -------- |
| 设备无法启动 | 待实测   | 待实测   | 待实测   |

### 10.3 批量编码记录

| 测试内容     | 结果   |
| ------------ | ------ |
| 输入文本数量 | 待实测 |
| 输出向量数量 | 待实测 |
| 向量维度     | 待实测 |
| 批量请求耗时 | 待实测 |
| 单条平均耗时 | 待计算 |
| Token 使用量 | 待实测 |

### 10.4 相似度验证记录

| 文本 A           | 文本 B           | 相似度 | 业务判断 |
| ---------------- | ---------------- | ------ | -------- |
| 设备无法启动     | 设备启动失败     | 待实测 | 待分析   |
| 设备无法启动     | 电机温度过高     | 待实测 | 待分析   |
| 电机温度过高     | 设备出现异常振动 | 待实测 | 待分析   |
| 设备出现异常振动 | 如何更换设备滤芯 | 待实测 | 待分析   |
| 设备启动失败     | 如何更换设备滤芯 | 待实测 | 待分析   |

注意：

相似度数值必须来自真实模型调用。

不能为了让实验看起来合理，人工填写预期数值。

### 10.5 设备领域专项测试

除了普通中文文本，还应该增加以下测试：

| 类型       | 示例                    | 关注点           |
| ---------- | ----------------------- | ---------------- |
| 设备型号   | CPU 1214C               | 型号是否完整     |
| 告警代码   | 设备告警代码 Axxxx      | 代码是否正确关联 |
| 技术参数   | 24 V DC                 | 数值和单位       |
| 故障描述   | 上电后控制器无法启动    | 业务语义         |
| 中英文混合 | CPU 1214C digital input | 多语言理解       |
| 操作步骤   | 更换滤芯的操作流程      | 顺序和上下文     |

设备型号、告警代码和技术参数通常需要结合关键词检索或结构化 Metadata 进行保护。

不能假设 Embedding 一定能精确区分所有型号和数字。

### 10.6 真实实验结果总结

正式实验后填写：

```
模型名称：

模型版本：

实际向量维度：

实际 Token 限制：

单条编码平均耗时：

批量编码平均耗时：

相似度最高的文本组合：

相似度最低的文本组合：

最明显的领域识别问题：

是否适合设备知识库：

选型结论：
```

## 十一、工程选型原则与核心总结

### 11.1 模型选择原则

选择 Embedding 模型时，应按照以下顺序判断：

1. 是否支持目标语言。
2. 是否适合实际业务领域。
3. 是否满足输入长度要求。
4. 是否满足向量维度和存储要求。
5. 是否支持所需部署方式。
6. 是否满足成本和性能要求。
7. 是否能够通过真实测试集验证。

不能仅根据排行榜或维度选择模型。

### 11.2 向量兼容性原则

必须明确：

```
文档向量模型
        |
        v
查询向量模型
```

两者需要处于兼容的向量空间。

如果切换到不同模型，通常需要重新生成文档向量。

### 11.3 Embedding 缓存原则

缓存 Key 需要包含：

- 文本内容。
- 模型名称。
- 模型版本。
- 向量维度。
- 预处理版本。
- Query/Document 任务类型。
- 归一化配置。

避免重复调用和错误复用。

### 11.4 核心知识总结

1. Embedding 将文本转换为浮点数向量。
2. 向量整体表达模型学习到的文本语义特征。
3. Tokenization 是文本编码过程，不等于 Embedding。
4. Embedding 不负责自然语言答案生成。
5. 向量维度决定表示空间的大小。
6. 维度越高不代表模型质量越好。
7. 余弦相似度主要比较向量方向。
8. 点积同时受到方向和模长影响。
9. 欧几里得距离衡量向量空间中的距离。
10. 归一化会影响不同距离算法之间的关系。
11. 相似度分数不等于答案正确概率。
12. Chat Model 和 Embedding Model 可以来自不同厂商。
13. Spring AI 使用 EmbeddingModel 抽象统一调用方式。
14. 单条编码和批量编码都需要考虑服务商限制。
15. 字符数不等于 Token 数。
16. 文档向量和查询向量必须兼容。
17. 模型版本变化可能导致向量空间变化。
18. 不同模型的向量不能仅因维度相同就混合使用。
19. Embedding 缓存需要包含模型及配置标识。
20. 设备知识库必须通过真实领域测试验证模型效果。

## 十二、最终验收清单

### 理论理解

- 

  能解释 Embedding 的定义和基本原理。

- 

  能解释 Tokenization 与 Embedding 的区别。

- 

  能解释向量维度。

- 

  能解释余弦相似度。

- 

  能解释点积。

- 

  能解释欧几里得距离。

- 

  能解释向量归一化。

- 

  能说明相似度分数不是答案正确率。

### 模型选型

- 

  能比较中文和多语言 Embedding 模型。

- 

  能解释向量维度对资源的影响。

- 

  能识别模型输入 Token 限制。

- 

  能比较云端 API 与本地模型。

- 

  能解释模型成本和性能的差异。

- 

  能结合设备知识库选择候选模型。

### Spring AI 实践

- 

  能解释 EmbeddingModel。

- 

  能解释 EmbeddingRequest。

- 

  能解释 EmbeddingResponse。

- 

  能解释 EmbeddingOptions。

- 

  能使用 `embed(String)`。

- 

  能使用 `embed(List<String>)`。

- 

  能使用 `embed(Document)`。

- 

  能使用 `embedForResponse(List<String>)`。

- 

  能使用 `dimensions()`。

- 

  能区分单条和批量调用。

### 工程能力

- 

  能独立配置 Embedding 服务。

- 

  能将 Chat Model 和 Embedding Model 分开配置。

- 

  能处理 API 超时和限流。

- 

  能设计 Embedding 缓存。

- 

  能记录模型版本和维度。

- 

  能解释模型切换后的重新向量化问题。

- 

  能通过真实设备问题验证语义匹配效果。

## 十三、参考资料

### Spring AI 官方资料

1. **EmbeddingModel (Spring AI Parent 1.1.8 API) | EmbeddingModel（Spring AI 1.1.8 接口文档）**：https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/embedding/EmbeddingModel.html
2. **EmbeddingRequest (Spring AI Parent 1.1.8 API) | EmbeddingRequest（Spring AI 1.1.8 请求对象文档）**：https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/embedding/EmbeddingRequest.html
3. **EmbeddingResponse (Spring AI Parent 1.1.8 API) | EmbeddingResponse（Spring AI 1.1.8 响应对象文档）**：https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/embedding/EmbeddingResponse.html
4. **EmbeddingOptions (Spring AI Parent 1.1.8 API) | EmbeddingOptions（Spring AI 1.1.8 模型选项文档）**：https://docs.spring.io/spring-ai/docs/1.1.8/api/org/springframework/ai/embedding/EmbeddingOptions.html
5. **OpenAI Embeddings | OpenAI Embedding 模型集成**：https://docs.spring.io/spring-ai/reference/api/embeddings/openai-embeddings.html

### 阿里云官方资料

1. **OpenAI compatible - Embedding | OpenAI 兼容 Embedding 接口**：https://help.aliyun.com/en/model-studio/embedding-interfaces-compatible-with-openai
2. **text-embedding-v4 Model Info | text-embedding-v4 模型说明**：https://help.aliyun.com/en/model-studio/text-embedding-v4
3. **Embedding | 阿里云 Embedding 模型说明与选型**：https://help.aliyun.com/en/model-studio/embedding
4. **Base URL overview | Model Studio API 地址说明**：https://help.aliyun.com/en/model-studio/base-url

### BGE-M3 官方资料

1. **BAAI/bge-m3 | BGE-M3 多语言 Embedding 模型说明**：https://huggingface.co/BAAI/bge-m3
2. **BGE-M3 | BGE-M3 模型技术文档**：https://bge-model.com/bge/bge_m3

### OpenAI 官方资料

1. **Vector embeddings | 向量 Embedding 指南**：https://developers.openai.com/api/docs/guides/embeddings
2. **text-embedding-3-small Model | text-embedding-3-small 模型说明**：https://developers.openai.com/api/docs/models/text-embedding-3-small
3. **text-embedding-3-large Model | text-embedding-3-large 模型说明**：https://developers.openai.com/api/docs/models/text-embedding-3-large

### DeepSeek 官方资料

1. **Your First API Call | DeepSeek API 首次调用指南**：https://api-docs.deepseek.com/en/
2. **DeepSeek API | DeepSeek API 接口说明**：https://api-docs.deepseek.com/api/deepseek-api/

## 十四、本次学习交付记录

| 交付物             | 要求                  | 当前状态       |
| ------------------ | --------------------- | -------------- |
| Markdown 知识笔记  | 原理、API、选型和总结 | 已整理         |
| 向量相似度算法     | Java 实现             | 已提供         |
| 单条 Embedding     | Java 调用             | 已提供         |
| 批量 Embedding     | Java 调用             | 已提供         |
| Document Embedding | Java 调用             | 已提供         |
| 模型选型表         | 至少两个候选模型      | 已整理三个候选 |
| 真实向量输出       | 真实 API 调用         | 待执行         |
| 真实相似度测试     | 至少五组文本          | 待执行         |
| 模型延迟对比       | 实际调用统计          | 待执行         |
| 模型选型结论       | 基于真实测试          | 待执行         |

**最终目标：** 能够独立配置 Spring AI 1.1.8 EmbeddingModel，理解文本向量化和相似度计算原理，并根据中文设备知识库的实际测试结果选择合适的 Embedding 模型，而不是只会调用一个 API。