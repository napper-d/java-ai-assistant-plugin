# 成员 2 任务：实现课程资料的本地 RAG 检索

状态：原路径登记、边界切分和通用多步检索已实现，48 项 RAG 自动测试及插件打包/配置/结构检查通过；原有 Swing/11 项请求生命周期检查此前已通过。阈值校准和成员 1/3 接线仍待完成，真实供应商/课件的完整验收需补充记录。使用方法见 [rag-search.md](rag-search.md)。

## 交付目标

输入课程资料目录和一个问题，能够完成资料解析、切分、向量生成、本地保存、重启加载和相关片段检索，返回可追溯到文档与页码/章节的 `KnowledgeChunk`。

对外保留现有接口：

```java
CompletableFuture<List<KnowledgeChunk>> search(String query, int maxResults);
```

更新后的技术建议：使用 LangChain4j 的 Embedding/EmbeddingStore 能力，采用 `InMemoryEmbeddingStore<TextSegment>` 在内存中检索，并持久化为本地 JSON 快照。此前的手写 JSONL/余弦检索方案改为备选；具体依赖版本与插件打包兼容性在开发时验证。

已确认输入主要为 PDF、PPTX，课程文件保存在用户原有本地目录，导入只登记原路径，不复制课件。PDF 按页、PPTX 按幻灯片保留引用位置。扫描件/图片型幻灯片需要 OCR，第一版应明确报告正文不可提取的情况。

LangChain4j 的内存向量库支持保存/恢复向量及关联文本，参见 [本地持久化文档](https://docs.langchain4j.dev/integrations/embedding-stores/in-memory/)；其 RAG API 也支持携带片段元数据，参见 [RAG 文档](https://docs.langchain4j.dev/tutorials/rag/)。使用这些基础组件，继续由现有 `KnowledgeSearch` 作为对外交接接口。

## 开始前确定的事项

- [x] 主要资料格式：PDF、PPTX；保存在本地目录，支持文件导入。
- [x] Embedding 生成方式：在线 API；提供方尚未选定，配置保持可替换。
- [ ] 拿到真实 PDF/PPTX 样例，确认主要语言、文本可提取性、表格和备注使用范围。
- [ ] 选择实际 Embedding 提供方：确定接口协议、模型标识/版本、向量维度、请求限制，以及查询与文档是否需要不同的输入前缀/任务类型。
- [ ] 与成员 3 约定分数含义、相关性阈值、引用标识及检索失败的转换方式。
- [ ] 确定课程知识库的标识。索引目录按用户要求优先放在当前打开的工程内，建议默认 `.course-ai/`，并允许配置绝对路径。

Embedding 提供方尚未选择时，可以用测试替身验证管线；正式交付必须运行真实 Embedding 的建库和查询，不能用随机向量或固定回答充当检索实现。

## 代码范围和文件建议

生产代码放在 `src/main/java/edu/teamc/courseaiassistant/rag/`。以下文件名可按实现需要合并，重点是职责完整。

| 文件 | 职责 |
| --- | --- |
| `RagConfig.java` | 知识库 ID、输入/索引目录、切分参数、阈值、Embedding 配置、请求超时 |
| `RagConfigLoader.java` | 读取进程环境和显式指定目录的开发 `.env`，解析相对于工程根目录的数据路径 |
| `DocumentSection.java` | 解析后的页/章节文本及来源位置 |
| `DocumentChunk.java` | 待建库片段：稳定 ID、原文和 `SourceReference` |
| `DocumentImporter.java` | 登记原路径、持久化清单、移除登记；建库直接读取原文件，不复制课件 |
| `IndexManifest.java` | 格式版本、模型指纹、维度、切分配置、源文件指纹、片段数和快照文件名 |
| `DocumentLoader.java` | 按格式分派解析，PDF 用 PDFBox 按页提取，PPTX 用 POI XSLF 按幻灯片提取 |
| `ChunkSplitter.java` | 页/幻灯片内按段落→句子→词→码点逐级切分，完整单元重叠与来源继承 |
| `EmbeddingClient.java` | 包装所选模型适配器，统一文档/查询生成模式、异常与取消能力 |
| `EmbeddingProviderFactory.java` | 根据配置创建实际在线模型适配器；提供方选定后引入相应模块 |
| `LocalIndexStore.java` | 包装 LangChain4j 内存向量库的 JSON 快照保存/加载、元数据与向量校验 |
| `CourseIndexer.java` | 编排加载 → 切分 → Embedding → 保存，提供显式建库/重建入口 |
| `LocalKnowledgeSearch.java` | 实现 `KnowledgeSearch`，后台执行查询向量生成、向量库检索和共享模型转换 |
| `RagException.java` | 表达输入错误、索引不可用/不兼容、解析失败、Embedding 失败等原因 |
| `FollowUpQueryPlanner.java` | 通用异步后续查询规划接口，支持编号或自然语言子查询及来源关联 |
| `ReferenceQueryPlanner.java` | 可配置编号识别与定义补查策略，适用于多种文档编号 |
| `MultiStepRetrieval.java` | 轮数/查询预算、去重、文档范围、父证据保留、后续语义查询和取消传播 |

采用框架后无需重复实现向量存储数据结构和余弦检索。仍需实现配置、位置保留、导入、快照发布、共享模型适配及生命周期管理。框架具体模块在提供方选定并验证依赖后确定。

复用 `service/KnowledgeSearch.java`、`model/KnowledgeChunk.java` 和 `model/SourceReference.java`。共享接口或字段如需修改，先形成具体提案交给受影响成员确认。

`KnowledgeChunk.score` 是本次查询的相关性，不是片段固有属性。磁盘中保存正文、来源和向量；检索时再创建带当前分数的 `KnowledgeChunk`。

## T1：内部数据和配置

- [x] 实现配置和内部数据类型，验证非空标识、有效来源位置和合法切分参数。
- [x] 定义稳定标识规则：课程 ID 和规范化原路径决定文档 ID，同一路径内容更新后保持文档 ID；片段 ID 包含位置、正文和切分配置。
- [x] 文档名称用于显示，独立文档标识用于区分身份；保存内容哈希用于检测资料变化。
- [x] 核心 RAG 类通过构造器接收 `Path`、配置和 Embedding 客户端，保持可脱离 Swing/IDEA 运行。
- [x] API 密钥从外部配置注入；配置示例只包含占位值。
- [x] 开发期显式加载 `.env`，进程环境变量优先；使用 [dotenv-java](https://github.com/cdimascio/dotenv-java)，通过 `dotenv.get` 读取。
- [ ] 安装给使用者后，由配置界面/适配层将密钥保存到 IntelliJ [PasswordSafe](https://plugins.jetbrains.com/docs/intellij/persisting-sensitive-data.html)；不把 `.env` 打进插件包。此配置 UI 与成员 1 对接。
- [ ] 目录默认相对于 IDEA 当前打开的工程根目录解析；IDE 适配层传入 `Project.getBasePath()` 对应路径，核心层不依赖 `user.dir`。没有工程根目录时要求显式配置数据目录。

验收：可以构造一个有真实来源位置的片段；非法配置被清楚拒绝。

## T2：资料解析与切分

- [x] 实现 PDF 页级提取，页号从 1 开始，保留空白页造成的页号间隔。
- [x] 实现 PPTX 幻灯片级提取，保留原始幻灯片序号；提取文本框、表格、组合形状；备注由配置控制，默认不提取。
- [x] PPTX 的 `SourceReference.scope` 使用如 `slide 7` 的范围，页码字段留空；索引 metadata 保存 `slideNumber`。
- [x] 按段落→句子→词→Unicode 码点逐级细分；片段不跨页/幻灯片，长度上限和完整单元重叠预算可配置。
- [x] 转成 LangChain4j `TextSegment` 后保留标识、文档名、页码/范围，保存和加载后还原 `SourceReference`。
- [ ] 明确长度计量是字符还是 token；若使用字符切分，不把它描述为 token 切分，并遵守提供方输入上限。
- [x] 空白内容不建片段；全部不可提取、损坏文件和不支持的格式有明确错误。
- [x] 释放文件资源；文档提取后不改变引用页号或根据正文猜测页码。

验收：用至少两页、包含空白页和长段落的资料检查片段正文、页号、重叠和标识稳定性。

PDF 解析可使用 Apache PDFBox；依赖版本在实施时锁定并通过本项目 JDK 21 构建。3.x 使用 `Loader` 加载文档，参见 [入门文档](https://pdfbox.apache.org/3.0/getting-started.html) 和 [迁移指南](https://pdfbox.apache.org/3.0/migration.html)。PPTX 使用 Apache POI XSLF，参见 [XSLF Cookbook](https://poi.apache.org/components/slideshow/xslf-cookbook.html)。为保留位置，先按页/幻灯片提取，再交给框架切分。

文件导入交接：成员 1 提供文件选择按钮，成员 2 提供原路径登记和建库入口。流程为选择文件 → 登记原路径 → 后台读取原文件并建库。同一路径重复登记复用记录，不同路径下的同名文件独立登记。原文件丢失只影响重建，已有索引检索仍读取保存的正文和向量。

## T3：真实 Embedding

- [x] 实现 `EmbeddingClient` 和可配置的 `openai-compatible` JSON 协议适配器，区分文档/查询前缀。
- [x] 批量输入按响应序号恢复顺序，拒绝重复/越界序号。
- [x] 验证向量数量、维度、有限数值和非零范数；异常响应不能写进索引。
- [x] 配置连接/请求超时，取消传播到 JDK HTTP transport future；本地 HTTP 测试覆盖。
- [x] 失败提供安全消息与重试性，不返回原始响应和密钥。
- [ ] 选定实际供应商并验证其协议、限制、模型、文档/查询模式和真实调用效果。

验收：至少一份真实资料可以生成向量，同一模型可以生成问题向量；同时用受控测试验证超时、错误响应和取消。

## T4：本地持久化和建库入口

- [x] 实现 `LocalIndexStore.save/load`，框架 JSON 保存向量、正文、metadata 和稳定条目 ID。
- [x] `manifest` 记录版本、模型/输入模式指纹、切分配置、资料哈希、维度、数量及快照校验和。
- [x] 加载校验片段 ID、数量、维度、数值、来源及模型兼容性；失败不会转成无匹配。
- [x] 新快照使用独立文件名，完整写入后原子替换 manifest；失败保留旧版本。
- [x] 跨进程文件锁串行重建，检索使用完整的只读快照。
- [x] `CourseIndexer.rebuild` 显式全量重建，查询复用已保存的文档向量。
- [x] 提供 Gradle `ragImport`、`ragIndex`、`ragSearch` 和配置示例。

建议存储布局：

```text
<当前打开的工程根目录>/
    .course-ai/
        documents/<corpus-id>/
            .registry.lock
            documents.json
        indexes/<corpus-id>/
            manifest.json
            store-<generation-id>.json
```

`.course-ai` 是建议默认数据目录，可通过配置改为绝对路径，以便多个工程共用课程知识库。这里的“工程根目录”是插件使用者打开的工程；开发/命令行测试可显式指定当前插件仓库根目录。保存目录由解析后的路径决定，不依赖 `runIde` 的进程工作目录。生成目录加入对应工程的 Git 忽略规则。

验收：建库后重启程序，直接加载已有向量仍可检索；模拟保存失败后，旧索引仍可使用。

## T5：检索算法与 KnowledgeSearch 实现

- [x] 校验进入向量库的向量维度、有限数值及非零范数。
- [x] 锁定 LangChain4j 1.7.1，分数为 `(cosine + 1) / 2`，并用测试验证。
- [ ] 阈值用实际资料评测后确定并与成员 3 约定，分数表示相关性而非概率，不直接套用任意固定值。
- [x] `LocalKnowledgeSearch` 实现原有接口，调用向量库检索并转换为 `KnowledgeChunk`。
- [x] 同分按稳定 ID 排序，返回不可变结果且不超过 `maxResults`。
- [x] 校验空白问题和非法 `maxResults`，结果数量范围为 1–100。
- [x] 无匹配返回空列表，未建库、损坏和 Embedding 失败异常完成。
- [x] 耗时操作在后台执行，缓存只读快照并提供显式 `reload()`。

验收：同一知识库能检索出相关片段；无关问题经过阈值过滤后得到空结果；返回值符合现有数据契约。

## T6：异步生命周期和错误交接

- [x] 返回非 null future，失败异常完成，不吞错返回空列表。
- [x] 取消传播到 Embedding 和本地工作任务，按分区检查取消。
- [x] 服务拥有工作线程并提供 `close()`；共享 Embedding 客户端由组装者统一关闭。
- [ ] 与成员 3 明确 `RagException` 到 `ServiceFailure` 的映射，包括错误分类和重试性。

接口注意：`KnowledgeSearch.search(String, int)` 没有聊天请求 ID，而 `ServiceFailure` 必须包含请求 ID。建议 RAG 返回带明确原因的 `RagException`，由成员 3 的 `ChatService` 使用真实 `ChatRequest.requestId` 转换为 `ServiceFailure`，避免在 RAG 内编造聊天请求 ID。最终异常约定应记录在交接文档中。

验收：取消、超时、释放后资源停止；失败不会被误判为一般知识问答的“无匹配”分支。

## T7：测试、演示与构建

- [x] 在 `src/test/java/edu/teamc/courseaiassistant/rag/` 添加行为测试并配置 Gradle 测试任务。
- [x] 解析/切分：PDF 页码和空白页、PPTX 幻灯片号/表格/组合文本、段落/中英文句子/词边界、Unicode 重叠、长度上限和稳定片段 ID。
- [x] 导入后端：重复导入、同名不同内容、无效文件和后台建库结果。
- [x] 存储：保存后重载、课程隔离、校验和损坏、模型不兼容、重建失败保留旧索引；原文件移动/删除后旧索引仍可查询。
- [x] 检索：相关排序、归一化分数、同分稳定排序、Top-K、无匹配、非法输入、维度错误、零向量和非有限数值。
- [x] 生命周期：Embedding 失败、超时、取消和关闭时停止自己拥有的后台任务。
- [x] 测试使用生成的 PDF/PPTX、可控向量及本地 HTTP 服务，明确未代表真实供应商验收。
- [ ] 补充真实课件、清单/索引发布失败及 IDE 文件选择场景的实际环境验收。
- [x] `tools/check-chat-ui.ps1` 改为复用 Gradle 导出的依赖 classpath，并增加 `-Offline`。
- [x] 按需引入并锁定依赖，测试和插件打包/结构检查通过。
- [ ] 在真实 IDEA 窗口中验收依赖加载和最终接线。
- [x] 多步检索接入后的 48 项模块测试及插件打包/配置/结构检查通过；此前 UI/lifecycle 检查通过，结果记录在使用文档。

```powershell
.\gradlew.bat test buildPlugin verifyPluginProjectConfiguration verifyPluginStructure
.\tools\check-chat-ui.ps1
```

验收演示：导入实际资料 → 保存索引 → 重启加载 → 输入相关问题 → 输出片段/分数/页码；另演示无匹配和失败两条路径。记录资料规模、模型、阈值、机器环境和检索耗时，不预先声称固定性能。

## T8：交接文档

- [x] 新增 `docs/rag-search.md`，说明配置、命令、数据布局、分数、异常和资源释放。
- [x] 提供创建和调用 `LocalKnowledgeSearch` 的 Java 示例及资源归属说明，供成员 3 审阅。
- [ ] 提供带期望来源的课程问题及无关问题清单，用于人工核对召回和阈值；用分开的调参与验证样例记录结果。
- [ ] 与成员 1 对接索引路径解析和服务构造；由其完成最终 Tool Window 接线。
- [x] 更新共享状态文档，明确 RAG 模块已实现、Tool Window 仍使用 Mock。

## T9：多步检索

- [x] 通用异步规划接口与执行框架，保留原有 `KnowledgeSearch` 和数据契约。
- [x] 默认支持可配置编号引用补查，明确区分内置规则和模型驱动规划的能力范围。
- [x] 同文档范围、完整编号匹配、循环去重、轮数/总查询预算、严格结果数量和父证据保留。
- [x] 自然语言后续查询可通过自定义规划器接入，取消传播到规划器/后续 Embedding future。
- [x] 搜索时复用持久化索引，原问题分数不人为抬高，多步配置不要求重新建库。
- [x] 多步测试覆盖次级分工证据、中文编号、跨页/章节/非 F 编号、多轮和循环、文档隔离、结果预算、通用自然语言规划接口及取消/错误处理。
- [ ] 真实课件上的组别问题、定义覆盖程度、结果预算和规划策略评测，与成员 3 联调。

## 建议开发顺序

1. T1 + T2：先看到课程原文被正确切成带页码/范围的片段。
2. 用测试 Embedding 跑通 T4 + T5：先验证保存、加载和排序逻辑。
3. T3：接入实际模型，跑通真实建库和问题检索。
4. T6 + T7：补齐取消、异常、资源释放和验证。
5. T8：完成交接，再由成员 1/3 接入真实聊天流程。

## 完成标准

以上任务全部验收，且成员 3 可以通过原有 `KnowledgeSearch` 调用你的实现，稳定获得“带来源的相关片段 / 成功但无匹配 / 明确检索失败”三种结果。UI、答案生成和 Diff 修改继续按现有模块分工交付。
