# RAG GUI R2：课程资料管理与索引任务审阅

日期：2026-10-27。前置：用户基本认可 R1 并授权后续。本阶段对应 [实施计划](rag-gui-plan.md) 的 R2；R3 检索预览仍未开始。

## 1. 实现内容

- Tool Window 增加 Chat / Course materials 两个页签。资料页提供 Add PDF / PPTX、Remove、Refresh / Load、Rebuild index、Settings 和 Cancel。
- 原生文件选择器支持 PDF/PPTX 多选；登记原始路径，不复制课件。移除登记不删除原件，不立即改变已有索引。
- RAG 层新增轻量登记列表 API：只读取路径清单与文件元数据，不解析/计算原文件哈希。缺失或不可读的文件仍列出，允许移除。损坏清单明确报错，不静默显示为空。
- 分开显示登记数量、已加载索引的文档/片段数量与可用性。未填写 Embedding 地址/模型时可以添加和移除，重建按钮禁用；未保存 API Key 的错误在真正建库时反馈，不为读取列表解锁 PasswordSafe。
- 管理任务在独立单线程队列串行执行；拒绝重复在途操作。UI 回调回到 EDT，文件、哈希、解析、索引加载和网络工作不在 EDT 执行。
- 全量重建成功后显式刷新检索快照；区分“已保存且已加载”和“已保存但加载失败”。后者用 Refresh / Load 重试，不再次调用 Embedding。
- 取消传播至当前索引/加载 future 及其子任务。取消后重新检查实际清单和索引；部分成功的登记不会撤回。取消早于索引原子发布时保留旧索引，晚于发布时不能保证撤回新索引。
- 设置改变后取消旧会话并通知资料页重新加载；操作编号和销毁标记隔离过期回调。销毁资料页取消本页任务并解除监听；项目关闭时释放管理队列与 RAG 资源，隐藏侧栏不关闭服务。
- 右键提问切回 Chat 页签并附上原代码快照。聊天仍为 Sample，无真实 LLM 接线。

## 2. 状态与限制

| 状态 | 表现与恢复 |
| --- | --- |
| 未配置 | 提示设置 Embedding 地址/模型；登记资料不受影响，密钥在建库时校验 |
| 未建库 | No saved index；配置就绪且有登记资料后允许重建 |
| 可用 | Saved index available；显示已加载快照数量，明确未检查原文件内容变化 |
| 资料变更 | Materials changed；旧库可能包含已移除文件，重建后替换 |
| 配置不兼容 | 模型/分块等改变后明确要求重建，不当成空库 |
| 索引损坏/不可读 | 加载失败及安全错误，允许重新加载或重建 |
| 已发布但刷新失败 | loading needs retry；Refresh / Load 重试 |

首版不自动扫描原文件内容变化。重开项目时核对登记 ID 集合和快照文档 ID 集合；同一路径的内容修改不能仅靠元数据判定，所以界面始终提示编辑课件后显式重建。本会话已知的登记/移除操作会标记待重建。

移除最后一份登记不会删除旧索引。核心模块拒绝构建空索引，因此提示“添加资料并重建以替换旧库”，没有增加隐含的删除索引操作。检索 GUI、点击引用打开原件、详细百分比和自动历史快照清理均不在 R2。

## 3. 主要文件

| 文件 | 用途 |
| --- | --- |
| `ui/KnowledgeBasePanel.java` | 列表、状态、互斥、取消、恢复与过期回调隔离 |
| `ui/CourseAiToolWindowFactory.java` | 双页签、原生多选、设置入口和释放 |
| `action/AskAssistantAction.java` | 右键提问切回聊天页 |
| `application/KnowledgeBaseOperations.java`、`KnowledgeBaseSnapshot.java` | UI 与后台的操作/状态契约 |
| `application/KnowledgeBaseManager.java` | 串行后台任务、重建后刷新、取消桥接 |
| `application/ProjectRagService.java`、`RagSession.java` | 配置变更通知与资源归属 |
| `rag/DocumentImporter.java` | 不读取原文的登记列表，保留原接口 |
| `rag/LocalKnowledgeSearch.java`、`LocalIndexStore.java` | 实际加载快照的只读文档 ID / 数量摘要 |

## 4. 验证

环境：Temurin JDK / javac 21.0.12.1，Java 21 / IDEA 2025.2.4 目标与既有依赖版本保持不变。

最终执行：

```powershell
.\gradlew.bat --offline --console=plain test buildPlugin verifyPluginProjectConfiguration verifyPluginStructure --continue --configuration-cache
```

- **BUILD SUCCESSFUL，8m 34s，21 项任务：13 执行、1 来自缓存、7 已是最新；配置缓存已保存。**
- **79 项测试通过，0 skipped / errors / failures**：此前 61 项 + R2 新增 18 项（登记列表 2、后台管理 9、Swing 资料页 7）。报告位于 `build/reports/tests/test/index.html`。
- 插件包已更新：`build/distributions/course-ai-assistant-1.0-SNAPSHOT.zip`。`git diff --check` 通过，未暂存、提交或推送。

既有 `ChatPanelSmokeCheck` 和全部 11 项 `RequestLifecycleCheck` 也通过：使用 JDK 21 编译实际生产源码及两个入口到 `build/ui-check`，通过 `java -Djava.awt.headless=true`、目标 SDK 和 Gradle 导出的实际依赖 classpath 运行。输入、引用、清空、主题、窄布局及聊天请求生命周期均无回归。

资料页测试覆盖部分失败后刷新真实清单、发布但加载失败的恢复、已排队成功的取消、设置切换过期回调、销毁和 340px 窄布局。后台测试覆盖真实快照保存/重载、旧库保留和取消对子 future 的传播。已检查离屏预览 `build/reports/rag-gui-r2/materials-narrow.png`，表头/三列、按钮和状态均可见，长路径可悬停查看完整值。

测试使用临时课件、可控 Embedding 和本地 HTTP，不使用用户实际密钥或远程课程模型。离屏图使用测试环境的 Swing 外观；真实课件/供应商、原生文件选择、多项目与实际 IDEA 主题仍待用户验收。自动测试不代表已启动交互式 IDEA。RAG 存储格式和既有接口保持兼容。

## 5. 用户验收

1. 重启开发 IDEA，打开本地 Java 示例项目和 Course AI Assistant，确认出现 Chat / Course materials 页签；Chat 仍为 Sample。
2. 在 Course materials 点 Settings，确认打开 R1 的设置页。先留空地址/模型/密钥，返回后应仍可 Add / Remove，Rebuild 禁用且有配置提示。
3. 多选实际 PDF/PPTX，核对原路径、数量与 Available 状态。同一路径重复添加不增加记录；不同目录的同名文件分别列出。悬停查看完整路径。
4. 移走一份课件后 Refresh / Load，确认仍显示 Missing 且可 Remove；新位置的原件不能被删除。缺失路径仍登记时重建应明确失败。
5. 保存可用的 Embedding 地址、模型和密钥，返回后应自动刷新。点击 Rebuild，确认界面响应、操作防重复、进度不编造百分比；成功后报告文档和片段数量。
6. 已有索引时添加/移除登记，确认提示待重建及旧库可能仍含已移除资料。移走原件后仍可加载旧库；再重建失败不能覆盖旧库。
7. 重建中点 Cancel，确认界面恢复并检查实际保存状态。操作接近完成时允许提示索引可能已保存；不能把取消当成强制回滚或远端停止计费。
8. 批量登记部分失败后保留安全错误提示，列表刷新为实际已登记结果，可继续修正/重试。已发布但加载失败由自动检查模拟，无需破坏用户真实索引。
9. 重建过程中从 IDEA 全局 Settings 修改 Course RAG，返回后旧任务不能覆盖新配置状态；模型/分块变化应提示重建。
10. 停在 Course materials 页签，选中编辑器代码并右键 Ask Course AI Assistant，确认切回 Chat 并显示原选区上下文。
11. 检查隐藏/重开侧栏、重开项目和多项目隔离，以及实际浅/深色主题和窄侧栏布局。

R2 验收后再进入 R3；本报告不提前勾选用户验收。

**推进记录：2026-10-27，用户要求“继续推进第三阶段”，据此进入 R3。未将该授权扩展为真实供应商、多项目等每一项用例均已完成人工验收。**
