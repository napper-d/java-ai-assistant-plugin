# RAG GUI R3：独立检索预览审阅

日期：2026-10-27。用户授权继续 R3；对应 [实施计划](rag-gui-plan.md)。本阶段不依赖成员 3，不生成 LLM 回答，不改变聊天的 Sample 模式。

## 1. 实现与入口

Course AI Assistant > Course materials 下有 Files & index / Retrieval preview 两个子页。前者保留 R2 管理功能，后者提供问题输入、Search、Cancel、Clear、Settings 与可选 Show similarity scores。

- 使用当前项目配置和现有 `KnowledgeSearch.search(question, 5)` 检索已保存的课程索引。输入快照在提交时捕获，Enter 查询、Shift+Enter 换行，等待中可以编辑下一条草稿但不会重复发送。
- 返回片段以只读、可选择复制的普通文本展示，保留真实引用 ID、文档名、PDF 物理页码/范围或 PPTX slide 范围，以及原文。HTML 风格字符按字面显示，不解释为链接或指令。
- 相似度默认隐藏；显示时注明不是概率。不会用阈值再次过滤返回证据，保留核心多步检索可能返回的低分关联定义。
- 三种结果独立展示：有命中、成功但无匹配、查询失败。无匹配不生成一般知识回答；未建库、模型不兼容、网络/鉴权或加载错误不伪装成无匹配。未知异常使用安全提示，不回显原始异常内容。
- 查询返回原始可取消 future。取消、清空、设置变化、资料/索引操作完成或内容销毁均使旧请求编号失效，阻止排队成功和晚到结果覆盖新状态；设置/资料变化只清空结果，不自动再次联网查询。
- 资料管理的成功、失败和取消均可触发预览失效通知，因为部分登记或索引原子发布可能已经完成。内容销毁解除两个监听并取消当前查询；项目关闭由项目服务释放底层资源。
- 所有文件读取与 Embedding 仍在既有 RAG 后台任务执行，结果交付回到 EDT。源文件移走后可检索建库时保存的原文，界面提示编辑/移除资料后重建。

## 2. 文件

| 文件 | 用途 |
| --- | --- |
| `ui/RetrievalPreviewPanel.java` | 输入、证据、来源、三态结果、取消及过期回调隔离 |
| `ui/CourseAiToolWindowFactory.java` | Course materials 子页与共同释放生命周期 |
| `application/KnowledgeBaseOperations.java` | 管理与检索的 UI 操作契约 |
| `application/ProjectRagService.java` | 现有真实查询接线与资料变更通知，保留原 future 取消桥 |
| `application/KnowledgeBaseManager.java` | 保留内部管理职责；查询由项目服务直接交给检索服务 |
| `RetrievalPreviewPanelTest.java` | 界面结果、来源、错误恢复、取消/变更/销毁与窄布局 |
| `RetrievalPreviewIntegrationTest.java` | 临时 PDF/PPTX、真实本地快照、丢失原件、失败和查询取消 |
| `RagSettingsTest.java` | 管理变更通知与解除订阅 |

## 3. 验证

环境：Temurin JDK / javac 21.0.12.1，Java 21 / IDEA 2025.2.4 与既有依赖版本不变。最终命令：

```powershell
.\gradlew.bat --offline --console=plain test buildPlugin verifyPluginProjectConfiguration verifyPluginStructure --continue --configuration-cache
```

- **BUILD SUCCESSFUL，1m 5s，21 项任务（15 执行、6 已是最新），复用配置缓存。**
- **93 项测试通过，0 skipped / errors / failures**：R2 时的 79 项 + R3 新增 14 项（预览界面 8、持久化检索集成 5、管理通知 1）。报告：`build/reports/tests/test/index.html`。
- 首次全量回归暴露一项旧测试的取消时序竞态：它在子任务被工作线程绑定之前就立即检查已取消。修正为在 5 秒上限内明确等待子 future 抛出 `CancellationException`；同类断言一并修正，继续验证实际取消传播。之后两次完整检查均通过 93 项。
- 既有 `ChatPanelSmokeCheck` 和 11 项 `RequestLifecycleCheck` 通过：使用 JDK 21 编译实际生产代码及入口到 `build/ui-check`，通过缓存依赖/目标 SDK classpath 运行 headless Swing；没有启动交互式 IDEA。
- 已检查 340px 离屏预览 `build/reports/rag-gui-r3/retrieval-narrow.png`：长引用、普通文本与滚动、按钮和状态可见。它采用测试 Swing 外观，不替代 IDEA 实际主题验收。
- 最终插件包：`build/distributions/course-ai-assistant-1.0-SNAPSHOT.zip`。`git diff --check` 通过；未暂存、提交或推送。

集成测试生成含空白首页的 PDF/PPTX，使用真实本地索引与受控向量检查物理页码/slide、稳定引用、移走原件后新实例加载、无匹配、索引不兼容、失败恢复和取消；既有本地 HTTP 测试也通过。不使用用户密钥，不代表真实供应商或真实课程问题的检索质量验收。

## 4. 用户验收步骤

1. 重启开发 IDEA，打开本地 Java 示例项目，在 Course materials > Files & index 配置 Embedding，登记真实 PDF/PPTX 并完成重建。
2. 切到 Retrieval preview，在底部输入一个与已建课件对应的问题。发送箭头或 Enter 应在中间对话区显示最多 5 条原文，来源必须能对应文档和物理页码/幻灯片。
3. 选择并复制原文与引用，检查内容完整；打开 Show similarity scores，确认保留真实引用 ID，分数注明不是概率且不触发新请求。
4. 对无关问题检查无匹配提示。该分支不应生成答案或编造引用；能否无匹配取决于实际模型、资料和阈值，默认阈值仍未标为已校准。
5. 用没有索引的新项目查询，检查明确失败；在已有索引项目改变模型/分块参数，检查提示不兼容/需要重建。恢复原配置或按需重建后可继续查询。
6. 查询中 Cancel 或点击顶部垃圾桶清空，再发新问题；被取消请求的结果不能进入新查询。Cancel 保留之前已完成的问答，垃圾桶清空全部问答与草稿。等待中编辑草稿，旧结果区域中的 Query 应仍是实际提交的问题。
7. 查询中通过 IDEA 全局 Settings 修改 Course RAG，确认旧结果被清空且不自动再查询；在 Files & index 添加、移除或重建后，也应清空旧预览并提示重新查询。
8. 移走原课件后，在已有索引上查询仍能返回旧快照的正文与来源；重开项目再查询验证持久化。此时结果不表示原文件仍存在。
9. 在 Retrieval preview 打开期间从编辑器右键提问，仍应切回 Chat 附上代码上下文；聊天明确为 Sample。
10. 检查实际 IDEA 浅/深色主题、窄侧栏及内容/项目关闭。网络/鉴权失败需修正配置后恢复，不能显示为无匹配。

## 5. 边界与后续

引用没有原文件路径，本阶段不增加点击打开原件。检索仍使用当前已保存快照，不自动重建、不校准阈值，也不证明答案生成已经接通。真实 ChatService、提示词与最终引用校验待成员 3 就绪后另行集成。

**用户验收：待确认。**

## 6. 2026-10-28 交互调整：与 Chat 共用布局

根据用户对顶部输入、大片只读区域难以区分的反馈，改为顶部标题与清空、中间可滚动问答记录、底部输入卡片。沿用 ChatTheme 的浅/深色、字号、间距与气泡，不另建视觉系统。

- Chat 与 Retrieval preview 共用 `ChatWidgets.composer`、输入框、发送/清空按钮、滚动条和 `ConversationView` 消息排版。检索新增普通文本消息入口，不构造 ChatAnswer，不混入固定聊天样例。
- 底部输入按 Enter 查询、Shift+Enter 换行，发送箭头与 Chat 一致。提交后草稿清空，可在等待中输入下一条草稿；一次只查询一个问题。
- 中间按顺序保留问题和课程证据、无匹配、失败或取消信息。每次检索仍只使用当前问题，历史仅在窗口内存中显示，不作为多轮模型上下文。
- Cancel 仅取消当前查询，保留已完成记录和下一条草稿；顶部垃圾桶取消查询并清除全部记录和草稿。设置、资料或索引变更时，清除旧记录并使旧请求回调失效。
- 分数开关可切换所有已显示原文的分数，不重新请求服务。真实引用、页码、slide、原文复制及低分扩展证据规则保持有效。
- 切到 Retrieval preview 时将首选焦点指向底部输入框。资料管理页签与原聊天服务不变。

验证：检索预览 9 项定向检查通过，覆盖连续记录、取消保留旧证据、草稿、分数开关、清空/失效、键盘与窄侧栏位置。原 ChatPanelSmokeCheck 与 11 项 RequestLifecycleCheck 通过。最终执行本报告第 3 节同一 Gradle 命令：BUILD SUCCESSFUL，18m 3s，21 项任务（15 执行、6 已是最新），94 项测试全部通过，0 failures / errors / skipped；插件打包、配置与结构检查通过。本次配置缓存因 Java 运行时元数据变化失效，主要耗时在 IDEA SDK 内置插件扫描；完成后已重新保存缓存。

新增浅/深色离屏预览位于 `build/reports/rag-gui-r3/retrieval-chat-light.png` 与 `retrieval-chat-dark.png`；不代表真实 IDEA 主题和输入法验收。用户验收仍待确认，未暂存、提交或推送。
