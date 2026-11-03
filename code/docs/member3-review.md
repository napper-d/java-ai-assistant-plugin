# 成员 3 本地交付记录

## 2026-11-03 修复与回归验证

分支：`codex/fix-llm-line-endings`，基于 `origin/master` 的 `be435b7`。

- 修复模型返回 CRLF 或 CR 换行时，成员 3 生成的建议被成员 4 的 LF 校验拒绝的问题。
  解析模型结果时统一为 LF；原始文件快照保持不变，Java 字符串中的字面转义不受影响。
- 比较实际内容时统一换行，拒绝仅改变换行格式的无实质修改建议。
- 提示词补充 LF 约定；新增回归用例覆盖混合换行、空行、末尾换行、原始快照和转义保留，
  并扩展本地 HTTP → LLM 生成 → 成员 4 校验的联调用例。
- 同步更新成员 3 接入说明，以及报告和 PPT 的自动化验证结果。个人材料及人工验收占位保留。

使用 JDK 21 在 `code/` 下验证：

- 修复前运行新增回归测试：17 个测试中 3 个失败，复现上述兼容性及无变化判断问题。
- 修复后定向运行 `LlmServicesTest` 和 `LlmF2IntegrationTest`：通过。
- `gradlew.bat clean test buildPlugin verifyPluginProjectConfiguration verifyPluginStructure --offline --console=plain`：
  通过；22 个测试套件，共 137 tests，0 failures，0 errors，0 skipped，插件 ZIP 已生成。

当前生产入口已接入真实服务适配器，F2 的 Diff 审核、批准、写入及撤销已有实现。
下文是 10 月 28 日交付时的历史状态，不代表当前实现仍未接线。
本次使用本地 HTTP 测试服务，未调用真实供应商；真实课程问答质量、引用语义和实际 IDEA 操作仍需人工验收。

## 2026-10-28 原始交付记录

日期：2026-10-28。分支：`feature/member3-llm-services`。

## 交付

- `LlmChatService`：检索、提示词、引用 ID 校验、通用知识声明与统一错误转换。
- `LlmCodeGenerationService`：单文件完整替换建议、输入快照/路径检查，生成过程无源文件 I/O。
- `HttpLlmClient`：显式 OpenAI-compatible 非流式协议，HTTP 错误分类、截断拒绝、响应大小限制。
- `LlmTasks`：后台调用、总超时、取消传播与关闭；服务借用检索和模型依赖，不擅自关闭共享资源。
- 独立配置与 `.env.example`，详细接入说明见 [llm-services.md](llm-services.md)。
- 21 个新增测试方法及 `tools/check-llm-services.ps1` 独立验证入口。

没有修改共享接口、既有 RAG 算法、目标 IDEA/JDK 版本或聊天窗口接线。

## 本地验证

使用独立安装在本机缓存中的 JDK 21；未修改系统 Java 8 的默认环境。

- `tools/check-llm-services.ps1 -Offline`：通过；21 tests，0 failures，0 errors，0 skipped。
  实际使用 Java 21 编译，Gson 2.13.2、dotenv-java 3.2.0 和 JUnit 5.13.4；
  HTTP 测试使用本机临时端口，不访问真实模型供应商。
- `git diff --check`：通过。
- `gradlew.bat test buildPlugin verifyPluginProjectConfiguration verifyPluginStructure --console=plain`：
  通过；全项目 115 tests，0 failures，0 errors，0 skipped。插件 ZIP 已生成，约 25 MiB。
- `tools/check-chat-ui.ps1 -Offline`：通过；Swing 输入/布局/主题检查及 11 项请求生命周期检查通过。
  这些是无头 Swing 检查，不代表在实际 IDEA 中人工验收。

首次完整测试发现本地 HTTP 测试服务器使用虚拟线程时，会启动 JVM 全局 I/O poller，
触发 IDEA 的逐测试线程泄漏检查。参照已有 `HttpEmbeddingClientTest`，测试服务器改用
明确拥有并等待关闭的普通线程池；未关闭或绕过泄漏检查。修复后全量测试通过，
独立 21 项测试也重新通过。

新增用例覆盖：真实来源/代码上下文传递、无匹配声明、检索失败不调用模型、
错误请求 ID 转换、伪造/缺失/重复/不一致引用、非法输入、检索/模型/生成取消、
总超时与恢复、关闭后拒绝请求、原快照保留、错误目标/路径穿越/空文件/无变化建议拒绝、
HTTP 请求协议、401/403/429/503/302 分类、截断/拒答/超大响应、配置校验及密钥隐藏。

## 尚未验收

- 未配置真实聊天供应商或密钥，未进行付费 API 调用。实际供应商必须支持文档中的协议。
- 未用真实课程问题评估回答质量、引用语义正确性和提示注入抵抗效果。
- Tool Window 仍为 Sample 模式；成员 1 负责项目设置、Password Safe、服务组装及界面验收。
- 成员 4 的 Diff 审核、文件变化检查、显式批准、写入和撤销未实现于本次交付。
- 建议代码未自动编译或运行；结构校验不等于代码行为正确性验证。

此记录不替代用户、成员 1/2/4 或真实供应商的验收确认。
