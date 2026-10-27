# 本地课程 RAG：成员 2 使用与交接

## 实现范围

`rag` 包提供 PDF/PPTX 原路径登记、按页/幻灯片解析、边界切分、可取消的在线 Embedding、LangChain4j 检索、可插拔的多步检索和 JSON 快照持久化。课件保留在原位置，插件不复制课件。对外接口仍为 `KnowledgeSearch`。

聊天窗口继续使用原有 Mock。成员 1 按 [RAG GUI 分阶段计划](rag-gui-plan.md) 完成 R1 配置、R2 资料管理和 R3 独立检索预览；用户已授权从 R2 推进 R3，R3 等待用户验收。Course materials 内分 Files & index / Retrieval preview 两个子页，后者直接显示真实检索片段和来源，不生成回答。最终 ChatService 接线仍待成员 3，CLI / Java API 也可继续独立使用。

当前在线适配器支持显式选择的 `openai-compatible` Embeddings JSON 协议。它不默认选择供应商、不默认使用任何远端地址或模型，也不保证任意提供方的私有协议兼容。供应商选定后应先核对协议并做真实联调。

## 开发配置

1. 在本地将仓库根目录的 `.env.example` 复制为 `.env`。
2. 填写在线 API 的配置；密钥留在本地。
3. 使用下面的命令导入、建库和查询。

```dotenv
RAG_EMBEDDING_PROVIDER=openai-compatible
RAG_EMBEDDING_BASE_URL=https://your-provider.example/v1
RAG_EMBEDDING_MODEL=your-embedding-model
RAG_EMBEDDING_API_KEY=your-local-key
RAG_CORPUS_ID=default
RAG_DATA_DIR=.course-ai
```

上述 URL 和模型仅为占位符。`BASE_URL` 不包含 `/embeddings`，客户端会追加该路径。

协议为 `POST <base-url>/embeddings`，携带 `Authorization: Bearer <key>`。请求含 `model`、字符串数组 `input` 和 `encoding_format: "float"`；若配置了正数维度，还会传 `dimensions`。响应必须包含 `data` 数组，每项含唯一的整数 `index` 和数值数组 `embedding`。适配器按 `index` 恢复输入顺序。

`RagConfigLoader.load(projectRoot, envDirectory)` 只使用显式传入的开发配置目录；进程环境变量覆盖 `.env`。Java 不会自动加载 `.env`。配置不使用模型密钥作为索引指纹，也不会将密钥写入 manifest。

| 配置 | 默认值与含义 |
| --- | --- |
| `RAG_CORPUS_ID` | `default`；1–64 位字母、数字、下划线或连字符，首位为字母/数字 |
| `RAG_DATA_DIR` | `.course-ai`；相对于传入的工程根目录，也可以使用绝对路径 |
| `RAG_CHUNK_CHARACTERS` | 最多 `800` 个 Unicode 码点，按页/幻灯片独立切分，优先保留段落和句子 |
| `RAG_OVERLAP_CHARACTERS` | 重叠预算最多 `120` 个 Unicode 码点，复用完整语义单元，实际可少于该值或为 0 |
| `RAG_BATCH_SIZE` | `16`；范围 1–128，需适配供应商请求限制 |
| `RAG_HTTP_TIMEOUT_MS` | `20000`；HTTP 连接/单次请求时限，不是整个建库的总时限 |
| `RAG_MIN_SCORE` | `0.75`；开发用初始值，尚未通过真实课程问题校准 |
| `RAG_INCLUDE_SLIDE_NOTES` | `false`；设为 `true` 时将备注中的文本形状一并提取 |
| `RAG_EMBEDDING_DIMENSIONS` | `0` 自动从首个文档向量推断；正数同时成为请求参数和校验值 |
| `RAG_DOCUMENT_PREFIX` | 空；有需要时给文档输入添加前缀 |
| `RAG_QUERY_PREFIX` | 空；有需要时给查询输入添加前缀 |
| `RAG_MULTI_STEP_ENABLED` | `true`；启用多步查询执行与引用补查，设 `false` 恢复单次语义检索 |
| `RAG_MULTI_STEP_MAX_ROUNDS` | `2`；规划/扩展轮数，范围 1–4 |
| `RAG_MULTI_STEP_MAX_QUERIES` | `8`；单次搜索的后续查询总预算，范围 1–16，重复计划不重复执行 |
| `RAG_REFERENCE_PATTERN` | 空时使用通用编号模式，支持如 `F0`、`REQ-42`、`R1.2`、`2.1`；可提供匹配完整编号的正则 |

字符切分不是 token 切分。选定提供方后，应按其 token/批量上限调整片段和批次长度。若其协议使用任务类型字段而非前缀，应新增对应的 `EmbeddingClient` 实现。

### 切分边界与升级

每一页 PDF、每一张 PPTX 幻灯片单独处理：先保留空行划分的完整段落；段落超过上限时，使用 JDK `BreakIterator` 识别句子；过长句子继续按空白分隔的词细分；只有无法继续分词的超长字符串才按 Unicode 码点细分。正常的软换行保留在句子内部，中文和英文句末边界均有测试。

将上述单元依次组合成不超过上限的片段。重叠复用前一片段末尾能够完整容纳的段落/句子/词，最多达到配置预算，并为新内容留出空间；不会为了凑满 120 字符截断正常句子或词。因此片段长度和实际重叠长度会变化。所有片段继续保留原页码/幻灯片位置。

策略指纹已从 `unicode-codepoint-v1` 升为 `boundary-codepoint-v2`。已有旧策略索引需要运行 `ragIndex` 重建，不能仅修改配置后继续使用旧向量；重建会调用 Embedding API。单独修改 `RAG_MIN_SCORE` 不要求重建。

### 多步检索与通用规划接口

多步检索属于查询阶段。已采用 `boundary-codepoint-v2` 的现有索引可直接使用，不需要为此重新 Embedding；多步开关、预算和编号模式不参与建库指纹。

默认流程：

1. 用原问题进行语义检索，保留最相关片段作为主要证据，同时让最多 8 个预选片段参与首轮规划，避免组别分工排在某个功能定义之后时失去补查机会。
2. 内置 `ReferenceQueryPlanner` 从命中正文识别尚未在该片段内定义的编号，查找同一文档中对应的标题/定义片段。例如从组别分工里的 `F0 + F1` 补查文档后面的两个功能定义；同样适用于 `REQ-42 → 2.1` 等编号链。
3. 新定义里的编号可在下一轮继续补查。查询按文档和编号去重，设置轮数与总预算，防止循环或无限扩展。
4. 为补查结果保留触发它的证据（例如“这个组负责哪些编号”的分工片段），先给每个依赖分配候选位置，再补充其他候选和原始语义命中；最终按片段 ID 去重，保持原来源，返回数量始终不超过 `maxResults`。

默认编号补查只使用索引已保存的正文、来源和向量，不读取原课件，也不额外调用 Embedding API。它区分 `F1` 与 `F10`，支持“实现F0和F1”等紧邻中文的编号及“功能F0：”等标题，优先识别明确标题或定义语句，避免把另一个编号列表当作定义。每次补查跟随触发证据所在文档，避免不同文档中同名编号互相混用。若问题本身明确包含编号且初轮无匹配，可以直接在当前课程知识库中查找该编号定义。

这是**通用执行框架 + 内置编号规则规划器**。它没有硬编码组名或某一份作业的功能编号，但默认规则不会自动推理任意自然语言关系，不会将 `Group A` 猜成 `Group1`。初轮组别命中仍需准确；找不到定义时只返回已经找到的证据，不编造缺失内容。超长定义的续文不保证全部返回，应根据上下文需要设置结果预算。

第三个构造参数允许替换规划器：

```java
FollowUpQueryPlanner planner = new ReferenceQueryPlanner(config.retrieval());
LocalKnowledgeSearch search = new LocalKnowledgeSearch(config, embeddings, planner);
```

成员 3 可实现 `FollowUpQueryPlanner.plan(question, evidence)`，返回可取消的异步计划。`evidence` 初轮包含最多 8 个预选语义片段，之后包含本轮新增证据；每项都有文档 ID 和来源。执行器负责轮数、预算、去重和取消：

```java
// 明确编号：在指定文档内定位定义，不额外请求向量。
new FollowUpQueryPlanner.Query("REQ-42 的定义", documentId, "REQ-42", sourceChunkId);

// 自然语言子查询：reference 为空，执行后续 Embedding 和语义检索。
new FollowUpQueryPlanner.Query("传输子系统的职责和约束", documentId, null);
```

可选 `sourceChunkId` 必须指向已提供的证据片段，用于将该证据和补查结果一起纳入返回预算。内置规划器自动设置该字段；自定义规划器根据非主要候选生成查询时也应填写。三参数构造器适合无需补充额外父证据的查询。

自然语言子查询会额外调用 Embedding API，规划器若调用模型则还需要自己的模型客户端。当前不默认启动 ChatModel，Embedding 配置也不会被当作聊天规划模型使用。搜索关闭或取消时会取消当前规划/Embedding future，借用规划器的资源由其组装者释放。

验证示例（组名应与真实资料一致）：

```powershell
.\gradlew.bat ragSearch '-PragQuery=Group A需要完成哪些功能？' -PragMaxResults=8 --console=plain
```

返回的是组别分工和功能定义等证据片段及其页码。`maxResults=1` 只能返回一个片段，无法保证同时容纳主要证据和两个定义；这类问题建议提供至少 3 个结果位置。

## 命令行使用

下面命令从插件仓库根目录执行，默认也把该仓库作为数据根目录。

```powershell
# 多个路径使用 | 分隔；只登记原路径，不复制课件，也不调用在线 API。
.\gradlew.bat ragImport '-PragFiles=C:\course\week1.pdf|C:\course\week2.pptx'

# 对已导入的本课程资料全量重建索引，会调用在线 Embedding API。
.\gradlew.bat ragIndex

.\gradlew.bat ragSearch '-PragQuery=What is inheritance?' -PragMaxResults=5

# 文件移走后可移除旧路径，再 ragImport 登记新路径；此命令只修改清单。
.\gradlew.bat ragUnregister '-PragFiles=C:\course\week1.pdf'
```

显式指定别的工程或配置目录：

```powershell
.\gradlew.bat ragIndex '-PragProjectRoot=E:\my-java-project' '-PragEnvDir=E:\java_assignment_A\java-ai-assistant-plugin'
```

程序错误以非零退出码结束，打印安全错误类别和提示。问题、密钥等参数不要直接硬编码到 Gradle 构建脚本中。

## 文件布局与更新

```text
<projectRoot>/.course-ai/
    documents/<corpus-id>/
        .registry.lock
        documents.json
    indexes/<corpus-id>/
        .rebuild.lock
        manifest.json
        store-<generation-id>.json
```

导入只将课件规范化后的绝对原路径写入 `documents.json`，不复制或修改课件。用课程 ID 和原路径生成稳定文档 ID，同一路径重复登记复用记录，不同路径下的同名课件独立登记。重新建库时计算原文件当前内容的 SHA-256，因此原位置上的文件内容更新后，重建会读取新内容。

登记默认不自动建库，UI 可在登记成功后调用 `rebuild()`。批量登记遇错时异常完成，之前成功登记的路径会保留，可安全重试。

**已经建好的索引检索不需要原文件。** 索引包含向量、片段正文和来源，课件移动或删除后仍可加载和查询旧索引；结果反映建库时的内容。只有重新建库时才读取原文件，此时缺失或不可读会明确失败，并保留旧索引。文件移动后使用 `ragUnregister` 移除旧路径，再 `ragImport` 登记新路径并重建。移除登记不会删除原文件或现有索引。

旧版本复制进 `.course-ai/documents/` 的课件不会被新版本自动扫描或清理；使用新模式时重新登记原文件即可。路径清单损坏时修复清单，不会静默视为空知识库。

PDF 页码是文件中的物理页序号，空白页不产生片段但仍占页号。PPTX 使用 `SourceReference.scope = "slide N"`，页码字段为空。支持文本框、表格、组合形状和可选备注；不识别图片/图表里的文字，全部不可提取正文的文件会报错。

片段 ID 对相同文档内容、位置、切分配置和正文稳定；引用 ID 为 `R-<chunkId>`。metadata 保留文档 ID、显示名、片段 ID、引用 ID 和页码/范围。成员 3 可以生成展示编号，但必须维护编号与真实来源的映射。

manifest 记录格式版本、模型/输入模式指纹、切分规则、实际维度、片段数、源资料哈希、快照文件名和快照校验和。模型、维度/前缀或切分配置改变后要求重建，单独更换同一服务的 API Key 不使索引失效。

重建持有知识库的跨进程文件锁。新快照先完整写入，再原子替换 manifest；解析/API/保存失败时不替换旧 manifest。不支持原子 manifest 替换的文件系统会明确报错。已发布的旧快照暂时保留，便于在途读取，自动清理历史快照尚未实现。

检索实例缓存一个完整快照。成功重建后调用 `reload()` 切换；在途请求继续使用原快照。原始资料改变不会自动建库，需显式 `rebuild()` 和 `reload()`。

## Java 接入示例

```java
// IDE 适配层从当前 Project 获取根目录，并显式传给核心模块。
RagConfig config = RagConfigLoader.load(projectRoot, developmentEnvDirectory);

EmbeddingClient embeddings = EmbeddingProviderFactory.create(config);
CourseIndexer indexer = new CourseIndexer(config, embeddings);
LocalKnowledgeSearch search = new LocalKnowledgeSearch(config, embeddings);

// 文件选择按钮拿到 paths 后登记原路径；建库直接读原文件。
indexer.importFiles(paths)
        .thenCompose(imported -> indexer.rebuild())
        .thenCompose(result -> search.reload());

CompletableFuture<List<KnowledgeChunk>> pending = search.search(question, 5);
// 取消会传播至当前 HTTP future 和本地工作任务。
pending.cancel(true);

// 内容/项目释放时：先关闭两个调用方，再关闭它们共享的客户端。
search.close();
indexer.close();
embeddings.close();
```

IDE 适配通过 `settings.RagSettingsConfigurable` 配置普通参数，通过 PasswordSafe 单独存取密钥；`application.ProjectRagService` 直接组装 `RagConfig` 与项目级服务，不自动加载 CLI 的 `.env` 或进程环境变量。Embedding 客户端和密钥读取延迟到真正需要 Embedding 时，登记资料不需要密钥。保存配置取消旧服务任务，不自动建库或联网。IDE 首版固定使用当前项目根目录下的 `.course-ai` 和 `default` 课程库。`CourseAiToolWindowFactory` 通过 `KnowledgeBasePanel` 调用项目级管理服务，聊天仍使用 Mock。

R2 管理 API：`DocumentImporter.listRegisteredDocuments()` 返回不含正文/哈希的原路径、稳定文档 ID 和 Available/Missing/Unreadable 元数据；原有 `listDocuments()` 仍保留给显式建库。`LocalKnowledgeSearch.reloadSummary()` 返回与实际加载快照一致的文档 ID 集合、片段数和维度，取消语义与 `reload()` 一致。清单损坏不会静默返回空列表。完整界面验收见 [R2 审阅](rag-gui-r2-review.md)。

## 分数、失败和生命周期

LangChain4j 1.7.1 内存库的分数为 `(cosine + 1) / 2`，范围 `[0,1]`，不是概率。开发阈值 0.75 未校准；交接前使用真实问题/来源清单调参，并用独立问题验证。返回结果按分数降序、片段 ID 升序排序，限制 `maxResults` 为 1–100。索引以最多 256 条的只读分区检索，在分区之间和结果处理时检查取消。

多步执行后，所有片段的返回分数仍统一为**原问题与该片段**的余弦换算值，不人为给补查结果提高分数。`RAG_MIN_SCORE` 控制语义命中门槛；通过明确编号关联或有效子查询选中的证据，原问题分数可以低于这个门槛，这是为了解决定义内容与原问题字面相似度低的情况。结果预算优先保留主要证据及其补查依赖，再填充普通候选，所以多步结果集合不一定等于单次向量 Top-K。

`0.75` 对应余弦相似度 `0.5`，是否严格取决于模型、问题和资料，不能凭单个分数判断。测试时可对同一组已知相关/无关问题比较 `0.70`（余弦 `0.4`）与 `0.75`，查看相关片段是否被漏掉、无关片段是否混入。降低阈值通常增加候选，也可能引入噪声；此次保留现有默认值和用户本地配置，尚未把任何值标记为已校准。

成功加载知识库，语义检索和显式编号补查均无证据时，返回不可变空列表。未建库、损坏、模型不兼容、错误向量、规划失败和 API 失败均异常完成，不将失败伪装成无匹配。向量必须维度一致、非零、数值有限。

`RagException` 不包含聊天请求 ID。成员 3 应使用当前 `ChatRequest.requestId` 转成 `ServiceFailure`，不要把检索失败当成空结果：

| RagException.Reason | 建议 ServiceFailure.Category | 重试性 |
| --- | --- | --- |
| `INVALID_INPUT`、`CONFIGURATION` | `INVALID_INPUT` | 通常需要修改输入/配置 |
| `TIMEOUT` | `TIMEOUT` | 可重试 |
| `NETWORK_ERROR` | `NETWORK_ERROR` | 可重试 |
| `EMBEDDING_ERROR` | `MODEL_ERROR` | 使用 `isRetryable()`，429/5xx 可重试 |
| 索引/文档/存储错误、`CLOSED` | `UNKNOWN` | 使用安全提示及 `isRetryable()` |
| `RETRIEVAL_ERROR` | `UNKNOWN` | 规划器失败或计划非法，使用安全提示及 `isRetryable()` |

取消保持 `CancellationException`/已取消 future，由现有请求生命周期处理。各服务只关闭自己的工作线程；借用的 Embedding 客户端由组装者统一关闭。HTTP future 取消会调用 JDK HTTP transport future 的取消；不保证远端已经接收的请求停止计费或执行。

## 验证与实际联调

```powershell
.\gradlew.bat test buildPlugin verifyPluginProjectConfiguration verifyPluginStructure --console=plain
.\tools\check-chat-ui.ps1
# 依赖已缓存时可使用 -Offline，内部 Gradle 解析不会联网。
.\tools\check-chat-ui.ps1 -Offline
```

自动测试使用现场生成的 PDF/PPTX、确定性 Embedding 替身和本机 HTTP 服务，覆盖文件/来源/索引/异常/取消。它们不代表已经通过真实供应商、真实课程材料和 IDEA 内文件选择 UI 的验收。

实际联调仍需：提供方/模型/密钥、真实课件、标注期望来源的问题列表、阈值验证，以及成员 1/3 的 UI/ChatService 接入。此时记录资料规模、Embedding 模型、阈值、检索耗时和引用正确性。

## 本次验证记录（2026-10-26）

- 通用多步检索接入后运行 `gradlew.bat test buildPlugin verifyPluginProjectConfiguration verifyPluginStructure --offline --console=plain --no-configuration-cache`：通过。
- RAG 测试共 48 项：边界切分 9、配置 4、解析/登记 6、本地 HTTP 5、基础检索/索引/生命周期 10、多步检索 14；失败和错误均为 0。
- 多步用例覆盖远处 PDF 页面中的定义、次级分工候选与父证据保留、非 F 编号和章节号、中文编号、同文档范围、F1/F10 边界、循环/轮数/查询预算、严格结果数量、关闭开关、无语义命中的显式编号、自定义自然语言后续查询、规划/Embedding 取消及安全失败。
- 保留“原文件删除/移动后重启仍可查询，重建失败且保留旧索引”的原路径模式测试。本地 HTTP 测试使用显式管理并等待退出的平台线程，避免 JVM 全局虚拟线程轮询器干扰 IDEA 的逐测试线程泄漏检查。
- 此前运行 `tools/check-chat-ui.ps1 -Offline`：通过原有 Swing 交互/布局检查及全部 11 项请求生命周期检查；此次未修改 UI 接线。
- 构建产物位于 `build/distributions/`，测试报告位于 `build/reports/tests/test/index.html`。
- 已只读核对本地现有索引的 `F0/F1/F2` 标题形式与新版切分指纹；本次未代用户执行付费/在线多步查询。真实资料的完整评测及 IDEA 内上传/聊天接线仍待验收。
