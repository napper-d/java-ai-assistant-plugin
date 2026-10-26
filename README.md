# Course AI Assistant

IntelliJ IDEA plugin for CST3492 Assignment A, Group C (F0 + F2).

## Current status

Stages 1–3 were accepted. Stage 4 has service contracts, background invocation,
a 30-second request deadline, cancellation and failure recovery. The Tool Window
still runs in **Sample mode** with simulated replies and invented citations.
Editor selections are captured as immutable code snapshots and passed to chat requests.
The UI supports Enter to send, Shift+Enter for newlines, clearing during a request,
and light/dark themes. Stage 4 review fixes are ready for IDE acceptance.
The `rag` package now registers original PDF/PPTX paths without copying files, with configurable online
embeddings, persisted local vector search, bounded multi-step reference expansion
and a pluggable asynchronous query planner. It is not
connected to the Tool Window yet. A real embedding provider and course corpus still
need acceptance; LLM answers, Diff review and source writes remain pending.

See [RAG configuration, commands and integration](docs/rag-search.md) and
[Member 2 task status](docs/rag-search-task.md). `ragImport` needs no API key;
`ragIndex` and `ragSearch` require the explicitly configured embedding endpoint.

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

Use Enter to send and Shift+Enter for newlines. Blank questions are rejected.
The top trash icon (**Clear chat**) removes the conversation and draft. History is in memory only,
per window: it is not persisted or supplied to a model. No code or course files
are read automatically. The editor action reads only the selected text and its
metadata; no surrounding code or whole-project scan is performed. Sources are
plain text, not document-opening links.

Select code in an editor and choose **Ask Course AI Assistant** from its context
menu. Check the attached path, language and inclusive line range before sending.
The snapshot stays attached even if another editor is opened, until sent, removed
or cleared. This is a snapshot from the action invocation, not a live selection.

While waiting, Send and the example selector are disabled; typing a new draft is
allowed. Clear chat cancels the request and invalidates queued replies before
starting a new conversation. Hiding the Tool Window preserves its session;
disposing its content or closing the project releases the coordinator.

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

The script compiles all production Java sources and both check entry points with
Java 21. It uses the IDEA 2025.2.4 SDK extracted by an earlier Gradle sync and asks
Gradle to export the dependency classpath, including the RAG libraries. It starts
no IDE or model service. Gradle may download missing dependencies; pass `-Offline`
after they are cached to prohibit downloads. If automatic SDK discovery fails:

```powershell
.\tools\check-chat-ui.ps1 -IdeHome 'C:\path\to\ideaIU-2025.2.4-win'
```

The original interaction/theme/layout checks also write light/dark/narrow previews
to `build/reports/stage2/`. Eleven additional request lifecycle cases use real
Swing controls and the coordinator with a proxy for project disposal state. They
cover stale replies, clearing, disposal, failures, deadlines, recovery, example
selection and context delivery. These do not replace actual IDE acceptance.

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
src/main/java/          UI, context, coordinator, service contracts, sample adapters and RAG
src/test/java/          RAG parsing, persistence, retrieval and local HTTP tests
src/main/resources/     Plugin descriptor, icon and message resources
docs/                   Module boundaries, draft contracts and review notes
tools/                  Standalone Swing interaction checks
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
