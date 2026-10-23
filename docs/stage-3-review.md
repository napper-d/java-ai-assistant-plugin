# 第三阶段审阅：右键提问与代码上下文

> 第二阶段已通过。本阶段实现编辑器右键提问入口、代码上下文采集与预览。第三阶段不接入真实 LLM、RAG 或代码修改。

## 1. 本阶段功能

- 编辑器右键菜单新增 `Ask Course AI Assistant` 操作，仅在有选区时启用。
- 选中 Java 代码后，右键操作捕获不可变的 `CodeContext` 快照，包含文件路径、语言、选区文本、行号范围和文档修改戳记。
- 操作激活后自动打开右侧 `Course AI Assistant` 工具窗口，并在输入区上方显示代码上下文预览卡片。
- 预览卡片展示项目相对路径、语言 ID、行号范围和选区文本（超过 200 字符时截断并显示省略号）。
- 预览卡片提供关闭按钮，可移除已附加的上下文。
- **发送问题时，上下文随消息一起"发送"：预览卡片消失，在会话记录的用户消息气泡下方显示位置摘要**（如 `📎 src/Main.java  lines 10–15`）。
- 清空会话时同时清除代码上下文。
- 无选区、无编辑器或无关联文件时，右键操作自动禁用。

## 2. 结构与设计

| 文件或目录 | 用途 |
| --- | --- |
| `context/CodeContext.java` | 不可变的代码上下文数据结构，使用行号（1-based）而非偏移量 |
| `context/ContextCollector.java` | 从活动编辑器捕获 `CodeContext`，处理文件路径计算、语言检测和行号转换 |
| `action/AskAssistantAction.java` | 编辑器右键菜单操作，捕获上下文并激活工具窗口 |
| `ui/CourseAiToolWindowFactory.java` | 新增 `findChatPanel` 方法，供操作查找并传递上下文 |
| `ui/ChatPanel.java` | 新增 `attachCodeContext` 方法、上下文预览卡片 UI，发送时清除预览 |
| `ui/ConversationView.java` | `UserMessage` 支持显示附带的代码上下文位置摘要 |
| `META-INF/plugin.xml` | 注册 `AskAssistantAction` 到 `EditorPopupMenu` |

上下文采集时机：用户右键点击操作时立即捕获，而非发送问题时。这确保上下文反映用户意图时刻的代码状态，避免后续编辑导致的不一致。

位置信息使用行号（1-based）而非偏移量，更符合用户直觉。行号范围包含起始行和结束行（如 `lines 10–15`），当选择结束于某行行首时，结束行号会回退到上一行。

预览卡片设计：使用 `ChatWidgets.RoundedPanel` 保持与聊天界面一致的视觉风格；代码预览使用等宽字体和可滚动文本区，限制最大 6 行；元数据使用辅助文字颜色，与主内容区分。

发送后显示：用户消息气泡下方右对齐显示 `📎 文件路径  lines X–Y` 格式的上下文摘要，使用正常文字样式（非辅助色），与消息气泡紧密组合（间距 2px），视觉上作为一个整体。

未实现：真实 LLM 调用、RAG 检索、代码修改、Diff 审阅和文件写回。当前仍为 Sample mode，发送问题返回固定示例回复。

## 3. 验证记录

- Gradle 构建通过（`buildPlugin`，1m 7s），Java 21 编译成功。
- 插件结构验证通过（`verifyPluginStructure`，5m 18s），无结构错误。
- 插件 ZIP 产物包含所有新增类：`CodeContext.class`、`ContextCollector.class`、`AskAssistantAction.class`。
- 插件 XML 正确注册操作到 `EditorPopupMenu`，ID 为 `CourseAiAssistant.AskAssistant`。
- 有一处弃用 API 警告（`PsiDocumentManager.getPsiFile`），不影响功能，后续可优化。
- 实际 IDEA 窗口集成、右键菜单可见性、上下文预览显示、发送后摘要显示和主题兼容性：待用户手动验收。

```powershell
.\gradlew.bat --offline --console=plain buildPlugin verifyPluginStructure
```

产物：`build/distributions/course-ai-assistant-1.0-SNAPSHOT.zip`。

## 4. 用户验收步骤

1. 关闭旧开发用 IDEA，运行 `Run IDE with Plugin`。
2. 在开发用 IDEA 中打开任意 Java 项目，打开一个 `.java` 文件。
3. 选中一段代码（例如一个方法或几行语句）。
4. 右键点击选区，在弹出菜单中查找 `Ask Course AI Assistant`（应在菜单底部附近）。
5. 点击该操作，确认右侧 `Course AI Assistant` 工具窗口自动打开。
6. 确认输入区上方出现代码上下文预览卡片，显示文件路径（如 `src/Main.java`）、语言（`JAVA`）、行号范围（如 `lines 10–15`）和选区文本。
7. 输入问题并发送，确认：
   - 预览卡片消失
   - 会话记录中出现用户消息气泡
   - 气泡下方显示 `📎 src/Main.java  lines 10–15` 格式的上下文摘要
8. 重新选中代码，再次右键调用操作，确认预览重新出现。
9. 点击预览卡片右上角的 `✕` 按钮，确认卡片消失（不发送消息）。
10. 不选中任何代码，右键点击编辑器，确认菜单中没有 `Ask Course AI Assistant` 或该操作为灰色禁用状态。
11. 点击 `Clear chat`，确认会话和上下文预览均被清除。
12. 切换 IDEA 主题（浅色/深色），确认预览卡片和上下文摘要样式跟随更新。

不要选中非 Java 文件（如 `.txt` 或 `.xml`）测试语言检测：第三阶段接受任何有选区的文件，语言 ID 显示为实际检测值或 `unknown`。

## 5. 审阅边界

本阶段没有真实模型调用、知识库检索或代码修改功能。发送问题仍返回固定示例回复，与第二阶段相同。

上下文预览仅展示选区文本，不提供语法高亮或行号。位置信息使用行号（1-based）而非偏移量，更符合用户习惯。

`CodeContext` 当前不传递到示例回复服务；第四阶段将定义服务接口时，将上下文作为请求参数传入。

第四阶段将实现服务接口定型、后台异步请求、超时和失败处理，并为成员 2、3、4 提供接入说明。

**状态：第三阶段实现与构建验证已完成，等待用户在实际 IDEA 中复核。**
