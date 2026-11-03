# I1：LLM 项目配置与服务组装审阅

日期：2026-10-28。基线：master `e68ff1f`。本阶段不自动暂存、提交或推送，由用户进行 Git 提交。

## 完成范围

- Settings > Tools > Course AI Assistant > **Language model**：固定 OpenAI-compatible 协议，可填写端点、聊天/代码生成模型、总超时、输出 token 上限和输入字符预算。
- 非秘密参数保存在每个项目的 workspace 状态中；没有默认供应商、模型或环境变量发现。允许保存未配置完成的状态，实际调用要求完整配置。
- LLM Key 单独保存到 Password Safe，命名空间包含项目 location hash，与 RAG/Embedding Key 隔离。输入框不回填；留空保留、输入替换、勾选清除；拒绝纯空白/换行密钥。
- Apply 使用后台进度任务写凭据，失败时明确报错，再发布新配置。保存设置不读取已存密钥、不创建 HTTP 客户端、不建库、不调用 API。
- `ProjectLlmService` 为 Chat 与 F2 提供同一项目的服务接口；`LlmRuntime` 按需在后台完成凭据读取及共享模型会话初始化。
- `LlmSession` 拥有问答、生成服务与模型客户端，借用 `ProjectRagService.search`，不关闭 RAG 所有者。
- 取消一个请求不关闭共享客户端、不取消其他请求的初始化；RAG 设置或资料/索引操作完成时只取消在途 Chat；LLM 配置改变时取消全部模型请求并关闭旧会话。
- 项目关闭释放资源；若 OS 凭据解锁迟到，旧初始化返回的会话被关闭，不会重新发布。初始化失败不缓存为永久故障，允许重试。
- RequestCoordinator 增加逐请求读取超时的入口；项目外层期限为配置总超时 + 5 秒，避免旧固定 30 秒上限截断较长配置。

外层期限从用户提交开始计时，包含凭据解锁和服务启动；解锁等待很长时，外层期限可以先取消请求。OS 凭据对话框不保证能被中断，但迟到的结果不会启动已取消的模型请求。

## 主要文件

| 文件 | 用途 |
| --- | --- |
| `settings/LlmSettings.java` | 非秘密状态、校验、LlmConfig 构造与外层期限 |
| `settings/LlmCredentials.java` | 项目隔离的 Password Safe 访问，拒绝 EDT I/O |
| `settings/LlmSettingsConfigurable.java` | 设置页、保留/替换/清除密钥与保存失败提示 |
| `application/ProjectLlmService.java` | 项目服务、RAG 事件订阅、配置发布及释放 |
| `application/LlmRuntime.java` | 惰性共享初始化、取消桥接、配置代际隔离 |
| `application/LlmSession.java` | 实际模型适配器及客户端的资源归属 |
| `application/RequestCoordinator.java` | 动态期限，保留原 Duration 构造器行为 |
| `META-INF/plugin.xml` | 注册 Language model 项目设置页 |
| 新增配置/运行时/项目服务测试及 RequestLifecycleTest | 状态隔离、UI 输入、并发初始化、取消、迟到资源及期限恢复 |

## 与后续阶段的边界

本阶段交付配置与可调用的项目服务。Chat 仍使用 Mock，F2 仍使用既有显式 `.env` 入口；本阶段保存的 LLM 设置尚未被这两个旧入口消费。独立 Retrieval preview 的移除与来源详情在 I3 实施。

I2 接线时使用 `new RequestCoordinator(project, modelService, modelService::coordinatorTimeout)`，订阅 `onChatInvalidated(coordinator::cancelAll)`，内容释放时取消订阅并释放 coordinator。订阅用于阻止已经排队等待 EDT 的旧成功结果，不只取消仍在执行的 future。项目服务由项目释放，不随 Chat 内容关闭。

I4 需把显式 F2 操作接到项目 generator，并将配置切换/项目关闭关联到整个生成与审阅操作；已经生成成功后打开的 Diff 不属于生成 future 的在途工作，不能仅靠 generator 取消代替关闭审阅。

## 自动验证

使用项目配置的 JDK 21 / IDEA 2025.2.4 SDK，实际执行：

```powershell
.\gradlew.bat test --offline --console=plain
.\gradlew.bat buildPlugin verifyPluginProjectConfiguration verifyPluginStructure --offline --console=plain
git diff --check
```

- 全项目正式 Gradle 测试：**143 tests，0 failures，0 errors，0 skipped**，15m 55s。首次任务图计算耗时集中在读取 IDEA SDK 的内置插件描述，随后编译与测试成功，未改变依赖或平台版本。
- 插件打包、项目配置及结构检查：**全部通过**，7m 19s。生成 `build/distributions/course-ai-assistant-1.0-SNAPSHOT.zip`（26,018,909 bytes）。设置索引过程出现 IDEA 平台 Marketplace 网络/取消、主题字段及后台写入警告，任务最终成功；未发现新增设置或模型服务的实例化错误。
- `git diff --check`：通过；Git 提示 RequestCoordinator 文件未来检出会转换为 CRLF，没有空白错误。
- 辅助 JDK 21 全源码编译通过。临时独立 JUnit 启动缺少 IDEA 测试 JVM 的模块开放参数与系统目录配置，未能运行测试；最终测试结论以正式 Gradle 测试为准。

测试使用替身与本地测试服务，不读取实际 LLM 密钥或调用真实供应商。新增检查覆盖：状态快照与序列化、项目凭据命名空间、非法配置与密钥输入、EDT 凭据访问拦截、共享初始化与单请求取消、RAG 事件、重配与关闭、迟到资源清理、初始化失败恢复，以及逐请求期限更新与失败后恢复。

## IDEA 验收步骤（待用户执行）

1. 重新运行开发 IDEA，打开本地项目，在 Settings > Tools > Course AI Assistant 找到 Language model。
2. 不填端点/模型/密钥，点击 Advanced settings 标题展开，把超时改为 90000 后保存未就绪配置；再打开并展开确认数值保留，密钥框为空。保存不应触发建库或模型请求。
3. 使用占位 HTTPS 端点及模型名保存，再关闭/重开设置和项目，确认非秘密参数保留；在另一项目检查参数隔离。
4. 在 IDE 中输入实际 Key 或测试占位 Key 保存；再次打开密钥框仍为空。留空保存其他参数应保留密钥；输入新 Key 应替换；勾选 Clear 后保存应删除。Password Safe 的实际持久化与后续调用验证需要 IDE 环境，自动测试未替代此步骤。
5. 尝试远程 HTTP、URL 中的凭据/查询/fragment、零超时、超范围预算，确认无法保存且普通参数不更新。localhost HTTP 可以保存。
6. 检查项目普通 workspace 状态没有明文 Key；Embedding 设置及已有资料管理保持可用。
7. Chat 仍显示 Sample，F2 仍使用原入口，这是本阶段边界；不据此宣称真实模型验收通过。

当前状态：**I1 实现及自动检查完成，等待用户 IDE 验收与 Git 提交；未进入 I2，未暂存、提交或推送。**

## I1 设置页一致性修订

根据用户审阅，两个并列设置页共用 `SettingsForm`：字段布局、间距、密钥输入/清除行为和提示文案统一。Language model 的超时与预算归入高级设置，Course RAG 保留原有高级参数分组。

- 将“Advanced settings”复选框替换为平台原生 `HideableDecorator`，点击标题展开/收起，默认收起；该动作只改变视图，不改变设置或启用功能。
- 提示采用 `JBLabel` 的 SMALL 样式及主题感知的 context-help 前景色，并支持自动换行。端点后缀说明从字段标签移到输入框下方，保存说明与密钥说明同样使用提示样式。
- 两页统一使用 `New API key`、`Clear the stored API key` 和 `Leave the key blank to keep it. Keys are stored in IDE Password Safe.`。
- 参考官方 [UI DSL 的 collapsibleGroup、rowComment 和 Cell.comment 示例](https://plugins.jetbrains.com/docs/intellij/kotlin-ui-dsl-version-2.html)。本工程保留 Java/Swing 实现，使用目标 SDK 已有的对应平台组件。

修订验证：全项目 143 项测试通过（0 失败、0 错误、0 跳过，7m 24s）。既有表单测试增加展开/收起不改变设置、提示实际字号小于正文及主题文字颜色检查。已检查 `build/reports/settings-ui/` 中两个页面展开/收起的四张预览，未发现截断或重叠；预览使用无头 Swing 环境，不代替实际 IDEA 主题验收。插件打包、项目配置和结构检查全部通过（3m 21s）；`git diff --check` 通过。编译提示使用了目标 SDK 中的已弃用 UI API，未影响构建；本轮没有调用真实模型或提交 Git。
