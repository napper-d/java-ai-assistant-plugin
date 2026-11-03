# 第二阶段审阅：聊天 Tool Window

> 第一阶段已由用户验收通过。第二阶段交付聊天界面和本地示例回复，不接入真实 LLM、RAG、代码上下文或代码修改。

> 后续反馈：用户确认本版本主要功能正常，并提出视觉改进；最新界面与配色问题诊断见[第二阶段视觉修订](./stage-2-visual-review.md)。下文保留首版验证记录。

## 1. 本阶段功能

- 右侧 `Course AI Assistant` 工具窗口取代随机数模板。
- 顶部始终提示 `Sample mode`，明确固定回复、无模型和课程检索连接。
- 多行问题输入，支持 Send 和 Ctrl+Enter；Enter 保留换行。
- 空白输入禁用发送按钮，键盘入口也独立拦截空输入。
- 可复制的会话记录，保留多轮展示；发送后清空输入，不清除历史。
- 可选择课程引用示例或一般知识示例。课程来源明确标为虚构，非真实检索结果。
- 一般知识回复包含明确的无课程资料声明，且不会继承上一条回复的引用。
- Clear chat 清空会话和未发送草稿。历史仅存于当前窗口内存。

## 2. 结构与设计

| 文件或目录 | 用途 |
| --- | --- |
| `ui/CourseAiToolWindowFactory.java` | IDEA 适配、注册内容与默认输入焦点；索引期间也可使用 |
| `ui/ChatPanel.java` | 纯 Swing 视图：输入、会话展示、状态、键盘操作 |
| `sample/` | 与 UI 分离的固定示例数据和提供者；不作为队友的最终服务契约 |
| `META-INF/plugin.xml`、`messages/CourseAiBundle.properties` | 新工具窗口 ID、右侧位置和显示名称 |
| `tools/ChatPanelSmokeCheck.java`、`tools/check-chat-ui.ps1` | 无 IDE/网络依赖的交互检查及离屏预览 |

布局：顶部标题和示例声明，中间滚动会话区，底部示例选择、问题输入、状态及发送。
字体与颜色来自 Swing/IDE 当前外观设置；正文和角色标题使用不同字重，避免自行覆盖 IDE 品牌样式。
输入和回答使用样式文本而不是 HTML 渲染，用户粘贴的尖括号、代码和中文按文字显示。

未加入后台网络请求：示例服务仅返回内存中的固定文本。真实异步请求、取消、超时和服务错误处理留到第四阶段。
没有注册全局监听器或保留 Project/Editor 引用；界面监听器只附着于本窗口的控件。

## 3. 验证记录

- 独立 EDT 交互检查已通过：空白按钮/快捷键拦截、发送、中文/多行/类似 HTML 的原样文本、两种回答及引用隔离、保留历史、清空后重新发送。
- 已生成并检查独立 Swing 的浅色、深色和 320 像素窄侧栏预览；此预览不等于 IDEA 内运行截图。
- 最终构建、项目配置检查和插件结构检查全部通过（26s，13 项任务执行）。命令如下；本次强制重跑任务，更新了 instrumentation 的正确缓存。
- ZIP 验证通过：仅包含插件主 JAR；10 个 class 均为 Java 21 字节码；新工厂、右侧位置和显示名称资源正确；已无旧 Kotlin 模板类或旧消息资源。
- XML 解析、5 份工程 Markdown 的本地链接和 `git diff --check` 均通过。
- 实际 IDEA 窗口集成及快捷键体验：待用户手动验收。

```powershell
.\gradlew.bat --offline --console=plain buildPlugin verifyPluginProjectConfiguration verifyPluginStructure --rerun-tasks
```

产物：`build/distributions/course-ai-assistant-1.0-SNAPSHOT.zip`。
独立预览：`build/reports/stage2/chat-light.png`、`chat-dark.png`、`chat-narrow.png`。

处理过的问题：移除 Kotlin 模板后，Gradle instrumentation 的增量输出与缓存仍保存旧 class；清理对应生成目录并强制重跑后，已按 ZIP 实际内容确认消除。README 补充了删除/移动源码后的干净构建指引。

## 4. 用户验收步骤

1. 关闭上一轮开发用 IDEA，运行 `Run IDE with Plugin`。
2. 在开发用 IDEA 中打开任意项目，通过 `View → Tool Windows → Course AI Assistant` 打开右侧窗口。
3. 确认不再出现 Shuffle，顶部显示 Sample mode 声明。
4. 不输入或仅输入空格，检查 Send 禁用；按 Ctrl+Enter 不应添加会话。
5. 选择 `With course citation`，输入问题并发送。确认问题被保留，出现固定样例回答、`[S1] sample-course.pdf — page 3` 和来源虚构声明。
6. 选择 `General knowledge`，输入另一问题，用 Ctrl+Enter 发送。确认旧对话保留，新回复声明未引用课程资料，不带旧引用。
7. 输入多行中文或 Java 代码，确认 Enter 换行、文本不被解释为 HTML。
8. 输入尚未发送的草稿后点击 Clear chat，确认历史及草稿被清除；之后仍能重新发送。
9. 拖窄侧栏，并分别检查实际 IDEA 的浅色/深色主题，确认主要操作可见且文字可读。

不要用样例回答是否回答了任意问题来判断模型效果：第二阶段刻意返回固定样例，回答质量在真实服务集成阶段验收。

## 5. 审阅边界

本阶段没有右键代码提问、真实知识库、模型调用、Diff 或写回功能。点击来源不会打开课程文档。
现有 `docs/interfaces.md` 仍是供队友确认的草案，未被示例服务替代或冻结。

第二阶段通过后，第三阶段实现编辑器右键提问、代码选区快照和侧栏上下文预览。

**状态：第二阶段实现与自动验证已完成，等待用户审阅及 IDEA 内手动验收。第三阶段尚未开始。**

## 6. 工程参考

- [JetBrains Tool Windows](https://plugins.jetbrains.com/docs/intellij/tool-windows.html)：工厂注册、右侧位置、内容与默认焦点。
- [JetBrains Disposer](https://plugins.jetbrains.com/docs/intellij/disposers.html)：界面资源生命周期；本阶段未引入后台任务或全局监听器，后续如引入必须绑定适当的内容或服务生命周期。
