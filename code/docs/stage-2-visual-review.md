# 第二阶段视觉修订审阅

> 用户已确认第二阶段主要功能可正常运行，本次根据提供的聊天侧栏参考图修订视觉。第三阶段尚未开始。

## 1. 开发用 IDEA 的白底代码区

检查到沙盒配置 `build/idea-sandbox/IU-2025.2.4/config/options/colors.scheme.xml` 中为：

```xml
<global_color_scheme name="_@user_Default" />
```

这是开发用 IDEA 的编辑器配色设置。`runIde` 使用独立配置目录，不会自动等同于日常开发用的 IDEA 设置。
插件主代码没有设置全局 Look and Feel、写入 UIManager 默认值或修改 EditorColorsManager 的逻辑。
侧栏的颜色只应用于侧栏控件，独立预览检查的主题模拟只发生在单独的测试 JVM 中。

在 **runIde 打开的开发用 IDEA** 中操作：

1. `Settings → Editor → Color Scheme`，选择 `Darcula` 或自己使用的暗色方案。
2. 如果 IDE 外壳也需要暗色，在 `Appearance & Behavior → Appearance → Theme` 选择暗色主题。
3. 点击 Apply / OK。UI 主题与代码编辑器配色是两项不同设置。

本次未直接改写沙盒配色文件，避免与正在运行的 IDEA 设置保存冲突，也不让聊天插件强制覆盖用户全局设置。

参考：[JetBrains 编辑器配色说明](https://www.jetbrains.com/help/idea/configuring-colors-and-fonts.html)。

## 2. 视觉变更

- 参考图中的深灰聊天背景、右对齐圆角问题气泡、开放式助手回答排版。
- 助手回答与来源、免责声明分层；来源放在轻描边圆角卡片中。
- 输入框与示例选择、发送合为一个底部圆角面板；发送使用向上箭头按钮。
- 顶部清空功能改为垃圾桶图标，保留 Tooltip、无障碍名称和键盘焦点提示。
- 使用低对比度的辅助文字、适度行距与留白，移除上一版的矩形表单边框。
- 保留明确的 Sample mode 提示；只提供已实现的清空、示例选择与发送操作。
- 同时提供浅色版本，且已有会话在 IDE 切换主题后更新颜色并保持内容。

深色样式的主要颜色：背景 `#242424`，用户气泡 `#303030`，输入区 `#26292D`，描边 `#44474D`，正文 `#D5D7DC`，辅助文字 `#9499A3`。
这些是插件侧栏自身的局部样式，不是 IDE 编辑器的主题配置。

## 3. 实现边界

| 文件 | 用途 |
| --- | --- |
| `ui/ChatPanel.java` | 页面结构、输入、状态与既有发送/清空逻辑 |
| `ui/ConversationView.java` | 可随侧栏宽度重排的消息列表、气泡、助手回答与来源卡片 |
| `ui/ChatWidgets.java` | 圆角控件、主题响应、图标、输入占位文字、滚动条与可复制文字 |
| `ui/ChatTheme.java` | 读取 IDE 当前外观，为局部控件选择浅色或深色样式 |
| `tools/ChatPanelSmokeCheck.java` | 原交互回归检查及主题切换、长文本、缩放布局检查 |

所有消息内容仍以普通文本显示，不执行 HTML。长路径与无空格标识符允许换行；连续消息垂直滚动。
模型、RAG、Markdown 代码块、右键代码上下文、Diff 和写回功能没有在本次修订实现。

## 4. 验证

- 交互检查通过：空输入、按钮/快捷键、中文、多行文本、引用隔离、会话保留和清空。
- 补充检查通过：插件不更改全局 UI 主题、主题切换后保留会话并更新输入颜色。
- 补充检查通过：长标识符换行，多条消息在 440 → 320 → 440 像素宽度变化时不重叠，列表宽度跟随视口且内容可垂直溢出。
- 补充检查通过：每个消息文本控件的实际高度足以容纳换行内容，防止问题气泡截字。
- 已生成并目视检查浅色、深色、一般知识和窄侧栏离屏预览。
- 最终 Gradle 构建、项目配置和插件结构检查全部通过（7s）；Java 21 编译成功。
- ZIP 内容、局部 UI 新组件、Java 21 字节码和文档链接检查通过，无旧模板或测试类混入插件包。

验证命令：

```powershell
.\tools\check-chat-ui.ps1
.\gradlew.bat --offline --console=plain buildPlugin verifyPluginProjectConfiguration verifyPluginStructure
```

独立 UI 检查需当前终端的 JAVA_HOME 指向 JDK 21，说明见 README。

离屏预览使用真实 Swing 控件，但不是 IDEA 内实际截图；IDE 内的字体缩放、系统外观、下拉交互与编辑器配色仍需手动确认。

预览位于 `build/reports/stage2/`，运行 `tools/check-chat-ui.ps1` 可重新生成。

## 5. 用户验收

1. 关闭旧开发用 IDEA，重新运行 Run IDE with Plugin，打开 Course AI Assistant。
2. 对照参考图检查消息气泡、回答排版、引用卡片、输入区和按钮。
3. 重试发送、Ctrl+Enter、两种示例和清空，确认功能保持正常。
4. 粘贴长路径和多行中文，拖窄侧栏，检查换行与滚动。
5. 在开发用 IDEA 中分别设置暗色 UI 主题与编辑器配色，确认代码区和侧栏均可读。

**状态：视觉修订与自动检查完成，等待用户在实际 IDEA 中复核后再推进第三阶段。**
