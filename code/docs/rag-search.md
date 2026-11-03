# 本地课程 RAG：成员 2 使用与交接

## 实现范围

`rag` 包提供 PDF/PPTX 原路径登记、按页/幻灯片解析、边界切分、可取消的在线 Embedding、LangChain4j 向量检索和 JSON 快照持久化。每次查询生成一次问题向量，按相似度阈值过滤并返回最相关的片段。课件保留在原位置，插件不复制课件。对外接口仍为 `KnowledgeSearch`。

当前 Chat 已接入真实 LLM 与课程检索。Course materials 采用文件名列表、加号添加、逐行删除及齿轮设置；没有独立 Retrieval preview，来源及原文详情随 Chat 回答展示。详见 [I3 审阅](integration-i3-review.md)。

当前在线适配器支持显式选择的 `openai-compatible` Embeddings JSON 协议。它不默认选择供应商、不默认使用任何远端地址或模型，也不保证任意提供方的私有协议兼容。供应商选定后应先核对协议并做真实联调。

## 插件配置

1. 打开本地项目，在 Settings > Tools > Course AI Assistant > Course RAG 填写 API 基础地址、Embedding 模型和 API Key。
2. 点击 Apply 或 OK 保存。普通参数保存到当前项目的 workspace，密钥单独存入 IDEA Password Safe；密钥框留空会保留已保存的密钥。
3. 在 Course materials 通过加号添加 PDF/PPTX，插件自动建库，顶部显示进度。
4. 索引就绪后，在 Chat 输入问题，查看回答、编号来源及可展开证据。

基础地址不包含 `/embeddings`，客户端会追加该路径。保存设置不会调用 API 或自动建库；登记资料不需要密钥，建库和查询需要已配置的 Embedding 服务。

协议为 `POST <base-url>/embeddings`，携带 `Authorization: Bearer <key>`。请求含 `model`、字符串数组 `input` 和 `encoding_format: "float"`；若配置了正数维度，还会传 `dimensions`。响应必须包含 `data` 数组，每项含唯一的整数 `index` 和数值数组 `embedding`。适配器按 `index` 恢复输入顺序。

`ProjectRagService` 从当前项目的 `RagSettings` 构造配置，并在真正需要 Embedding 时从 Password Safe 读取密钥。配置不使用模型密钥作为索引指纹，也不会将密钥写入 manifest。

| 配置 | 默认值与含义 |
| --- | --- |
| 课程库与数据目录 | 固定为 `default` 和当前打开项目下的 `.course-ai` |
| Chunk size | 最多 `800` 个 Unicode 码点，按页/幻灯片独立切分，优先保留段落和句子 |
| Maximum overlap | 重叠预算最多 `120` 个 Unicode 码点，复用完整语义单元，实际可少于该值或为 0 |
| Embedding batch size | `16`；范围 1–128，需适配供应商请求限制 |
| HTTP request timeout (ms) | `20000`；HTTP 连接/单次请求时限，不是整个建库的总时限 |
| Minimum semantic score | `0.70`；开发用初始值，尚未通过真实课程问题校准 |
| Include PPTX slide notes | 默认关闭；启用时将备注中的文本形状一并提取 |
| Dimensions | `0` 自动从首个文档向量推断；正数同时成为请求参数和校验值 |
| Document input prefix | 空；有需要时给文档输入添加前缀 |
| Query input prefix | 空；有需要时给查询输入添加前缀 |

字符切分不是 token 切分。选定提供方后，应按其 token/批量上限调整片段和批次长度。若其协议使用任务类型字段而非前缀，应新增对应的 `EmbeddingClient` 实现。

### 切分边界与升级

每一页 PDF、每一张 PPTX 幻灯片单独处理：先保留空行划分的完整段落；段落超过上限时，使用 JDK `BreakIterator` 识别句子；过长句子继续按空白分隔的词细分；只有无法继续分词的超长字符串才按 Unicode 码点细分。正常的软换行保留在句子内部，中文和英文句末边界均有测试。

将上述单元依次组合成不超过上限的片段。重叠复用前一片段末尾能够完整容纳的段落/句子/词，最多达到配置预算，并为新内容留出空间；不会为了凑满 120 字符截断正常句子或词。因此片段长度和实际重叠长度会变化。所有片段继续保留原页码/幻灯片位置。

策略指纹已从 `unicode-codepoint-v1` 升为 `boundary-codepoint-v2`。已有旧策略索引需要在 Course materials 重试索引或重新添加文件以自动重建，不能仅修改配置后继续使用旧向量；重建会调用 Embedding API。单独修改 Minimum semantic score 不要求重建。

### 查询流程

1. 读取当前项目已保存的索引快照，校验模型、切分配置、向量和来源信息。
2. 为提交的问题调用一次 Embedding API，校验返回向量的数量和维度。
3. 在本地向量库中计算相似度，按 Minimum semantic score 过滤，按分数降序取前 `maxResults` 个片段。
4. 返回片段原文、文档名和 PDF 页码或 PPTX 幻灯片编号；无匹配时返回空列表。

查询复用已保存的文档向量，不读取原课件或重新建库。原文件移动后，旧索引仍可检索建库时保存的内容。

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

同一路径重复添加时弹出提示并列出已登记的原始路径；批量选择中的新文件继续正常登记。若全部重复，提示没有新增文件，保持原有索引状态。提示依据后台实际登记结果，不按文件名或内容判断重复。

**已经建好的索引检索不需要原文件。** 索引包含向量、片段正文和来源，课件移动或删除后仍可加载和查询旧索引；结果反映建库时的内容。只有重新建库时才读取原文件，此时缺失或不可读会明确失败，并保留旧索引。文件移动后，在 Files & index 移除旧路径，添加新位置的文件后重建。移除登记不会删除原文件或现有索引。

旧版本复制进 `.course-ai/documents/` 的课件不会被新版本自动扫描或清理；使用新模式时重新登记原文件即可。路径清单损坏时修复清单，不会静默视为空知识库。

PDF 页码是文件中的物理页序号，空白页不产生片段但仍占页号。PPTX 使用 `SourceReference.scope = "slide N"`，页码字段为空。支持文本框、表格、组合形状和可选备注；不识别图片/图表里的文字，全部不可提取正文的文件会报错。

片段 ID 对相同文档内容、位置、切分配置和正文稳定；引用 ID 为 `R-<chunkId>`。metadata 保留文档 ID、显示名、片段 ID、引用 ID 和页码/范围。成员 3 可以生成展示编号，但必须维护编号与真实来源的映射。

manifest 记录格式版本、模型/输入模式指纹、切分规则、实际维度、片段数、源资料哈希、快照文件名和快照校验和。模型、维度/前缀或切分配置改变后要求重建，单独更换同一服务的 API Key 不使索引失效。

重建持有知识库的跨进程文件锁。新快照先完整写入，再原子替换 manifest；解析/API/保存失败时不替换旧 manifest。不支持原子 manifest 替换的文件系统会明确报错。已发布的旧快照暂时保留，便于在途读取，自动清理历史快照尚未实现。

检索实例缓存一个完整快照。成功重建后调用 `reload()` 切换；在途请求继续使用原快照。原始资料改变不会自动建库，需显式 `rebuild()` 和 `reload()`。

## 插件 Java 接入示例

界面通过项目级服务调用核心模块；索引就绪后执行查询：

```java
ProjectRagService rag = project.getService(ProjectRagService.class);
CompletableFuture<List<KnowledgeChunk>> pending = rag.search(question, 5);
// 取消会传播至当前 HTTP future 和本地工作任务。
pending.cancel(true);

// 隐藏或释放面板时取消其请求；项目关闭时由 IDEA 释放项目级服务。
```

资料管理使用 `registerMaterials(paths)`、`unregisterMaterials(paths)`、`refreshLibrary()` 和 `rebuildLibrary()`；重建成功后服务会刷新索引状态，界面更新在 EDT 执行。各操作直接返回可取消的 future。

`registerMaterials(paths)` 返回 `MaterialRegistrationResult`，包含 `snapshot`、新增数量 `addedFiles` 和去重后的重复路径 `alreadyRegistered`；其他资料管理方法返回 `KnowledgeBaseSnapshot`。重复添加的提示在有效操作完成后由 IDEA 信息弹窗显示，取消、设置切换或面板销毁后的旧结果不会弹出提示。

IDE 适配通过 `settings.RagSettingsConfigurable` 配置普通参数，通过 PasswordSafe 单独存取密钥；`application.ProjectRagService` 直接组装 `RagConfig` 与项目级服务。Embedding 客户端和密钥读取延迟到真正需要 Embedding 时，登记资料不需要密钥。保存配置取消旧服务任务，不自动建库或联网。IDE 首版固定使用当前项目根目录下的 `.course-ai` 和 `default` 课程库。`CourseAiToolWindowFactory` 通过 `KnowledgeBasePanel` 调用项目级管理服务，Chat 通过项目服务调用真实检索与模型。

R2 管理 API：`DocumentImporter.listRegisteredDocuments()` 返回不含正文/哈希的原路径、稳定文档 ID 和 Available/Missing/Unreadable 元数据；原有 `listDocuments()` 仍保留给显式建库。`LocalKnowledgeSearch.reloadSummary()` 返回与实际加载快照一致的文档 ID 集合、片段数和维度，取消语义与 `reload()` 一致。清单损坏不会静默返回空列表。完整界面验收见 [R2 审阅](rag-gui-r2-review.md)。

## 分数、失败和生命周期

LangChain4j 1.7.1 内存库的分数为 `(cosine + 1) / 2`，范围 `[0,1]`，不是概率。当前默认阈值 0.70 未校准；交接前使用真实问题/来源清单调参，并用独立问题验证。返回结果按分数降序、片段 ID 升序排序，限制 `maxResults` 为 1–100。索引以最多 256 条的只读分区检索，在分区之间和结果处理时检查取消。

所有返回片段均通过 Minimum semantic score 过滤。结果按问题与片段的相似度降序排列，同分按片段 ID 排序，返回数量不超过 `maxResults`；当前检索预览请求最多 5 个片段。

`0.75` 对应余弦相似度 `0.5`，是否严格取决于模型、问题和资料，不能凭单个分数判断。测试时可对同一组已知相关/无关问题比较 `0.70`（余弦 `0.4`）与 `0.75`，查看相关片段是否被漏掉、无关片段是否混入。降低阈值通常增加候选，也可能引入噪声；此次保留现有默认值和用户本地配置，尚未把任何值标记为已校准。

成功加载知识库但没有片段达到相似度阈值时，返回不可变空列表。未建库、损坏、模型不兼容、错误向量和 API 失败均异常完成，不将失败伪装成无匹配。向量必须维度一致、非零、数值有限。

`RagException` 不包含聊天请求 ID。成员 3 应使用当前 `ChatRequest.requestId` 转成 `ServiceFailure`，不要把检索失败当成空结果：

| RagException.Reason | 建议 ServiceFailure.Category | 重试性 |
| --- | --- | --- |
| `INVALID_INPUT`、`CONFIGURATION` | `INVALID_INPUT` | 通常需要修改输入/配置 |
| `TIMEOUT` | `TIMEOUT` | 可重试 |
| `NETWORK_ERROR` | `NETWORK_ERROR` | 可重试 |
| `EMBEDDING_ERROR` | `MODEL_ERROR` | 使用 `isRetryable()`，429/5xx 可重试 |
| 索引/文档/存储错误、`CLOSED` | `UNKNOWN` | 使用安全提示及 `isRetryable()` |

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

## 验证记录（2026-10-28）

- 83 项自动测试通过，失败、错误和跳过均为 0；覆盖配置、资料登记、切分、持久化、检索、取消及界面状态。
- 重复添加检查覆盖单次提示、混合批次继续新增、取消后抑制旧提示；后台检查确认重复路径不增加记录、不调用 Embedding，也不会使已有可用索引变为待重建。
- 编号文本回归检查确认：每次查询只生成一次问题向量，低于阈值的片段不返回，查询不重建文档向量。
- `test`、`buildPlugin`、`verifyPluginProjectConfiguration`、`verifyPluginStructure` 和 `exportCheckClasspath` 通过。设置搜索索引随插件 JAR 更新而重新生成。
- 测试报告：`build/reports/tests/test/index.html`；插件包：`build/distributions/course-ai-assistant-1.0-SNAPSHOT.zip`。
- 自动检查使用生成的资料、可控向量与本地 HTTP 服务；真实供应商和 IDEA 手动验收另行执行。


## 本轮合并的兼容说明（2026-10-29）

重复登记通过 MaterialRegistrationResult 返回信息，资料列表不增加重复行，并在顶部短提示中说明。为保留当前产品“再次添加以更新原文件内容”的行为，再次添加仍触发自动索引；重建失败时旧快照不能用于当前 Chat。旧 RAG CLI、环境配置加载器及多步检索已移除；F2 暂时仍使用自己的 .env 配置与 dotenv 依赖，待 I4 统一。
