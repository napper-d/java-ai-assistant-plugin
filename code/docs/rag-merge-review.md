# RAG 更新合并审阅

日期：2026-10-29。当前分支 master，HEAD 为 b012694；正在合并 RAG 成员的 1fa9011。由用户完成最终 Git 提交。

## 处理原则与结果

- 保留 I2/I3 的真实 Chat/RAG 接线、右侧图标导航、欢迎页、Markdown 表格与回答/来源卡、编号引用、原文快照及当前极简资料列表。
- 不恢复独立 Retrieval preview、旧 Files & index 嵌套导航或常驻 Refresh/Rebuild 按钮。该页面专属测试继续删除，有价值的检索验证保留在 CourseRetrievalIntegrationTest 和 LiveChatIntegrationTest。
- 接纳成员的 MaterialRegistrationResult 与重复原路径检测；将新返回类型接入 MaterialUpdateTask、当前资料页和测试替身。重复添加不产生第二条文件记录，在顶部显示短提示，完整路径可悬停查看。
- 保留再次添加同一文件时自动重新索引的既有行为，使原文件内容变化仍能更新。重建开始即标记待更新；重建失败或取消后，旧索引不能因路径相同被当作当前证据。
- 接纳旧 RAG CLI、RagConfigLoader、多步检索及相关设置/测试的移除，清理 Gradle 中对应命令，保留普通单次向量检索。
- 保留 dotenv 依赖及 .env.example：现有 LiveModifyAction / LlmConfigLoader 仍使用显式 .env 配置。F2 统一配置接线在 I4，不在此次合并中删除其仍使用的依赖。
- 保留 SettingsForm 共用外观、原生可折叠高级设置与统一 Password Safe 措辞，仅移除已被 RAG 后端删除的多步选项。
- 合并成员的文档更新并修正旧 Mock / 独立预览 / 手动建库说明，避免覆盖当前集成状态。

## 验证

首轮接口接线后全量测试通过。进一步补充两项回归：重复导入在极简资料页中的通知与自动索引、同一路径重建失败后拒绝旧 Chat 证据。

产物打包、项目配置与结构检查通过；已检查 ZIP 内全部插件 JAR，RagCli / RagConfigLoader / EmbeddingProviderFactory / MultiStepRetrieval / ReferenceQueryPlanner / FollowUpQueryPlanner / RetrievalPreviewPanel 等已删除类残留为 0。

首轮最终回归运行 135 项，一项历史测试仍期望取消重建后保持 AVAILABLE；按本轮保护要求更新为 NEEDS_REBUILD，仍保留原 manifest 不变及重试后恢复 AVAILABLE 的断言。最终复跑 **135 项全部通过，0 失败、0 错误、0 跳过**；同次插件打包、配置与结构检查均通过（3m 10s，主要耗时为依赖图处理）。未运行真实供应商或写入用户源码。未提交或推送。

合并工具遗留的四个 .env_*_1531.example 临时备份已在冲突解决后清理；项目原有 .env.example 保留，未读取或删除实际凭据配置。

验证命令：

```powershell
.\gradlew.bat test buildPlugin verifyPluginProjectConfiguration verifyPluginStructure -x buildSearchableOptions --offline --console=plain
```

本次跳过搜索索引辅助 IDE，避免再次启动共享开发配置的 headless 进程；未删除整个 build 或修改用户主题。Git 冲突条目为 0，解决结果已暂存以解除冲突标记，合并提交留给用户执行。
