# RAG GUI R1：配置与项目级服务组装审阅

日期：2026-10-27。对应 [分阶段计划](rag-gui-plan.md) 的 R1。R2、R3 尚未开始，用户验收待完成。

## 1. 实现内容

- Settings > Tools > Course AI Assistant > Course RAG：固定现有 OpenAI-compatible Embeddings 协议，配置服务基础地址、模型和新密钥。高级区可展开 HTTP 时限、维度、分块/重叠、批量大小、阈值、PPTX 备注和输入前缀。
- 未填写基础地址/模型/密钥可以保存未就绪配置；已填写的地址及数值必须合法。基础地址不包含 `/embeddings`。保存不触发网络请求或索引构建。
- 普通参数存入当前项目本地 workspace；不存在 API Key 状态字段。API Key 通过项目隔离的 PasswordSafe 条目存取，不回填设置表单。留空保留、输入替换、显式选项清除。
- PasswordSafe 写入通过后台进度任务完成，失败反馈给 Apply；读取延迟到建库/查询真正需要 Embedding 时，且禁止 EDT 调用。密钥跨重启持久化能力取决于用户 IDE Password Safe 的存储模式；插件不绕过其仅内存配置。
- `ProjectRagService` 从项目设置与 PasswordSafe 按需组装 RAG 会话，直接使用当前项目根目录。登记资料不要求 API Key。固定使用 `.course-ai` 数据目录和 `default` 课程库。
- 检索与索引共享同一个惰性 HTTP 客户端。保存配置后取消旧会话，后续操作使用新参数；不删除或自动重建原索引。项目关闭时依序关闭检索、索引、共享客户端；隐藏侧栏不会释放项目服务。
- 直接返回核心模块的可取消 future，保留已有取消桥接。R2 再实现重建与刷新等复合任务的取消/状态交付。
- 设置搜索索引已重新启用；聊天仍使用原 Mock 与 Sample 标识，选中代码功能保持原接线。

## 2. 主要文件

| 文件 | 职责 |
| --- | --- |
| `settings/RagSettings.java` | 非密钥参数的项目本地持久化、快照和 RagConfig 构造 |
| `settings/RagCredentials.java` | PasswordSafe 的项目隔离及后台访问约束 |
| `settings/RagSettingsConfigurable.java` | 配置界面、校验、保留/替换/清除密钥、Apply/Reset |
| `settings/CourseAiSettingsGroup.java` | IDEA 设置树父分组 |
| `application/ProjectRagService.java` | 项目根目录、资源归属、配置切换和释放 |
| `application/RagSession.java` | 单代配置的索引/检索服务与惰性共享传输 |
| `META-INF/plugin.xml`、`build.gradle.kts` | 项目设置入口与搜索索引 |
| `src/test/java/.../settings/RagSettingsTest.java` | 配置边界、表单、项目服务及 EDT 密钥访问约束 |
| `src/test/java/.../application/RagSessionTest.java` | 无密钥登记、共享传输、真实持久化检索、关闭取消 |

## 3. 自动验证

环境：Temurin JDK / javac 21.0.12.1，保持 Java 21 和 IDEA 2025.2.4 目标。测试使用可控本地 HTTP 服务和生成的 PDF，不接触用户的实际 API Key，不代表真实供应商或真实 IDEA 手动验收。

- 全部生产源码以 `javac -encoding UTF-8 --release 21` 和本机目标 IDEA SDK/缓存依赖编译通过；只有既有 ContextCollector 的弃用 API 提示。
- 全部 61 项 JUnit 检查（原 48 项 RAG + 新增 13 项 R1）通过 Gradle 原生测试运行，0 skipped / aborted / failed；报告位于 `build/reports/tests/test/index.html`。独立 JUnit Platform launcher 同样通过 61 项，日志位于 `build/r1-check/junit.log`。使用项目真实测试源码，未使用或访问真实 PasswordSafe 条目。
- `ChatPanelSmokeCheck` 通过输入/快捷键、示例与来源、清空、主题及窄布局回归；`RequestLifecycleCheck` 全部 11 项通过。实际入口通过 `java -Djava.awt.headless=true -cp <编译输出与缓存 SDK/依赖> edu.teamc.courseaiassistant.ui.ChatPanelSmokeCheck` / `RequestLifecycleCheck` 执行，没有把该结果描述为启动 IDEA。
- 新设置表单已生成并检查离屏预览：`build/reports/rag-gui-r1/settings-preview.png`。该图使用普通 Swing 外观，不代表 IDEA 的最终主题与设置树集成。
- Gradle 离线检查已完成生产/测试编译，在执行测试前因本机缺失 IDEA 测试框架传递依赖而中止；在线补齐项目声明的依赖后，完整检查 **BUILD SUCCESSFUL，6m 59s，22 项任务（10 项执行、12 项已是最新）**。`test`、`buildPlugin`、`verifyPluginProjectConfiguration`、`verifyPluginStructure`、`exportCheckClasspath` 均成功。
- `buildSearchableOptions`、`jarSearchableOptions` 成功。已检查生成的 `build/tmp/buildSearchableOptions/p-edu.teamc.course-ai-assistant-searchableOptions.json`：包含 Course AI Assistant 分组、Course RAG 页面及基础/高级字段。该过程启动了 SDK 的无界面索引程序，不等同于交互式 IDEA 验收。
- 插件包已更新：`build/distributions/course-ai-assistant-1.0-SNAPSHOT.zip`。索引程序输出了目标 SDK 的主题、Marketplace 取消/JCEF 等警告，没有阻止构建；未为消除这些环境提示升级依赖或改动既有 IDE 配色。

完整 Gradle 检查命令：

```powershell
.\gradlew.bat --console=plain test buildPlugin verifyPluginProjectConfiguration verifyPluginStructure exportCheckClasspath --continue --no-configuration-cache
```

首次普通沙箱访问 Gradle 缓存锁被拒绝；独立 RAG 测试还需要回环 HTTP 和文件原子替换，沙箱内运行因这些限制失败。已使用受审查的本机执行权限重跑；没有据此修改或放宽生产代码、测试断言或依赖版本。

工作区保留本阶段变更，`git diff --check` 通过；没有暂存、提交或推送。

## 4. 用户手动验收步骤

1. 在插件仓库使用 JDK 21 运行 `.\gradlew.bat runIde`；打开独立的本地 Java 示例项目。
2. 在 Settings > Tools > Course AI Assistant > Course RAG 打开设置。确认初始基础地址/模型/密钥为空；高级默认分块 800、重叠 120、批量 16、时限 20000ms、阈值 0.75。
3. 不填密钥，修改模型或高级参数，Apply 后关闭再打开；确认参数保留，API Key 框仍为空。Apply 不应建库或发送网络请求。
4. 依次输入无效地址、0ms 时限、重叠不小于分块和超范围阈值；确认阻止保存并有可理解提示。Reset 恢复已保存值。
5. 使用测试密钥验证替换；再次打开并留空 Apply，确认保留行为；勾选 Clear the stored API key 再 Apply 验证清除。此阶段没有真实联网入口，可使用调试器在后台读取或 IDE Password Safe 存储检查；真实建库/查询验证留待 R2/R3，不把空白输入框当作已清除密钥的证据。
6. 重开项目检查普通配置保留；打开另一个项目，确认参数独立。API Key 的跨重启行为同时核对 IDE Settings > Appearance & Behavior > System Settings > Passwords 的存储模式。
7. 打开 Course AI Assistant，确认聊天仍为 Sample、示例引用仍标明虚构；选中 Java 代码后右键提问仍能附上原有上下文。

## 5. 待验收与后续边界

- 真实 IDEA 设置树、PasswordSafe 解锁/存储行为、持久化与多项目场景由用户按上述步骤验收，自动化代理与本地 HTTP 不替代该验收。
- 尚无课程资料 GUI 和检索 GUI，因此 R1 无法在界面里演示资料登记；底层行为由自动测试检查，界面操作属于 R2。
- R1 验收通过后再实施 R2：登记列表 API、资料页、文件选择和独立索引任务。成员 3 就绪前不切换真实聊天。

**用户验收：2026-10-27，用户表示“第一阶段我认为基本可以”，并授权继续 R2。未将该表述扩展为对每个 PasswordSafe/多项目边界用例的逐项确认。**
