# 第四阶段审阅：服务接口与后台请求

> 2026-10-24 审阅修订。前三阶段已通过；本阶段修复请求状态、取消、生命周期、示例标识和检查入口。真实 LLM、RAG、Diff 和写回仍未接入。

## 1. 完成内容

- 问答与检索契约已实现；补齐修改请求、单文件修改建议、批准/取消结果及成员 3/4 的服务接口。F2 契约为可审阅的交接提案，尚待队友确认，无服务实现或文件写入。
- 保留用户确认的行号上下文设计：一基、首尾均包含，不传递选区偏移量；完整选区文本和修改戳记随请求保留。
- RequestCoordinator 使用自有后台执行器启动 ChatService，包括可能同步抛异常的服务启动阶段；结果统一在 EDT 交付。
- 默认 30 秒实际超时，产生可重试 TIMEOUT；停止本地调用并取消服务 Future。不能把 Future 取消等同于强制中断任意网络工作，真实适配器必须传播取消。
- 请求在结果交付前仍保留追踪，清空可以使已排队的成功结果失效；取消走独立回调，不显示为 UNKNOWN 错误。
- ChatPanel 核对当前 request ID，清空先使旧 ID 失效，再取消请求；旧结果不能进入新会话或结束新请求。
- 首次等待时即可 Clear chat；等待时输入草稿不会启用 Send。完成、失败或取消后恢复操作。
- 内容销毁及项目关闭时释放协调器和执行器，移除窗口注册表条目；仅隐藏侧栏不会销毁会话。EDT 交付时再次检查释放状态。
- Mock 仍明确显示 Sample mode；回答及来源保持固定示例/虚构标识。两种 Example 选择均影响下一次异步请求。
- 超时、网络及模型错误保留 ServiceFailure 分类和重试属性；未知异常使用通用消息，不将原始诊断内容直接显示给用户。
- 右键采集使用触发操作的 Editor，与操作启用判断保持一致。
- 快捷键统一为 Enter 发送、Shift+Enter 换行，检查脚本和 README 同步。

## 2. 主要文件

| 文件 | 用途 |
| --- | --- |
| `application/RequestCoordinator.java` | 后台启动、实际超时、取消、异常归一化、EDT 交付和释放 |
| `ui/ChatPanel.java` | 当前请求 ID、等待状态、清空、样例配置和内容释放 |
| `ui/CourseAiToolWindowFactory.java` | Mock 接线与内容生命周期绑定 |
| `ui/ConversationView.java` | 异步示例标识与来源展示 |
| `service/ChatService.java` | 调用、结果 ID、失败及取消契约 |
| `service/mock/MockChatService.java` | 可配置成功/失败场景，延迟完成，不睡眠占用工作线程 |
| `model/ModificationRequest.java`、`CodeChange.java`、`ChangeProposal.java`、`ModificationResult.java` | F2 单文件/整文件快照交接数据，无 I/O |
| `service/CodeGenerationService.java`、`ModificationService.java` | 成员 3/4 接入端口，无实现 |
| `tools/check-chat-ui.ps1` | 使用已缓存目标 SDK 编译实际生产代码并运行两组检查 |
| `tools/RequestLifecycleCheck.java` | 请求生命周期回归检查 |
| `docs/interfaces.md` | 实际 Java 签名、线程/取消约定及队友交接说明 |

## 3. 验证

命令：

```powershell
.\tools\check-chat-ui.ps1
.\gradlew.bat --offline --console=plain buildPlugin verifyPluginProjectConfiguration verifyPluginStructure --no-configuration-cache
```

独立检查需要 JDK 21 和此前 Gradle 同步提取的 IDEA 2025.2.4 SDK；也可传入 `-IdeHome`。不启动 IDE、不调用模型、不下载依赖。

- 原有输入、示例、引用隔离、会话、清空、主题、长文本及窄窗口布局检查通过，并重新生成离屏预览。
- 新增 11 项生命周期用例通过：清空/取消与过期回调、已排队成功、内容销毁、项目销毁、Mock 超时、Mock 网络错误、嵌套异常、实际超时与服务取消、同步启动异常后恢复、异步示例/上下文传递、空结果/错误 request ID。
- 全部生产 Java 源码通过 JDK 21 对目标 SDK 的编译；仍有 ContextCollector 使用弃用 API 的提示。
- Gradle 打包、项目配置检查和插件结构检查全部通过（6m 19s；13 项任务，11 项执行、2 项已是最新）。
- 真实 IDEA 窗口、编辑器集成、项目关闭及 IDE 内主题切换仍需手动验收；独立检查使用项目生命周期代理，不能证明实际 IDE 集成已通过。

预览位于 `build/reports/stage2/`。插件 ZIP 仍应由 Gradle 生成，不能以独立 javac 检查替代打包验证。

## 4. 用户验收

1. 重启开发用 IDEA，打开一个 Java 示例工程和 Course AI Assistant。
2. 确认顶部为 Sample mode。选择 With course citation，输入并按 Enter；立即看到问题和 Thinking，Send/Example 禁用，Clear chat 可用。
3. 等待示例完成，确认 Fixed sample、Example sources (invented) 及虚构来源声明；不显示 Live mode。
4. 改选 General knowledge 后发送，确认一般知识声明出现，本次回复没有课程引用。
5. 选中代码并右键 Ask Course AI Assistant，检查路径、语言、行号和文本；切换编辑器后该快照仍保持，发送后显示位置摘要，示例回复包含位置。
6. 首次发送后立即 Clear chat，再发送新问题，确认没有旧回答/取消错误，且新请求仍正常等待及完成。
7. 输入等待期间的草稿，确认 Send 仍禁用；Shift+Enter 换行，不触发发送。
8. 发送后关闭项目，确认日志没有失效界面访问；仅隐藏再打开侧栏应保留会话。
9. 运行独立脚本验证慢响应、失败、超时和恢复路径；这些场景由自动检查配置，无需为验收修改默认 Mock 参数。
10. 检查实际 IDEA 的浅色/深色主题及窄侧栏布局。

## 5. 边界与后续

KnowledgeSearch、CodeGenerationService 和 ModificationService 尚无真实实现。F2 初始契约采用单文件整文件建议，待成员 3/4 确认；普通聊天仍只读取选区。第五阶段 A 才接入真实检索和问答，验收后再进行第五阶段 B 的 Diff/批准/写回。

**状态：审阅修复、独立回归及打包/结构检查完成；等待用户 IDE 内验收，未进入第五阶段。**
