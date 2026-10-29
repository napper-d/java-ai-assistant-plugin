# Course AI Assistant

IntelliJ IDEA plugin for CST3492 Assignment A, Group C (F0 + F2).

## Current status

Model/embedding connections, Password Safe credentials, course materials and the index
are shared across all projects in this IDE. Configure them once in Settings > Tools >
Course AI Assistant. After this update, re-enter both connections/keys and add materials
once; old project data is left untouched and is not automatically imported.
Global course data is stored under the IDE configuration directory at
`course-ai-assistant/.course-ai/`. Chat history and modification requests remain project-local.
See [global settings review](docs/integration-global-settings-review.md).


The Tool Window uses right-aligned **Chat** and **Course materials** icon navigation,
with Clear chat next to Chat. The welcome screen describes course Q&A, selected-code
questions and reviewed code changes. Configure **Language model** and **Course RAG**
under Settings > Tools > Course AI Assistant; their keys are separate in Password Safe.

Adding PDF/PPTX materials automatically registers and indexes them using the configured
embedding service. Removing materials updates the remaining index; with no materials,
Chat cannot use the old index. Opening the window loads saved state automatically.
The page is a filename list: use the plus icon to add files, a row’s trash icon to remove it,
and the top-right gear to open Course RAG settings. Loading/indexing is shown by a top
progress bar. Failure-only feedback and a retry icon replace permanent status panels.
There are no Refresh / Load, Rebuild or Update materials controls. Re-adding a registered
file shows a compact duplicate notice and refreshes the index from the original contents.
Saving settings alone does not make provider calls. If configuration is incomplete,
files remain registered and the materials page explains how to finish indexing.

Chat sends the current question, explicit selection and retrieved evidence to the model.
Answers render CommonMark headings, emphasis, lists, quotes, code blocks and GFM tables in bordered,
theme-aware cards. Sources have a separate bordered card. Processing is shown only as
Thinking in the conversation. There is no footer status or keyboard hint text. Enter sends; Shift+Enter inserts a newline.
Citations use numbers starting at 1 for each answer. Each source row shows only its
number, document and page/slide; click it to expand the exact original evidence used
for that answer. Expanding does not retrieve or call a model again. Internal reference
IDs are validated in the service and never used as visible citation labels. Details
show original chunks and retrieval metadata, not the embedding vector arrays.

There is one question composer in Chat. The independent retrieval UI and its dedicated
implementation have been removed. Chat history is display-only and remains in memory.
Retrieval failure is an error; only successful no-match retrieval discloses general knowledge.
F2 now shares global Language model settings with Chat. Model setting changes cancel pending generation and review; RAG changes leave F2 independent. Only explicit Diff approval applies a single-file change. See the
[UI / automatic indexing review](docs/integration-i3-review.md), [I2 review](docs/integration-i2-review.md)
and [integration plan](docs/integration-plan.md). Real-provider / IDE acceptance remains pending.

## Development baseline

| Component | Version |
| --- | --- |
| JDK / Java language target | 21 |
| Gradle Wrapper | 9.0.0 |
| IntelliJ Platform Gradle Plugin | 2.10.2 |
| Target IntelliJ IDEA | 2025.2.4 (IU) |
| Kotlin Gradle Plugin / JVM target | 2.1.20 / 21 |
| Plugin ID | `edu.teamc.course-ai-assistant` |

Use the checked-in Wrapper. Keep target versions unchanged until a compatibility
review; the IDEA used to edit the plugin may be newer than the target IDEA.

## Open and configure

1. Install JDK 21, for example Eclipse Temurin.
2. Open **this directory**, containing `settings.gradle.kts`, as a Gradle project.
3. Set **Project SDK** to JDK 21 and **Language level** to 21 without preview.
4. Set **Gradle JVM** to JDK 21 or the daemon criteria supplied by this project.
5. Reload Gradle. Initial dependency downloads need network access.

Java/Kotlin toolchains and daemon criteria require 21. No machine-specific JDK path
is committed. Install a local JDK 21; automatic JDK download repositories are not configured.

## Build and verify

From a terminal at this directory:

```powershell
.\gradlew.bat --version
.\gradlew.bat buildPlugin verifyPluginProjectConfiguration verifyPluginStructure
```

On macOS/Linux use `./gradlew`. `--version` may report a different **Launcher JVM**
when the shell's `JAVA_HOME` differs; the daemon and compilation must use 21.
The project's daemon criteria select that daemon.

The installable ZIP is generated under `build/distributions/`. It is the plugin
baseline, not the assignment's complete source/report/slides package. The two
verification tasks check project configuration and plugin structure, not UI
behavior or compatibility with every IDEA version.

Once dependencies are cached, add `--offline` if needed. If a dependency is absent,
retry online rather than changing versions.

After removing or moving source classes, run a clean release build to avoid stale
instrumentation outputs: `.\gradlew.bat clean buildPlugin verifyPluginProjectConfiguration verifyPluginStructure --rerun-tasks`.

## Run the chat window

1. Run **Run IDE with Plugin**, or execute `.\gradlew.bat runIde`.
2. Wait for the separate development IDEA to open.
3. Open a small Java project in that IDEA.
4. Select **View > Tool Windows > Course AI Assistant** (ID: `CourseAiAssistant`).
5. Configure **Language model** and **Course RAG** in IDE Settings, including their respective keys.
6. Add course files in **Course materials** and wait for automatic indexing, then ask a question in **Chat**. No example selector or fixed sample answers appear in this entry.
7. Close the development IDEA to finish `runIde`.

Use the Course materials folder icon to add PDF/PPTX files. Indexing starts automatically
and runs in the background. Cancel stops the current operation; earlier registration
or an already published index may remain. Retry indexing resumes from saved registrations.
Removing files never deletes originals. After editing a registered source file, add the same file again to index its current contents. Failed updates retain the previous snapshot on disk, but pending
material changes prevent Chat from answering from stale registrations.

Use Enter to send and Shift+Enter for newlines. Blank questions are rejected.
The top trash icon (**Clear chat**) removes the conversation and draft. History is in memory only,
per window: it is not persisted or supplied to a model. Only the registered course index is used for retrieval; source code is not scanned automatically. The editor action reads only the selected text and its
metadata; no surrounding code or whole-project scan is performed. Source rows expand original evidence; opening external documents is not yet implemented.

Select code in an editor and choose **Ask Course AI Assistant** from its context
menu. Check the attached path, language and inclusive line range before sending.
The snapshot stays attached even if another editor is opened, until sent, removed
or cleared. This is a snapshot from the action invocation, not a live selection.

While waiting, Send is disabled; typing a new draft is
allowed. Clear chat cancels the request and invalidates queued replies before
starting a new conversation. Hiding the Tool Window preserves its session;
disposing its content or closing the project releases the coordinator.

### Development IDEA editor colors

The development IDEA has separate settings under `build/idea-sandbox/`. If its code
editor is white while your everyday IDEA is dark, open **Settings > Editor > Color
Scheme** in the development IDEA and choose a dark scheme such as Darcula. The IDE
UI theme is separately configured in **Appearance & Behavior > Appearance**.
This plugin only styles its own window; it does not change the global editor scheme.

### Development log output

`runIde` keeps IDE log output in `build/idea-sandbox/IU-2025.2.4/log/idea.log`
instead of duplicating it in the Run console. This includes both platform and
Course AI Assistant diagnostics. The shared run configuration's **idea.log** tab
still shows the full log, and IDE error notifications remain enabled. Gradle
build failures and JVM messages are unaffected; this is not a zero-warning mode.
It does not prevent background network requests or improve Gradle configuration time.

To restore IDE logging in the Run console for debugging:

```powershell
.\gradlew.bat runIde -PideConsoleLog=true
```

The Gradle plugin's automatic version check is disabled through
`org.jetbrains.intellij.platform.selfUpdateCheck=false`; dependency resolution,
project verification and the pinned plugin/IDE versions remain unchanged.

## Interaction and regression tests

All chat, retrieval and material-management checks now run through JUnit:

```powershell
.\gradlew.bat test --configuration-cache
```

For only chat interaction and request lifecycle checks, use:

```powershell
.\tools\check-chat-ui.ps1 -Offline
```

The script delegates to the same Gradle test task with class filters; it no longer
exports a classpath or separately compiles Java sources. Omit `-Offline` when
missing dependencies need downloading. JDK 21 and the configured IDEA SDK remain
required. Tests start no interactive IDE or external model service.

Chat tests exercise the asynchronous path used by the Tool Window, including
literal input, examples, citations, history, theme changes, narrow layouts,
cancellation, stale replies, disposal, failures, deadlines, recovery and selected
code context. Light/dark/narrow previews are written to `build/reports/stage2/`.
These checks do not replace actual IDE acceptance.

Sandbox log:

```text
build/idea-sandbox/IU-2025.2.4/log/idea.log
```

The shared run configuration points to this path. Update it if the target platform
is deliberately changed later.

Searchable-settings index generation is enabled for the Course RAG settings page.
The platform's bundled Kotlin standard library is used
instead of packaging a duplicate.

## Source layout and language policy

```text
.run/                   Shared IDE run configuration
gradle/                 Wrapper and JDK 21 daemon criteria
src/main/java/          UI, context, coordinator, service contracts, sample adapters and RAG
src/test/java/          JUnit chat/UI, lifecycle, RAG and local HTTP tests
src/main/resources/     Plugin descriptor, icon and message resources
docs/                   Module boundaries, draft contracts and review notes
tools/                  Shortcut for filtered Gradle chat tests
build/                  Generated output and sandbox (ignored)
```

Application code uses Java 21. The old Kotlin sample and helper are removed;
Kotlin build scripts and the existing build plugin remain unchanged.

## Collaboration and stage reviews

- [Team onboarding, ownership and GitHub workflow](CONTRIBUTING.md)
- [Architecture and ownership](docs/architecture.md)
- [Service contracts and pending integration agreements](docs/interfaces.md)
- [Local RAG configuration and integration](docs/rag-search.md)
- [Stage 1 review and verification](docs/stage-1-review.md)
- [Stage 2 review and acceptance](docs/stage-2-review.md)
- [Stage 2 visual revision and editor color diagnosis](docs/stage-2-visual-review.md)
- [Stage 3 context review](docs/stage-3-review.md)
- [Stage 4 implementation, fixes and acceptance](docs/stage-4-review.md)

Each member owns their module and documentation. Coordinate interface changes
with affected members. Complete one stage, report checks and limitations, then
wait for the user's review before proceeding.

## References

- [IntelliJ Platform Plugin SDK](https://plugins.jetbrains.com/docs/intellij/developing-plugins.html)
- [Gradle daemon JVM criteria](https://docs.gradle.org/current/userguide/gradle_daemon.html#sec:daemon_jvm_criteria)
- [Gradle toolchains](https://docs.gradle.org/current/userguide/toolchains.html)
- [IntelliJ Platform Gradle tasks](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-tasks.html)
