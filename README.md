# Course AI Assistant

IntelliJ IDEA plugin for CST3492 Assignment A, Group C (F0 + F2).

## Current status

Stage 1 was reviewed and accepted. Stage 2 replaces the random-number template
with a Java chat Tool Window. It supports local sample conversations, example
citations, general-knowledge disclosure, multiline questions, Ctrl+Enter and clearing.
The visual revision uses right-aligned question bubbles, open assistant responses,
source cards and a rounded bottom composer, with light/dark theme support.
RAG, live LLM calls, editor context and reviewed changes are not implemented yet.

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
5. Check the visible **Sample mode** notice. Type a question and click the upward-arrow **Send** button.
6. Switch **Example** between **With course citation** and **General knowledge**.
   The first includes an invented source; the second explicitly discloses general
   knowledge. Neither generates an answer from your question.
7. Close the development IDEA to finish `runIde`.

Use Ctrl+Enter to send and Enter for newlines. Blank questions are rejected.
The top trash icon (**Clear chat**) removes the conversation and draft. History is in memory only,
per window: it is not persisted or supplied to a model. No code or course files
are read. Sources are plain text for this UI stage, not document-opening links.

### Development IDEA editor colors

The development IDEA has separate settings under `build/idea-sandbox/`. If its code
editor is white while your everyday IDEA is dark, open **Settings > Editor > Color
Scheme** in the development IDEA and choose a dark scheme such as Darcula. The IDE
UI theme is separately configured in **Appearance & Behavior > Appearance**.
This plugin only styles its own window; it does not change the global editor scheme.

## Standalone interaction checks

With a local JDK 21 selected in the current shell's `JAVA_HOME`:

```powershell
.\tools\check-chat-ui.ps1
```

The script compiles the real Swing view and sample provider using Java 21, then
checks interactions on the event dispatch thread. It needs no Gradle download,
JUnit dependency or IDE process. It also writes light/dark/narrow standalone
previews to `build/reports/stage2/`. These are not screenshots of IntelliJ's exact
look and feel; validate actual IDE integration manually before accepting Stage 2.

Sandbox log:

```text
build/idea-sandbox/IU-2025.2.4/log/idea.log
```

The shared run configuration points to this path. Update it if the target platform
is deliberately changed later.

Searchable-settings index generation is disabled because this baseline provides
no custom settings pages. Re-enable `buildSearchableOptions` when a plugin
`Configurable` is added. The platform's bundled Kotlin standard library is used
instead of packaging a duplicate.

## Source layout and language policy

```text
.run/                   Shared IDE run configuration
gradle/                 Wrapper and JDK 21 daemon criteria
src/main/java/          Chat view, IDE factory and isolated sample provider
src/main/resources/     Plugin descriptor, icon and message resources
docs/                   Module boundaries, draft contracts and review notes
tools/                  Standalone Swing interaction checks
build/                  Generated output and sandbox (ignored)
```

Application code uses Java 21. The old Kotlin sample and helper are removed;
Kotlin build scripts and the existing build plugin remain unchanged.

## Collaboration and stage reviews

- [Architecture and ownership](docs/architecture.md)
- [Draft service contracts](docs/interfaces.md)
- [Stage 1 review and verification](docs/stage-1-review.md)
- [Stage 2 review and acceptance](docs/stage-2-review.md)
- [Stage 2 visual revision and editor color diagnosis](docs/stage-2-visual-review.md)

Each member owns their module and documentation. Coordinate interface changes
with affected members. Complete one stage, report checks and limitations, then
wait for the user's review before proceeding.

## References

- [IntelliJ Platform Plugin SDK](https://plugins.jetbrains.com/docs/intellij/developing-plugins.html)
- [Gradle daemon JVM criteria](https://docs.gradle.org/current/userguide/gradle_daemon.html#sec:daemon_jvm_criteria)
- [Gradle toolchains](https://docs.gradle.org/current/userguide/toolchains.html)
- [IntelliJ Platform Gradle tasks](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-tasks.html)
