# 第一阶段审阅：工程基线与模块边界

> 日期：2026 年 10 月 23 日  
> 范围：成员 1 第一阶段的历史交付记录。用户已确认本阶段通过；后续见[第二阶段审阅报告](./stage-2-review.md)。

## 1. 用户配置复核

- `.idea/misc.xml` 已保存 `languageLevel="JDK_21"`，Project SDK 为 `temurin-21`。
- 原工程 Java/Kotlin 编译目标均为 21。
- 现有 Gradle Daemon 日志记录了 Temurin 21 进程。
- 系统终端的 `JAVA_HOME` 仍指向 Java 25。此次不修改系统环境；在工程内声明 Daemon 与编译工具链为 21，使团队的构建要求可共享。

## 2. 本阶段变更

| 文件 | 变更与目的 |
| --- | --- |
| `build.gradle.kts` | 声明 Java/Kotlin JDK 21 工具链；当前无设置页，关闭无用的设置搜索索引生成；保留依赖版本与编译目标 |
| `gradle.properties` | 使用 IDEA 自带的 Kotlin 标准库，避免重复打包冲突 |
| `gradle/gradle-daemon-jvm.properties` | 声明构建 Daemon 使用 JDK 21，不保存本机路径 |
| `.run/Run IDE with Plugin.run.xml` | 修正日志标签为实际 sandbox 日志路径 |
| `src/main/resources/META-INF/plugin.xml` | 更新名称、厂商和描述；补充平台模块依赖；如实注明功能尚待实现 |
| `.gitignore` | 忽略生成的 `.intellijPlatform/` 缓存 |
| `README.md` | 英文开发说明、版本、运行与验证步骤、示例窗口验收及 Java 实现约定 |
| `docs/architecture.md` | 四人模块边界、依赖流向和包结构草案 |
| `docs/interfaces.md` | 上下文、引用、问答、修改建议的数据约定及待队友确认事项 |

现有 Kotlin 示例窗口仍保留；业务实现计划使用 Java 21，构建脚本保留 Kotlin DSL。
接口文档是供团队确认的草案，未创建假服务或声称已实现业务功能。

## 3. 验证结果

- `gradlew.bat --version`：通过；Wrapper 为 9.0.0，识别到项目 Daemon 的 Java 21 条件。
- 本次构建 Daemon 日志：确认实际使用 Temurin 21.0.12.1，而非仅检查编译目标声明。
- `git diff --check`：通过；只有 Windows 换行转换提示，无空白错误。
- 两个 XML 文件解析通过；README 与三份阶段文档的本地链接检查通过。
- 首次离线构建成功（16m 31s），同时报告 Kotlin 标准库重复依赖、缺少平台模块声明、无可搜索设置项三项提示。修正后再次构建成功（9m 59s），配置和结构检查未再报告上述问题。
- 已检查构建包：名称、ID、厂商和平台/Java 依赖正确；示例类字节码 major version 为 65（Java 21）；不再重复打包 Kotlin 标准库。
- 首轮留下的空设置索引 JAR 已清理。相同任务使用配置缓存复验成功（9s），最终 ZIP 仅包含插件主 JAR，不含重复 Kotlin 标准库或旧设置索引 JAR。

最终执行命令：

```powershell
.\gradlew.bat --offline --console=plain buildPlugin verifyPluginProjectConfiguration verifyPluginStructure
```

结果：`BUILD SUCCESSFUL`。产物为 `build/distributions/course-ai-assistant-1.0-SNAPSHOT.zip`。

构建的配置阶段耗时较长。线程检查显示执行位置在 JetBrains 插件结构库扫描目标 IDEA 的内置插件及 JAR 元数据。构建成功结论依据任务最终输出，不以线程活动代替验收。

首次打包还启动无界面的 IDE 生成设置索引；本插件目前无设置页，最终配置关闭此步骤以减少后续开销。以后增加 `Configurable` 设置页时需重新开启。关闭索引不等于跳过插件配置/结构验证。

验证边界：此前日志已确认原始模板插件加载成功。本阶段未启动图形化 IDE，也未实际点击示例窗口，不能将旧日志当成本次修改后的运行验证。

当前没有 Java 业务源码，`compileJava NO-SOURCE` 符合本阶段范围；已验证保留的 Kotlin 示例编译及 Java 21 字节码。完整跨版本兼容性检查与真实业务功能验收不在本阶段完成。

## 4. 用户验收步骤

1. 在原 IDEA 中执行 Reload All Gradle Projects。
2. 确认 Project SDK、语言级别和 Gradle JVM/Daemon 条件为 21。
3. 运行 `Run IDE with Plugin`，在开发用 IDEA 中打开一个 Java 工程。
4. 通过 `View → Tool Windows → My Tool Window` 打开示例窗口。
5. 点击 `Shuffle`，确认随机数显示变化。
6. 在开发用 IDEA 的插件管理页检查插件名称为 `Course AI Assistant`，厂商为 `CST3492 Team C`。
7. 阅读架构及接口草案，确认成员分工边界适合团队协作。

新名称体现在插件信息中；工具窗口本阶段仍叫 `My Tool Window`，第二阶段才替换为聊天界面。

## 5. 审阅重点与后续范围

- 同意固定当前 IDEA 2025.2.4 / Gradle 9.0.0 / Java 21 基线。
- 同意新业务代码采用 Java 21，并暂时保留生成的 Kotlin 示例。
- 同意 UI 通过服务接口对接队友模块，不直接实现检索、提示词和源码写回。
- 接口字段、异步机制、检索阈值与初期修改范围，仍需与对应队友确认；目前没有替队友作出已达成一致的声明。

下一阶段仅实现聊天 Tool Window 与明确标记的示例数据，不接入真实模型或修改代码。

**阶段审阅状态：用户已完成验证并确认第一阶段通过，已授权推进第二阶段。**
