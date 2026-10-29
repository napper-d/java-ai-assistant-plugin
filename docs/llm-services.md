# 成员 3：大模型问答与代码生成

> Current UI update: Chat and Course materials share one icon navigation surface;
> import indexes automatically and numbered citations expand original evidence.
> Historical independent retrieval-page descriptions below are superseded.
> See [current UI review](integration-i3-review.md).


> I2 update (2026-10-28): Tool Window Chat now uses ProjectLlmService through
> LiveChatSession, with project settings/Password Safe and ProjectRagService.search.
> Mock and Sample descriptions below are historical. F2 already has a separate live
> Diff/apply entry and awaits shared settings wiring in I4. See [I2 review](integration-i2-review.md).


## 本次交付边界

`llm.LlmChatService` 实现 `ChatService`，`llm.LlmCodeGenerationService` 实现
`CodeGenerationService`。共享接口和数据结构未修改。实现包含提示词、结构化结果校验、
OpenAI-compatible `/chat/completions` 传输、配置加载、错误转换、总时限和取消传播。

当前 Tool Window 仍使用 `MockChatService`。本交付是成员 3 的可调用服务，
不是已完成的 IDE 真实聊天接线；成员 1 负责项目配置/Password Safe、替换 Mock、
去除 Sample 标识并绑定服务生命周期。成员 4 负责修改入口、原文件快照采集、
Diff 审核、批准、目标验证、写入与撤销。本模块不读取或写入源文件。

## 开发配置

通过显式目录调用 `LlmConfigLoader.load(envDirectory)`，加载该目录的 `.env`，
进程环境变量优先。也可以直接构造 `LlmConfig`，供 IDE 从项目设置和 Password Safe
组装。不会自动查找父目录、读取 RAG 的密钥或选择供应商。

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
   返回前将标记转为 UI 可读的 `[R-id]`，直接复用检索返回的 `SourceReference`，
   不从模型结果构造页码。只返回被引用的来源。
5. 有证据必须有引用；成功空检索允许无引用回答，由服务添加固定的通用知识声明。
   检索失败不调用模型，也不转成通用知识答案。

结构校验只能保证引用 ID 的成员关系和标记一致性，不能证明自然语言主张确实由
该片段支持，也不能完全杜绝提示注入。真实课件问答仍需人工检查引用语义准确性。
模型若不遵守 JSON 协议，当前实现直接返回安全错误，不自动重试产生额外费用。

## 修改建议

`generate` 接收显式 `ModificationRequest`，检查相对路径（拒绝绝对路径、盘符、
空路径段和 `.`/`..`）及选区确实出现在传入快照中。它不根据行号推断替换偏移。
模型返回 `path`、`proposedFileText`、`summary`；目标必须与输入完全一致，替换全文和
摘要必须非空，拒绝无变化的结果。原文件内容和请求 ID 来自输入，不信任模型重建。
proposal ID 由本地数据模型生成。

成员 4 仍必须验证工程内实际路径（含符号链接）、准确的原文件快照及批准状态。
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

IDE 项目服务可通过 `projectRagService::search` 提供 `KnowledgeSearch`。
不要在 `CourseAiToolWindowFactory` 里隐式读取 `.env` 或使用静态共享密钥。
成员 1 的 `RequestCoordinator` 仍有 30 秒截止时间；默认服务 25 秒先行结束，
若修改配置需要一起考虑这两个时限。成员 4 可独立调用 generator。

每个服务拥有虚拟线程任务及截止时间调度器，依赖启动也在后台执行。
取消、超时和 close 取消当前阶段 future 并中断工作任务；关闭后拒绝新请求。
取消不保证远端已经收到的请求停止执行或计费。

`RagException` 按现有建议转换为带原请求 ID 的 `ServiceFailure`；网络、超时、
模型错误、输入错误分别分类。HTTP 408/429/5xx 可重试，其他 HTTP 错误不可自动重试。
未知异常用通用提示，错误响应正文和原始异常不回显给 UI。

## 验证

测试位于 `src/test/java/edu/teamc/courseaiassistant/llm/`，使用确定性模型替身和本地
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

真实供应商/真实课件、IDE 真实聊天入口和 Diff 修改流程尚未验收。
本地运行结果另见 `docs/member3-review.md`。
