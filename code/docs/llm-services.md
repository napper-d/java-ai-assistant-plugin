# 成员 3：大模型问答与代码生成

当前状态（2026-11-03）：正式 Chat 和 F2 均已接入成员 3 服务，经 `ProjectLlmService`
使用 Language model 设置和 Password Safe。普通连接参数、凭据和课程库在同一 IDE
配置内全局共享；请求与模型会话仍按项目管理。详见 [全局配置](integration-global-settings-review.md)
和 [F2 接线](integration-i4-review.md)。

Chat 与 Course materials 共用 Tool Window；添加资料自动索引，回答中的编号引用可以
展开本次实际证据。正式聊天不使用 Mock，F2 不再要求选择 `.env` 目录。
历史开发与验收过程保留在 [成员 3 交付记录](member3-review.md) 和各阶段 review 中；
它们不能替代最新版本的真实供应商/IDE 验收。

## 模块边界

`llm.LlmChatService` 实现 `ChatService`，`llm.LlmCodeGenerationService` 实现
`CodeGenerationService`。实现包含提示词、结构化结果校验、
OpenAI-compatible `/chat/completions` 传输、配置加载、错误转换、总时限和取消传播。

成员 1 的 `LiveChatSession` 将 Tool Window 接到项目服务，并管理配置和 UI 生命周期；
检索通过 `ProjectRagService.search` 借用共享课程库。成员 4 的修改入口负责原文件快照、
Diff 审核、批准、目标验证、写入与撤销。成员 3 只生成回答或建议，不读取或写入源文件。

## 开发配置

正式 IDE 入口从全局 Language model 设置与 Password Safe 组装 `LlmConfig`，不读取 `.env`。
以下环境变量仅用于显式开发工具：调用 `LlmConfigLoader.load(envDirectory)` 加载指定目录
的 `.env`，进程环境变量优先。不会自动查找父目录、读取 RAG 密钥或选择供应商。

| 参数 | 含义 |
| --- | --- |
| `LLM_PROVIDER` | 必填 `openai-compatible` |
| `LLM_BASE_URL` | 必填，例如 `https://provider.example/v1`；不含 `/chat/completions` |
| `LLM_MODEL` | 必填，供应商提供的聊天模型标识 |
| `LLM_API_KEY` | 必填；不提交到 Git、不写日志 |
| `LLM_TIMEOUT_MS` | 默认 25000，包含问答的检索和生成阶段；最大 300000 |
| `LLM_MAX_OUTPUT_TOKENS` | 默认 4096，传为 `max_tokens`；范围 1–32768 |
| `LLM_MAX_INPUT_CHARACTERS` | 默认 60000，系统提示词及 JSON 输入的 UTF-16 字符总预算；范围 100–1000000 |

远程端点要求 HTTPS，本机 localhost/127.0.0.1/::1 可使用 HTTP。禁止 URL 中的
用户凭据、查询字符串和 fragment，禁止 HTTP 自动重定向。配置对象的 `toString()`
不暴露密钥或端点。配置加载错误不携带原始配置行。

这是显式的非流式协议适配：请求使用 `model`、`messages`、`stream=false`、
`max_tokens`；不假定所有供应商支持 `response_format`。模型必须遵守提示词返回 JSON。
只接受一个 choice、`finish_reason=stop` 和非空文本 content；截断、拒答、工具调用、
无效结构均作为模型错误返回，不把截断文件当作修改建议。响应体最多 4 MiB。
服务商若要求 `max_completion_tokens` 或其他私有协议，需要新增适配器，不能声称已兼容。

输入超预算明确失败，不静默截断课件证据或原文件。字符预算不是 token 估计，
真实联调时需按模型窗口调整；代码生成可能因输出 token 不足而明确失败。

## 问答与引用

1. 先校验问题/选区输入，再调用 `KnowledgeSearch.search(question, 5)`。
2. 将问题、选区路径/语言/行号/文本、真实课件片段和来源作为 JSON 数据传给模型。
   普通聊天不采集全文件、不扫描工程、不发送历史对话。
3. 系统提示明确代码和课件是数据，要求模型输出
   `{"answer":"... [[cite:R-id]]", "citations":["R-id"]}`。
4. 校验每个 ID 来自本次证据；正文标记与 citations 必须一致，重复或伪造 ID 拒绝。
   返回前将标记转为内部 `[R-id]`，直接复用检索返回的 `SourceReference`，
   不从模型结果构造页码。只返回被引用的来源及真实证据；UI 再映射为每条回答从 1
   开始的可见编号，展开证据不重新检索或调用模型。
5. 有证据必须有引用；成功空检索允许无引用回答，由服务添加固定的通用知识声明。
   检索失败不调用模型，也不转成通用知识答案。

结构校验只能保证引用 ID 的成员关系和标记一致性，不能证明自然语言主张确实由
该片段支持，也不能完全杜绝提示注入。真实课件问答仍需人工检查引用语义准确性。
模型若不遵守 JSON 协议，当前实现直接返回安全错误，不自动重试产生额外费用。

## 修改建议

`generate` 接收显式 `ModificationRequest`，检查相对路径（拒绝绝对路径、盘符、
空路径段和 `.`/`..`）及选区确实出现在传入快照中。它不根据行号推断替换偏移。
模型返回 `path`、`proposedFileText`、`summary`；目标必须与输入完全一致，替换全文和
摘要必须非空。解析 JSON 后，将生成全文中的 CRLF 和单独 CR 统一为 IDE 文档使用的 LF；
Java 字符串里的字面转义 `\r` / `\n` 保持不变。归一化后检查内容变化，只有换行格式
不同的结果也按无变化拒绝。原始快照按输入原样保留，供后续过期检查；不修改输入快照、
不改变原文件的磁盘编码或换行设置。proposal ID 由本地数据模型生成。

成员 4 的 `ProposalValidator` 继续要求 LF，修改服务验证工程内实际路径（含符号链接）、
准确的原文件快照及批准状态，批准后由 IDE 保存并保留文件的编码/换行约定。
服务不会编译建议代码，也不声称通过测试；完整性和行为正确性需要 Diff 审阅与实际测试。
当前不支持生成空文件或删除文件。

## 组装与资源归属

```java
// 后台组装；IDE 凭据从 Password Safe 获取，不能在 EDT 上读取。
LlmConfig config = LlmConfigLoader.load(explicitDevelopmentDirectory);
HttpLlmClient model = new HttpLlmClient(config);
LlmChatService chat = new LlmChatService(knowledgeSearch, model, config);
LlmCodeGenerationService generator = new LlmCodeGenerationService(model, config);

CompletableFuture<ChatAnswer> answer = chat.ask(chatRequest);
CompletableFuture<ChangeProposal> proposal = generator.generate(modificationRequest);
// 所有阶段取消均传给当前检索/HTTP future。
answer.cancel(true);

// 内容/项目释放或更换配置时，由组装者按依赖顺序关闭。
chat.close();
generator.close();
model.close();
// knowledgeSearch 的所有者另外关闭它；chat 不关闭借用资源。
```

上面的代码展示独立开发工具的资源归属。正式 IDE 的项目会话通过
`projectRagService::search` 提供 `KnowledgeSearch`，从 Password Safe 获取密钥。
聊天的外层协调期限由模型配置的 timeout 加 5 秒派生；F2 生成阶段同样包含启动余量，
人工 Diff 审阅不受生成期限限制。模型设置变化会使生成和待批准审阅失效；
RAG 变化只取消相关 Chat，不误取消 F2。

每个服务拥有虚拟线程任务及截止时间调度器，依赖启动也在后台执行。
取消、超时和 close 取消当前阶段 future 并中断工作任务；关闭后拒绝新请求。
取消不保证远端已经收到的请求停止执行或计费。

`RagException` 按现有建议转换为带原请求 ID 的 `ServiceFailure`；网络、超时、
模型错误、输入错误分别分类。HTTP 408/429/5xx 可重试，其他 HTTP 错误不可自动重试。
未知异常用通用提示，错误响应正文和原始异常不回显给 UI。

## 验证

以下命令从仓库的 `code/` 目录运行。测试位于 `src/test/java/edu/teamc/courseaiassistant/llm/`，使用确定性模型替身和本地
HTTP 服务，不需要真实 API Key，不发送课件或代码给外部模型。

```powershell
.\tools\check-llm-services.ps1
# 缓存依赖后可使用 -Offline。要求当前 shell 的 JAVA_HOME 为 JDK 21。
.\gradlew.bat test buildPlugin verifyPluginProjectConfiguration verifyPluginStructure --console=plain
.\tools\check-chat-ui.ps1
```

独立脚本从 Maven Central 下载约 3.5 MiB 的固定版本依赖并校验 SHA-256，
只编译成员 3 服务、共享契约和对应测试，不需要 IDEA SDK，不替代全项目构建。
依赖与报告均写入 Git 忽略的 `build/member3-check/`。

换行回归包含 LF、CRLF、CR、混合换行、Java 字面转义保留及归一化后无变化拒绝；
`modification/LlmF2IntegrationTest` 通过本地 HTTP 返回不同换行的生成文件，验证真实
生成服务交给成员 4 的 `ProposalValidator` 后仍能通过，不放宽 Diff 侧校验。

真实供应商/真实课件、IDE 真实聊天入口和 Diff 修改流程尚未验收。
本地运行结果另见 `docs/member3-review.md`。
