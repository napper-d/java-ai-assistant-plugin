# I2：真实 Chat / RAG 接线审阅

> 2026-10-29 更新：下文为 I2 完成时的历史记录。当前工作区已覆盖 I2 与扩展后的 I3；资料添加/删除后自动索引，独立 Retrieval preview 已删除，引用可点击展开。当前验收入口与状态以 [I3 阶段审阅](integration-i3-review.md) 为准。

日期：2026-10-28。基线：用户提交的 I1 `b6712b5`。本阶段由用户进行 Git 提交，不自动暂存、提交或推送。

## 实现

- Tool Window 的 Chat 改为 `LiveChatSession → RequestCoordinator → ProjectLlmService → LlmChatService → ProjectRagService.search / HttpLlmClient`，不再组装 MockChatService，不在 UI 二次检索。
- 使用 I1 的 Language model 设置及独立 Password Safe 密钥。外层截止时间按每次请求读取项目配置，保留服务对检索和生成的总时限。
- Chat 无样例选择和固定回复提示，欢迎说明列出配置与建库路径，并明确每个问题独立处理。右键选区仍附加原始快照，不采集全文件或对话历史。
- 回答继续使用既有正文、来源文档/页码/slide 与通用知识声明展示。仅成功空检索可进入通用知识分支；检索失败不调用后续模型。
- 细分未配置、未建库、索引不兼容/损坏、Embedding 网络/服务错误和检索超时的安全提示，不回显原始供应商响应或秘密。
- LiveChatSession 订阅项目服务失效通知：配置/资料变化取消当前请求，也使排队等待 EDT 的旧回复失效。内容释放取消订阅并释放 coordinator，项目模型服务仍可被其他入口借用。
- 保留原清空取消、重复发送保护、超时恢复和项目/内容释放检查。隐藏窗口不释放会话。

主要文件：`application/LiveChatSession.java`、`ui/CourseAiToolWindowFactory.java`、`ui/ChatPanel.java`、`llm/LlmFailures.java`。`ProjectRagService` 增加包内凭据供应器构造入口供集成测试注入占位密钥；生产构造仍读取 Password Safe，没有环境变量或明文凭据回退。

README 和插件描述已反映当前入口；架构/接口/LLM 交接文档增加 I2 状态说明，旧阶段文字保留为历史背景。

## 验证

实际执行：

```powershell
.\gradlew.bat test --offline --console=plain
.\gradlew.bat buildPlugin verifyPluginProjectConfiguration verifyPluginStructure --offline --console=plain
git diff --check
```

- 全项目测试最终通过：**149 tests，0 failures，0 errors，0 skipped**，50s。
- 前两次运行被 IDEA 的线程泄漏检查拦截，先后报告 JVM 级 `Write-Updater` / `Write-Poller`。它们由 JDK 首次虚拟 Socket I/O 惰性启动并存续到 JVM 退出，不属于模型会话。测试类的 BeforeAll 使用可关闭并等待结束的本机 Socket 产生写入背压，等待全局基础线程完整启动，再建立每项测试基线。没有禁用或放宽泄漏检查，没有修改生产逻辑以绕过测试。调整后完整回归通过。
- 插件打包、项目配置及结构检查：**全部通过**，6m 50s。产物为 `build/distributions/course-ai-assistant-1.0-SNAPSHOT.zip`。IDE 设置索引阶段出现 Marketplace、主题、后台写入和 ActionUpdater 平台警告，构建最终成功。
- `git diff --check`：通过。

新增 `LiveChatIntegrationTest` 使用真实解析、索引、检索、HTTP 适配器、项目服务和 ChatPanel，只将两个供应商替换为本机 HTTP 服务、使用占位密钥。覆盖：

- PDF 第 2 页与 PPTX slide 2 的证据和引用进入模型、回到 Chat；选区路径/文本/行号传递；每次提问只检索一次。
- 无匹配声明、Embedding 故障不调用模型、故障恢复。
- 未建库、不兼容及损坏索引在查询 Embedding/模型之前失败。
- 模型结构异常安全报错及恢复；清空和重配后丢弃迟到结果；关闭 Chat 后项目模型服务仍可用。
- ProjectLlmServiceTest 增加真实 LiveChatSession 订阅路径下的排队成功失效检查，覆盖模型设置、RAG 设置、内容关闭及项目关闭。

本机 HTTP 测试不代表真实供应商及实际 IDEA 验收通过，没有读取用户实际密钥或发起外部模型请求。

## IDEA 验收步骤

1. 重新启动开发 IDEA，在本地测试工程的 Language model 页面填写实际协议兼容的端点、模型、Key；在 Course RAG 配置 Embedding 端点、模型、Key。两者独立保存。
2. 在 Course materials 登记实际 PDF/PPTX，显式 Rebuild index，等待成功。回到 Chat，确认不再出现 Example 选择器或固定样例欢迎语。
3. 提问可在课件中定位的问题，人工核对答案、文档名及物理 PDF 页码/PPTX slide。当前来源卡仍为展开显示，点击原文详情由 I3 补齐。
4. 提问明确无关的问题，确认只有成功无匹配时显示通用知识声明；检索故障应显示失败。
5. 选中 Java 代码，右键 Ask Course AI Assistant，检查附件后提问，确认回答使用选区。切换编辑器不应改变已经附上的快照。
6. 请求期间清空、修改模型/RAG 设置或关闭 Chat 内容，确认旧回复不回填；隐藏再显示窗口应保留历史。错误恢复后能继续提问。
7. 在可丢弃的测试工程检查未建库、不兼容索引、错误 Embedding Key、模型鉴权/网络故障和超时，确认提示有区分且没有伪来源。

## 阶段边界

I2 完成请求链路与基础展示，**独立 Retrieval preview 暂时保留**，I3 将移除并增加随回答保存的可展开引用证据。F2 继续使用既有显式 `.env` 入口，统一设置接线在 I4。普通聊天不触发源码写回。

当前状态：**I2 实现及自动验证完成，等待用户 IDEA / 真实供应商验收与 Git 提交。未进入 I3，未暂存、提交或推送。**
