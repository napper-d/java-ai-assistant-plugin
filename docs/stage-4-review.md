# 第四阶段审阅：服务接口与后台请求

> 第三阶段已通过。本阶段实现服务接口契约、异步请求协调、加载状态和错误处理。第四阶段不接入真实 LLM 或 RAG，使用 MockChatService 模拟异步行为。

## 1. 本阶段功能

- 定义完整的服务接口契约：`ChatRequest`、`ChatAnswer`、`SourceReference`、`KnowledgeChunk`、`ServiceFailure`。
- 实现 `ChatService` 和 `KnowledgeSearch` 接口，提供 `MockChatService` 模拟异步调用。
- 创建 `RequestCoordinator` 管理请求生命周期：后台执行、取消、超时、项目关闭时的资源释放。
- `ChatPanel` 支持两种模式：Sample mode（本地固定回复）和 Live mode（异步服务调用）。
- 异步请求时显示加载指示器（"Thinking…"），禁用发送按钮防止重复提交。
- 请求完成后显示真实回复，包含来源引用和免责声明。
- 请求失败时显示错误消息，区分可重试和不可重试错误。
- 清空会话时自动取消正在进行的请求。
- 使用 `Disposer` 确保工具窗口关闭时释放 `RequestCoordinator` 资源。

## 2. 结构与设计

| 文件或目录 | 用途 |
| --- | --- |
| `model/ChatRequest.java` | 问答请求数据结构，包含问题文本和可选的代码上下文 |
| `model/ChatAnswer.java` | 问答回复数据结构，包含答案文本、来源列表、出处标记和免责声明 |
| `model/SourceReference.java` | 来源引用数据结构，包含文档名、页码范围或作用域 |
| `model/KnowledgeChunk.java` | 知识块数据结构，用于 RAG 检索结果 |
| `model/ServiceFailure.java` | 服务失败数据结构，继承 `Exception`，包含错误类别和可重试标志 |
| `service/ChatService.java` | 聊天服务接口，返回 `CompletableFuture<ChatAnswer>` |
| `service/KnowledgeSearch.java` | 知识检索服务接口，返回 `CompletableFuture<List<KnowledgeChunk>>` |
| `service/mock/MockChatService.java` | 模拟聊天服务，支持多种场景（成功、超时、网络错误、慢响应） |
| `application/RequestCoordinator.java` | 请求协调器，管理异步请求生命周期，实现 `Disposable` |
| `ui/ChatPanel.java` | 支持 Sample mode 和 Live mode，集成加载状态和错误处理 |
| `ui/ConversationView.java` | 新增 `LoadingMessage` 和 `ErrorMessage` 组件 |
| `ui/CourseAiToolWindowFactory.java` | 创建 `RequestCoordinator` 并注册到 `Disposer` |

异步执行：使用 `CompletableFuture.supplyAsync()` 在后台线程执行服务调用，通过 `whenComplete()` 在完成后回调。所有 UI 更新通过 `SwingUtilities.invokeLater()` 切换到 EDT。

请求取消：`RequestCoordinator` 维护 `pendingRequests` 映射表，支持按 ID 取消单个请求或取消所有请求。取消时调用 `future.cancel(true)` 中断后台任务。

资源管理：`RequestCoordinator` 实现 `Disposable` 接口，在 `dispose()` 时取消所有待处理请求。通过 `Disposer.register(toolWindow.getDisposable(), coordinator)` 确保工具窗口关闭时自动释放资源。

错误处理：`ServiceFailure` 继承 `Exception`，包含错误类别（TIMEOUT、NETWORK_ERROR、MODEL_ERROR、INVALID_INPUT、UNKNOWN）和可重试标志。`RequestCoordinator` 在 `whenComplete()` 中捕获异常，区分 `ServiceFailure` 和其他错误，统一转换为 `ServiceFailure` 后回调 UI。

MockChatService 场景：
- `SUCCESS_COURSE_GROUNDED`：返回带课程来源的回复
- `SUCCESS_GENERAL_KNOWLEDGE`：返回带免责声明的一般知识回复
- `TIMEOUT`：模拟超时错误
- `NETWORK_ERROR`：模拟网络错误
- `SLOW_SUCCESS`：模拟慢响应（延迟 2 秒后成功）

## 3. 验证记录

- Gradle 构建通过（`buildPlugin`，20s），Java 21 编译成功。
- 插件结构验证通过（`verifyPluginStructure`），无结构错误。
- 插件 ZIP 产物包含所有新增类：model 包（5 个类）、service 包（2 个接口）、service.mock 包（1 个类）、application 包（1 个类）。
- `ServiceFailure` 继承 `Exception`，可作为异常抛出和捕获。
- `RequestCoordinator` 正确注册到 `Disposer`，工具窗口关闭时自动释放资源。
- 实际 IDEA 窗口集成、异步请求、加载状态、错误处理和取消功能：待用户手动验收。

```powershell
.\gradlew.bat --offline --console=plain buildPlugin verifyPluginStructure
```

产物：`build/distributions/course-ai-assistant-1.0-SNAPSHOT.zip`。

## 4. 用户验收步骤

1. 关闭旧开发用 IDEA，运行 `Run IDE with Plugin`。
2. 在开发用 IDEA 中打开任意 Java 项目。
3. 打开 `Course AI Assistant` 工具窗口，确认顶部显示 `Live mode`（而非 `Sample mode`）。
4. 输入问题并按 Enter，确认：
   - 问题气泡立即显示在会话区
   - 下方出现 "Thinking…" 加载指示器
   - 发送按钮变为禁用状态
   - 状态栏显示 "Waiting for response…"
5. 等待约 0.5 秒后，确认加载指示器消失，出现 Course AI 回复，包含来源引用。
6. 测试代码上下文：选中一段代码，右键调用 `Ask Course AI Assistant`，输入问题并发送，确认回复中包含代码位置信息。
7. 测试快速连续发送：发送一个问题后，立即输入另一个问题并按 Enter，确认状态栏提示 "Already waiting for a response…"，不会重复发送。
8. 测试清空会话：在等待回复时点击 Clear chat，确认会话清空，加载指示器消失。
9. 测试错误场景（需要修改 MockChatService 的场景参数）：
   - 将 `MockChatService` 构造改为 `new MockChatService(MockChatService.Scenario.TIMEOUT, 1000)`
   - 重新运行插件，发送问题
   - 确认显示错误消息 "⚠ Request timed out after 1000ms (You can retry)"
10. 测试项目关闭：发送问题后，在等待回复时关闭项目窗口，确认没有异常或内存泄漏。

## 5. 审阅边界

本阶段使用 `MockChatService` 模拟异步调用，不接入真实 LLM 或 RAG 服务。回复内容为固定文本，不包含真实的课程知识检索结果。

`KnowledgeSearch` 接口已定义但未在 `RequestCoordinator` 中使用，留待第五阶段 F0 集成时由成员 2 实现。

`ChatAnswer` 的 `Provenance` 字段（COURSE_GROUNDED / GENERAL_KNOWLEDGE）已定义，但 UI 层未根据此字段做差异化显示，留待后续优化。

第五阶段将接入真实的 RAG 检索和 LLM 调用，替换 `MockChatService`。

**状态：第四阶段实现与构建验证已完成，等待用户在实际 IDEA 中复核。**
