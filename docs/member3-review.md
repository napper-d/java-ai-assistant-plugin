# 成员 3 本地交付记录

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
